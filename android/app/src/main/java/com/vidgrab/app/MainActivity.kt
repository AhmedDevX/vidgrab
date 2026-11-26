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
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    private lateinit var qualityGroup: RadioGroup
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

    private val prefs by lazy { getSharedPreferences("vidgrab_settings", MODE_PRIVATE) }
    private val pinStore by lazy { PinStore(this) }
    private var nsd: Nsd? = null
    private var devices = listOf<DiscoveredDevice>()
    private var pickedUris = listOf<Uri>()
    private var receiving = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        QUALITIES.forEachIndexed { index, q ->
            val rb = RadioButton(this).apply { text = q.label; id = index }
            qualityGroup.addView(rb)
        }
        qualityGroup.check(0)

        findViewById<Button>(R.id.fetchButton).setOnClickListener { fetchInfo() }
        findViewById<Button>(R.id.downloadButton).setOnClickListener { startDownload() }
        findViewById<Button>(R.id.updateEngineButton).setOnClickListener { updateEngine() }
        findViewById<Button>(R.id.pickFilesButton).setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.refreshDevicesButton).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.pairSendButton).setOnClickListener { pairAndSend() }
        findViewById<Button>(R.id.openShareButton).setOnClickListener {
            startActivity(Intent(this, ShareActivity::class.java))
        }
        receiveButton.setOnClickListener { toggleReceive() }

        setupBottomNav()
        setupSettings()
        showOwnFingerprint()

        handleIntent(intent)
        lifecycleScope.launch {
            ProgressBus.state.collect { s -> s?.let { render(it) } }
        }
        lifecycleScope.launch {
            ReceiveBus.state.collect { s -> renderReceive(s) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        nsd?.close()
        nsd = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- navigation

    private fun setupBottomNav() {
        val pages = listOf(
            findViewById<View>(R.id.pageDownload),
            findViewById<View>(R.id.pageSend),
            findViewById<View>(R.id.pageReceive),
            findViewById<View>(R.id.pageSettings),
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val idx = when (item.itemId) {
                R.id.nav_download -> 0
                R.id.nav_send -> 1
                R.id.nav_receive -> 2
                else -> 3
            }
            pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
            if (idx == 1) ensureDiscovery()
            true
        }
        nav.selectedItemId = R.id.nav_download
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
                    "${info.title}$mins"
                } catch (e: Exception) {
                    "Could not read this link: ${e.message}"
                }
            }
            infoText.text = result
        }
    }

    private fun startDownload() {
        val url = currentUrl() ?: return
        val quality = QUALITIES[qualityGroup.checkedRadioButtonId.coerceIn(0, QUALITIES.size - 1)]
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
        )
        statusText.text = getString(R.string.download_starting)
    }

    private fun updateEngine() {
        toast(getString(R.string.download_starting))
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                Engine.ensureInit(this@MainActivity)
                if (!Engine.ready) return@withContext "Engine failed to start: ${Engine.error}"
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    "Engine updated"
                } catch (e: Exception) {
                    "Update failed: ${e.message}"
                }
            }
            toast(msg)
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

    private fun refreshDevices() {
        ensureDiscovery()
        renderDevices()
    }

    private fun renderDevices() {
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
                        val ok = askFingerprint(device.serviceName, client.presentedFingerprint!!)
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
                        val saved = client.send(name, size) {
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
        fingerprintText.text = fp.chunked(8).chunked(4)
            .joinToString("\n") { it.joinToString("   ") }
    }

    private fun toggleReceive() {
        if (receiving) {
            ReceiveService.stop(this)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ReceiveService.start(this)
        }
    }

    private fun renderReceive(s: ReceiveBus.State) {
        receiving = s.active
        receiveButton.text = getString(
            if (s.active) R.string.receive_stop else R.string.receive_start)
        receiveStatus.text = when {
            s.status != null -> s.status
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
