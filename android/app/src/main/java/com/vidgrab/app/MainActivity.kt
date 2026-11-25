package com.vidgrab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val urlRegex = Regex("https?://\\S+")

    private lateinit var urlInput: EditText
    private lateinit var infoText: TextView
    private lateinit var qualityGroup: RadioGroup
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        infoText = findViewById(R.id.infoText)
        qualityGroup = findViewById(R.id.qualityGroup)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        QUALITIES.forEachIndexed { index, q ->
            val rb = RadioButton(this).apply {
                text = q.label
                id = index
            }
            qualityGroup.addView(rb)
        }
        qualityGroup.check(0)

        findViewById<Button>(R.id.fetchButton).setOnClickListener { fetchInfo() }
        findViewById<Button>(R.id.downloadButton).setOnClickListener { startDownload() }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            startActivity(Intent(this, ShareActivity::class.java))
        }
        findViewById<Button>(R.id.updateEngineButton).setOnClickListener { updateEngine() }

        handleIntent(intent)
        lifecycleScope.launch {
            ProgressBus.state.collect { s -> s?.let { render(it) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                urlRegex.find(text)?.let { urlInput.setText(it.value) }
            }
        }
    }

    private fun currentUrl(): String? =
        urlRegex.find(urlInput.text.toString())?.value.also {
            if (it == null) toast("Paste or share a video link first")
        }

    private fun fetchInfo() {
        val url = currentUrl() ?: return
        infoText.text = getString(R.string.fetching)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Engine.ensureInit(this@MainActivity)
                if (!Engine.ready) return@withContext "Engine failed to start: ${Engine.error}"
                try {
                    val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url))
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
        toast("Updating download engine...")
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

    private fun render(s: ProgressBus.State) {
        progressBar.progress = s.percent.toInt()
        statusText.text = when {
            s.error != null -> "Failed: ${s.error}"
            s.done -> s.line
            s.running -> "${s.percent.toInt()}%  ${s.line}"
            else -> s.line
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
