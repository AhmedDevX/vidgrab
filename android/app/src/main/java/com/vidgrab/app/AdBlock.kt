package com.vidgrab.app

import android.content.Context

/** Host-based blocker. Hosts come from assets/blocklist.txt, derived from
 *  EasyList (dual GPLv3+ / CC BY-SA 3.0, https://easylist.to). Matches the
 *  listed host and any of its subdomains. It cannot block ads served from
 *  the same host as the content, and it is not a full filter engine. */
class AdBlock(private val hosts: Set<String>) {

    val size: Int get() = hosts.size

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (h.contains('.')) {
            if (h in hosts) return true
            h = h.substringAfter('.')
        }
        return false
    }

    companion object {
        fun parse(text: String): Set<String> = text.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toHashSet()

        @Volatile
        private var cached: AdBlock? = null

        fun get(context: Context): AdBlock {
            cached?.let { return it }
            val text = context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }
            return AdBlock(parse(text)).also { cached = it }
        }
    }
}

/** Per-site on/off switch (stored by page host). Default: on. */
class AdBlockSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vidgrab_adblock", Context.MODE_PRIVATE)

    fun enabledFor(host: String?): Boolean {
        if (host.isNullOrEmpty()) return true
        return host.lowercase() !in prefs.getStringSet("off", emptySet())!!
    }

    fun setEnabled(host: String, on: Boolean) {
        val set = prefs.getStringSet("off", emptySet())!!.toMutableSet()
        if (on) set.remove(host.lowercase()) else set.add(host.lowercase())
        prefs.edit().putStringSet("off", set).apply()
    }
}
