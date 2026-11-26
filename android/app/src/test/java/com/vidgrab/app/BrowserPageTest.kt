package com.vidgrab.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserPageTest {
    private fun page(): BrowserPage {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return BrowserPage(ctx, {}, {}).also { it.build() }
    }

    @Test fun submitSearchTextAndUrlsDoNotThrow() {
        val p = page()
        p.go("how to cook rice")
        p.go("example.com")
        p.go("https://example.com/a?b=c")
        p.go("   ")
        assertTrue(true)
    }

    @Test fun siteToggleStoresPerHost() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val s = AdBlockSettings(ctx)
        assertTrue(s.enabledFor("a.com"))
        s.setEnabled("a.com", false)
        assertTrue(!s.enabledFor("a.com") && s.enabledFor("b.com"))
        s.setEnabled("a.com", true)
        assertTrue(s.enabledFor("a.com"))
    }
}
