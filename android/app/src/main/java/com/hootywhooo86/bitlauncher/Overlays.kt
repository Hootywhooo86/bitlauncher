package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.text.Normalizer
import kotlin.math.abs

/** Lowercase, accents stripped, so "cafe" finds "Café". */
fun searchable(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/**
 * The app drawer: every app A–Z with a search box on top. Typing filters instantly; the
 * keyboard's Go opens the first match. Swipe down at the top of the list to close.
 */
class DrawerView(context: Context, private val bind: (AppIconView, AppEntry) -> Unit) : FrameLayout(context) {
    val search: EditText
    private val grid: GridView
    private val empty: TextView
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    var onOpenFirst: ((AppEntry) -> Unit)? = null
    var onCloseRequest: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? AppIconView) ?: AppIconView(context).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight)
            }
            bind(view, shown[position])
            return view
        }
    }

    var cellHeight = Pixel.dpi(context, 92)

    init {
        setBackgroundColor(withAlpha(Pixel.BG, 242))
        isClickable = true // don't let taps fall through to home
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(context).apply {
            hint = "SEARCH APPS"
            typeface = Pixel.font(context)
            textSize = 13f
            setTextColor(Pixel.INK)
            setHintTextColor(Pixel.MUTED)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            val p = Pixel.dpi(context, 14)
            setPadding(p, p, p, p)
            setOnEditorActionListener { _, action, event ->
                val go = action == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (go) shown.firstOrNull()?.let { onOpenFirst?.invoke(it) }
                go
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        column.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            val m = Pixel.dpi(context, 14)
            setMargins(m, m, m, m / 2)
        })
        grid = GridView(context).apply {
            adapter = this@DrawerView.adapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column)
        empty = Pixel.label(context, "NO APPS MATCH", 11f, Pixel.MUTED).apply { gravity = Gravity.CENTER; visibility = GONE }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setAccent(accent: Int) {
        search.background = PanelDrawable(context, Pixel.PANEL, accent)
    }

    fun setColumns(cols: Int) {
        grid.numColumns = cols
    }

    fun setBottomInset(px: Int) {
        grid.setPadding(0, 0, 0, px)
    }

    fun setApps(apps: List<AppEntry>) {
        all = apps
        filter()
    }

    fun refresh() = adapter.notifyDataSetChanged()

    private fun filter() {
        val q = searchable(search.text.toString().trim())
        shown = if (q.isEmpty()) all else {
            val matches = all.filter { searchable(it.label).contains(q) }
            // Names that start with what you typed come first.
            matches.sortedBy { if (searchable(it.label).startsWith(q)) 0 else 1 }
        }
        empty.visibility = if (shown.isEmpty() && all.isNotEmpty()) VISIBLE else GONE
        adapter.notifyDataSetChanged()
    }

    fun reset() {
        search.setText("")
        grid.setSelection(0)
        hideKeyboard()
    }

    fun showKeyboard() {
        search.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        search.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (dy > slop * 2 && dy > abs(e.x - downX) && !grid.canScrollVertically(-1)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_MOVE && e.y - downY > slop * 4) {
            onCloseRequest?.invoke()
            return true
        }
        return true
    }
}

/** An open folder: a panel with an editable name and its apps. Tap outside to close. */
class FolderView(context: Context) : FrameLayout(context) {
    val title: EditText
    val grid: LinearLayout
    private val panel: LinearLayout
    var onClose: (() -> Unit)? = null

    init {
        setBackgroundColor(withAlpha(Pixel.BG, 170))
        isClickable = true
        setOnClickListener { onClose?.invoke() }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Pixel.dpi(context, 16)
            setPadding(p, p, p, p)
            isClickable = true // taps on the panel don't close it
        }
        title = EditText(context).apply {
            typeface = Pixel.font(context)
            textSize = 14f
            setTextColor(Pixel.INK)
            isSingleLine = true
            gravity = Gravity.CENTER
            background = null
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
        }
        panel.addView(title)
        grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(grid)
        addView(panel, LayoutParams(Pixel.dpi(context, 300), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setAccent(accent: Int) {
        panel.background = PanelDrawable(context, Pixel.PANEL, accent)
    }

    /** Lay [icons] out four to a row. */
    fun setIcons(icons: List<View>, cellHeight: Int) {
        grid.removeAllViews()
        icons.chunked(4).forEach { rowIcons ->
            val row = LinearLayout(context)
            rowIcons.forEach { row.addView(it, LinearLayout.LayoutParams(0, cellHeight, 1f)) }
            repeat(4 - rowIcons.size) { row.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f)) }
            grid.addView(row)
        }
    }

    /** The panel pops open in three steps, from a little smaller to full size. */
    fun popIn() {
        panel.scaleX = 0.7f
        panel.scaleY = 0.7f
        panel.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(StepInterpolator(3)).start()
    }

    fun icons(): List<AppIconView> {
        val out = mutableListOf<AppIconView>()
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as? ViewGroup ?: continue
            for (i in 0 until row.childCount) (row.getChildAt(i) as? AppIconView)?.let(out::add)
        }
        return out
    }

    fun hideKeyboard() {
        title.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }
}

/** A long-press menu in the pixel style: a bordered panel of rows. */
class PixelMenu(private val context: Context, private val accent: Int) {
    class Entry(val label: String, val action: () -> Unit)

    private var popup: PopupWindow? = null

    val isShowing: Boolean get() = popup?.isShowing == true

    fun show(anchor: View, entries: List<Entry>, anchorRect: Rect? = null) {
        dismiss()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, accent)
            val p = Pixel.dpi(context, 6)
            setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
        }
        val window = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        for (entry in entries) {
            list.addView(Pixel.label(context, entry.label.uppercase(), 11f).apply {
                val ph = Pixel.dpi(context, 14)
                val pv = Pixel.dpi(context, 12)
                setPadding(ph, pv, ph, pv)
                minWidth = Pixel.dpi(context, 160)
                isClickable = true
                background = context.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener {
                    window.dismiss()
                    entry.action()
                }
            })
        }
        window.isOutsideTouchable = true
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        window.elevation = 0f
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        // Above the anchor if there's room, otherwise below; clamped to the screen.
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val r = anchorRect ?: Rect(0, 0, anchor.width, anchor.height)
        val screenW = anchor.rootView.width
        var x = loc[0] + r.centerX() - list.measuredWidth / 2
        x = x.coerceIn(Pixel.dpi(context, 8), (screenW - list.measuredWidth - Pixel.dpi(context, 8)).coerceAtLeast(0))
        var y = loc[1] + r.top - list.measuredHeight
        if (y < Pixel.dpi(context, 32)) y = loc[1] + r.bottom
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        popup = window
        if (Pixel.motion(context)) {
            // Two quick steps from 85% to full: a pixel "pop".
            list.scaleX = 0.85f
            list.scaleY = 0.85f
            list.animate().scaleX(1f).scaleY(1f).setDuration(80).setInterpolator(StepInterpolator(2)).start()
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
