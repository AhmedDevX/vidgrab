package com.vidgrab.app

enum class FileKind { VIDEO, AUDIO, IMAGE, OTHER;
    companion object {
        private val video = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts")
        private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "oga")
        private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")

        fun of(name: String, mime: String?): FileKind {
            val m = (mime ?: "").lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                m.startsWith("video/") || ext in video -> VIDEO
                m.startsWith("audio/") || ext in audio -> AUDIO
                m.startsWith("image/") || ext in image -> IMAGE
                else -> OTHER
            }
        }
    }
}
