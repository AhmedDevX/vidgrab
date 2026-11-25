package com.vidgrab.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Lazy one-time init of the embedded yt-dlp + ffmpeg engine. */
object Engine {
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
