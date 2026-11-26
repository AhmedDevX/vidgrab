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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one video at a time with the embedded yt-dlp engine,
 * then moves the result into Downloads/VidGrab via MediaStore.
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_AUDIO_ONLY = "audioOnly"
        const val EXTRA_DIRECT = "direct"
        const val EXTRA_USER_AGENT = "userAgent"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_REFERER = "referer"
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
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        scope.launch {
            if (direct) doDirectDownload(
                url,
                intent.getStringExtra(EXTRA_USER_AGENT),
                intent.getStringExtra(EXTRA_COOKIE),
                intent.getStringExtra(EXTRA_REFERER),
            ) else doDownload(url, format, audioOnly)
        }
        return START_STICKY
    }

    /** Plain direct file link: stream into a PENDING Downloads/VidGrab entry
     *  (invisible until complete, deleted on failure, single copy on disk). */
    private fun doDirectDownload(
        url: String, userAgent: String?, cookie: String?, referer: String?,
    ) {
        try {
            val conn = DirectFetch.openFollowing(url, userAgent, cookie, referer)
            val ctype = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            if (conn.responseCode !in 200..299 || ctype.startsWith("text/html")) {
                conn.disconnect()
                throw IllegalStateException("Not a direct file link")
            }
            val total = conn.contentLengthLong
            val name = DirectFetch.fileName(
                conn.headerFields["Content-Disposition"]?.firstOrNull(),
                conn.url.toString())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create download entry")
            var received = 0L
            val t0 = System.nanoTime()
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            val pct = if (total > 0) received * 100f / total else 0f
                            ProgressBus.post(ProgressBus.State(
                                true, pct,
                                ProgressText.line(
                                    this@DownloadService, name, received, total, t0
                                ).take(90)))
                            startForeground(NOTIF_ID, Notifs.builder(this@DownloadService)
                                .setContentTitle("VidGrab downloading")
                                .setContentText("${pct.toInt()}%")
                                .setProgress(100, pct.toInt(), total <= 0)
                                .setOngoing(true)
                                .build())
                        }
                    }
                }
                if (total > 0 && received != total) {
                    throw IllegalStateException("download incomplete ($received != $total)")
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            } finally {
                conn.disconnect()
            }
            ProgressBus.post(ProgressBus.State(
                false, 100f, "Saved to Downloads/VidGrab/$name", done = true))
            notifyDone("Download complete", name)
        } catch (e: Exception) {
            ProgressBus.post(ProgressBus.State(
                false, 0f, "", error = e.message ?: e.toString()))
            notifyDone("Download failed", e.message ?: "unknown error")
        } finally {
            running = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
            val onProg: (Float, Long, String) -> Unit = { progress, _, line ->
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
            // Format fallback chain: chosen selector -> yt-dlp default (no -f)
            // -> "best*" (any single stream). Only for "format not available".
            fun runWithFormatFallback() {
                val selectors = listOf(format, null, "best*")
                var last: Exception? = null
                for (sel in selectors) {
                    val req = YoutubeDLRequest(url)
                    Engine.jsRuntimeOption(this)?.let { req.addOption(it.first, it.second) }
                    if (sel != null) req.addOption("-f", sel)
                    req.addOption("-o", "${tmpDir.absolutePath}/%(title)s.%(ext)s")
                    if (audioOnly) {
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                    }
                    try {
                        YoutubeDL.getInstance().execute(req, "vidgrab", onProg)
                        return
                    } catch (e: Exception) {
                        last = e
                        if (e.message?.contains("Requested format is not available") != true) throw e
                        tmpDir.deleteRecursively(); tmpDir.mkdirs()
                    }
                }
                throw last!!
            }
            try {
                runWithFormatFallback()
            } catch (first: Exception) {
                // One retry on the nightly yt-dlp channel (YouTube changes fast).
                ProgressBus.post(ProgressBus.State(true, 0f, "Retrying with nightly yt-dlp..."))
                try {
                    Engine.update(this, nightly = true)
                } catch (_: Exception) {
                    throw first
                }
                tmpDir.deleteRecursively(); tmpDir.mkdirs()
                runWithFormatFallback()
            }

            val produced = tmpDir.listFiles()?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("No output file produced")
            val savedName = copyToDownloads(produced)
            tmpDir.deleteRecursively()
            ProgressBus.post(ProgressBus.State(false, 100f, "Saved to Downloads/VidGrab/$savedName", done = true))
            notifyDone("Download complete", savedName)
        } catch (e: Exception) {
            val msg = "[yt-dlp ${Engine.version(this)}] " + (e.message ?: e.toString())
            ProgressBus.post(ProgressBus.State(false, 0f, "", error = msg))
            notifyDone("Download failed", msg.take(200))
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
