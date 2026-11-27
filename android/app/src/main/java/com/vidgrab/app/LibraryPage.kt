package com.vidgrab.app

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.card.MaterialCardView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.MaterialColors

data class LibItem(
    val uri: Uri, val name: String, val size: Long, val mime: String?, val addedSec: Long,
) { val kind: FileKind get() = FileKind.of(name, mime) }

/** Downloads page: files VidGrab saved to Download/VidGrab, filtered by type. */
class LibraryPage(private val context: Context) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = TextView(context)
    private val chips = mutableListOf<Pair<Button, FileKind?>>()
    private var filter: FileKind? = null
    private var items: List<LibItem> = emptyList()
    private var query: String = ""

    companion object {
        /** Filter helper, kept pure for tests. */
        fun filter(all: List<LibItem>, kind: FileKind?): List<LibItem> =
            if (kind == null) all else all.filter { it.kind == kind }

        fun humanSize(n: Long): String = when {
            n >= 1_000_000_000L -> "%.1f GB".format(n / 1e9)
            n >= 1_000_000L -> "%.1f MB".format(n / 1e6)
            n >= 1_000L -> "%d KB".format(n / 1000)
            else -> "$n B"
        }

        fun query(context: Context): List<LibItem> {
            val out = mutableListOf<LibItem>()
            val proj = arrayOf(
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE, MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.DATE_ADDED)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf("Download/VidGrab%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += LibItem(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "file", c.getLong(2), c.getString(3), c.getLong(4))
                }
            }
            return out
        }
    }

    fun build() {
        val dp = context.resources.displayMetrics.density
        root.addView(TextView(context).apply {
            text = context.getString(R.string.my_files)
            textSize = 32f; paint.isFakeBoldText = true
            setTextColor(Ui.primaryText(context))
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        })
        root.addView(EditText(context).apply {
            hint = context.getString(R.string.search_files)
            setSingleLine(); textSize = 15f
            setTextColor(Ui.primaryText(context)); setHintTextColor(Ui.secondaryText(context))
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp; setColor(context.getColor(R.color.card_fill))
                setStroke(dp.toInt().coerceAtLeast(1), context.getColor(R.color.card_stroke))
            }
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty().trim(); render() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        }
        val defs = listOf(
            R.string.lib_all to null, R.string.lib_video to FileKind.VIDEO,
            R.string.lib_audio to FileKind.AUDIO, R.string.lib_images to FileKind.IMAGE,
            R.string.lib_other to FileKind.OTHER)
        defs.forEach { (res, kind) ->
            val b = Button(context).apply {
                text = context.getString(res)
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                textSize = 13.5f; paint.isFakeBoldText = true
                setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
                stateListAnimator = null
                setOnClickListener { filter = kind; render() }
            }
            chips += b to kind
            bar.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * dp).toInt() })
        }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })
        empty.apply {
            text = context.getString(R.string.lib_empty)
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt(), 0)
            visibility = View.GONE
        }
        root.addView(empty)
        root.addView(ScrollView(context).apply {
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
    }

    /** For tests and previews. */
    fun setItems(list: List<LibItem>) { items = list; render() }

    fun refresh() {
        items = try { query(context) } catch (_: Exception) { emptyList() }
        render()
    }

    private fun kindLabel(k: FileKind) = context.getString(when (k) {
        FileKind.VIDEO -> R.string.lib_video
        FileKind.AUDIO -> R.string.lib_audio
        FileKind.IMAGE -> R.string.lib_images
        FileKind.OTHER -> R.string.lib_other
    })

    private fun render() {
        val dp = context.resources.displayMetrics.density
        val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        val sub = MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        chips.forEach { (b, k) ->
            val n = filter(items, k).size
            val base = context.getString(when (k) {
                null -> R.string.lib_all; FileKind.VIDEO -> R.string.lib_video
                FileKind.AUDIO -> R.string.lib_audio; FileKind.IMAGE -> R.string.lib_images
                FileKind.OTHER -> R.string.lib_other })
            b.text = "$base $n"
            val on = k == filter
            b.alpha = 1f
            b.background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                if (on) { colors = intArrayOf(0xFFD8BC92.toInt(), 0xFFB98F6A.toInt()); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
                else { setColor(context.getColor(R.color.card_fill)); setStroke(dp.toInt().coerceAtLeast(1), context.getColor(R.color.card_stroke)) }
            }
            b.setTextColor(if (on) 0xFF2A1F14.toInt() else Ui.primaryText(context))
        }
        val shown = filter(items, filter).filter {
            query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        list.removeAllViews()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        shown.forEach { item -> list.addView(rowFor(item, dp, sub)) }
    }

    private fun rowFor(item: LibItem, dp: Float, sub: Int): View {
        fun px(n: Int) = (n * dp).toInt()
        val card = MaterialCardView(context).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { open(item) }
            setOnLongClickListener { confirmDelete(item); true }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px(16), px(5), px(16), px(5)) }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(12), px(8), px(12))
        }
        val (tileRes, iconRes, badgeColor) = when (item.kind) {
            FileKind.VIDEO -> Triple(R.drawable.tile_teal, R.drawable.ic_play, 0xFFC9A97A.toInt())
            FileKind.AUDIO -> Triple(R.drawable.tile_orange, R.drawable.ic_note, 0xFFC99A5B.toInt())
            FileKind.IMAGE -> Triple(R.drawable.tile_green, R.drawable.ic_image, 0xFF8FA68A.toInt())
            FileKind.OTHER -> Triple(R.drawable.tile_purple, R.drawable.ic_file, 0xFF9A8AA8.toInt())
        }
        val tile = FrameLayout(context).apply { setBackgroundResource(tileRes) }
        tile.addView(ImageView(context).apply {
            setImageResource(iconRes); setColorFilter(0xFF2A1F14.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(px(52), px(52)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = item.name; maxLines = 1; textSize = 15.5f; paint.isFakeBoldText = true
            setTextColor(Ui.primaryText(context))
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        val date = DateFormat.getMediumDateFormat(context).format(java.util.Date(item.addedSec * 1000))
        val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        meta.addView(Ui.badge(context, kindLabel(item.kind), badgeColor))
        meta.addView(TextView(context).apply {
            text = "${humanSize(item.size)}  \u00b7  $date"; textSize = 12.5f; setTextColor(sub)
            setPaddingRelative(px(8), 0, 0, 0)
        })
        col.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = px(12) })
        val more = ImageView(context).apply {
            setImageResource(R.drawable.ic_more); setColorFilter(sub)
            contentDescription = context.getString(R.string.lib_delete)
            setPadding(px(8), px(8), px(8), px(8))
            setOnClickListener { v ->
                PopupMenu(context, v).apply {
                    menu.add(0, 1, 0, R.string.menu_open)
                    menu.add(0, 2, 1, R.string.menu_share)
                    menu.add(0, 3, 2, R.string.lib_delete)
                    setOnMenuItemClickListener { m ->
                        when (m.itemId) { 1 -> open(item); 2 -> share(item); else -> confirmDelete(item) }
                        true
                    }
                }.show()
            }
        }
        row.addView(more, LinearLayout.LayoutParams(px(40), px(40)))
        card.addView(row)
        return card
    }

    private fun share(it: LibItem) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType(it.mime ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, it.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, it.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.lib_cannot_open, Toast.LENGTH_LONG).show()
        }
    }

    private fun open(it: LibItem) {
        if (it.kind == FileKind.OTHER) {
            // No in-app viewer for arbitrary files: hand off, only on the user's tap.
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(context, R.string.lib_cannot_open, Toast.LENGTH_LONG).show()
            }
            return
        }
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .setData(it.uri)
            .putExtra(PlayerActivity.EXTRA_KIND, it.kind.name)
            .putExtra(PlayerActivity.EXTRA_NAME, it.name)
            .putExtra(PlayerActivity.EXTRA_MIME, it.mime))
    }

    private fun confirmDelete(it: LibItem) {
        AlertDialog.Builder(context)
            .setTitle(R.string.lib_delete_title)
            .setMessage(it.name)
            .setPositiveButton(R.string.lib_delete) { _, _ ->
                try { context.contentResolver.delete(it.uri, null, null) } catch (_: Exception) {}
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
