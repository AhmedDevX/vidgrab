package com.vidgrab.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V8LogicTest {
    private val fm = listOf(
        Fmt(1080, true, false, 200_000_000, 0),
        Fmt(720, true, false, 100_000_000, 0),
        Fmt(360, true, false, 0, 500),       // size from bitrate x duration
        Fmt(0, false, true, 10_000_000, 0),
        Fmt(0, false, true, 4_000_000, 0),
        Fmt(480, true, true, 60_000_000, 0), // progressive (has audio)
    )

    @Test fun bestAndCappedQualities() {
        assertEquals(210_000_000L, SizeEstimate.estimate(fm, null, false, 100))
        assertEquals(210_000_000L, SizeEstimate.estimate(fm, 1080, false, 100))
        assertEquals(110_000_000L, SizeEstimate.estimate(fm, 720, false, 100))
        assertEquals(60_000_000L, SizeEstimate.estimate(fm, 480, false, 100))
    }

    @Test fun bitrateFallbackAndUnknown() {
        // 360p: 500 kbps * 100 s = 6.25 MB, + best audio 10 MB
        assertEquals(6_250_000L + 10_000_000L, SizeEstimate.estimate(fm, 360, false, 100))
        // no duration: bitrate cannot be used -> unknown, never a made-up number
        assertNull(SizeEstimate.estimate(fm, 360, false, 0))
        assertNull(SizeEstimate.estimate(emptyList(), null, false, 100))
        assertNull(SizeEstimate.estimate(fm, 144, false, 100))
    }

    @Test fun audioOnly() {
        assertEquals(10_000_000L, SizeEstimate.estimate(fm, null, true, 100))
    }

    @Test fun formatSize() {
        assertEquals("~300 MB", SizeEstimate.format(300_400_000))
        assertEquals("~1.5 GB", SizeEstimate.format(1_500_000_000))
        assertEquals("~5 KB", SizeEstimate.format(5_000))
    }

    @Test fun fileKinds() {
        assertEquals(FileKind.VIDEO, FileKind.of("a.MKV", null))
        assertEquals(FileKind.AUDIO, FileKind.of("a.bin", "audio/mpeg"))
        assertEquals(FileKind.IMAGE, FileKind.of("a.webp", null))
        assertEquals(FileKind.OTHER, FileKind.of("a.pdf", "application/pdf"))
        val u = android.net.Uri.EMPTY
        val items = listOf(LibItem(u, "a.mp4", 1, null, 0), LibItem(u, "b.mp3", 1, null, 0),
            LibItem(u, "c.zip", 1, null, 0))
        assertEquals(3, LibraryPage.filter(items, null).size)
        assertEquals(listOf("b.mp3"), LibraryPage.filter(items, FileKind.AUDIO).map { it.name })
    }

    @Test fun clipLink() {
        assertEquals("https://a.com/x?y=1", ClipLink.pick("look https://a.com/x?y=1 ok", "", null))
        assertNull(ClipLink.pick("no link", "", null))
        assertNull(ClipLink.pick(null, "", null))
        assertNull(ClipLink.pick("https://a.com/x", "https://a.com/x", null))   // already there
        assertNull(ClipLink.pick("https://a.com/x", "", "https://a.com/x"))     // already auto-pasted
        assertEquals("https://b.com", ClipLink.pick("https://b.com", "https://a.com", "https://a.com"))
    }
}
