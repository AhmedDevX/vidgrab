package com.vidgrab.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream

/** In-app browser: URL bar, back/forward/reload, "Download this page"
 *  (hands the URL to yt-dlp), a generic DownloadListener that routes plain
 *  file downloads into the app's own downloader, and a host-based ad
 *  blocker with a per-site shield toggle and popup blocking. */
class BrowserPage(
    private val context: Context,
    private val onDownloadPage: (String) -> Unit,
    private val onFileDownload: (FileRequest) -> Unit,
) {
    data class FileRequest(
        val url: String, val userAgent: String?, val cookie: String?, val referer: String?,
    )

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val urlBar = EditText(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val shield = TextView(context)
    private val shieldIcon = ImageView(context)
    private val lockIcon = ImageView(context)
    private val empty = LinearLayout(context)
    private val web = WebView(context)
    private val adblock = AdBlock.get(context)
    private val settings = AdBlockSettings(context)
    private val blockedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var popupCount = 0
    // WebView.getUrl() may only be called on the UI thread, but
    // shouldInterceptRequest runs on a network thread. Keep the current
    // page host in a field that the UI thread updates.
    @Volatile private var pageHost: String? = null
    private val hint = TextView(context)

    private fun host(url: String?): String? = try { Uri.parse(url).host } catch (_: Exception) { null }

    /** Safe from any thread: uses the cached page host, never the WebView. */
    private fun siteOn(): Boolean = settings.enabledFor(pageHost)

    private fun refreshShield() {
        val h = pageHost
        val on = siteOn()
        shield.text = context.getString(
            if (on) R.string.shield_pill_on else R.string.shield_pill_off, blockedCount.get() + popupCount)
        val tint = if (on) 0xFFD9BC90.toInt() else Ui.secondaryText(context)
        shield.setTextColor(tint)
        shieldIcon.setColorFilter(tint)
        shield.isEnabled = h != null
        shieldIcon.alpha = if (h != null) 1f else 0.5f
        shield.alpha = if (h != null) 1f else 0.5f
    }

    private fun glass(radius: Float, dp: Float) = GradientDrawable().apply {
        cornerRadius = radius * dp
        setColor(context.getColor(R.color.card_fill))
        setStroke(dp.toInt().coerceAtLeast(1), context.getColor(R.color.card_stroke))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun build() {
        val dp = context.resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        val tint = Ui.primaryText(context)
        fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(res); setColorFilter(tint); contentDescription = desc
            setPadding(px(10), px(10), px(10), px(10))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        // ---- address pill
        lockIcon.apply { setImageResource(R.drawable.ic_search); setColorFilter(Ui.secondaryText(context)) }
        urlBar.apply {
            setSingleLine()
            hint = context.getString(R.string.browse_go)
            setHintTextColor(Ui.secondaryText(context))
            setTextColor(tint)
            background = null
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setSelectAllOnFocus(true)
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(28f, dp)
            setPadding(px(14), px(4), px(4), px(4))
            addView(lockIcon, LinearLayout.LayoutParams(px(20), px(20)))
            addView(urlBar, LinearLayout.LayoutParams(0, px(48), 1f).apply { marginStart = px(8) })
            addView(iconBtn(R.drawable.ic_refresh, "Reload") { web.reload() }, LinearLayout.LayoutParams(px(44), px(44)))
        }
        progress.apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFD9BC90.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
        }
        // ---- web area, rounded and clipped
        val webHost = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(Color.BLACK) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 22 * dp) }
            }
        }
        webHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        // empty state
        empty.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = Ui.background(context)
            setPadding(px(32), 0, px(32), px(60))
            addView(FrameLayout(context).apply {
                setBackgroundResource(R.drawable.orb)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_compass); setColorFilter(0xFF2A1F14.toInt())
                }, FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(84), px(84)))
            addView(TextView(context).apply {
                text = context.getString(R.string.browse_empty_title)
                textSize = 22f; paint.isFakeBoldText = true; gravity = Gravity.CENTER
                setTextColor(tint)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14) })
            addView(TextView(context).apply {
                text = context.getString(R.string.browse_empty_sub)
                textSize = 14f; gravity = Gravity.CENTER
                setTextColor(Ui.secondaryText(context))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        }
        webHost.addView(empty, FrameLayout.LayoutParams(-1, -1))
        // ---- floating controls over the page
        shieldIcon.setImageResource(R.drawable.ic_shield)
        shield.apply { textSize = 14f; paint.isFakeBoldText = true }
        val shieldPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(0xE61E1A18.toInt()); setStroke(px(1), 0x44FFFFFF) }
            setPadding(px(10), px(10), px(10), px(10))
            addView(shieldIcon, LinearLayout.LayoutParams(px(18), px(18)))
            addView(shield, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
            isClickable = true
            setOnClickListener {
                val h = pageHost ?: return@setOnClickListener
                settings.setEnabled(h, !siteOn())
                blockedCount.set(0); popupCount = 0
                refreshShield()
                web.reload()
            }
        }
        fun round(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(res); setColorFilter(0xFFF6EFE6.toInt()); contentDescription = desc
            setPadding(px(12), px(12), px(12), px(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61E1A18.toInt()); setStroke(px(1), 0x44FFFFFF) }
            setOnClickListener { onClick() }
        }
        val back = round(R.drawable.ic_back, "Back") { if (web.canGoBack()) web.goBack() }
        val fwd = round(R.drawable.ic_back, "Forward") { if (web.canGoForward()) web.goForward() }
        fwd.scaleX = -1f
        val dl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFD8BC92.toInt(), 0xFFB98F6A.toInt())).apply { cornerRadius = 26 * dp }
            setPadding(px(12), px(12), px(14), px(12))
            isClickable = true
            contentDescription = context.getString(R.string.download_this_page)
            setOnClickListener {
                val u = web.url
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) onDownloadPage(u)
            }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_download); setColorFilter(0xFF2A1F14.toInt()) },
                LinearLayout.LayoutParams(px(20), px(20)))
            addView(TextView(context).apply {
                text = context.getString(R.string.download); textSize = 15f; paint.isFakeBoldText = true
                setTextColor(0xFF2A1F14.toInt())
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(back, LinearLayout.LayoutParams(px(42), px(42)))
            addView(fwd, LinearLayout.LayoutParams(px(42), px(42)).apply { marginStart = px(6) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(shieldPill)
            addView(dl, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        }
        webHost.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(px(10), 0, px(10), px(10)) })

        root.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(14), px(40), px(14), 0) })
        root.addView(progress, LinearLayout.LayoutParams(-1, px(3)).apply { setMargins(px(30), px(3), px(30), px(3)) })
        root.addView(webHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(px(10), 0, px(10), px(4)) })
        refreshShield()
        setupWeb()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Popups: allow window requests only so we can refuse the ones
            // that have no user gesture (see onCreateWindow).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? {
                if (!request.isForMainFrame && siteOn() &&
                    adblock.isBlockedHost(request.url.host)) {
                    blockedCount.incrementAndGet()
                    view.post { refreshShield() }
                    return WebResourceResponse("text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean {
                val s = request.url.scheme?.lowercase()
                // Only http(s) loads in the app; intent:, market: and other
                // app-launching schemes are refused.
                return s != "http" && s != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                blockedCount.set(0); popupCount = 0
                pageHost = host(url)
                url?.let { urlBar.setText(it) }
                empty.visibility = View.GONE
                val https = url?.startsWith("https://") == true
                lockIcon.setImageResource(if (https) R.drawable.ic_lock else R.drawable.ic_globe)
                refreshShield()
            }

            override fun onPageFinished(view: WebView, url: String?) = refreshShield()
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
            ): Boolean {
                if (!isUserGesture && siteOn()) {
                    popupCount++
                    refreshShield()
                    return false // refuse: script-opened popup without a tap
                }
                // Tap-opened window: load its target in this same view.
                val tmp = WebView(context)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        val s = r.url.scheme?.lowercase()
                        if (s == "http" || s == "https") web.loadUrl(r.url.toString())
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }
        web.setDownloadListener { url, ua, _, _, _ ->
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                android.widget.Toast.makeText(context, R.string.download_unsupported,
                    android.widget.Toast.LENGTH_LONG).show()
            } else {
                val name = URLUtil.guessFileName(url, null, null)
                AlertDialog.Builder(context)
                    .setTitle(R.string.file_download_title)
                    .setMessage(context.getString(R.string.file_download_msg, name, host(url) ?: ""))
                    .setPositiveButton(R.string.download) { _, _ ->
                        onFileDownload(FileRequest(
                            url, ua, CookieManager.getInstance().getCookie(url), web.url))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Turn typed text into a URL (https:// added) or a web search. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
    }

    fun load(url: String) = go(url)

    /** Returns true when it consumed the back press. */
    fun onBack(): Boolean = if (web.canGoBack()) { web.goBack(); true } else false

    fun destroy() = web.destroy()
}
