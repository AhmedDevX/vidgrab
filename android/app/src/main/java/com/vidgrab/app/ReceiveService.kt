package com.vidgrab.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
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
        /** Nearby (Bluetooth + Wi-Fi Direct) advertising is running. */
        val nearby: Boolean = false,
    )
    val state = MutableStateFlow(State())

    /** The offer currently awaiting the user's decision, if any. */
    var pendingRespond: ((Boolean) -> Unit)? = null
    var pendingOfferText: String? = null

    /** Non-null while an offer awaits a decision; the receive page shows an
     *  in-app accept/decline dialog from this (the notification covers the
     *  backgrounded case). */
    val pendingOffer = MutableStateFlow<String?>(null)

    /** Non-null while a nearby phone asks to connect: name to 4-digit code. */
    val pendingCode = MutableStateFlow<Pair<String, String>?>(null)
    var pendingCodeRespond: ((Boolean) -> Unit)? = null
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
        const val ACTION_CODE_ACCEPT = "com.vidgrab.app.CODE_ACCEPT"
        const val ACTION_CODE_DECLINE = "com.vidgrab.app.CODE_DECLINE"
        private const val NOTIF_ID = 3
        private const val OFFER_NOTIF_ID = 4
        private const val DONE_NOTIF_ID = 5
        private const val CODE_NOTIF_ID = 6

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
    private var nearby: NearbyReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var identity: Identity
    private var lastRxFile = ""
    private var rxStartNanos = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopReceiving()
            ACTION_ACCEPT -> resolveOffer(true)
            ACTION_DECLINE -> resolveOffer(false)
            ACTION_CODE_ACCEPT -> resolveCode(true)
            ACTION_CODE_DECLINE -> resolveCode(false)
            ACTION_START -> startReceiving()
            else -> stopSelf()
        }
        return START_STICKY
    }

    /** Shared receive callbacks: same-Wi-Fi and nearby transports both
     *  report through these so the UI shows one consistent state. */
    private val transferUi = object : TransferUi {
            override fun decideOffer(
                senderName: String, filename: String, size: Long,
                respond: (Boolean) -> Unit,
            ) = onOffer(senderName, filename, size, respond)

            override fun onProgress(filename: String, received: Long, total: Long) {
                if (filename != lastRxFile) {
                    lastRxFile = filename
                    rxStartNanos = System.nanoTime()
                }
                ReceiveBus.state.value = ReceiveBus.state.value.copy(
                    status = ProgressText.line(
                        this@ReceiveService, filename, received, total, rxStartNanos))
            }

            override fun onDone(filename: String, savedPath: String) {
                ReceiveBus.state.value = ReceiveBus.state.value.copy(
                    status = getString(R.string.receive_done, filename, savedPath))
                doneNotification(filename, savedPath)
            }

            override fun onFailed(filename: String, reason: String) {
                ReceiveBus.state.value = ReceiveBus.state.value.copy(
                    status = getString(R.string.receive_failed, reason))
                failedNotification(filename)
            }
    }

    private fun receiveDir(): File =
        File(getExternalFilesDir(null), "Received").also { it.mkdirs() }

    private fun startReceiving() {
        stopServerOnly()
        try {
            identity = Identity.loadOrCreate(this)
            val name = "VidGrab-${identity.deviceId}"
            val srv = TransferServer(0, identity, receiveDir(), transferUi,
                openTarget = { name, _ -> downloadsTarget(name) })
            srv.makeSecure(identity.serverSocketFactory())
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            val discovery = Nsd(this)
            discovery.advertise(name, srv.listeningPort, identity.deviceId, identity.fingerprint)
            nsd = discovery
            // Nearby (Bluetooth + Wi-Fi Direct) alongside the same-Wi-Fi
            // server: one "receiving on" switch, both transports listening.
            val nearbyOn = NearbyLink.available(this)
            if (nearbyOn) {
                nearby = NearbyReceiver(
                    this, name, identity.deviceId,
                    ui = transferUi,
                    askCode = { peer, digits, respond -> onCodeRequest(peer, digits, respond) },
                    openTarget = { fileName, _ -> downloadsTarget(fileName) },
                ).also { it.start() }
            }
            ReceiveBus.state.value = ReceiveBus.State(
                active = true, deviceName = name,
                fingerprint = identity.fingerprint, nearby = nearbyOn)
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
        ReceiveBus.pendingOffer.value = ReceiveBus.pendingOfferText
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
        ReceiveBus.pendingOffer.value = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(OFFER_NOTIF_ID)
    }

    /** A nearby phone asked to connect: show its 4-digit code for comparison. */
    private fun onCodeRequest(peerName: String, digits: String, respond: (Boolean) -> Unit) {
        ReceiveBus.pendingCodeRespond = respond
        ReceiveBus.pendingCode.value = peerName to digits
        ReceiveBus.state.value = ReceiveBus.state.value.copy(
            status = getString(R.string.nearby_code_status, peerName, digits))
        Notifs.ensureChannel(this)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = Notifs.builder(this)
            .setContentTitle(getString(R.string.nearby_code_title))
            .setContentText(getString(R.string.nearby_code_notification, peerName, digits))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(CODE_NOTIF_ID, notif)
    }

    private fun resolveCode(accepted: Boolean) {
        ReceiveBus.pendingCodeRespond?.invoke(accepted)
        ReceiveBus.pendingCodeRespond = null
        ReceiveBus.pendingCode.value = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(CODE_NOTIF_ID)
    }

    /** Stream uploads straight into a PENDING public Downloads/VidGrab
     *  entry: invisible until committed, deleted on abort, and no second
     *  copy of the file is ever needed (matters for multi-GB transfers).
     *  Falls back to app-private storage if MediaStore is unavailable. */
    private fun downloadsTarget(name: String): StoreTarget {
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values) ?: throw java.io.IOException("insert failed")
            return object : StoreTarget {
                override fun open(): java.io.OutputStream =
                    resolver.openOutputStream(uri)
                        ?: throw java.io.IOException("write failed")
                override fun commit(): String {
                    val done = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    val realName = resolver.query(
                        uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
                    return "Download/VidGrab/$realName"
                }
                override fun abort() { resolver.delete(uri, null, null) }
            }
        } catch (e: Exception) {
            // MediaStore unavailable: keep the file in app-private storage.
            val dir = receiveDir()
            val tmp = File(dir, ".vidgrab-part-fallback")
            val target = uniqueName(dir, name)
            return object : StoreTarget {
                override fun open(): java.io.OutputStream = java.io.FileOutputStream(tmp)
                override fun commit(): String {
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = false)
                        tmp.delete()
                    }
                    return target.absolutePath
                }
                override fun abort() { tmp.delete() }
            }
        }
    }

    private fun uniqueName(dir: File, name: String): File {
        var target = File(dir, name)
        val stem = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
        var n = 1
        while (target.exists()) {
            n += 1
            target = File(dir, "$stem ($n)$ext")
        }
        return target
    }

    private fun doneNotification(filename: String, savedPath: String) {
        Notifs.ensureChannel(this)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notifs.builder(this)
            .setContentTitle(getString(R.string.receive_done_notif_title))
            .setContentText(getString(R.string.receive_done_notif_text, filename, savedPath))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(DONE_NOTIF_ID, notif)
    }

    private fun failedNotification(filename: String) {
        Notifs.ensureChannel(this)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notifs.builder(this)
            .setContentTitle(getString(R.string.receive_failed_notif_title))
            .setContentText(filename)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(DONE_NOTIF_ID, notif)
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
        nearby?.stop()
        nearby = null
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
