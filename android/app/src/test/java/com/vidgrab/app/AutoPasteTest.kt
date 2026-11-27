package com.vidgrab.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoPasteTest {
    private fun copy(text: String) {
        val cm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("t", text))
    }

    @Test fun copiedLinkIsPastedWhenWindowGainsFocus() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = a.findViewById<EditText>(R.id.urlInput)
        copy("check this https://example.com/watch?v=abc please")
        a.onWindowFocusChanged(true)
        assertEquals("https://example.com/watch?v=abc", field.text.toString())
        // user clears it; same link must not come back on the next focus
        field.setText("")
        a.onWindowFocusChanged(true)
        assertEquals("", field.text.toString())
        // a new link is pasted
        copy("https://example.org/v/2")
        a.onWindowFocusChanged(true)
        assertEquals("https://example.org/v/2", field.text.toString())
    }

    @Test fun losingFocusOrNoUrlDoesNothing() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = a.findViewById<EditText>(R.id.urlInput)
        copy("https://example.com/x")
        a.onWindowFocusChanged(false)
        assertEquals("", field.text.toString())
        copy("just some words")
        a.onWindowFocusChanged(true)
        assertEquals("", field.text.toString())
    }

    @Test fun switchOffDisablesIt() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("autopaste", false).commit()
        copy("https://example.com/x")
        a.onWindowFocusChanged(true)
        assertEquals("", a.findViewById<EditText>(R.id.urlInput).text.toString())
    }
}
