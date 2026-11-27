package com.vidgrab.app

import com.yausername.youtubedl_android.mapper.VideoFormat

/** One stream, reduced to what the size estimate needs. size is 0 when unknown. */
data class Fmt(
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val size: Long,
    val tbrKbps: Int,
)

/** Estimated download size per quality choice, from yt-dlp's own format list
 *  (filesize, else filesize_approx, else bitrate x duration). Always an
 *  estimate: the final file can differ (merging overhead, MP3 conversion). */
object SizeEstimate {

    fun fromVideoFormat(f: VideoFormat): Fmt {
        val v = f.vcodec
        val a = f.acodec
        val size = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate
        return Fmt(
            height = f.height,
            hasVideo = v != null && v != "none" && f.height > 0,
            hasAudio = a != null && a != "none",
            size = size,
            tbrKbps = f.tbr,
        )
    }

    private fun sizeOf(f: Fmt, durationSec: Long): Long = when {
        f.size > 0 -> f.size
        f.tbrKbps > 0 && durationSec > 0 -> f.tbrKbps * 1000L / 8 * durationSec
        else -> 0L
    }

    /** maxHeight null = best available. Returns null when it cannot be known. */
    fun estimate(formats: List<Fmt>, maxHeight: Int?, audioOnly: Boolean, durationSec: Long): Long? {
        val audioOnlyFmts = formats.filter { it.hasAudio && !it.hasVideo }
        fun bestAudio(): Long? = audioOnlyFmts
            .map { sizeOf(it, durationSec) }.filter { it > 0 }.maxOrNull()
        if (audioOnly) {
            return bestAudio() ?: formats.filter { it.hasAudio }
                .map { sizeOf(it, durationSec) }.filter { it > 0 }.minOrNull()
        }
        val videos = formats.filter { it.hasVideo && (maxHeight == null || it.height <= maxHeight) }
        if (videos.isEmpty()) return null
        val top = videos.maxOf { it.height }
        val v = videos.filter { it.height == top }
            .maxByOrNull { sizeOf(it, durationSec) } ?: return null
        val vs = sizeOf(v, durationSec)
        if (vs <= 0) return null
        if (v.hasAudio) return vs
        val a = bestAudio() ?: return null
        return vs + a
    }

    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "~%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "~%d MB".format((bytes / 1e6).toLong().coerceAtLeast(1))
        else -> "~%d KB".format((bytes / 1e3).toLong().coerceAtLeast(1))
    }
}
