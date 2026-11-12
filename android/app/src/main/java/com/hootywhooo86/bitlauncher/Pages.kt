package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * One page, small: a picture of it in a pixel frame with its number. Tap to go there, hold and
 * move to drag it somewhere else, tap the X in the corner to delete it.
 */
class PageThumb(context: Context, var picture: Bitmap?, var number: Int) : View(context) {
    var accent = Pixel.ACCENTS[0]
    var onTap: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onHold: (() -> Unit)? = null
    var onHoldMove: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    var lifted = false
        set(v) { field = v; invalidate() }

    private val frame = PanelDrawable(context, Pixel.PANEL, Pixel.MUTED)
    private val pic = Paint().apply { isFilterBitmap = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Pixel.font(context); color = Pixel.INK }
    private val box = Paint()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val px = Pixel.dp(context, 2f)
    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private var moved = false
    private val hold = Runnable {
        holding = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onHold?.invoke()
    }

    /** The corner X, in this view's coordinates. */
    private fun deleteBox(): RectF {
        val s = Pixel.dp(context, 26f)
        return RectF(width - s - px * 2, px * 2, width - px * 2, px * 2 + s)
    }

    private fun pictureRect(): Rect {
        val inset = (px * 4).toInt()
        val labelSpace = Pixel.dpi(context, 22)
        return Rect(inset, inset, width - inset, height - inset - labelSpace)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pictureRect()
        if (lifted) {
            // A hard pixel shadow under the lifted card.
            box.color = withAlpha(Pixel.SHADOW, 160)
            canvas.drawRect(p.left + px * 4, p.top + px * 4, p.right + px * 4, p.bottom + px * 4, box)
        }
        frame.border = if (lifted) accent else Pixel.MUTED
        frame.setBounds(p.left - px.toInt() * 2, p.top - px.toInt() * 2, p.right + px.toInt() * 2, p.bottom + px.toInt() * 2)
        frame.draw(canvas)
        picture?.let { canvas.drawBitmap(it, null, p, pic) }
        // Page number underneath.
        text.textSize = Pixel.dp(context, 10f)
        val label = "PAGE $number"
        canvas.drawText(label, (width - text.measureText(label)) / 2, height - Pixel.dp(context, 6f), text)
        // The delete X.
        val d = deleteBox()
        box.color = Pixel.SHADOW
        canvas.drawRect(d, box)
        box.color = Pixel.PANEL
        canvas.drawRect(d.left + px, d.top + px, d.right - px, d.bottom - px, box)
        box.color = Pixel.INK
        val c = d.width() / 2
        for (i in -3..3) {
            canvas.drawRect(d.left + c + i * px - px / 2, d.top + c + i * px - px / 2, d.left + c + i * px + px / 2, d.top + c + i * px + px / 2, box)
            canvas.drawRect(d.left + c + i * px - px / 2, d.top + c - i * px - px / 2, d.left + c + i * px + px / 2, d.top + c - i * px + px / 2, box)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                holding = false
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (holding) onHoldMove?.invoke(e.rawX, e.rawY)
                else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                    moved = true
                    removeCallbacks(hold)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (holding) {
                    holding = false
                    onHoldEnd?.invoke()
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !moved) {
                    if (deleteBox().contains(e.x, e.y)) onDelete?.invoke() else onTap?.invoke()
                }
            }
        }
        return true
    }
}

/**
 * All home pages at once (pinch in on home, or long-press > Pages). Drag to reorder, X to
 * delete, + to add, tap one to go there. The activity applies each change to the layout.
 */
class PageOverview(context: Context) : FrameLayout(context) {
    val grid = FolderGrid(context)
    private val scroll = ScrollView(context)
    private val title = Pixel.label(context, "PAGES", 14f)
    var onClose: (() -> Unit)? = null
    var onGoTo: ((Int) -> Unit)? = null
    var onMove: ((from: Int, to: Int) -> Unit)? = null
    var onDelete: ((Int) -> Unit)? = null
    var onAdd: (() -> Unit)? = null

    private var dragging: PageThumb? = null
    private var dragFrom = -1
    private var grabX = -1f
    private var grabY = 0f

    init {
        setBackgroundColor(withAlpha(Pixel.BG, 245))
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.gravity = Gravity.CENTER
        title.setPadding(0, Pixel.dpi(context, 18), 0, Pixel.dpi(context, 4))
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        column.addView(Pixel.label(context, "HOLD AND DRAG TO REORDER", 9f, Pixel.MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 12) })
        grid.clipChildren = false
        val p = Pixel.dpi(context, 12)
        grid.setPadding(p, 0, p, p)
        scroll.clipChildren = false
        scroll.addView(grid)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(Pixel.button(context, "DONE", Pixel.ACCENTS[0]) { onClose?.invoke() }.also { done = it },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(p * 2, p, p * 2, p * 2) })
        addView(column, LayoutParams(-1, -1))
    }

    private lateinit var done: View

    fun setInsets(top: Int, bottom: Int) = setPadding(0, top, 0, bottom)

    /**
     * Show [pictures] (one per page) plus an add tile. [columns] is 2 on a phone, 3 on a wide
     * screen; [ratio] is a page's height over its width, so thumbnails keep its shape.
     */
    fun show(pictures: List<Bitmap?>, accent: Int, columns: Int, ratio: Float, cellWidth: Int) {
        done.background = PanelDrawable(context, Pixel.PANEL, accent)
        grid.removeAllViews()
        grid.order.clear()
        grid.columns = columns
        grid.cellHeight = (cellWidth * ratio).toInt() + Pixel.dpi(context, 30)
        pictures.forEachIndexed { i, bmp ->
            val t = PageThumb(context, bmp, i + 1).apply { this.accent = accent }
            t.onTap = { onGoTo?.invoke(grid.order.indexOf(t)) }
            t.onDelete = { onDelete?.invoke(grid.order.indexOf(t)) }
            t.onHold = { begin(t) }
            t.onHoldMove = { x, y -> drag(t, x, y) }
            t.onHoldEnd = { end(t) }
            grid.addView(t)
            grid.order.add(t)
        }
        val add = Pixel.button(context, "+", accent) { onAdd?.invoke() }.apply { textSize = 24f }
        grid.addView(add)
        grid.order.add(add)
        grid.requestLayout()
    }

    private fun begin(t: PageThumb) {
        dragging = t
        dragFrom = grid.order.indexOf(t)
        grabX = -1f
        t.lifted = true
        t.translationZ = Pixel.dp(context, 8f)
    }

    private fun drag(t: PageThumb, rawX: Float, rawY: Float) {
        if (dragging !== t) return
        val g = IntArray(2)
        grid.getLocationOnScreen(g)
        val gx = rawX - g[0]
        val gy = rawY - g[1]
        if (grabX < 0f) {
            grabX = gx - t.left
            grabY = gy - t.top
        }
        t.translationX = gx - grabX - t.left
        t.translationY = gy - grabY - t.top
        // Pages only; the + tile always stays last.
        val slot = grid.slotAt(gx, gy).coerceAtMost(grid.order.size - 2)
        grid.moveTo(t, slot, except = t)
    }

    private fun end(t: PageThumb) {
        if (dragging !== t) return
        dragging = null
        t.lifted = false
        t.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(Ease.move).withEndAction { t.translationZ = 0f }.start()
        val to = grid.order.indexOf(t)
        if (to != dragFrom) onMove?.invoke(dragFrom, to)
    }
}

/**
 * Resizing a widget by its edges: a frame with a pixel handle on each side. Drag a handle and
 * the frame snaps to whole cells; let go and the widget takes that size. Tap anywhere else
 * to finish.
 */
class ResizeFrame(context: Context) : View(context) {
    /** Cell rectangles of the page, in this view's coordinates: cell (x, y) is at cells[y][x]. */
    var cellLeft = 0f
    var cellTop = 0f
    var cellW = 1f
    var cellH = 1f
    var cols = 1
    var rows = 1
    var minW = 1
    var minH = 1
    var accent = Pixel.ACCENTS[0]

    /** Current span (cells). */
    var x = 0
    var y = 0
    var w = 1
    var h = 1

    /** Asked whether a span is allowed (on the grid, nothing in the way). */
    var canFit: ((x: Int, y: Int, w: Int, h: Int) -> Boolean)? = null
    var onResize: ((x: Int, y: Int, w: Int, h: Int) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val line = Paint().apply { style = Paint.Style.STROKE }
    private val fill = Paint()
    private var edge = -1 // 0 left, 1 top, 2 right, 3 bottom
    private val handle = Pixel.dp(context, 14f)

    private fun rectOf(x: Int, y: Int, w: Int, h: Int) =
        RectF(cellLeft + x * cellW, cellTop + y * cellH, cellLeft + (x + w) * cellW, cellTop + (y + h) * cellH)

    private fun handles(r: RectF) = listOf(
        RectF(r.left - handle, r.centerY() - handle, r.left + handle, r.centerY() + handle),
        RectF(r.centerX() - handle, r.top - handle, r.centerX() + handle, r.top + handle),
        RectF(r.right - handle, r.centerY() - handle, r.right + handle, r.centerY() + handle),
        RectF(r.centerX() - handle, r.bottom - handle, r.centerX() + handle, r.bottom + handle),
    )

    override fun onDraw(canvas: Canvas) {
        val r = rectOf(x, y, w, h)
        fill.color = withAlpha(accent, 40)
        canvas.drawRect(r, fill)
        line.color = accent
        line.strokeWidth = Pixel.dp(context, 3f)
        canvas.drawRect(r, line)
        for (hr in handles(r)) {
            fill.color = Pixel.SHADOW
            canvas.drawRect(hr.left + 4, hr.top + 4, hr.right + 4, hr.bottom + 4, fill)
            fill.color = accent
            canvas.drawRect(hr, fill)
            fill.color = Pixel.INK
            canvas.drawRect(hr.centerX() - 4, hr.centerY() - 4, hr.centerX() + 4, hr.centerY() + 4, fill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hs = handles(rectOf(x, y, w, h))
                // A little extra reach around each handle: fingers are bigger than pixels.
                edge = hs.indexOfFirst { RectF(it.left - handle, it.top - handle, it.right + handle, it.bottom + handle).contains(e.x, e.y) }
                if (edge < 0 && !rectOf(x, y, w, h).contains(e.x, e.y)) onDone?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (edge >= 0) {
                val cx = Math.round((e.x - cellLeft) / cellW).coerceIn(0, cols)
                val cy = Math.round((e.y - cellTop) / cellH).coerceIn(0, rows)
                var nx = x; var ny = y; var nw = w; var nh = h
                when (edge) {
                    0 -> { nx = cx.coerceAtMost(x + w - minW); nw = x + w - nx }
                    1 -> { ny = cy.coerceAtMost(y + h - minH); nh = y + h - ny }
                    2 -> nw = (cx - x).coerceAtLeast(minW)
                    3 -> nh = (cy - y).coerceAtLeast(minH)
                }
                if ((nx != x || ny != y || nw != w || nh != h) && canFit?.invoke(nx, ny, nw, nh) == true) {
                    x = nx; y = ny; w = nw; h = nh
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edge >= 0) onResize?.invoke(x, y, w, h)
                edge = -1
            }
        }
        return true
    }
}
