package com.vidgrab.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

open class TransferException(message: String) : Exception(message)

/** A previously pinned device presented a different fingerprint. */
class PinChangedException(message: String) : TransferException(message)

private const val IO_TIMEOUT_MS = 30_000
private const val CONNECT_TIMEOUT_MS = 10_000
private const val SEND_CHUNK = 1024 * 1024 // upload streaming chunk; bounds sender RAM

/** device-id -> SHA-256 fingerprint pins; pin changes fail closed. */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("vidgrab_pins", Context.MODE_PRIVATE)

    fun get(deviceId: String): String? = prefs.getString(deviceId, null)

    fun set(deviceId: String, fingerprint: String) {
        prefs.edit().putString(deviceId, fingerprint).apply()
    }
}

/**
 * Send side of the direct transfer protocol (v2).
 *
 * connect() performs ONLY the TLS handshake and fingerprint check; no HTTP
 * request leaves this client until the fingerprint is trusted (pinned match
 * or explicit user confirmation via confirmTrust()). All requests then
 * reuse that single verified connection. CA validation is off because the
 * peer cert is self-signed; the full fingerprint comparison IS the
 * authentication (mDNS data is never trusted).
 */
class SendClient(
    private val host: String,
    private val port: Int,
    private val senderName: String,
    private val pins: PinStore,
    private val deviceId: String,
) {
    var presentedFingerprint: String? = null
        private set
    var trusted = false
        private set

    private var socket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    fun connect(): String {
        // Trust-all manager: required to complete the handshake with a
        // self-signed peer; authentication happens below via fingerprint.
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.soTimeout = IO_TIMEOUT_MS
        s.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.enabledProtocols = s.supportedProtocols.filter {
            it == "TLSv1.3" || it == "TLSv1.2"
        }.toTypedArray()
        s.startHandshake()
        val cert = s.session.peerCertificates[0] as X509Certificate
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        presentedFingerprint = fp

        val pinned = pins.get(deviceId)
        when {
            pinned == null -> trusted = false // first contact: user must compare
            pinned != fp -> {
                close()
                throw PinChangedException(
                    "device $deviceId fingerprint changed; refusing to connect")
            }
            else -> trusted = true
        }
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())
        return fp
    }

    /** Pin the presented fingerprint after the user compared it on both screens. */
    fun confirmTrust() {
        val fp = presentedFingerprint ?: throw TransferException("connect first")
        pins.set(deviceId, fp)
        trusted = true
    }

    // -- minimal HTTP/1.1 over the single verified connection --

    private fun request(method: String, path: String, body: ByteArray? = null): JSONObject {
        if (!trusted) throw TransferException("unverified connection")
        val out = output ?: throw TransferException("not connected")
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n")
        if (body != null) {
            sb.append("Content-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body)
        out.flush()
        return readResponse()
    }

    /** Stream a request body from a source stream in bounded chunks; peak
     *  memory is one chunk regardless of file size. If the source size
     *  changes mid-send the connection is desynced: fail and close. */
    private fun requestStream(method: String, path: String, size: Long,
                              openStream: () -> InputStream): JSONObject {
        if (!trusted) throw TransferException("unverified connection")
        val out = output ?: throw TransferException("not connected")
        val head = "$method $path HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n" +
            "Content-Type: application/octet-stream\r\nContent-Length: $size\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        var sent = 0L
        openStream().use { ins ->
            val buf = ByteArray(SEND_CHUNK)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sent += n
            }
        }
        out.flush()
        if (sent != size) {
            close()
            throw TransferException(
                "source size changed during send ($sent != $size); connection closed")
        }
        return readResponse()
    }

    private fun readResponse(): JSONObject {
        val statusLine = readLine()
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw TransferException("bad response: $statusLine")
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("content-length", true)) {
                length = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input!!.read(payload, read, length - read)
            if (n < 0) throw TransferException("connection dropped")
            read += n
        }
        val obj = try {
            if (payload.isEmpty()) JSONObject() else JSONObject(String(payload))
        } catch (e: Exception) {
            JSONObject()
        }
        if (code >= 400) {
            throw TransferException(obj.optString("error").ifEmpty { "HTTP $code" })
        }
        return obj
    }

    private fun readLine(): String {
        val buf = StringBuilder()
        val inp = input ?: throw TransferException("not connected")
        while (true) {
            val c = inp.read()
            if (c < 0) throw TransferException("connection dropped")
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.append(c.toChar())
        }
        return buf.toString()
    }

    /** Offer then upload one file, streaming from the given source; returns
     *  the name it was saved as. size must match what openStream yields. */
    fun send(filename: String, size: Long,
             progress: ((String) -> Unit)? = null,
             openStream: () -> InputStream): String {
        progress?.invoke("waiting for recipient...")
        val offerResp = request("POST", "/v1/offer", JSONObject()
            .put("sender_name", senderName)
            .put("filename", filename)
            .put("size", size)
            .toString().toByteArray())
        val offerId = offerResp.getString("offer_id")

        val deadline = System.currentTimeMillis() + 90_000
        var token: String? = null
        while (System.currentTimeMillis() < deadline) {
            val status = request("GET", "/v1/offer/$offerId")
            when (status.getString("status")) {
                "accepted" -> { token = status.getString("upload_token"); break }
                "declined" -> throw TransferException("recipient declined")
                "expired" -> throw TransferException("offer expired")
            }
            Thread.sleep(1000)
        }
        if (token == null) throw TransferException("no answer from recipient")

        progress?.invoke("sending...")
        val resp = requestStream("POST", "/v1/upload/$token", size, openStream)
        return resp.optString("saved_as", filename)
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        trusted = false
    }
}
