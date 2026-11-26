package com.vidgrab.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Lazy one-time init of the embedded yt-dlp + ffmpeg engine. */
object Engine {
    /** The bundled QuickJS runtime (a PIE executable shipped as libqjs.so).
     *  yt-dlp needs a JS runtime for current YouTube extraction; it is not
     *  used unless passed via --js-runtimes. */
    fun jsRuntimeOption(context: Context): Pair<String, String>? {
        val qjs = java.io.File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        return if (qjs.canExecute()) "--js-runtimes" to "quickjs:${qjs.absolutePath}" else null
    }

    /** Installed yt-dlp version (what the app actually runs), or "unknown". */
    fun version(context: Context): String = try {
        YoutubeDL.getInstance().versionName(context.applicationContext)
            ?: YoutubeDL.getInstance().version(context.applicationContext) ?: "unknown"
    } catch (e: Exception) { "unknown" }

    /** Update yt-dlp; returns a human line with real status and version. */
    fun update(context: Context, nightly: Boolean): String {
        val ch = if (nightly) YoutubeDL.UpdateChannel._NIGHTLY else YoutubeDL.UpdateChannel._STABLE
        val st = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, ch)
        val name = if (nightly) "nightly" else "stable"
        return when (st) {
            YoutubeDL.UpdateStatus.DONE -> "Updated ($name). yt-dlp ${version(context)}"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($name). yt-dlp ${version(context)}"
            else -> "Update finished ($name). yt-dlp ${version(context)}"
        }
    }

    @Volatile
    var ready = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready || error != null) return
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
}
