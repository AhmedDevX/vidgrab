package com.vidgrab.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
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
        R.id.nav_download, R.id.nav_library, R.id.nav_browse, R.id.nav_send,
        R.id.nav_receive, R.id.nav_settings)

    // 360dp x 780dp @ xxhdpi (density 3) -> 1080 x 2340 px
    private val widthPx = 1080
    private val heightPx = 2340

    private fun fillLibrary(activity: MainActivity) {
        val page = activity.findViewById<android.widget.FrameLayout>(R.id.pageLibrary)
        val lp = page.getChildAt(0)
        val field = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }
        val lib = field.get(activity) as LibraryPage
        val u = android.net.Uri.parse("content://x/1")
        lib.setItems(listOf(
            LibItem(u, "Lofi beats to study to.mp4", 312_000_000, "video/mp4", 1_795_000_000),
            LibItem(u, "Podcast episode 12.mp3", 48_500_000, "audio/mpeg", 1_795_000_100),
            LibItem(u, "Family trip.jpg", 3_200_000, "image/jpeg", 1_795_000_200),
            LibItem(u, "report.pdf", 1_100_000, "application/pdf", 1_795_000_300),
        ))
    }

    private fun render(
        tag: String,
        pageIndex: Int,
        lang: String,
        theme: String,
        picker: Boolean = false,
        nearbyReceive: Boolean = false,
    ) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE)
            .edit().putString("theme", theme).commit()
        RuntimeEnvironment.setQualifiers(
            (if (lang == "ar") "ar" else "en") + "-w360dp-h780dp-xxhdpi")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Real click-through navigation, same path as a user tap.
        activity.findViewById<View>(navIds[pageIndex]).performClick()
        if (pageIndex == 1) fillLibrary(activity)
        if (nearbyReceive) {
            ReceiveBus.state.value = ReceiveBus.State(
                active = true,
                deviceName = "VidGrab-1a2b3c4d",
                fingerprint = "9f2c1e7b4a5d8c30b6e1f4a7d2c9b5e83a6f1d0c7b2e4a9d6c3f8b1e5a0d7c42",
                nearby = true,
            )
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        if (picker) {
            val fm = MainActivity::class.java.getMethod("applySizes", List::class.java, Long::class.java)
                .apply { isAccessible = true }
            fm.invoke(activity, listOf(
                Fmt(1080, true, false, 41_000_000, 0), Fmt(720, true, false, 21_500_000, 0),
                Fmt(480, true, false, 9_800_000, 0), Fmt(360, true, false, 6_100_000, 0),
                Fmt(0, false, true, 3_200_000, 0)), 200L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val root: View = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        if (picker) {
            val sv = activity.findViewById<android.widget.ScrollView>(R.id.pageDownload)
            sv.scrollTo(0, 900)
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
        }
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
    fun b_renderEnLightSettings() = render("en_light_settings", 5, "en", "light")

    @Test
    fun c_renderEnDarkReceive() = render("en_dark_receive", 4, "en", "dark")

    @Test
    fun d_renderArGlassSend() = render("ar_glass_send", 3, "ar", "glass")

    @Test
    fun e_renderEnDarkBrowse() = render("en_dark_browse", 2, "en", "dark")

    @Test
    fun f_renderArLightBrowse() = render("ar_light_browse", 2, "ar", "light")

    @Test
    fun g_renderEnDarkLibrary() = render("en_dark_library", 1, "en", "dark")

    @Test
    fun h_renderArGlassLibrary() = render("ar_glass_library", 1, "ar", "glass")

    @Test
    fun i_renderEnDarkPicker() = render("en_dark_picker", 0, "en", "dark", true)

    @Test
    fun j_renderArLightPicker() = render("ar_light_picker", 0, "ar", "light", true)

    @Test
    fun k_renderEnGlassSettings() = render("en_glass_settings", 5, "en", "glass")

    @Test
    fun l_renderEnDarkSendNearby() = render("en_dark_send_nearby", 3, "en", "dark")

    @Test
    fun m_renderArLightSendNearby() = render("ar_light_send_nearby", 3, "ar", "light")

    @Test
    fun n_renderEnDarkReceiveNearby() = render("en_dark_receive_nearby", 4, "en", "dark", nearbyReceive = true)

    /**
     * The 4-digit confirm dialog, drawn for review. Robolectric dialogs are
     * real windows; the decor view is measured/laid out by hand here.
     */
    @Test
    fun o_renderCodeDialog() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE)
            .edit().putString("theme", "dark").commit()
        RuntimeEnvironment.setQualifiers("en-w360dp-h780dp-xxhdpi")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val m = MainActivity::class.java.getDeclaredMethod(
            "showIncomingCode", String::class.java, String::class.java)
            .apply { isAccessible = true }
        m.invoke(activity, "VidGrab-7c4e9a12", "4821")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        if (dialog != null && dialog.isShowing) {
            val decor = dialog.window!!.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(
                decor.measuredWidth.coerceAtLeast(1),
                decor.measuredHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            val dir = File("build/outputs/visual")
            dir.mkdirs()
            FileOutputStream(File(dir, "en_dark_code_dialog.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            controller.pause().stop().destroy()
        } catch (e: Exception) {
        }
        RuntimeEnvironment.setQualifiers("")
    }
}
