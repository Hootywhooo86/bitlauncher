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
    /** Something search can do besides open an app: a sum, a conversion, a setting, a contact, the web. */
    class Answer(val title: String, val detail: String, val action: () -> Unit)

    /** Answers for what's typed (the activity knows about contacts, settings and the web). */
    var answersFor: ((query: String, appMatches: Int) -> List<Answer>)? = null

    /** The apps opened most recently from bitlauncher, newest first. */
    var recentFor: (() -> List<AppEntry>)? = null

    val search: EditText
    private val grid: GridView
    private val answers: LinearLayout
    private val recentBox: LinearLayout
    private val recentRow: LinearLayout
    private var firstAnswer: Answer? = null
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
        search = NoDropEditText(context).apply {
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
                if (go) {
                    val app = shown.firstOrNull()
                    if (app != null) onOpenFirst?.invoke(app) else firstAnswer?.action?.invoke()
                }
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
        answers = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
        }
        column.addView(answers, LinearLayout.LayoutParams(-1, -2).apply {
            val m = Pixel.dpi(context, 14)
            setMargins(m, 0, m, 0)
        })
        recentRow = LinearLayout(context)
        recentBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            addView(Pixel.label(context, "RECENT", 9f, Pixel.MUTED).apply {
                setPadding(Pixel.dpi(context, 18), Pixel.dpi(context, 4), 0, 0)
            })
            addView(recentRow)
            // A thin rule between recent and A-Z.
            addView(View(context).apply { setBackgroundColor(withAlpha(Pixel.MUTED, 50)) },
                LinearLayout.LayoutParams(-1, Pixel.dpi(context, 1)).apply {
                    val m = Pixel.dpi(context, 18)
                    setMargins(m, Pixel.dpi(context, 2), m, Pixel.dpi(context, 6))
                })
        }
        column.addView(recentBox, LinearLayout.LayoutParams(-1, -2))
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
        val raw = search.text.toString().trim()
        val q = searchable(raw)
        shown = if (q.isEmpty()) all else {
            val matches = all.filter { searchable(it.label).contains(q) }
            // Names that start with what you typed come first.
            matches.sortedBy { if (searchable(it.label).startsWith(q)) 0 else 1 }
        }
        showAnswers(if (raw.isEmpty()) emptyList() else answersFor?.invoke(raw, shown.size).orEmpty())
        showRecent(if (raw.isEmpty()) recentFor?.invoke().orEmpty() else emptyList())
        empty.visibility = if (shown.isEmpty() && all.isNotEmpty() && firstAnswer == null) VISIBLE else GONE
        adapter.notifyDataSetChanged()
    }

    private fun showAnswers(list: List<Answer>) {
        answers.removeAllViews()
        firstAnswer = list.firstOrNull()
        answers.visibility = if (list.isEmpty()) GONE else VISIBLE
        for (a in list.take(6)) {
            answers.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = PanelDrawable(context, Pixel.PANEL, withAlpha(Pixel.MUTED, 120))
                val p = Pixel.dpi(context, 12)
                setPadding(p, p - Pixel.dpi(context, 2), p + Pixel.dpi(context, 2), p)
                isClickable = true
                foreground = context.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener {
                    hideKeyboard()
                    a.action()
                }
                addView(TextView(context).apply {
                    text = a.title
                    setTextColor(Pixel.INK)
                    textSize = 16f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(Pixel.label(context, a.detail.uppercase(), 9f, Pixel.MUTED).apply {
                    setPadding(0, Pixel.dpi(context, 4), 0, 0)
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 6) })
        }
    }

    private fun showRecent(list: List<AppEntry>) {
        recentRow.removeAllViews()
        val cols = grid.numColumns.coerceAtLeast(1)
        recentBox.visibility = if (list.isEmpty()) GONE else VISIBLE
        list.take(cols).forEach { entry ->
            val v = AppIconView(context)
            bind(v, entry)
            recentRow.addView(v, LinearLayout.LayoutParams(0, cellHeight, 1f))
        }
        repeat(cols - minOf(cols, list.size)) { recentRow.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f)) }
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

/** A text field that never accepts drag-and-drop (an app dragged over it isn't text). */
class NoDropEditText(context: Context) : EditText(context) {
    override fun onDragEvent(event: android.view.DragEvent?): Boolean = false
}

/**
 * The apps in an open folder, in slots. Each icon stays attached the whole time (so a finger
 * holding one never loses it); re-ordering only changes which slot each is drawn in, and the
 * icons glide between slots.
 */
class FolderGrid(context: Context) : ViewGroup(context) {
    var columns = 4
    var cellHeight = Pixel.dpi(context, 90)
    /** Children in slot order: order[0] is spot 1. */
    val order = mutableListOf<View>()

    fun cellWidth() = (width - paddingLeft - paddingRight) / columns.toFloat()

    fun slotLeft(slot: Int) = paddingLeft + (slot % columns) * cellWidth()
    fun slotTop(slot: Int) = paddingTop + (slot / columns) * cellHeight.toFloat()

    /** The slot nearest a point in this view's coordinates. */
    fun slotAt(x: Float, y: Float): Int {
        val col = ((x - paddingLeft) / cellWidth()).toInt().coerceIn(0, columns - 1)
        val row = ((y - paddingTop) / cellHeight).toInt().coerceAtLeast(0)
        return (row * columns + col).coerceIn(0, (order.size - 1).coerceAtLeast(0))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = (order.size + columns - 1) / columns
        setMeasuredDimension(w, paddingTop + paddingBottom + rows * cellHeight)
        val cw = MeasureSpec.makeMeasureSpec(((w - paddingLeft - paddingRight) / columns), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        order.forEachIndexed { slot, v ->
            val left = slotLeft(slot).toInt()
            val top = slotTop(slot).toInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
    }

    /**
     * Move [v] to [slot]; everything between shifts along one. Each icon that changes slot is
     * re-laid there but drawn from where it was, then glides home. [except] is left alone
     * (the one under the finger, which follows the finger instead).
     */
    fun moveTo(v: View, slot: Int, except: View?) {
        val from = order.indexOf(v)
        if (from < 0 || from == slot) return
        val before = order.associateWith { it.left to it.top }
        order.removeAt(from)
        order.add(slot.coerceIn(0, order.size), v)
        order.forEachIndexed { i, child ->
            val left = slotLeft(i).toInt()
            val top = slotTop(i).toInt()
            val (oldL, oldT) = before.getValue(child)
            if (oldL == left && oldT == top) return@forEachIndexed
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            if (child === except) {
                // Keep it exactly under the finger: shift its offset by how far its slot moved.
                child.translationX += (oldL - left)
                child.translationY += (oldT - top)
                return@forEachIndexed
            }
            child.animate().cancel()
            child.translationX += (oldL - left)
            child.translationY += (oldT - top)
            if (Pixel.motion(context)) {
                child.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(Ease.move).start()
            } else {
                child.translationX = 0f
                child.translationY = 0f
            }
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = true
    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/**
 * An open folder: a panel with an editable name and its apps. Tap outside to close.
 * Hold an app and move to drag it to another spot; past the panel's edge it leaves the folder.
 */
class FolderView(context: Context) : FrameLayout(context) {
    val title: EditText
    val grid: FolderGrid
    private val panel: LinearLayout
    private val scroll: android.widget.ScrollView
    var onClose: (() -> Unit)? = null

    /** The new order, as icons (their tags say which app), when a drag inside ends. */
    var onReordered: ((List<AppIconView>) -> Unit)? = null

    /** A drag inside just started (close anything in the way, like the long-press menu). */
    var onDragStart: (() -> Unit)? = null

    /** The dragged app went past the panel's edge: hand it to the home screen (or dock). */
    var onDragLeave: ((AppIconView) -> Unit)? = null

    private var dragging: AppIconView? = null
    private var startOrder: List<View> = emptyList()
    private var grabX = 0f // where on the icon the finger is, so it doesn't jump
    private var grabY = 0f

    init {
        setBackgroundColor(withAlpha(Pixel.BG, 170))
        isClickable = true
        setOnClickListener { onClose?.invoke() }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Pixel.dpi(context, 16)
            setPadding(p, p, p, p)
            isClickable = true // taps on the panel don't close it
            clipChildren = false
            clipToPadding = false
        }
        title = NoDropEditText(context).apply {
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
        grid = FolderGrid(context).apply { clipChildren = false }
        scroll = object : android.widget.ScrollView(context) {
            // Never taller than 70% of the screen; a big folder scrolls instead.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            addView(grid)
        }
        panel.addView(scroll)
        addView(panel, LayoutParams(Pixel.dpi(context, 300), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setAccent(accent: Int, tint: Int? = null) {
        panel.background = PanelDrawable(context, if (tint != null) blend(Pixel.PANEL, tint, 0.18f) else Pixel.PANEL, tint ?: accent)
    }

    /** Lay [icons] out in slots: four across, five once the folder is big. */
    fun setIcons(icons: List<AppIconView>, cellHeight: Int) {
        dragging = null
        grid.columns = if (icons.size > 16) 5 else 4
        grid.cellHeight = cellHeight
        (panel.layoutParams as LayoutParams).width = Pixel.dpi(context, if (grid.columns == 5) 360 else 300)
        grid.removeAllViews()
        grid.order.clear()
        for (icon in icons) {
            grid.addView(icon)
            grid.order.add(icon)
            icon.onHoldDrag = { v -> beginDrag(v); true }
            icon.onHoldMove = { x, y -> dragTo(v = icon, rawX = x, rawY = y) }
            icon.onHoldEnd = { _, _, _ -> endDrag() }
        }
        grid.requestLayout()
        panel.requestLayout()
    }

    /** True if a point (in this view's coordinates) is on the panel rather than the dim around it. */
    fun panelContains(x: Float, y: Float): Boolean =
        x >= panel.left && x <= panel.right && y >= panel.top && y <= panel.bottom

    /** Pick the icon up: it lifts a little, with a hard pixel shadow, and follows the finger. */
    private fun beginDrag(v: AppIconView) {
        dragging = v
        onDragStart?.invoke()
        startOrder = grid.order.toList()
        grabX = -1f
        v.animate().cancel()
        v.translationZ = Pixel.dp(context, 8f)
        if (Pixel.motion(context)) v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).setInterpolator(Ease.enter).start()
        else { v.scaleX = 1.15f; v.scaleY = 1.15f }
        v.lifted = true
    }

    private fun dragTo(v: AppIconView, rawX: Float, rawY: Float) {
        if (dragging !== v) return
        val me = IntArray(2)
        getLocationOnScreen(me)
        val x = rawX - me[0]
        val y = rawY - me[1]
        if (!panelContains(x, y)) {
            // Out of the folder: undo the shuffling it did on the way out (it's leaving, not
            // moving), and let the home screen take the drag.
            settle(v, animate = false)
            dragging = null
            v.stopHolding()
            grid.order.clear()
            grid.order.addAll(startOrder)
            for (child in startOrder) { child.animate().cancel(); child.translationX = 0f; child.translationY = 0f }
            grid.requestLayout()
            onDragLeave?.invoke(v)
            return
        }
        val g = IntArray(2)
        grid.getLocationOnScreen(g)
        val gx = rawX - g[0]
        val gy = rawY - g[1]
        if (grabX < 0f) {
            // First move: remember where the finger holds the icon.
            grabX = gx - (v.left + v.translationX)
            grabY = gy - (v.top + v.translationY)
        }
        v.translationX = gx - grabX - v.left
        v.translationY = gy - grabY - v.top
        // Near the top or bottom of a long folder, scroll it.
        val sy = rawY - IntArray(2).also { scroll.getLocationOnScreen(it) }[1]
        val edge = Pixel.dp(context, 36f)
        if (sy < edge) scroll.scrollBy(0, -Pixel.dpi(context, 8)) else if (sy > scroll.height - edge) scroll.scrollBy(0, Pixel.dpi(context, 8))
        grid.moveTo(v, grid.slotAt(gx, gy), except = v)
    }

    private fun endDrag() {
        val v = dragging ?: return
        dragging = null
        settle(v, animate = true)
        onReordered?.invoke(grid.order.filterIsInstance<AppIconView>())
    }

    /** Drop the lifted icon into its slot. */
    private fun settle(v: AppIconView, animate: Boolean) {
        v.lifted = false
        if (animate && Pixel.motion(context)) {
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .withEndAction { v.translationZ = 0f }.start()
        } else {
            v.animate().cancel()
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationZ = 0f
        }
    }

    fun icons(): List<AppIconView> = grid.order.filterIsInstance<AppIconView>()

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
