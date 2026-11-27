package com.vidgrab.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Shared look for v9: dark navy-violet gradient with soft colour glows,
 *  translucent cards. Layering and transparency only; no real blur. */
object Ui {
    fun themeName(ctx: Context): String =
        ctx.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE).getString("theme", "dark") ?: "dark"

    fun background(ctx: Context): Drawable {
        val t = themeName(ctx)
        val (top, bottom, glowA, glowB) = when (t) {
            "light" -> listOf(0xFFFAF6EF.toInt(), 0xFFEDE3D3.toInt(), 0x338A6D4F, 0x33C9A97A)
            "glass" -> listOf(0xFF1A1315.toInt(), 0xFF3A2B2E.toInt(), 0x66C9A0A0, 0x55D9B38C)
            else -> listOf(0xFF14110F.toInt(), 0xFF271F1A.toInt(), 0x44C9A97A, 0x40A68A7B)
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        fun glow(color: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 520f
            colors = intArrayOf(color, color and 0x00FFFFFF)
            shape = GradientDrawable.RECTANGLE
        }
        val a = glow(glowA); val b = glow(glowB)
        val layers = LayerDrawable(arrayOf(base, a, b))
        // glow A top-left, glow B bottom-right
        a.setGradientCenter(0.1f, 0.12f); b.setGradientCenter(0.9f, 0.8f)
        return layers
    }

    fun isLight(ctx: Context) = themeName(ctx) == "light"

    fun primaryText(ctx: Context) = ContextCompat.getColor(ctx, R.color.text_primary)
    fun secondaryText(ctx: Context) = ContextCompat.getColor(ctx, R.color.text_secondary)

    fun tile(ctx: Context, color: String): Int = when (color) {
        "blue" -> R.drawable.tile_blue
        "orange" -> R.drawable.tile_orange
        "pink" -> R.drawable.tile_pink
        "green" -> R.drawable.tile_green
        "teal" -> R.drawable.tile_teal
        "yellow" -> R.drawable.tile_yellow
        else -> R.drawable.tile_purple
    }

    fun badge(ctx: Context, text: String, bg: Int, fg: Int = 0xFF2A1F14.toInt()): TextView {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(fg)
            paint.isFakeBoldText = true
            setPadding((9 * dp).toInt(), (2 * dp).toInt(), (9 * dp).toInt(), (2 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(bg) }
        }
    }

    fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
}
