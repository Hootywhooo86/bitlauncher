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
    /** The drawer is being pulled down from the top of the list, [px] from where it started. */
    var onPullDown: ((Float) -> Unit)? = null
    var onPullRelease: ((px: Float, velocityY: Float) -> Unit)? = null

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
                if (dy > slop && dy > abs(e.x - downX) && !grid.canScrollVertically(-1)) {
                    // Start the pull from here, so the drawer doesn't jump by the slop.
                    downY = e.y
                    hideKeyboard()
                    return true
                }
            }
        }
        return false
    }

    private var velocity: android.view.VelocityTracker? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = android.view.VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> onPullDown?.invoke((e.y - downY).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = if (e.actionMasked == MotionEvent.ACTION_UP) velocity?.yVelocity ?: 0f else 0f
                onPullRelease?.invoke((e.y - downY).coerceAtLeast(0f), vy)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }
}

/**
 * An open folder: a panel with an editable name and its apps. Tap outside to close.
 * Big folders scroll; apps can be dragged around inside it (see [indexAt]).
 */
class FolderView(context: Context) : FrameLayout(context) {
    val title: EditText
    val grid: LinearLayout
    private val panel: LinearLayout
    private val scroll: android.widget.ScrollView
    var onClose: (() -> Unit)? = null
    private var columns = 4

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
        scroll = object : android.widget.ScrollView(context) {
            // Never taller than 70% of the screen; a big folder scrolls instead.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(grid)
        }
        panel.addView(scroll)
        addView(panel, LayoutParams(Pixel.dpi(context, 300), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setAccent(accent: Int, tint: Int? = null) {
        panel.background = PanelDrawable(context, if (tint != null) blend(Pixel.PANEL, tint, 0.18f) else Pixel.PANEL, tint ?: accent)
    }

    /** Lay [icons] out in rows: four across, five once the folder is big. */
    fun setIcons(icons: List<View>, cellHeight: Int) {
        columns = if (icons.size > 16) 5 else 4
        (panel.layoutParams as LayoutParams).width = Pixel.dpi(context, if (columns == 5) 360 else 300)
        grid.removeAllViews()
        icons.chunked(columns).forEach { rowIcons ->
            val row = LinearLayout(context)
            rowIcons.forEach { row.addView(it, LinearLayout.LayoutParams(0, cellHeight, 1f)) }
            repeat(columns - rowIcons.size) { row.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f)) }
            grid.addView(row)
        }
        panel.requestLayout()
    }

    /** True if a point (in this view's coordinates) is on the panel rather than the dim around it. */
    fun panelContains(x: Float, y: Float): Boolean =
        x >= panel.left && x <= panel.right && y >= panel.top && y <= panel.bottom

    /**
     * The slot nearest a point (in this view's coordinates), for dragging apps around inside.
     * Near the top or bottom edge of a long folder it also scrolls.
     */
    fun indexAt(x: Float, y: Float): Int {
        val icons = icons()
        if (icons.isEmpty()) return 0
        val edge = Pixel.dp(context, 40f)
        val scrollTop = panel.top + scroll.top
        if (y < scrollTop + edge) scroll.smoothScrollBy(0, -Pixel.dpi(context, 24))
        else if (y > scrollTop + scroll.height - edge) scroll.smoothScrollBy(0, Pixel.dpi(context, 24))
        val me = IntArray(2)
        getLocationInWindow(me)
        val loc = IntArray(2)
        return icons.indices.minBy { i ->
            val v = icons[i]
            v.getLocationInWindow(loc)
            val cx = loc[0] - me[0] + v.width / 2f
            val cy = loc[1] - me[1] + v.height / 2f
            (cx - x) * (cx - x) + (cy - y) * (cy - y)
        }
    }

    /** Where each icon is on screen now, so a re-order can glide them from there. */
    fun positions(): Map<Any?, Pair<Int, Int>> {
        val loc = IntArray(2)
        return icons().associate { v -> v.getLocationInWindow(loc); v.tagItem to (loc[0] to loc[1]) }
    }

    /** After the icons are re-laid, slide each from where it was to where it is. */
    fun glideFrom(before: Map<Any?, Pair<Int, Int>>) {
        if (!Pixel.motion(context)) return
        grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                val loc = IntArray(2)
                for (v in icons()) {
                    val old = before[v.tagItem] ?: continue
                    v.getLocationInWindow(loc)
                    v.translationX = (old.first - loc[0]).toFloat()
                    v.translationY = (old.second - loc[1]).toFloat()
                    v.animate().translationX(0f).translationY(0f).setDuration(220).setInterpolator(Ease.move).start()
                }
                return true
            }
        })
    }

    /** The panel grows out of nothing and the dim fades in. */
    fun popIn() {
        animate().cancel()
        panel.animate().cancel()
        alpha = 0f
        panel.scaleX = 0.85f
        panel.scaleY = 0.85f
        animate().alpha(1f).setDuration(180).setInterpolator(Ease.enter).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Ease.enter).start()
    }

    /** The reverse, a little quicker: leaving should never hold you up. */
    fun popOut(end: () -> Unit) {
        panel.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).setInterpolator(Ease.exit).start()
        animate().alpha(0f).setDuration(150).setInterpolator(Ease.exit).withEndAction {
            end()
            alpha = 1f
            panel.scaleX = 1f
            panel.scaleY = 1f
        }.start()
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
            // Grows from the side nearest the thing you pressed, and fades in.
            list.pivotX = list.measuredWidth / 2f
            list.pivotY = if (y < loc[1] + r.top) list.measuredHeight.toFloat() else 0f
            list.scaleX = 0.9f
            list.scaleY = 0.9f
            list.alpha = 0f
            list.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).setInterpolator(Ease.enter).start()
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
