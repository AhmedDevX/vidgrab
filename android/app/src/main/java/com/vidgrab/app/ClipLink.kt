package com.vidgrab.app

/** Decides whether copied text should be auto-pasted into the link field. */
object ClipLink {
    private val urlRegex = Regex("https?://\\S+")

    /** Returns the URL to paste, or null when there is none, it is already
     *  in the field, or it was the last one auto-pasted (so a link the user
     *  cleared is not pasted again). */
    fun pick(clip: String?, current: String, lastPasted: String?): String? {
        val url = urlRegex.find(clip ?: return null)?.value?.trimEnd('.', ',', ')') ?: return null
        if (url == lastPasted) return null
        if (current.contains(url)) return null
        return url
    }
}
