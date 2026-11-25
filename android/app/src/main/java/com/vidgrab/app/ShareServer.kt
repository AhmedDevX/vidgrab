package com.vidgrab.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import android.util.Base64

private val random = SecureRandom()

fun randomToken(bytes: Int): String {
    val buf = ByteArray(bytes)
    random.nextBytes(buf)
    return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

fun lanIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress ?: "127.0.0.1"
} catch (e: Exception) {
    "127.0.0.1"
}

data class SharedEntry(val id: String, val name: String, val size: Long, val uri: Uri)

fun entryFor(resolver: ContentResolver, uri: Uri): SharedEntry {
    var name = "file"
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
        }
    }
    return SharedEntry(randomToken(8), name, size, uri)
}

/**
 * Scoped first-version share server.
 *
 * Serves only the explicitly selected files, each behind a random file id,
 * all under an unpredictable session token. Request paths are never used as
 * filesystem paths, so traversal is not possible. Expires automatically.
 * Traffic is plain HTTP on the local network: not encrypted.
 */
class ShareServer(
    port: Int,
    private val resolver: ContentResolver,
    entries: List<SharedEntry>,
    private val token: String,
    private val expiresAtMillis: Long,
) : NanoHTTPD(port) {

    private val byId = entries.associateBy { it.id }

    override fun serve(session: IHTTPSession): Response {
        if (System.currentTimeMillis() >= expiresAtMillis) return notFound()
        val parts = session.uri.trimStart('/').split('/')
        if (parts.isEmpty() || parts[0] != token) return notFound()
        return when {
            parts.size == 1 || (parts.size == 2 && parts[1].isEmpty()) -> listing()
            parts.size == 3 -> byId[parts[1]]?.let { serveFile(it) } ?: notFound()
            else -> notFound()
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun listing(): Response {
        val rows = byId.values.joinToString("") { e ->
            val mb = if (e.size >= 0) " (${e.size / (1024 * 1024)} MB)" else ""
            "<li><a href=\"$token/${e.id}/${esc(e.name)}\">${esc(e.name)}</a>$mb</li>"
        }
        val page = "<!doctype html><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>VidGrab Share</title><h2>VidGrab shared files</h2><ul>$rows</ul>" +
            "<p>This page expires when sharing stops.</p>"
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
    }

    private fun serveFile(entry: SharedEntry): Response {
        val stream = resolver.openInputStream(entry.uri) ?: return notFound()
        val encoded = URLEncoder.encode(entry.name, "UTF-8")
        val response = if (entry.size >= 0) {
            newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, entry.size)
        } else {
            newChunkedResponse(Response.Status.OK, "application/octet-stream", stream)
        }
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
        return response
    }
}
