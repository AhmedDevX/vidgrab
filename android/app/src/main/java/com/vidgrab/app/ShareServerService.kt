package com.vidgrab.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service hosting the share server while a session is active. */
class ShareServerService : Service() {

    companion object {
        const val ACTION_START = "com.vidgrab.app.SHARE_START"
        const val ACTION_STOP = "com.vidgrab.app.SHARE_STOP"
        const val EXTRA_URIS = "uris"
        const val EXPIRY_MILLIS = 10 * 60 * 1000L
        private const val NOTIF_ID = 2

        fun start(context: Context, uris: List<Uri>) {
            val intent = Intent(context, ShareServerService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShareServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ShareServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var expiresAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            ACTION_START -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS)?.map { Uri.parse(it) }
                if (uris.isNullOrEmpty()) stopSelf() else startSharing(uris)
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun startSharing(uris: List<Uri>) {
        stopServerOnly()
        try {
            val entries = uris.map { entryFor(contentResolver, it) }
            val token = randomToken(16)
            expiresAt = System.currentTimeMillis() + EXPIRY_MILLIS
            val srv = ShareServer(0, contentResolver, entries, token, expiresAt)
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            val url = "http://${lanIp()}:${srv.listeningPort}/$token/"
            ShareBus.post(ShareBus.State(true, url, entries.size))
            startForeground(NOTIF_ID, notification(entries.size))
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:share")
                .apply { acquire(EXPIRY_MILLIS + 60_000L) }
            watchExpiry()
        } catch (e: Exception) {
            ShareBus.post(ShareBus.State(false, null, 0, error = e.message ?: e.toString()))
            stopSelf()
        }
    }

    private fun watchExpiry() {
        scope.launch {
            while (server != null) {
                delay(15_000)
                if (System.currentTimeMillis() >= expiresAt) stopSharing()
            }
        }
    }

    private fun notification(fileCount: Int): android.app.Notification {
        Notifs.ensureChannel(this)
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ShareServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notifs.builder(this)
            .setContentTitle("VidGrab sharing $fileCount file(s)")
            .setContentText("On for 10 minutes. Tap Stop to end now.")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun stopServerOnly() {
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopSharing() {
        stopServerOnly()
        ShareBus.post(ShareBus.State(false, null, 0))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerOnly()
        super.onDestroy()
    }
}
