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
import android.widget.Button
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
    private val shield = Button(context)
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
            if (on) R.string.shield_on else R.string.shield_off, blockedCount.get() + popupCount)
        shield.isEnabled = h != null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun build() {
        val dp = context.resources.displayMetrics.density
        fun btn(text: String, onClick: () -> Unit) = Button(context).apply {
            this.text = text
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
        }
        urlBar.apply {
            setSingleLine()
            hint = context.getString(R.string.browse_hint)
            textDirection = View.TEXT_DIRECTION_LTR
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), 0)
            addView(btn("<") { if (web.canGoBack()) web.goBack() })
            addView(btn(">") { if (web.canGoForward()) web.goForward() })
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("\u21BB") { web.reload() })
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            addView(btn(context.getString(R.string.download_this_page)) {
                val u = web.url
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) onDownloadPage(u)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            shield.setOnClickListener {
                val h = pageHost ?: return@setOnClickListener
                settings.setEnabled(h, !siteOn())
                blockedCount.set(0); popupCount = 0
                refreshShield()
                web.reload()
            }
            addView(shield)
        }
        hint.apply {
            text = context.getString(R.string.browse_note)
            textSize = 12f
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        progress.max = 100
        root.addView(bar)
        root.addView(actions)
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (4 * dp).toInt()))
        root.addView(hint)
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
