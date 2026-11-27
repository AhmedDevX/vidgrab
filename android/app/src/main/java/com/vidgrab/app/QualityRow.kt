package com.vidgrab.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** One selectable quality card: icon tile, title, size on the right, badges. */
class QualityRow(private val ctx: Context, q: Quality, onClick: () -> Unit) {
    val view: MaterialCardView
    private val size: TextView
    private val accent = 0xFFD9BC90.toInt()

    init {
        val dp = ctx.resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        view = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = q.label
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }
        val tile = FrameLayout(ctx).apply {
            setBackgroundResource(if (q.audioOnly) R.drawable.tile_orange else R.drawable.tile_blue)
        }
        tile.addView(ImageView(ctx).apply {
            setImageResource(if (q.audioOnly) R.drawable.ic_note else R.drawable.ic_play)
            setColorFilter(0xFF2A1F14.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(px(42), px(42)))

        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(ctx).apply {
            text = when (q.label) {
                "Best quality" -> ctx.getString(R.string.q_best_quality)
                "Audio only (MP3)" -> ctx.getString(R.string.q_audio_mp3)
                else -> q.label
            }; textSize = 20f; paint.isFakeBoldText = true
            setTextColor(Ui.primaryText(ctx))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; textDirection = View.TEXT_DIRECTION_LOCALE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mid.addView(TextView(ctx).apply {
            text = ctx.getString(if (q.audioOnly) R.string.q_audio_only else R.string.q_video_audio)
            textSize = 13f; setTextColor(Ui.secondaryText(ctx))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = px(12); marginEnd = px(8) })

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        size = TextView(ctx).apply {
            textSize = 15f; paint.isFakeBoldText = true
            setSingleLine(true)
            setTextColor(Ui.primaryText(ctx)); gravity = Gravity.END
        }
        right.addView(size, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val badges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val hd = Regex("(\\d+)").find(q.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val blue = 0xFFC9A97A.toInt()
        if (q.label.startsWith("Best")) badges.addView(Ui.badge(ctx, ctx.getString(R.string.q_best), blue))
        else if (hd >= 720 && !q.audioOnly) badges.addView(Ui.badge(ctx, ctx.getString(R.string.q_hd), blue))
        right.addView(badges, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        row.addView(right, LinearLayout.LayoutParams(px(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(row)
        setSize(null)
    }

    fun setSize(text: String?) { size.text = text?.replace(' ', '\u00A0') ?: ""; size.visibility = if (text == null) View.GONE else View.VISIBLE }

    fun setSelected(on: Boolean) {
        val dp = ctx.resources.displayMetrics.density
        view.strokeColor = if (on) accent else ctx.getColor(R.color.card_stroke)
        view.strokeWidth = if (on) (2.2f * dp).toInt() else (1f * dp).toInt()
        view.isChecked = false
        view.alpha = if (on) 1f else 0.92f
        view.contentDescription = null
    }
}
