package com.vidgrab.app

import android.content.Context

/** Formats one transfer progress line: percent, speed and ETA, localized. */
object ProgressText {
    fun line(ctx: Context, name: String, received: Long, total: Long, startedNanos: Long): String {
        val pct = if (total > 0) (received * 100 / total).toInt() else 0
        val elapsedSec = ((System.nanoTime() - startedNanos) / 1_000_000_000.0)
            .coerceAtLeast(0.001)
        val bps = received / elapsedSec
        val speed = if (bps >= 1_000_000) {
            ctx.getString(R.string.speed_mbps, bps / 1_000_000.0)
        } else {
            ctx.getString(R.string.speed_kbps, bps / 1_000.0)
        }
        val etaSec = if (bps > 1) ((total - received) / bps).toLong().coerceAtLeast(0) else 0
        val eta = "%d:%02d".format(etaSec / 60, etaSec % 60)
        return ctx.getString(R.string.transfer_progress, name, pct, speed, eta)
    }
}
