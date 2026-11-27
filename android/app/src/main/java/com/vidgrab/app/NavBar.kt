package com.vidgrab.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Floating, detached bottom bar: a rounded card with a thin translucent
 *  border. Only the selected item shows its label. This is layering and
 *  transparency only; there is no real blur. */
class NavBar(
    private val context: Context,
    private val items: List<Item>,
    private val onSelect: (Int) -> Unit,
) {
    data class Item(val id: Int, val icon: Int, val label: Int)

    val view: MaterialCardView
    private val row = LinearLayout(context)
    private val cells = mutableListOf<LinearLayout>()
    private var selected = -1

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(context, attr, fallback)

    init {
        val dp = context.resources.displayMetrics.density
        val surface = color(com.google.android.material.R.attr.colorSurface, Color.DKGRAY)
        val outline = color(com.google.android.material.R.attr.colorOutline, Color.GRAY)
        view = MaterialCardView(context).apply {
            radius = 30 * dp
            cardElevation = 6 * dp
            setCardBackgroundColor(ColorUtils.setAlphaComponent(surface, 0xF2))
            strokeWidth = (1.2f * dp).toInt().coerceAtLeast(1)
            strokeColor = ColorUtils.setAlphaComponent(outline, 0xAA)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt()) }
        }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        items.forEachIndexed { i, it ->
            val cell = LinearLayout(context).apply {
                id = it.id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(it.label)
                setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { select(i) }
            }
            val icon = ImageView(context).apply {
                setImageResource(it.icon)
                tag = "icon"
            }
            cell.addView(icon, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            val label = TextView(context).apply {
                text = context.getString(it.label)
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "label"
                visibility = View.GONE
                setPadding((6 * dp).toInt(), 0, 0, 0)
                paint.isFakeBoldText = true
            }
            cell.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cells += cell
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        view.addView(row)
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        val primary = color(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val dp = context.resources.displayMetrics.density
        cells.forEachIndexed { i, cell ->
            val on = i == index
            val icon = cell.findViewWithTag<ImageView>("icon")
            val label = cell.findViewWithTag<TextView>("label")
            icon.setColorFilter(if (on) primary else onSurface)
            label.setTextColor(primary)
            label.visibility = if (on) View.VISIBLE else View.GONE
            cell.background = if (on) GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(ColorUtils.setAlphaComponent(primary, 0x2E))
            } else null
            (cell.layoutParams as LinearLayout.LayoutParams).weight = if (on) 2.4f else 1f
            cell.requestLayout()
        }
        onSelect(index)
    }
}
