package com.vidgrab.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Live receive-side state for the UI to observe. */
object ReceiveBus {
    data class State(
        val active: Boolean = false,
        val deviceName: String? = null,
        val status: String? = null,
        val fingerprint: String? = null,
    )
    val state = MutableStateFlow(State())

    /** The offer currently awaiting the user's decision, if any. */
    var pendingRespond: ((Boolean) -> Unit)? = null
    var pendingOfferText: String? = null
}

/**
 * Foreground service hosting the TLS receive server + NSD advertising.
 * Incoming offers surface as a notification with Accept/Decline actions so
 * consent works even when the app is backgrounded; the sender is always an
 * unverified LAN peer (TLS authenticates the receiver to the sender, not
 * the reverse).
 */
class ReceiveService : Service() {

    companion object {
        const val ACTION_START = "com.vidgrab.app.RECEIVE_START"
        const val ACTION_STOP = "com.vidgrab.app.RECEIVE_STOP"
        const val ACTION_ACCEPT = "com.vidgrab.app.OFFER_ACCEPT"
        const val ACTION_DECLINE = "com.vidgrab.app.OFFER_DECLINE"
        private const val NOTIF_ID = 3
        private const val OFFER_NOTIF_ID = 4

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ReceiveService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ReceiveService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: TransferServer? = null
    private var nsd: Nsd? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var identity: Identity

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopReceiving()
            ACTION_ACCEPT -> resolveOffer(true)
            ACTION_DECLINE -> resolveOffer(false)
            ACTION_START -> startReceiving()
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun receiveDir(): File =
        File(getExternalFilesDir(null), "Received").also { it.mkdirs() }

    private fun startReceiving() {
        stopServerOnly()
        try {
            identity = Identity.loadOrCreate(this)
            val name = "VidGrab-${identity.deviceId}"
            val srv = TransferServer(0, identity, receiveDir(), object : TransferUi {
                override fun decideOffer(
                    senderName: String, filename: String, size: Long,
                    respond: (Boolean) -> Unit,
                ) = onOffer(senderName, filename, size, respond)

                override fun onProgress(filename: String, received: Long, total: Long) {
                    ReceiveBus.state.value = ReceiveBus.state.value.copy(
                        status = "$filename ${received * 100 / total.coerceAtLeast(1)}%")
                }

                override fun onDone(filename: String, savedPath: String) {
                    ReceiveBus.state.value = ReceiveBus.state.value.copy(
                        status = getString(R.string.receive_done, filename))
                }

                override fun onFailed(filename: String, reason: String) {
                    ReceiveBus.state.value = ReceiveBus.state.value.copy(
                        status = getString(R.string.receive_failed, reason))
                }
            })
            srv.makeSecure(identity.serverSocketFactory())
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            val discovery = Nsd(this)
            discovery.advertise(name, srv.listeningPort, identity.deviceId, identity.fingerprint)
            nsd = discovery
            ReceiveBus.state.value = ReceiveBus.State(
                active = true, deviceName = name, fingerprint = identity.fingerprint)
            startForeground(NOTIF_ID, ongoingNotification(name))
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:receive")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (e: Exception) {
            ReceiveBus.state.value = ReceiveBus.State(
                active = false, status = e.message ?: e.toString())
            stopSelf()
        }
    }

    private fun onOffer(
        senderName: String, filename: String, size: Long,
        respond: (Boolean) -> Unit,
    ) {
        val mb = "%.1f MB".format(size / 1e6)
        ReceiveBus.pendingRespond = respond
        ReceiveBus.pendingOfferText =
            getString(R.string.offer_notification_text, senderName, filename, mb)
        ReceiveBus.state.value = ReceiveBus.state.value.copy(
            status = ReceiveBus.pendingOfferText)
        Notifs.ensureChannel(this)
        val accept = PendingIntent.getService(
            this, 1, Intent(this, ReceiveService::class.java).setAction(ACTION_ACCEPT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getService(
            this, 2, Intent(this, ReceiveService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = Notifs.builder(this)
            .setContentTitle(getString(R.string.offer_notification_title))
            .setContentText(ReceiveBus.pendingOfferText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, getString(R.string.accept), accept)
            .addAction(0, getString(R.string.decline), decline)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(OFFER_NOTIF_ID, notif)
    }

    private fun resolveOffer(accepted: Boolean) {
        ReceiveBus.pendingRespond?.invoke(accepted)
        ReceiveBus.pendingRespond = null
        ReceiveBus.pendingOfferText = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(OFFER_NOTIF_ID)
    }

    private fun ongoingNotification(name: String): Notification {
        Notifs.ensureChannel(this)
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ReceiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notifs.builder(this)
            .setContentTitle(getString(R.string.receive_notification_title, name))
            .setContentText(getString(R.string.receive_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun stopServerOnly() {
        server?.stop()
        server = null
        nsd?.close()
        nsd = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopReceiving() {
        stopServerOnly()
        ReceiveBus.state.value = ReceiveBus.State(active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerOnly()
        super.onDestroy()
    }
}
