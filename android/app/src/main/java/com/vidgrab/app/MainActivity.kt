package com.vidgrab.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private val urlRegex = Regex("https?://\\S+")

    private lateinit var urlInput: EditText
    private lateinit var infoText: TextView
    private lateinit var qualityGroup: android.widget.LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var filesLabel: TextView
    private lateinit var devicesHint: TextView
    private lateinit var deviceList: ListView
    private lateinit var sendStatus: TextView
    private lateinit var receiveButton: Button
    private lateinit var receiveStatus: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var receiveQr: ImageView
    private lateinit var sendModeGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var sendHint: TextView

    /** "nearby" (Bluetooth + Wi-Fi Direct) or "wifi" (same network). */
    private var sendMode = "nearby"
    private var nearbySender: NearbySender? = null
    private var nearbyPeers = listOf<NearbyPeer>()
    private var codeDialog: AlertDialog? = null
    private var pendingNearbyAction: (() -> Unit)? = null
    private var onNearbyDenied: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences("vidgrab_settings", MODE_PRIVATE) }
    private val pinStore by lazy { PinStore(this) }
    private var nsd: Nsd? = null
    private var devices = listOf<DiscoveredDevice>()
    private var pickedUris = listOf<Uri>()
    private var receiving = false
    private var offerDialog: AlertDialog? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var directUrl: String? = null
    private val qualityRows = mutableListOf<QualityRow>()
    private var selectedQuality = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val nearbyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result.isNotEmpty() && result.values.all { it }
            val action = pendingNearbyAction
            val denied = onNearbyDenied
            pendingNearbyAction = null
            onNearbyDenied = null
            if (ok) action?.invoke() else denied?.invoke()
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                pickedUris = uris
                filesLabel.text = uris.joinToString(", ") { displayName(it) }.take(400)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        infoText = findViewById(R.id.infoText)
        qualityGroup = findViewById(R.id.qualityGroup)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        filesLabel = findViewById(R.id.filesLabel)
        devicesHint = findViewById(R.id.devicesHint)
        deviceList = findViewById(R.id.deviceList)
        sendStatus = findViewById(R.id.sendStatus)
        receiveButton = findViewById(R.id.receiveButton)
        receiveStatus = findViewById(R.id.receiveStatus)
        fingerprintText = findViewById(R.id.fingerprintText)
        receiveQr = findViewById(R.id.receiveQr)

        window.setBackgroundDrawable(Ui.background(this))
        QUALITIES.forEachIndexed { index, q ->
            val row = QualityRow(this, q) { selectQuality(index) }
            qualityGroup.addView(row.view)
            qualityRows += row
        }
        selectQuality(0)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.downloadButton).apply {
            backgroundTintList = null
            setBackgroundResource(R.drawable.btn_gradient)
            setTextColor(0xFF2A1F14.toInt())
        }

        setupSearch()
        setupBrowser()
        findViewById<Button>(R.id.fetchButton).setOnClickListener { fetchInfo() }
        findViewById<Button>(R.id.downloadButton).setOnClickListener { startDownload() }
        findViewById<Button>(R.id.updateEngineButton).setOnClickListener { updateEngine() }
        refreshEngineVersion()
        findViewById<Button>(R.id.pickFilesButton).setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.refreshDevicesButton).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.pairSendButton).setOnClickListener { pairAndSend() }
        findViewById<Button>(R.id.openShareButton).setOnClickListener {
            startActivity(Intent(this, ShareActivity::class.java))
        }
        receiveButton.setOnClickListener { toggleReceive() }
        setupSendMode()

        setupBottomNav()
        setupSettings()
        findViewById<TextView>(R.id.versionPill).text =
            "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" })
        showOwnFingerprint()

        handleIntent(intent)
        lifecycleScope.launch {
            ProgressBus.state.collect { s -> s?.let { render(it) } }
        }
        lifecycleScope.launch {
            ReceiveBus.state.collect { s -> renderReceive(s) }
        }
        lifecycleScope.launch {
            ReceiveBus.pendingCode.collect { pair ->
                if (pair == null) {
                    codeDialog?.dismiss()
                    codeDialog = null
                } else if (codeDialog?.isShowing != true) {
                    showIncomingCode(pair.first, pair.second)
                }
            }
        }
        lifecycleScope.launch {
            ReceiveBus.pendingOffer.collect { text ->
                if (text != null) {
                    if (offerDialog?.isShowing != true) showOfferDialog(text)
                } else {
                    offerDialog?.dismiss()
                    offerDialog = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var browser: BrowserPage? = null

    private fun setupBrowser() {
        val b = BrowserPage(
            this,
            onDownloadPage = { url ->
                urlInput.setText(url)
                navBar.select(0)
                fetchInfo()
            },
            onFileDownload = { req -> startFileDownload(req) },
        )
        b.build()
        findViewById<android.widget.FrameLayout>(R.id.pageBrowse).addView(b.root)
        browser = b
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val onBrowse = findViewById<View>(R.id.pageBrowse).visibility == View.VISIBLE
        if (onBrowse && browser?.onBack() == true) return
        super.onBackPressed()
    }

    private fun startFileDownload(req: BrowserPage.FileRequest) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        startService(
            Intent(this, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_URL, req.url)
                .putExtra(DownloadService.EXTRA_DIRECT, true)
                .putExtra(DownloadService.EXTRA_USER_AGENT, req.userAgent)
                .putExtra(DownloadService.EXTRA_COOKIE, req.cookie)
                .putExtra(DownloadService.EXTRA_REFERER, req.referer)
        )
        toast(getString(R.string.download_starting))
    }

    // ------------------------------------------------------------- search

    private fun setupSearch() {
        val input = findViewById<EditText>(R.id.searchInput)
        val go = { runSearch(input.text.toString()) }
        findViewById<Button>(R.id.searchButton).setOnClickListener { go() }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        val status = findViewById<TextView>(R.id.searchStatus)
        val box = findViewById<android.widget.LinearLayout>(R.id.searchResults)
        status.text = getString(R.string.searching)
        box.removeAllViews()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(Search.run(this@MainActivity, q)) }
                catch (e: Exception) { Result.failure(e) }
            }
            res.onFailure {
                status.text = getString(R.string.search_failed,
                    "[yt-dlp ${Engine.version(this@MainActivity)}] ${it.message}".take(300))
            }
            res.onSuccess { hits ->
                status.text = if (hits.isEmpty()) getString(R.string.search_none) else ""
                hits.forEach { box.addView(resultRow(it)) }
            }
        }
    }

    private fun resultRow(h: SearchHit): View {
        val dp = resources.displayMetrics.density
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }
        row.addView(thumb, android.widget.LinearLayout.LayoutParams(
            (112 * dp).toInt(), (63 * dp).toInt()))
        val meta = listOf(h.channel, Search.formatDuration(h.durationSec))
            .filter { it.isNotEmpty() }.joinToString(" - ")
        val tv = TextView(this).apply {
            text = if (meta.isEmpty()) h.title else "${h.title}\n$meta"
            maxLines = 3
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }
        row.addView(tv, android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            urlInput.setText(h.url)
            fetchInfo()
            findViewById<View>(R.id.pageDownload).let { (it as? android.widget.ScrollView)?.smoothScrollTo(0, 0) }
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    java.net.URL(h.thumbUrl).openStream().use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                } catch (_: Exception) { null }
            }
            if (bmp != null) thumb.setImageBitmap(bmp)
        }
        return row
    }

    override fun onDestroy() {
        browser?.destroy()
        offerDialog?.dismiss()
        offerDialog = null
        nsd?.close()
        nsd = null
        nearbySender?.close()
        nearbySender = null
        codeDialog?.dismiss()
        codeDialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- navigation

    private lateinit var navBar: NavBar
    private var library: LibraryPage? = null

    private fun setupBottomNav() {
        val pages = listOf(
            findViewById<View>(R.id.pageDownload),
            findViewById<View>(R.id.pageLibrary),
            findViewById<View>(R.id.pageBrowse),
            findViewById<View>(R.id.pageSend),
            findViewById<View>(R.id.pageReceive),
            findViewById<View>(R.id.pageSettings),
        )
        val lib = LibraryPage(this).also { it.build() }
        library = lib
        findViewById<android.widget.FrameLayout>(R.id.pageLibrary).addView(lib.root)
        navBar = NavBar(this, listOf(
            NavBar.Item(R.id.nav_download, R.drawable.ic_download, R.string.tab_download),
            NavBar.Item(R.id.nav_library, R.drawable.ic_folder, R.string.tab_library),
            NavBar.Item(R.id.nav_browse, R.drawable.ic_compass, R.string.tab_browse),
            NavBar.Item(R.id.nav_send, R.drawable.ic_send, R.string.tab_send),
            NavBar.Item(R.id.nav_receive, R.drawable.ic_inbox, R.string.tab_receive),
            NavBar.Item(R.id.nav_settings, R.drawable.ic_gear, R.string.tab_settings),
        )) { idx ->
            pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
            if (idx == 1) library?.refresh()
            if (idx == 3) ensureDiscovery()
        }
        findViewById<android.widget.FrameLayout>(R.id.navHost).addView(navBar.view)
        navBar.select(0)
    }

    // ------------------------------------------------------------- settings

    private fun applyTheme() {
        when (prefs.getString("theme", "dark")) {
            "light" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_VidGrab)
            }
            "glass" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_VidGrab_Glass)
            }
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_VidGrab)
            }
        }
    }

    private fun setupSettings() {
        when (prefs.getString("theme", "dark")) {
            "light" -> findViewById<RadioButton>(R.id.themeLight).isChecked = true
            "glass" -> findViewById<RadioButton>(R.id.themeGlass).isChecked = true
            else -> findViewById<RadioButton>(R.id.themeDark).isChecked = true
        }
        val isArabic = resources.configuration.locales[0]?.language == "ar"
        findViewById<RadioButton>(if (isArabic) R.id.langAr else R.id.langEn).isChecked = true

        findViewById<android.widget.CompoundButton>(R.id.autoPasteSwitch).apply {
            isChecked = autoPasteEnabled()
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("autopaste", on).apply() }
        }
        findViewById<RadioGroup>(R.id.themeGroup).setOnCheckedChangeListener { _, id ->
            val name = when (id) {
                R.id.themeLight -> "light"
                R.id.themeGlass -> "glass"
                else -> "dark"
            }
            if (name != prefs.getString("theme", "dark")) {
                prefs.edit().putString("theme", name).apply()
                recreate()
            }
        }
        findViewById<RadioGroup>(R.id.langGroup).setOnCheckedChangeListener { _, id ->
            val tag = if (id == R.id.langAr) "ar" else "en"
            val current = resources.configuration.locales[0]?.language ?: "en"
            if (tag != current) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
    }

    // ------------------------------------------------------------- download

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                urlRegex.find(text)?.let { urlInput.setText(it.value) }
            }
        }
    }

    private fun currentUrl(): String? =
        urlRegex.find(urlInput.text.toString())?.value.also {
            if (it == null) toast(getString(R.string.url_hint))
        }

    private fun fetchInfo() {
        val url = currentUrl() ?: return
        directUrl = null
        applySizes(null, 0)
        infoText.text = getString(R.string.fetching)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Engine.ensureInit(this@MainActivity)
                if (!Engine.ready) return@withContext "Engine failed to start: ${Engine.error}"
                try {
                    val req = YoutubeDLRequest(url)
                    Engine.jsRuntimeOption(this@MainActivity)?.let { req.addOption(it.first, it.second) }
                    val info = YoutubeDL.getInstance().getInfo(req)
                    val mins = info.duration.let { if (it > 0) " - ${it / 60} min" else "" }
                    val fmts = (info.formats ?: arrayListOf()).map { SizeEstimate.fromVideoFormat(it) }
                    val dur = info.duration.toLong()
                    runOnUiThread { applySizes(fmts, dur) }
                    "${info.title}$mins"
                } catch (e: Exception) {
                    // yt-dlp has no extractor: maybe a plain direct file link.
                    val direct = DirectFetch.probe(url)
                    if (direct != null) {
                        directUrl = url
                        val sizeText = if (direct.size > 0) {
                            "%.1f MB".format(direct.size / 1e6)
                        } else {
                            getString(R.string.size_unknown)
                        }
                        return@withContext getString(
                            R.string.direct_file, direct.filename, sizeText)
                    }
                    "Could not read this link: ${e.message}"
                }
            }
            infoText.text = result
        }
    }

    /** Put an estimated size next to each quality (null formats = clear). */
    fun applySizes(fmts: List<Fmt>?, durationSec: Long) {
        QUALITIES.forEachIndexed { i, q ->
            val maxH = Regex("height<=(\\d+)").find(q.format)?.groupValues?.get(1)?.toInt()
            qualityRows.getOrNull(i)?.setSize(if (fmts == null) null else {
                val est = SizeEstimate.estimate(fmts, maxH, q.audioOnly, durationSec)
                if (est != null) SizeEstimate.format(est) else getString(R.string.size_unknown_q)
            })
        }
    }

    private fun selectQuality(i: Int) {
        selectedQuality = i
        qualityRows.forEachIndexed { k, r -> r.setSelected(k == i) }
    }

    // ---------------------------------------------------------- auto-paste

    private fun autoPasteEnabled() = prefs.getBoolean("autopaste", true)

    /** Android 10+ lets only the focused foreground app read the clipboard,
     *  so this runs when the window gains focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !autoPasteEnabled()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        val url = ClipLink.pick(text, urlInput.text.toString(), prefs.getString("last_pasted", null))
            ?: return
        prefs.edit().putString("last_pasted", url).apply()
        urlInput.setText(url)
        directUrl = null
        toast(getString(R.string.pasted_toast))
    }

    private fun startDownload() {
        val url = currentUrl() ?: return
        val quality = QUALITIES[selectedQuality.coerceIn(0, QUALITIES.size - 1)]
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startService(
            Intent(this, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_URL, url)
                .putExtra(DownloadService.EXTRA_FORMAT, quality.format)
                .putExtra(DownloadService.EXTRA_AUDIO_ONLY, quality.audioOnly)
                .putExtra(DownloadService.EXTRA_DIRECT, directUrl == url)
        )
        statusText.text = getString(R.string.download_starting)
    }

    private fun refreshEngineVersion() {
        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) {
                Engine.ensureInit(this@MainActivity)
                if (Engine.ready) Engine.version(this@MainActivity) else "not started"
            }
            findViewById<Button>(R.id.updateEngineButton).text =
                getString(R.string.update_engine) + " (yt-dlp $v)"
        }
    }

    private fun updateEngine() {
        toast(getString(R.string.download_starting))
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                Engine.ensureInit(this@MainActivity)
                if (!Engine.ready) return@withContext "Engine failed to start: ${Engine.error}"
                try {
                    Engine.update(applicationContext, nightly = false)
                } catch (e: Exception) {
                    "Update failed: ${e.message}"
                }
            }
            toast(msg)
            refreshEngineVersion()
        }
    }

    private var lastErrorDialog: String? = null

    private fun render(s: ProgressBus.State) {
        progressBar.progress = s.percent.toInt()
        statusText.text = when {
            s.error != null -> "Failed: ${s.error}"
            s.done -> s.line
            s.running -> "${s.percent.toInt()}%  ${s.line}"
            else -> s.line
        }
        if (s.error != null) showErrorOnce(s.error)
        if (s.done) library?.refresh()
    }

    private fun showErrorOnce(err: String) {
        if (err == lastErrorDialog) return
        lastErrorDialog = err
        AlertDialog.Builder(this)
            .setTitle("Download failed")
            .setMessage(err)
            .setPositiveButton("Copy error") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VidGrab error", err))
                toast("Error copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ----------------------------------------------------------------- send

    private fun ensureDiscovery() {
        if (nsd == null) {
            nsd = Nsd(this).apply {
                onDevicesChanged = { list ->
                    devices = list.filter { it.deviceId != ownDeviceId() }
                    runOnUiThread { renderDevices() }
                }
            }
        }
        try {
            nsd?.startDiscovery()
        } catch (e: Exception) {
            // NSD unavailable (rare devices, restricted profiles, test
            // harnesses): stay on the empty "no devices" state instead of
            // crashing; the user can retry with the refresh action.
            devices = emptyList()
            runOnUiThread { renderDevices() }
        }
    }

    private fun setupSendMode() {
        sendModeGroup = findViewById(R.id.sendModeGroup)
        sendHint = findViewById(R.id.sendHint)
        sendMode = prefs.getString("send_mode", "nearby") ?: "nearby"
        sendModeGroup.check(if (sendMode == "wifi") R.id.sendModeWifi else R.id.sendModeNearby)
        tintSendMode()
        applySendModeLabels()
        sendModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sendMode = if (checkedId == R.id.sendModeWifi) "wifi" else "nearby"
            prefs.edit().putString("send_mode", sendMode).apply()
            tintSendMode()
            applySendModeLabels()
            renderDevices()
        }
    }

    /** Selected pill in the app's sand accent with dark text, not the
     *  default violet Material tint. */
    private fun tintSendMode() {
        val sand = 0xFFD9BC90.toInt()
        val ink = 0xFF2A1F14.toInt()
        for (i in 0 until sendModeGroup.childCount) {
            val b = sendModeGroup.getChildAt(i) as? com.google.android.material.button.MaterialButton
                ?: continue
            val on = b.isChecked
            b.backgroundTintList =
                android.content.res.ColorStateList.valueOf(if (on) sand else 0x00000000)
            b.setTextColor(if (on) ink else Ui.secondaryText(this))
        }
    }

    private fun applySendModeLabels() {
        sendHint.text = getString(
            if (sendMode == "nearby") R.string.send_hint_nearby else R.string.send_hint)
        if (sendMode == "wifi") {
            nearbySender?.stopDiscovery()
            nearbyPeers = emptyList()
        } else {
            nsd?.stopDiscovery()
            devices = emptyList()
        }
    }

    /** Permissions the nearby transport needs on this Android version. */
    private fun nearbyPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        return perms
    }

    private fun missingNearbyPermissions(): Array<String> =
        nearbyPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    private fun refreshDevices() {
        if (sendMode == "nearby") startNearbyDiscovery() else ensureDiscovery()
        renderDevices()
    }

    private fun startNearbyDiscovery() {
        if (!NearbyLink.available(this)) {
            devicesHint.text = getString(R.string.nearby_unavailable)
            return
        }
        val missing = missingNearbyPermissions()
        if (missing.isNotEmpty()) {
            pendingNearbyAction = { startNearbyDiscovery() }
            onNearbyDenied = { devicesHint.text = getString(R.string.nearby_perm_needed) }
            nearbyPermission.launch(missing)
            return
        }
        if (nearbySender == null) {
            nearbySender = NearbySender(this, "VidGrab-${ownDeviceId()}").apply {
                onPeers = { list ->
                    nearbyPeers = list.filter { it.deviceId != ownDeviceId() }
                    runOnUiThread { renderDevices() }
                }
                confirmCode = { peerName, digits -> askNearbyCode(peerName, digits) }
            }
        }
        nearbySender?.startDiscovery()
        devicesHint.text = getString(R.string.nearby_searching)
    }

    private fun renderDevices() {
        if (sendMode == "nearby") {
            devicesHint.text = if (nearbyPeers.isEmpty()) {
                getString(R.string.no_devices)
            } else ""
            val labels = nearbyPeers.map { it.name }
            deviceList.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
            if (nearbyPeers.isNotEmpty()) deviceList.setItemChecked(0, true)
            return
        }
        devicesHint.text = if (devices.isEmpty()) getString(R.string.no_devices) else ""
        val labels = devices.map { "${it.serviceName}   (${it.host})" }
        deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
        if (devices.isNotEmpty()) deviceList.setItemChecked(0, true)
    }

    private fun pairAndSend() {
        if (pickedUris.isEmpty()) {
            filesLabel.text = getString(R.string.pick_first)
            return
        }
        if (sendMode == "nearby") sendNearby() else sendSameWifi()
    }

    /** Nearby link: Bluetooth finds the phone, Wi-Fi Direct moves the bytes. */
    private fun sendNearby() {
        val idx = deviceList.checkedItemPosition
        if (idx < 0 || idx >= nearbyPeers.size) {
            sendStatus.text = getString(R.string.pick_device)
            return
        }
        val peer = nearbyPeers[idx]
        val items = mutableListOf<SendItem>()
        for (uri in pickedUris) {
            val name = displayName(uri)
            val size = querySize(uri)
            if (size <= 0) {
                sendStatus.text = getString(R.string.send_failed, "cannot determine size of $name")
                return
            }
            items += SendItem(name, size) {
                contentResolver.openFileDescriptor(uri, "r")
                    ?: throw TransferException("cannot read $name")
            }
        }
        sendStatus.text = getString(R.string.nearby_connecting)
        lifecycleScope.launch {
            val sender = nearbySender ?: return@launch
            var progressName = ""
            var progressStart = 0L
            try {
                val saved = sender.send(
                    peer, items,
                    onStatus = { name ->
                        handler.post { sendStatus.text = "${getString(R.string.sending)} $name" }
                    },
                    onProgress = { name, sent, total ->
                        if (name != progressName) {
                            progressName = name
                            progressStart = System.nanoTime()
                        }
                        val line = ProgressText.line(
                            this@MainActivity, name, sent, total, progressStart)
                        handler.post { sendStatus.text = line }
                    },
                )
                sendStatus.text = getString(R.string.sent_ok, saved.joinToString(", "))
            } catch (e: Exception) {
                sendStatus.text =
                    getString(R.string.send_failed, (e.message ?: e.toString()).take(200))
            }
        }
    }

    private fun sendSameWifi() {
        val idx = deviceList.checkedItemPosition
        if (idx < 0 || idx >= devices.size) {
            sendStatus.text = getString(R.string.pick_device)
            return
        }
        val device = devices[idx]
        sendStatus.text = getString(R.string.sending)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = SendClient(
                        device.host, device.port,
                        senderName = "VidGrab-${ownDeviceId()}",
                        pins = pinStore, deviceId = device.deviceId,
                    )
                    client.connect() // handshake only; nothing else sent yet
                    if (!client.trusted) {
                        // Dialogs must be built on the main thread (has a Looper);
                        // this block runs on Dispatchers.IO.
                        val ok = withContext(Dispatchers.Main) {
                            askFingerprint(device.serviceName, client.presentedFingerprint!!)
                        }
                        if (!ok) {
                            client.close()
                            withContext(Dispatchers.Main) {
                                sendStatus.text = getString(R.string.pair_failed)
                            }
                            return@withContext
                        }
                        client.confirmTrust()
                    }
                    for (uri in pickedUris) {
                        val name = displayName(uri)
                        val size = querySize(uri)
                        if (size < 0) {
                            throw TransferException("cannot determine size of $name")
                        }
                        withContext(Dispatchers.Main) {
                            sendStatus.text = "${getString(R.string.sending)} $name"
                        }
                        val t0 = System.nanoTime()
                        val saved = client.send(name, size, onProgress = { sent, tot ->
                            val line = ProgressText.line(
                                this@MainActivity, name, sent, tot, t0)
                            handler.post { sendStatus.text = line }
                        }) {
                            contentResolver.openInputStream(uri)!!
                        }
                        withContext(Dispatchers.Main) {
                            sendStatus.text = getString(R.string.sent_ok, saved)
                        }
                    }
                    client.close()
                }
            } catch (e: PinChangedException) {
                sendStatus.text = getString(R.string.send_failed, "device identity changed - re-verify on its screen")
            } catch (e: Exception) {
                sendStatus.text = getString(R.string.send_failed, (e.message ?: e.toString()).take(200))
            }
        }
    }

    /** Full-fingerprint compare dialog; suspends for the user's decision. */
    private suspend fun askFingerprint(deviceName: String, fingerprint: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val grouped = fingerprint.uppercase().chunked(8).chunked(4)
                .joinToString("\n") { it.joinToString("   ") }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.pairing_title))
                .setMessage(getString(R.string.pairing_instructions, deviceName) + "\n\n" + grouped)
                .setPositiveButton(getString(R.string.code_matches)) { _, _ -> cont.resume(true) }
                .setNegativeButton(getString(R.string.code_mismatch)) { _, _ -> cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }

    /** 4-digit code from Nearby's own connection token, compared on both screens. */
    private suspend fun askNearbyCode(peerName: String, digits: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                codeDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.nearby_code_title))
                    .setMessage(getString(R.string.nearby_code_peer, peerName))
                    .setView(codeView(digits))
                    .setPositiveButton(getString(R.string.code_matches)) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton(getString(R.string.code_mismatch)) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .show()
            }
        }

    private fun codeView(digits: String): View {
        val tv = TextView(this)
        tv.text = NearbyLink.spaced(digits)
        tv.textSize = 40f
        tv.letterSpacing = 0.12f
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(Ui.primaryText(this))
        tv.setPadding(0, Ui.dp(tv, 22), 0, Ui.dp(tv, 18))
        return tv
    }

    /** Nearby phone asking to connect (receive side): confirm the code. */
    private fun showIncomingCode(peerName: String, digits: String) {
        codeDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.nearby_code_title))
            .setMessage(getString(R.string.nearby_code_peer, peerName))
            .setView(codeView(digits))
            .setPositiveButton(getString(R.string.accept)) { _, _ ->
                startService(Intent(this, ReceiveService::class.java)
                    .setAction(ReceiveService.ACTION_CODE_ACCEPT))
            }
            .setNegativeButton(getString(R.string.decline)) { _, _ ->
                startService(Intent(this, ReceiveService::class.java)
                    .setAction(ReceiveService.ACTION_CODE_DECLINE))
            }
            .setOnCancelListener {
                startService(Intent(this, ReceiveService::class.java)
                    .setAction(ReceiveService.ACTION_CODE_DECLINE))
            }
            .show()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Declared size of a SAF document, or -1 when the provider won't say. */
    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // -------------------------------------------------------------- receive

    private fun ownDeviceId(): String = Identity.loadOrCreate(this).deviceId

    private fun showOwnFingerprint() {
        val fp = Identity.loadOrCreate(this).fingerprint.uppercase()
        // Two 8-character blocks per line: four per line overflowed the
        // card on a 360dp screen.
        fingerprintText.text = fp.chunked(8).chunked(2)
            .joinToString("\n") { it.joinToString("   ") }
    }

    private fun toggleReceive() {
        if (receiving) {
            ReceiveService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = if (NearbyLink.available(this)) missingNearbyPermissions() else emptyArray()
        if (missing.isNotEmpty()) {
            pendingNearbyAction = { ReceiveService.start(this) }
            onNearbyDenied = { ReceiveService.start(this) }
            nearbyPermission.launch(missing)
            return
        }
        ReceiveService.start(this)
    }

    /** In-app accept/decline for an incoming offer; the notification action
     *  covers the backgrounded case. Auto-declines with the offer's TTL. */
    private fun showOfferDialog(text: String) {
        val autoDecline = Runnable {
            offerDialog?.let { if (it.isShowing) it.dismiss() }
            offerDialog = null
            startService(Intent(this, ReceiveService::class.java)
                .setAction(ReceiveService.ACTION_DECLINE))
        }
        fun answer(action: String) {
            handler.removeCallbacks(autoDecline)
            startService(Intent(this, ReceiveService::class.java).setAction(action))
        }
        offerDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.offer_notification_title))
            .setMessage(text)
            .setPositiveButton(getString(R.string.accept)) { _, _ ->
                answer(ReceiveService.ACTION_ACCEPT)
            }
            .setNegativeButton(getString(R.string.decline)) { _, _ ->
                answer(ReceiveService.ACTION_DECLINE)
            }
            .setOnCancelListener { answer(ReceiveService.ACTION_DECLINE) }
            .show()
        handler.postDelayed(autoDecline, 60_000)
    }

    private fun renderReceive(s: ReceiveBus.State) {
        receiving = s.active
        receiveButton.text = getString(
            if (s.active) R.string.receive_stop else R.string.receive_start)
        receiveStatus.text = when {
            s.status != null -> s.status
            s.active && s.nearby -> getString(R.string.receive_active_nearby, s.deviceName ?: "")
            s.active -> getString(R.string.receive_active, s.deviceName ?: "")
            else -> getString(R.string.receive_idle)
        }
        if (s.active && s.fingerprint != null) {
            val payload = "vidgrab://connect?h=&fp=${s.fingerprint}&n=${s.deviceName}"
            receiveQr.setImageBitmap(qrBitmap(payload, 180))
        } else {
            receiveQr.setImageBitmap(null)
        }
    }

    private fun qrBitmap(payload: String, sizeDp: Int): Bitmap {
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        return bmp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
