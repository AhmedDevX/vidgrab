package com.vidgrab.app

import java.net.HttpURLConnection
import java.net.URL

data class DirectInfo(
    val filename: String,
    val size: Long,
    val contentType: String,
    val finalUrl: String,
)

/** Direct HTTP(S) file links (images, archives, documents, any file) that
 *  yt-dlp has no extractor for. Plain links only: anything that serves an
 *  HTML page or needs auth/DRM is out of scope. */
object DirectFetch {

    /** HEAD-probe the URL; returns file metadata when it serves a file,
     *  null for HTML pages or unreachable/HEAD-rejecting servers. */
    fun probe(url: String): DirectInfo? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VidGrab/1.0")
            }
            val ctype = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            if (conn.responseCode !in 200..299 || ctype.isEmpty() || ctype.startsWith("text/html")) {
                conn.disconnect()
                return null
            }
            val info = DirectInfo(
                filename = fileName(
                    conn.headerFields["Content-Disposition"]?.firstOrNull(),
                    conn.url.toString()),
                size = conn.contentLengthLong,
                contentType = ctype,
                finalUrl = conn.url.toString(),
            )
            conn.disconnect()
            info
        } catch (e: Exception) {
            null
        }
    }

    /** GET with manual redirect following (max 5). Cookie and Referer are
     *  sent only to the original host and dropped on a cross-host redirect,
     *  so a page's session cookie never reaches another site. */
    fun openFollowing(
        url: String, userAgent: String?, cookie: String?, referer: String?,
    ): HttpURLConnection {
        var cur = url
        val origHost = android.net.Uri.parse(url).host
        for (i in 0..5) {
            val c = (URL(cur).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent ?: "VidGrab/1.0")
                val sameHost = android.net.Uri.parse(cur).host == origHost
                if (sameHost && !cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                if (sameHost && !referer.isNullOrEmpty()) setRequestProperty("Referer", referer)
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw IllegalStateException("Bad redirect")
                cur = URL(URL(cur), loc).toString()
                if (!cur.startsWith("http://") && !cur.startsWith("https://"))
                    throw IllegalStateException("Redirect to unsupported scheme")
                continue
            }
            return c
        }
        throw IllegalStateException("Too many redirects")
    }

    /** Best-effort filename: Content-Disposition first, then the URL path
     *  basename; always reduced to a safe basename. */
    fun fileName(disposition: String?, url: String): String {
        var name = ""
        if (disposition != null) {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(disposition)
            if (m != null) name = m.groupValues[1].trim()
        }
        if (name.isEmpty()) {
            // Uri.lastPathSegment percent-decodes; URL.path does not.
            name = android.net.Uri.parse(url).lastPathSegment ?: ""
        }
        name = name.replace(Regex("[<>:\"|?*\\u0000-\\u001f]"), "_")
            .trim().trimEnd('.', ' ')
        return name.ifEmpty { "download" }
    }
}
