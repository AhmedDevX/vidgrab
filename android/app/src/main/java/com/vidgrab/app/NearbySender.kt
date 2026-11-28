package com.vidgrab.app

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** One file to send over the nearby link. */
class SendItem(
    val name: String,
    val size: Long,
    /** Opens a read descriptor on the content; the caller owns nothing. */
    val open: () -> ParcelFileDescriptor,
)

data class NearbyPeer(val endpointId: String, val name: String, val deviceId: String)

/**
 * Nearby (Bluetooth + Wi-Fi Direct) send side.
 *
 * Flow: discover -> request connection -> BOTH devices show the same 4-digit
 * code from the API's own authentication token -> both users confirm ->
 * offer is sent as a BYTES payload -> recipient accepts -> files stream as
 * FILE payloads -> done.
 *
 * The code comes from Nearby's per-connection authentication token, so it
 * is a real man-in-the-middle check, not a value this app invented. The
 * sender is still labelled unverified on the recipient's screen: the code
 * proves the two screens belong to the same connection, not who is holding
 * the other phone.
 */
class NearbySender(
    context: Context,
    private val senderName: String,
) {
    private val client = NearbyLink.client(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peers = LinkedHashMap<String, NearbyPeer>()

    /** Device list changed (discovery). Called on a background thread. */
    var onPeers: ((List<NearbyPeer>) -> Unit)? = null

    /** Ask the user whether the 4-digit code matches; suspends until answered. */
    var confirmCode: (suspend (peerName: String, digits: String) -> Boolean)? = null

    private var discovering = false
    private var connected: CompletableDeferred<Boolean>? = null
    private var control: CompletableDeferred<JSONObject>? = null
    private var transferDone: CompletableDeferred<Boolean>? = null

    @Volatile private var activePayloadId = -1L
    @Volatile private var progressSink: ((Long, Long) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val json = try {
                JSONObject(String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (json != null) control?.complete(json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.payloadId != activePayloadId) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    progressSink?.invoke(update.bytesTransferred, update.totalBytes)
                PayloadTransferUpdate.Status.SUCCESS -> transferDone?.complete(true)
                else -> transferDone?.complete(false)
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Both sides land here; both show the same digits.
            scope.launch {
                val ok = confirmCode?.invoke(info.endpointName ?: "", info.authenticationDigits ?: "")
                    ?: false
                if (ok) client.acceptConnection(endpointId, payloadCallback)
                else client.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connected?.complete(result.status.statusCode == ConnectionsStatusCodes.STATUS_OK)
        }

        override fun onDisconnected(endpointId: String) {
            connected?.complete(false)
            transferDone?.complete(false)
        }
    }

    private fun publish() {
        val list = synchronized(peers) { peers.values.toList() }
        onPeers?.invoke(list)
    }

    fun startDiscovery() {
        if (discovering) return
        discovering = true
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startDiscovery(NearbyLink.SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                val id = try {
                    String(info.endpointInfo ?: ByteArray(0), Charsets.UTF_8)
                } catch (e: Exception) {
                    ""
                }
                synchronized(peers) {
                    peers[endpointId] = NearbyPeer(endpointId, info.endpointName ?: "device", id)
                }
                publish()
            }

            override fun onEndpointLost(endpointId: String) {
                synchronized(peers) { peers.remove(endpointId) }
                publish()
            }
        }, options)
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try { client.stopDiscovery() } catch (_: Exception) {}
        synchronized(peers) { peers.clear() }
        publish()
    }

    /**
     * Connect, confirm the code, offer [files] and stream them. Throws on
     * refusal, timeout or a broken link. Returns the names as saved.
     */
    suspend fun send(
        peer: NearbyPeer,
        files: List<SendItem>,
        onStatus: (String) -> Unit,
        onProgress: (String, Long, Long) -> Unit,
    ): List<String> {
        if (files.isEmpty()) throw TransferException("no files selected")

        val requested = CompletableDeferred<Boolean>()
        val link = CompletableDeferred<Boolean>()
        connected = link
        client.requestConnection(senderName, peer.endpointId, lifecycle)
            .addOnSuccessListener { requested.complete(true) }
            .addOnFailureListener { requested.complete(false) }
        if (!withTimeout(20_000) { requested.await() }) {
            throw TransferException("could not reach the other phone")
        }
        if (!withTimeout(120_000) { link.await() }) {
            throw TransferException("connection rejected or timed out")
        }

        val reply = CompletableDeferred<JSONObject>()
        control = reply
        val offer = JSONObject()
            .put("type", "offer")
            .put("sender", senderName)
            .put("files", JSONArray().apply {
                files.forEach { put(JSONObject().put("name", it.name).put("size", it.size)) }
            })
        client.sendPayload(peer.endpointId, Payload.fromBytes(offer.toString().toByteArray()))
        val answer = try {
            withTimeout(120_000) { reply.await() }
        } catch (e: Exception) {
            throw TransferException("no answer from recipient")
        }
        if (!answer.optBoolean("accept", false)) throw TransferException("recipient declined")

        val saved = mutableListOf<String>()
        try {
            for (file in files) {
                onStatus(file.name)
                val pfd = try {
                    file.open()
                } catch (e: Exception) {
                    throw TransferException("cannot read ${file.name}")
                }
                val payload = Payload.fromFile(pfd)
                payload.setFileName(file.name)
                activePayloadId = payload.id
                val done = CompletableDeferred<Boolean>()
                transferDone = done
                progressSink = { sent, total -> onProgress(file.name, sent, total) }
                client.sendPayload(peer.endpointId, payload)
                    .addOnFailureListener { done.complete(false) }
                val ok = try {
                    withTimeout(30 * 60_000L) { done.await() }
                } catch (e: Exception) {
                    false
                }
                try { pfd.close() } catch (_: Exception) {}
                progressSink = null
                if (!ok) throw TransferException("transfer failed for ${file.name}")
                saved += file.name
            }
        } finally {
            try { client.disconnectFromEndpoint(peer.endpointId) } catch (_: Exception) {}
        }
        return saved
    }

    fun close() {
        stopDiscovery()
        connected?.complete(false)
        transferDone?.complete(false)
        scope.cancel()
    }
}
