package com.vidgrab.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayerRenderTest {
    private fun shot(kind: String, tag: String, lang: String = "en") {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE).edit().putString("theme", "dark").commit()
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h780dp-xxhdpi")
        val i = Intent(app, PlayerActivity::class.java).setData(Uri.parse("content://x/1"))
            .putExtra(PlayerActivity.EXTRA_KIND, kind)
            .putExtra(PlayerActivity.EXTRA_NAME, "Lofi beats to study to.mp3")
        val c = Robolectric.buildActivity(PlayerActivity::class.java, i).setup()
        val content = c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 1080, 2340)
        println("PLAYER child=" + content.javaClass.simpleName + " n=" + (content as ViewGroup).childCount +
            " bg=" + content.background + " w=" + content.width)
        println("PLAYER vis=" + content.visibility + " alpha=" + content.alpha + " bounds=" + content.background.bounds)
        for (k in 0 until content.childCount) { val v = content.getChildAt(k); println("PLAYER kid " + v.javaClass.simpleName + " vis=" + v.visibility + " " + v.left + "," + v.top + "," + v.width + "x" + v.height) }
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        content.draw(cv)
        for (k in 0 until content.childCount) {
            val v = content.getChildAt(k)
            val b2 = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val c2 = Canvas(b2); c2.translate(v.left.toFloat(), v.top.toFloat()); v.draw(c2)
            val px = IntArray(1080 * 2340); b2.getPixels(px, 0, 1080, 0, 0, 1080, 2340)
            println("PLAYER kid" + k + " nonzero=" + px.count { it != 0 })
        }
        println("PLAYER attached=" + content.isAttachedToWindow + " hw=" + content.isHardwareAccelerated)
        println("PLAYER px=" + Integer.toHexString(bmp.getPixel(5, 5)) + " " + Integer.toHexString(bmp.getPixel(540, 2000)))
        val dir = File("build/outputs/visual"); dir.mkdirs()
        FileOutputStream(File(dir, "$tag.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try { c.pause().stop().destroy() } catch (_: Exception) {}
        RuntimeEnvironment.setQualifiers("")
    }

    @Test fun audio() = shot("AUDIO", "en_dark_player")
    @Test fun audioAr() = shot("AUDIO", "ar_glass_player", "ar")
    @Test fun video() = shot("VIDEO", "en_dark_video")
}
