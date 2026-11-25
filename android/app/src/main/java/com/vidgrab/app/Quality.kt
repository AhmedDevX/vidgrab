package com.vidgrab.app

data class Quality(
    val label: String,
    val format: String,
    val audioOnly: Boolean = false,
)

/** yt-dlp format selectors presented to the user as quality choices. */
val QUALITIES = listOf(
    Quality("Best quality", "bv*+ba/b"),
    Quality("1080p", "bv*[height<=1080]+ba/b[height<=1080]"),
    Quality("720p", "bv*[height<=720]+ba/b[height<=720]"),
    Quality("480p", "bv*[height<=480]+ba/b[height<=480]"),
    Quality("360p", "bv*[height<=360]+ba/b[height<=360]"),
    Quality("Audio only (MP3)", "ba/b", audioOnly = true),
)
