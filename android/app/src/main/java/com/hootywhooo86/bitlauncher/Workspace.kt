package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import kotlin.math.abs

/** Where a child sits on the grid. */
class CellParams(var x: Int, var y: Int, var w: Int = 1, var h: Int = 1) : ViewGroup.LayoutParams(0, 0)

/** One home-screen page: a cols x rows grid. Children are placed by [CellParams]. */
class CellLayout(context: Context, var cols: Int, var rows: Int) : ViewGroup(context) {
    private val highlight = Paint().apply { style = Paint.Style.STROKE }
    private var hint: Rect? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    fun cellWidth() = (width - paddingLeft - paddingRight) / cols.toFloat()
    fun cellHeight() = (height - paddingTop - paddingBottom) / rows.toFloat()

    /** The cell under a point in this view's coordinates. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val cx = ((x - paddingLeft) / cellWidth()).toInt().coerceIn(0, cols - 1)
        val cy = ((y - paddingTop) / cellHeight()).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }

    /** Outline the cells a drop would land in, or clear with null. */
    fun showDropHint(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val cw = cellWidth()
        val ch = cellHeight()
        hint = Rect(
            (paddingLeft + x * cw).toInt(), (paddingTop + y * ch).toInt(),
            (paddingLeft + (x + w) * cw).toInt(), (paddingTop + (y + h) * ch).toInt(),
        )
        highlight.color = color
        highlight.strokeWidth = Pixel.dp(context, 2f)
        invalidate()
    }

    fun clearDropHint() {
        if (hint != null) {
            hint = null
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight) / cols.toFloat()
        val ch = (h - paddingTop - paddingBottom) / rows.toFloat()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as CellParams
            child.measure(
                MeasureSpec.makeMeasureSpec((cw * lp.w).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((ch * lp.h).toInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth()
        val ch = cellHeight()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as CellParams
            val left = (paddingLeft + lp.x * cw).toInt()
            val top = (paddingTop + lp.y * ch).toInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        hint?.let {
            val inset = highlight.strokeWidth
            canvas.drawRect(it.left + inset, it.top + inset, it.right - inset, it.bottom - inset, highlight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is CellParams
    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(0, 0)
}

/**
 * The home pages side by side. Swipe left and right between pages, up for the app drawer,
 * down for notifications; long-press empty space for the home menu.
 */
class Workspace(context: Context) : ViewGroup(context) {
    interface Listener {
        fun onSwipeUp()
        fun onSwipeDown()
        fun onEmptyLongPress(x: Float, y: Float)
        fun onPageChanged(page: Int)
    }

    var listener: Listener? = null
    var currentPage = 0
        private set

    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var mode = 0 // 0 none, 1 horizontal, 2 vertical
    private var longPressFired = false
    private val longPress = Runnable {
        longPressFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onEmptyLongPress(downX + scrollX, downY)
    }

    val pageCount: Int get() = childCount

    fun page(i: Int): CellLayout? = getChildAt(i) as? CellLayout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec(w - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h - paddingTop - paddingBottom, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val left = i * width + paddingLeft
            getChildAt(i).layout(left, paddingTop, left + width - paddingLeft - paddingRight, b - t - paddingBottom)
        }
        if (scroller.isFinished) scrollTo(currentPage * width, 0)
    }

    fun snapTo(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != currentPage
        currentPage = target
        if (animate && width > 0) {
            scroller.startScroll(scrollX, 0, target * width - scrollX, 0, 220)
            postInvalidateOnAnimation()
        } else {
            scroller.forceFinished(true)
            scrollTo(target * width, 0)
        }
        if (changed) listener?.onPageChanged(target)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                mode = 0
                longPressFired = false
                if (!scroller.isFinished) {
                    mode = 1 // catch a page mid-scroll
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (dx > slop && dx > dy) mode = 1
                else if (dy > slop && dy > dx) mode = 2
                if (mode != 0) {
                    lastX = e.x
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                longPressFired = false
                if (mode == 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (mode == 0) {
                    if (dx > slop && dx > dy) mode = 1 else if (dy > slop && dy > dx) mode = 2
                    if (mode != 0) removeCallbacks(longPress)
                }
                if (mode == 1) {
                    val maxX = ((childCount - 1) * width).coerceAtLeast(0)
                    val next = (scrollX + (lastX - e.x)).coerceIn(-width / 8f, maxX + width / 8f)
                    scrollTo(next.toInt(), 0)
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val v = velocity
                v?.computeCurrentVelocity(1000)
                val vx = v?.xVelocity ?: 0f
                val vy = v?.yVelocity ?: 0f
                if (mode == 1) {
                    val page = when {
                        vx < -minFling -> currentPage + 1
                        vx > minFling -> currentPage - 1
                        else -> (scrollX + width / 2) / width.coerceAtLeast(1)
                    }
                    snapTo(page)
                } else if (mode == 2 && e.actionMasked == MotionEvent.ACTION_UP) {
                    val dy = e.y - downY
                    if (dy < -slop * 3 || vy < -minFling) listener?.onSwipeUp()
                    else if (dy > slop * 3 || vy > minFling) listener?.onSwipeDown()
                }
                mode = 0
                v?.recycle()
                velocity = null
            }
        }
        return true
    }
}

/** The fixed row at the bottom. One slot per column, no labels. */
class DockView(context: Context) : ViewGroup(context) {
    var slots = 5
    val panel = PanelDrawable(context, withAlpha(Pixel.PANEL, 200), withAlpha(Pixel.MUTED, 120))

    init {
        background = panel
        val p = Pixel.dpi(context, 6)
        setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
    }

    fun slotAt(x: Float): Int =
        ((x - paddingLeft) / ((width - paddingLeft - paddingRight) / slots.toFloat())).toInt().coerceIn(0, slots - 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val sw = (w - paddingLeft - paddingRight) / slots
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - paddingTop - paddingBottom, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sw = (width - paddingLeft - paddingRight) / slots
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val slot = (child.layoutParams as CellParams).x
            val left = paddingLeft + slot * sw
            child.layout(left, paddingTop, left + sw, paddingTop + child.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is CellParams
    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(0, 0)
}

/** Page dots: little squares, the current one in the accent. */
class PageDots(context: Context) : View(context) {
    var count = 1
        set(v) { field = v; invalidate() }
    var current = 0
        set(v) { field = v; invalidate() }
    var accent = Pixel.ACCENTS[0]
    private val paint = Paint()
    private val size = Pixel.dp(context, 6f)

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val gap = size
        val total = count * size + (count - 1) * gap
        var x = (width - total) / 2
        val y = (height - size) / 2
        for (i in 0 until count) {
            paint.color = if (i == current) accent else withAlpha(Pixel.INK, 110)
            canvas.drawRect(x, y, x + size, y + size, paint)
            x += size + gap
        }
    }
}
