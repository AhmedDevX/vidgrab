package com.vidgrab.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

data class SearchHit(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long?,
    val url: String,
) {
    /** Public YouTube thumbnail URL for this video id. */
    val thumbUrl: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** YouTube search through the embedded yt-dlp (flat extraction, no downloads). */
object Search {

    fun formatDuration(sec: Long?): String {
        if (sec == null || sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Parse yt-dlp `--dump-json --flat-playlist` output (one JSON object per line). */
    fun parseLines(out: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            try {
                val o = JSONObject(t)
                val id = o.optString("id", "")
                val title = o.optString("title", "")
                if (id.isEmpty() || title.isEmpty()) continue
                val channel = o.optString("channel", "").ifEmpty { o.optString("uploader", "") }
                val dur = if (o.has("duration") && !o.isNull("duration"))
                    o.optDouble("duration").toLong() else null
                val u = o.optString("url", "")
                val url = if (u.startsWith("http")) u else "https://www.youtube.com/watch?v=$id"
                hits += SearchHit(id, title, channel, dur, url)
            } catch (_: Exception) {
                // skip malformed line
            }
        }
        return hits
    }

    fun run(context: Context, query: String, count: Int = 15): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        Engine.ensureInit(context)
        if (!Engine.ready) throw IllegalStateException("Engine failed: ${Engine.error}")
        val req = YoutubeDLRequest("ytsearch$count:$q")
        Engine.jsRuntimeOption(context)?.let { req.addOption(it.first, it.second) }
        req.addOption("--flat-playlist")
        req.addOption("--dump-json")
        req.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(req, null, null)
        return parseLines(resp.out)
    }
}
