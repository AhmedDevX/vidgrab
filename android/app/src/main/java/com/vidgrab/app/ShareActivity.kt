package com.vidgrab.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

class ShareActivity : AppCompatActivity() {

    private val picked = mutableListOf<Uri>()

    private lateinit var filesText: TextView
    private lateinit var toggleButton: Button
    private lateinit var qrImage: ImageView
    private lateinit var linkText: TextView

    private var sharing = false

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                picked.clear()
                picked.addAll(uris)
                filesText.text = uris.joinToString("\n") { it.lastPathSegment ?: "file" }.take(400)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        window.setBackgroundDrawable(Ui.background(this))

        filesText = findViewById(R.id.shareFilesText)
        toggleButton = findViewById(R.id.shareToggleButton)
        qrImage = findViewById(R.id.qrImage)
        linkText = findViewById(R.id.shareLinkText)

        findViewById<Button>(R.id.pickFilesButton).setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        toggleButton.setOnClickListener { toggleSharing() }

        lifecycleScope.launch {
            ShareBus.state.collect { s -> s?.let { render(it) } }
        }
    }

    private fun toggleSharing() {
        if (sharing) {
            ShareServerService.stop(this)
        } else {
            if (picked.isEmpty()) {
                Toast.makeText(this, "Choose files first", Toast.LENGTH_LONG).show()
                return
            }
            ShareServerService.start(this, picked.toList())
        }
    }

    private fun render(s: ShareBus.State) {
        sharing = s.running
        toggleButton.text = getString(if (s.running) R.string.stop_sharing else R.string.start_sharing)
        when {
            s.error != null -> linkText.text = "Could not start sharing: ${s.error}"
            s.running && s.url != null -> {
                linkText.text = "Open on the other device (same Wi-Fi):\n${s.url}"
                qrImage.setImageBitmap(qrBitmap(s.url))
            }
            else -> {
                linkText.text = ""
                qrImage.setImageDrawable(null)
            }
        }
    }

    private fun qrBitmap(text: String, size: Int = 600): Bitmap? = try {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
