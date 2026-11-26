package com.vidgrab.app

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads one video at a time with the embedded yt-dlp engine,
 * then moves the result into Downloads/VidGrab via MediaStore.
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_AUDIO_ONLY = "audioOnly"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val format = intent?.getStringExtra(EXTRA_FORMAT) ?: "bv*+ba/b"
        val audioOnly = intent?.getBooleanExtra(EXTRA_AUDIO_ONLY, false) ?: false
        if (url == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            ProgressBus.post(ProgressBus.State(true, 0f, "A download is already running"))
            return START_STICKY
        }
        running = true
        Notifs.ensureChannel(this)
        startForeground(NOTIF_ID, Notifs.builder(this).setContentTitle("VidGrab downloading").build())
        acquireWakeLock()
        scope.launch { doDownload(url, format, audioOnly) }
        return START_STICKY
    }

    private fun doDownload(url: String, format: String, audioOnly: Boolean) {
        try {
            Engine.ensureInit(this)
            if (!Engine.ready) throw IllegalStateException("Engine failed: ${Engine.error}")

            val tmpDir = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            val request = YoutubeDLRequest(url)
            Engine.jsRuntimeOption(this)?.let { request.addOption(it.first, it.second) }
            request.addOption("-f", format)
            request.addOption("-o", "${tmpDir.absolutePath}/%(title)s.%(ext)s")
            if (audioOnly) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
            }
            YoutubeDL.getInstance().execute(request, "vidgrab") { progress, _, line ->
                val pct = if (progress < 0) 0f else progress
                ProgressBus.post(ProgressBus.State(true, pct, line.take(80)))
                val n = Notifs.builder(this)
                    .setContentTitle("VidGrab downloading")
                    .setContentText("${pct.toInt()}%")
                    .setProgress(100, pct.toInt(), false)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIF_ID, n)
            }

            val produced = tmpDir.listFiles()?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("No output file produced")
            val savedName = copyToDownloads(produced)
            tmpDir.deleteRecursively()
            ProgressBus.post(ProgressBus.State(false, 100f, "Saved to Downloads/VidGrab/$savedName", done = true))
            notifyDone("Download complete", savedName)
        } catch (e: Exception) {
            ProgressBus.post(ProgressBus.State(false, 0f, "", error = e.message ?: e.toString()))
            notifyDone("Download failed", e.message ?: "unknown error")
        } finally {
            running = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun copyToDownloads(file: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create download entry")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not write download")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return file.name
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun notifyDone(title: String, text: String) {
        val n = Notifs.builder(this)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIF_ID + 100, n)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:download").apply {
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }
}
