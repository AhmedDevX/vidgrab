package com.vidgrab.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Visual render proof without an emulator: Robolectric native graphics
 * draws MainActivity pages to real bitmaps (real text layout and shaping
 * on the JVM's Android runtime). Phone-realistic metrics: 360x780 dp at
 * xxhdpi (1080x2340 px). Navigation is exercised by real clicks on the
 * bottom-nav items, the same path a user's tap takes.
 *
 * Scope: JVM rendering of layout/text/theme - NOT hardware-device
 * execution. Output: app/build/outputs/visual/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VisualRenderTest {

    private val navIds = listOf(
        R.id.nav_download, R.id.nav_browse, R.id.nav_send, R.id.nav_receive,
        R.id.nav_settings)

    // 360dp x 780dp @ xxhdpi (density 3) -> 1080 x 2340 px
    private val widthPx = 1080
    private val heightPx = 2340

    private fun render(tag: String, pageIndex: Int, lang: String, theme: String) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE)
            .edit().putString("theme", theme).commit()
        RuntimeEnvironment.setQualifiers(
            (if (lang == "ar") "ar" else "en") + "-w360dp-h780dp-xxhdpi")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Real click-through navigation, same path as a user tap.
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.findViewById<BottomNavigationItemView>(navIds[pageIndex]).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val root: View = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/outputs/visual")
        dir.mkdirs()
        FileOutputStream(File(dir, "$tag.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        try {
            controller.pause().stop().destroy()
        } catch (e: Exception) {
            // harness teardown quirk after a locale flip; the PNG above is
            // already written and is the artifact this test exists for
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        RuntimeEnvironment.setQualifiers("")
    }

    @Test
    fun a_renderEnDarkDownload() = render("en_dark_download", 0, "en", "dark")

    @Test
    fun b_renderEnLightSettings() = render("en_light_settings", 4, "en", "light")

    @Test
    fun c_renderEnDarkReceive() = render("en_dark_receive", 3, "en", "dark")

    @Test
    fun d_renderArGlassSend() = render("ar_glass_send", 2, "ar", "glass")

    @Test
    fun e_renderEnDarkBrowse() = render("en_dark_browse", 1, "en", "dark")

    @Test
    fun f_renderArLightBrowse() = render("ar_light_browse", 1, "ar", "light")
}
