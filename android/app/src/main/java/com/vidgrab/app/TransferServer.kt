package com.vidgrab.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.net.ssl.SSLServerSocketFactory

private const val OFFER_TTL_MILLIS = 60_000L
private const val MAX_OFFER_BYTES = 1024L * 1024 * 1024 * 1024 * 1024 // 1 TiB per-file cap
private const val READ_CHUNK = 1024 * 1024

/** Where a fully-accepted upload gets written. The stream opens before the
 *  body arrives; commit makes the file visible under its real name, abort
 *  leaves nothing behind. */
interface StoreTarget {
    fun open(): java.io.OutputStream
    fun commit(): String // returns a human-readable saved location
    fun abort()
}

/** Default target: stream to a temp name, rename into place on commit. */
private class TempFileTarget(private val tmp: File, private val finalFile: File) : StoreTarget {
    override fun open(): java.io.OutputStream = FileOutputStream(tmp)
    override fun commit(): String {
        if (!tmp.renameTo(finalFile)) throw java.io.IOException("could not save")
        return finalFile.absolutePath
    }
    override fun abort() { tmp.delete() }
}

/** State of one pending/accepted offer. */
private class Offer(
    val id: String,
    val token: String,
    val senderName: String,
    val filename: String,
    val size: Long,
) {
    @Volatile var status = "pending" // pending -> accepted|declined|expired -> uploading -> done|failed
    val createdAt = System.currentTimeMillis()
}

/** Callbacks the receiver side surfaces to the user (service/activity). */
interface TransferUi {
    /** Ask the user whether to accept; call respond exactly once. */
    fun decideOffer(senderName: String, filename: String, size: Long, respond: (Boolean) -> Unit)
    fun onProgress(filename: String, received: Long, total: Long)
    fun onDone(filename: String, savedPath: String)
    fun onFailed(filename: String, reason: String)
}

private val WINDOWS_RESERVED = buildSet {
    addAll(listOf("con", "prn", "aux", "nul"))
    for (i in 1..9) { add("com$i"); add("lpt$i") }
}

/** Reduce an offered filename to a safe basename (see desktop module). */
fun sanitizeFilename(name: String): String {
    var base = name.replace('\\', '/').substringAfterLast('/')
    base = base.replace(Regex("[<>:\"|?*\\u0000-\\u001f]"), "_")
    base = base.trim().trimEnd('.', ' ')
    if (base.substringBefore('.').lowercase() in WINDOWS_RESERVED) base = "_$base"
    return base.ifEmpty { "received-file" }
}

/**
 * HTTPS receive side of the direct transfer protocol (v2).
 *
 * TLS authenticates THIS device to the sender (its certificate fingerprint
 * is compared/pinned on the sender side before any request is sent).
 * Inbound senders are unverified LAN peers, so every transfer requires the
 * recipient's explicit accept, and each upload is authorized by a one-time
 * token scoped to one accepted offer with an exact size. Files stream to a
 * temp name and are renamed into place only after full receipt.
 */
class TransferServer(
    port: Int,
    private val identity: Identity,
    private val receiveDir: File,
    private val ui: TransferUi,
    private val maxBytes: Long = MAX_OFFER_BYTES,
    /** Where uploads stream to. Default: temp file renamed into receiveDir. */
    private val openTarget: (String, Long) -> StoreTarget = { name, _ ->
        TempFileTarget(
            File(receiveDir, ".vidgrab-part-${randomToken(6)}"),
            uniqueFile(receiveDir, name))
    },
) : NanoHTTPD(port) {

    private val lock = Object()
    private var offer: Offer? = null
    private var uploadActive = false

    init {
        receiveDir.mkdirs()
    }

    fun makeSecure(factory: SSLServerSocketFactory) {
        super.makeSecure(factory, null)
    }

    val fingerprint: String get() = identity.fingerprint
    val deviceId: String get() = identity.deviceId

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            session.method == Method.GET && parts == listOf("v1", "info") -> json(200, JSONObject()
                .put("v", 2)
                .put("device_id", deviceId)
                .put("fingerprint", fingerprint))
            session.method == Method.POST && parts == listOf("v1", "offer") -> offerBegin(session)
            session.method == Method.GET && parts.size == 3 && parts[0] == "v1" && parts[1] == "offer" ->
                offerStatus(parts[2])
            session.method == Method.POST && parts.size == 3 && parts[0] == "v1" && parts[1] == "upload" ->
                upload(parts[2], session)
            else -> json(404, JSONObject().put("error", "not found"))
        }
    }

    private fun json(code: Int, obj: JSONObject): Response {
        val status = when (code) {
            200 -> Response.Status.OK
            202 -> Response.Status.ACCEPTED
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            409 -> Response.Status.CONFLICT
            // 507 Insufficient Storage has no enum constant in NanoHTTPD;
            // 413 is the closest supported "too big for us" signal.
            507 -> Response.Status.PAYLOAD_TOO_LARGE
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", obj.toString())
    }

    private fun offerBegin(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toIntOrNull() ?: -1
        if (length <= 0 || length > 4096) return json(400, JSONObject().put("error", "bad request"))
        val body = try {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = session.inputStream.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            JSONObject(String(buf, 0, read))
        } catch (e: Exception) {
            return json(400, JSONObject().put("error", "bad request"))
        }
        val size = body.optLong("size", -1)
        if (size <= 0) return json(400, JSONObject().put("error", "bad size"))
        if (size > maxBytes) return json(400, JSONObject().put("error", "size out of bounds"))
        if (size > receiveDir.freeSpace) return json(507, JSONObject().put("error", "not enough disk space"))

        val newOffer: Offer
        synchronized(lock) {
            offer?.let {
                // Apply the TTL here too, so a stale offer the sender stopped
                // polling cannot block every later offer with a 409 forever.
                if (it.status == "pending" &&
                    System.currentTimeMillis() - it.createdAt > OFFER_TTL_MILLIS
                ) {
                    it.status = "expired"
                }
                if (it.status == "pending" || it.status == "uploading") {
                    return json(409, JSONObject().put("error", "another offer is pending"))
                }
            }
            newOffer = Offer(
                id = randomToken(12),
                token = randomToken(16),
                senderName = body.optString("sender_name", "unknown sender").take(60),
                filename = sanitizeFilename(body.optString("filename", "")),
                size = size,
            )
            offer = newOffer
        }
        ui.decideOffer(newOffer.senderName, newOffer.filename, newOffer.size) { accepted ->
            synchronized(lock) {
                if (newOffer.status == "pending") {
                    newOffer.status = if (accepted) "accepted" else "declined"
                }
            }
        }
        return json(202, JSONObject().put("offer_id", newOffer.id))
    }

    private fun offerStatus(id: String): Response {
        val o = offer
        if (o == null || o.id != id) return json(404, JSONObject().put("error", "no such offer"))
        if (o.status == "pending" && System.currentTimeMillis() - o.createdAt > OFFER_TTL_MILLIS) {
            o.status = "expired"
        }
        val out = JSONObject().put("status", o.status)
        if (o.status == "accepted") out.put("upload_token", o.token)
        return json(200, out)
    }

    private fun upload(token: String, session: IHTTPSession): Response {
        val o: Offer
        synchronized(lock) {
            val cur = offer
            if (cur == null || cur.status != "accepted" || cur.token != token || uploadActive) {
                return json(404, JSONObject().put("error", "no such upload"))
            }
            uploadActive = true
            cur.status = "uploading"
            o = cur
        }
        try {
            return receiveBody(o, session)
        } finally {
            synchronized(lock) { uploadActive = false }
        }
    }

    private fun receiveBody(o: Offer, session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1
        if (length != o.size) {
            o.status = "failed"
            return json(400, JSONObject().put("error", "size mismatch"))
        }
        val target = openTarget(o.filename, o.size)
        try {
            var received = 0L
            val buf = ByteArray(READ_CHUNK)
            target.open().use { out ->
                var remaining = length
                while (remaining > 0) {
                    val n = session.inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw java.io.IOException("connection dropped")
                    out.write(buf, 0, n)
                    received += n
                    remaining -= n
                    ui.onProgress(o.filename, received, length)
                }
                (out as? FileOutputStream)?.fd?.sync()
            }
        } catch (e: Exception) {
            target.abort()
            o.status = "failed"
            ui.onFailed(o.filename, (e.message ?: "upload aborted").take(120))
            return json(400, JSONObject().put("error", "upload aborted"))
        }

        // Full body arrived intact over the authenticated TLS channel; only
        // now does the file become visible under its real name.
        val savedLocation = try {
            target.commit()
        } catch (e: Exception) {
            o.status = "failed"
            ui.onFailed(o.filename, "could not save")
            return json(500, JSONObject().put("error", "could not save"))
        }
        o.status = "done"
        ui.onDone(o.filename, savedLocation)
        return json(200, JSONObject().put("saved_as", o.filename))
    }

    companion object {
        private fun uniqueFile(dir: File, name: String): File {
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
    }
}
