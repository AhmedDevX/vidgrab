package com.vidgrab.app

import org.junit.Assert.*
import org.junit.Test

class V6LogicTest {

    @Test fun parseSearchLines() {
        val out = """
            {"id":"abc","title":"One","channel":"Ch","duration":125.0,"url":"https://www.youtube.com/watch?v=abc"}
            WARNING: noise
            {"id":"def","title":"Two","uploader":"Up","duration":null,"url":"def"}
            {"id":"","title":"bad"}
            {not json
        """.trimIndent()
        val hits = Search.parseLines(out)
        assertEquals(listOf("abc", "def"), hits.map { it.id })
        assertEquals("Ch", hits[0].channel)
        assertEquals(125L, hits[0].durationSec)
        assertEquals("Up", hits[1].channel)
        assertNull(hits[1].durationSec)
        assertEquals("https://www.youtube.com/watch?v=def", hits[1].url)
        assertEquals("https://i.ytimg.com/vi/abc/mqdefault.jpg", hits[0].thumbUrl)
    }

    @Test fun formatDuration() {
        assertEquals("2:05", Search.formatDuration(125))
        assertEquals("1:02:05", Search.formatDuration(3725))
        assertEquals("", Search.formatDuration(null))
    }

    @Test fun adblockMatchesHostAndSubdomains() {
        val ab = AdBlock(AdBlock.parse("# c\nads.example.com\ntracker.net\n"))
        assertTrue(ab.isBlockedHost("ads.example.com"))
        assertTrue(ab.isBlockedHost("cdn.ads.example.com"))
        assertTrue(ab.isBlockedHost("TRACKER.NET"))
        assertFalse(ab.isBlockedHost("example.com"))
        assertFalse(ab.isBlockedHost("nottracker.net"))
        assertFalse(ab.isBlockedHost(null))
        assertFalse(ab.isBlockedHost("com"))
    }

    @Test fun shippedBlocklistIsSaneAndSparesContentHosts() {
        val text = java.io.File("src/main/assets/blocklist.txt").readText()
        val ab = AdBlock(AdBlock.parse(text))
        assertTrue(ab.size > 10000)
        for (h in listOf("youtube.com", "www.youtube.com", "google.com", "facebook.com",
            "i.ytimg.com", "googlevideo.com", "wikipedia.org", "github.com")) {
            assertFalse("$h must not be blocked", ab.isBlockedHost(h))
        }
        assertTrue(ab.isBlockedHost("ad.doubleclick.net"))
    }
}
