package com.vidgrab.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** In-app player (Media3 ExoPlayer) for downloaded video and audio, and an
 *  image viewer. Nothing is handed to another app unless the user taps the
 *  fallback button after a playback error. Audio gets a custom control panel;
 *  video uses the standard Media3 controller. */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000).toInt()
            return "%d:%02d".format(s / 60, s % 60)
        }
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private fun px(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun glass(radius: Int) = GradientDrawable().apply {
        cornerRadius = radius * resources.displayMetrics.density
        setColor(0x24FFFFFF)
        setStroke(px(1), 0x44FFFFFF)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri = intent.data ?: run { finish(); return }
        val kind = FileKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "VIDEO")
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val mime = intent.getStringExtra(EXTRA_MIME)
        title = name
        val root = FrameLayout(this).apply { background = Ui.background(this@PlayerActivity) }
        setContentView(root)

        if (kind == FileKind.IMAGE) {
            root.setBackgroundColor(Color.BLACK)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                try { setImageURI(uri) } catch (_: Exception) {}
            }
            root.addView(iv, FrameLayout.LayoutParams(-1, -1))
            root.addView(topBar(name), topBarParams())
            return
        }

        val p = ExoPlayer.Builder(this).build()
        player = p
        val err = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(24), 0, px(24), 0)
            visibility = View.GONE
        }
        val msg = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val open = Button(this).apply {
            text = getString(R.string.player_open_other)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {}
            }
        }
        err.addView(msg); err.addView(open)

        if (kind == FileKind.VIDEO) {
            root.setBackgroundColor(Color.BLACK)
            val pv = PlayerView(this).apply { useController = false; this.player = p }
            playerView = pv
            root.addView(pv, FrameLayout.LayoutParams(-1, -1))
            buildControls(root, p, name, video = true, pv = pv)
        } else {
            buildControls(root, p, name, video = false, pv = null)
        }
        root.addView(err, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                msg.text = getString(R.string.player_error, error.errorCodeName)
                err.visibility = View.VISIBLE
            }
        })
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
    }

    private fun topBarParams() = FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        .apply { setMargins(px(14), px(40), px(14), 0) }

    private fun topBar(name: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(26)
            setPadding(px(8), px(6), px(8), px(6))
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE)
            contentDescription = getString(android.R.string.cancel)
            setPadding(px(10), px(10), px(10), px(10))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(44), px(44)))
        bar.addView(TextView(this).apply {
            text = name; maxLines = 1; textSize = 15f; paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(6); marginEnd = px(12) })
        return bar
    }

    private fun buildControls(root: FrameLayout, p: ExoPlayer, name: String, video: Boolean, pv: PlayerView?) {
        val top = topBar(name)
        root.addView(top, topBarParams())
        if (video && pv != null) {
            (top as LinearLayout).addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_expand); setColorFilter(Color.WHITE)
                setPadding(px(10), px(10), px(10), px(10))
                setOnClickListener {
                    pv.resizeMode = if (pv.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }, LinearLayout.LayoutParams(px(44), px(44)))
        }
        if (!video) buildCover(root, name)
        buildPanel(root, p, video, pv, top)
    }

    private fun buildCover(root: FrameLayout, name: String) {
        // Cover: our own artwork (rounded gradient tile with a note), no album-art extraction.
        val cover = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFD8BC92.toInt(), 0xFF8A6D4F.toInt(), 0xFFB98F6A.toInt())).apply { cornerRadius = px(40).toFloat() }
        }
        cover.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_note); setColorFilter(0xE6FFFFFF.toInt())
        }, FrameLayout.LayoutParams(px(120), px(120), Gravity.CENTER))
        root.addView(cover, FrameLayout.LayoutParams(px(240), px(240), Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            .apply { topMargin = px(150) })
        root.addView(TextView(this).apply {
            text = name; textSize = 18f; paint.isFakeBoldText = true; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textDirection = View.TEXT_DIRECTION_LTR
        }, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { setMargins(px(28), px(420), px(28), 0) })
    }

    private fun buildPanel(root: FrameLayout, p: ExoPlayer, video: Boolean, pv: PlayerView?, top: View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (video) GradientDrawable().apply { cornerRadius = 30 * resources.displayMetrics.density; setColor(0xCC1A1512.toInt()); setStroke(px(1), 0x44FFFFFF) } else glass(30)
            setPadding(px(20), px(18), px(20), px(20))
        }
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFD9BC90.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val cur = TextView(this).apply { setTextColor(0xFFE9DDD0.toInt()); textSize = 13f; text = "0:00" }
        val rem = TextView(this).apply { setTextColor(0xFFE9DDD0.toInt()); textSize = 13f; gravity = Gravity.END; text = "-0:00" }
        times.addView(cur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(rem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        var speedIdx = 0
        val speed = TextView(this).apply {
            text = "1x"; textSize = 16f; paint.isFakeBoldText = true; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; contentDescription = getString(R.string.player_speed)
            setOnClickListener {
                speedIdx = (speedIdx + 1) % SPEEDS.size
                p.setPlaybackSpeed(SPEEDS[speedIdx])
                text = SPEEDS[speedIdx].toString().removeSuffix(".0") + "x"
            }
        }
        fun skip(dir: Int): View {
            val f = FrameLayout(this).apply {
                setOnClickListener { p.seekTo((p.currentPosition + dir * 10_000L).coerceIn(0, p.duration.coerceAtLeast(0))) }
            }
            f.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_replay); setColorFilter(Color.WHITE)
                scaleX = if (dir > 0) -1f else 1f
            }, FrameLayout.LayoutParams(px(46), px(46), Gravity.CENTER))
            f.addView(TextView(this).apply {
                text = "10"; textSize = 10f; paint.isFakeBoldText = true; setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = px(3) })
            return f
        }
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause); setColorFilter(0xFF2A1F14.toInt())
            setPadding(px(22), px(22), px(22), px(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFD9BC90.toInt(), 0xFFB98F6A.toInt())).apply { shape = GradientDrawable.OVAL }
            setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
        }
        var repeat = false
        val rep = ImageView(this).apply {
            setImageResource(R.drawable.ic_repeat); setColorFilter(0x88FFFFFF.toInt())
            setPadding(px(8), px(8), px(8), px(8))
            setOnClickListener {
                repeat = !repeat
                p.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setColorFilter(if (repeat) 0xFFD9BC90.toInt() else 0x88FFFFFF.toInt())
            }
        }
        controls.addView(speed, LinearLayout.LayoutParams(0, px(56), 1f))
        controls.addView(skip(-1), LinearLayout.LayoutParams(0, px(56), 1f))
        controls.addView(play, LinearLayout.LayoutParams(px(84), px(84)))
        controls.addView(skip(1), LinearLayout.LayoutParams(0, px(56), 1f))
        controls.addView(rep, LinearLayout.LayoutParams(0, px(48), 1f))
        panel.addView(controls, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        root.addView(panel, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(px(14), 0, px(14), px(36)) })

        var dragging = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser && p.duration > 0) p.seekTo(p.duration * v / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar?) { dragging = false }
        })
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                play.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
        })
        val t = object : Runnable {
            override fun run() {
                val d = p.duration
                if (d > 0 && !dragging) {
                    seek.progress = (p.currentPosition * 1000 / d).toInt()
                    cur.text = clock(p.currentPosition)
                    rem.text = "-" + clock(d - p.currentPosition)
                }
                handler.postDelayed(this, 500)
            }
        }
        ticker = t
        handler.post(t)
        if (video && pv != null) {
            val hide = Runnable { if (p.isPlaying) { panel.visibility = View.GONE; top.visibility = View.GONE } }
            fun show() {
                panel.visibility = View.VISIBLE; top.visibility = View.VISIBLE
                handler.removeCallbacks(hide); handler.postDelayed(hide, 3500)
            }
            pv.setOnClickListener {
                if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE; top.visibility = View.GONE } else show()
            }
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) show() else { panel.visibility = View.VISIBLE; top.visibility = View.VISIBLE } }
            })
            show()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ticker?.let { handler.removeCallbacks(it) }
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
