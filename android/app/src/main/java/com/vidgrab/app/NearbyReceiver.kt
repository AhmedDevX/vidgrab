package com.vidgrab.app

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Nearby (Bluetooth + Wi-Fi Direct) receive side.
 *
 * Advertises this phone so nearby VidGrab installs can find it without any
 * shared Wi-Fi. Every inbound connection shows the API's 4-digit code for
 * visual confirmation, every file offer needs an explicit accept, and the
 * sender is always shown as unverified.
 */
class NearbyReceiver(
    context: Context,
    private val deviceName: String,
    private val deviceId: String,
    private val ui: TransferUi,
    /** Ask the user to confirm the 4-digit code; call the callback once. */
    private val askCode: (peerName: String, digits: String, respond: (Boolean) -> Unit) -> Unit,
    private val openTarget: (String, Long) -> StoreTarget,
) {
    private val client = NearbyLink.client(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var advertising = false
    @Volatile private var endpoint: String? = null
    @Volatile private var offered = false
    private val queue = ArrayDeque<Pair<String, Long>>()
    private val names = HashMap<Long, String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> onControl(endpointId, payload)
                Payload.Type.FILE -> onFile(endpointId, payload)
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                val name = names[update.payloadId] ?: return
                ui.onFailed(name, "transfer failed")
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val busy = endpoint != null && endpoint != endpointId
            if (busy) {
                try { client.rejectConnection(endpointId) } catch (_: Exception) {}
                return
            }
            askCode(info.endpointName ?: "", info.authenticationDigits ?: "") { ok ->
                try {
                    if (ok) {
                        endpoint = endpointId
                        client.acceptConnection(endpointId, payloadCallback)
                    } else {
                        client.rejectConnection(endpointId)
                    }
                } catch (_: Exception) {}
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                if (endpoint == endpointId) endpoint = null
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpoint == endpointId) {
                endpoint = null
                offered = false
            }
        }
    }

    fun start() {
        if (advertising) return
        advertising = true
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startAdvertising(
            deviceName,
            NearbyLink.SERVICE_ID,
            lifecycle,
            options,
        ).addOnFailureListener { advertising = false }
    }

    fun stop() {
        if (advertising) {
            try { client.stopAdvertising() } catch (_: Exception) {}
        }
        advertising = false
        endpoint?.let { try { client.disconnectFromEndpoint(it) } catch (_: Exception) {} }
        endpoint = null
        offered = false
        scope.cancel()
    }

    /** First message from the sender: who they are and what they offer. */
    private fun onControl(endpointId: String, payload: Payload) {
        val json = try {
            JSONObject(String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        if (json.optString("type") != "offer") return
        val files = json.optJSONArray("files") ?: return
        if (files.length() == 0) return
        val first = files.getJSONObject(0)
        val totalSize = (0 until files.length()).sumOf {
            files.getJSONObject(it).optLong("size", 0L)
        }
        val label = if (files.length() == 1) first.optString("name", "file")
                    else first.optString("name", "file") + " +" + (files.length() - 1)
        synchronized(queue) {
            queue.clear()
            names.clear()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                queue.addLast(f.optString("name", "file") to f.optLong("size", 0L))
            }
        }
        offered = true
        val sender = json.optString("sender", "unknown device").take(60)
        ui.decideOffer(sender, label, totalSize) { accepted ->
            val reply = JSONObject().put("accept", accepted)
            try {
                client.sendPayload(endpointId, Payload.fromBytes(reply.toString().toByteArray()))
            } catch (_: Exception) {}
            if (!accepted) {
                try { client.disconnectFromEndpoint(endpointId) } catch (_: Exception) {}
                endpoint = null
                offered = false
            }
        }
    }

    /** A file payload: copy it out as the bytes arrive, then commit. */
    private fun onFile(endpointId: String, payload: Payload) {
        val file = payload.asFile() ?: return
        val meta = synchronized(queue) { queue.removeFirstOrNull() } ?: ("received-file" to file.size)
        val name = sanitizeFilename(meta.first)
        names[payload.id] = name
        val target = openTarget(name, meta.second)
        scope.launch {
            try {
                val descriptor: ParcelFileDescriptor = file.asParcelFileDescriptor()
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    target.open().use { out ->
                        val buffer = ByteArray(1 shl 20)
                        var received = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            received += n
                            ui.onProgress(name, received, meta.second)
                        }
                        (out as? java.io.FileOutputStream)?.fd?.sync()
                    }
                }
                val saved = target.commit()
                names.remove(payload.id)
                ui.onDone(name, saved)
            } catch (e: Exception) {
                target.abort()
                names.remove(payload.id)
                ui.onFailed(name, (e.message ?: "transfer failed").take(120))
            }
        }
    }
}
