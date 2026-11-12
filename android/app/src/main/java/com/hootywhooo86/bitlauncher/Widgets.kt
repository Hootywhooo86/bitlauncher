package com.hootywhooo86.bitlauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Our widget host: hands out [PixelWidgetView]s so widgets get the same long-press as icons. */
class PixelWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        PixelWidgetView(context)

    companion object {
        const val HOST_ID = 0x128B
    }
}

/**
 * A hosted widget. Long-press anywhere on it opens the widget menu; moving after the long press
 * starts a drag. The widget still gets its own taps and scrolls.
 */
class PixelWidgetView(context: Context) : AppWidgetHostView(context) {
    var tagItem: WidgetItem? = null
    var onLongPress: ((PixelWidgetView) -> Unit)? = null
    var onDragOut: ((PixelWidgetView) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        onLongPress?.invoke(this)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                if (longPressed && moved) {
                    longPressed = false
                    onDragOut?.invoke(this)
                    return true
                }
                if (moved) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                // After a long press, swallow the release so the widget doesn't also see a tap.
                if (longPressed) return true
            }
        }
        return longPressed
    }

    /** Reached when no part of the widget took the touch (or after we intercepted it). */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val moved = abs(event.x - downX) > slop || abs(event.y - downY) > slop
                if (longPressed && moved) {
                    longPressed = false
                    onDragOut?.invoke(this)
                } else if (moved) {
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                longPressed = false
            }
        }
        return true
    }

    /** Tell the widget how big it really is, so it can pick the right layout. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val d = resources.displayMetrics.density
        val wd = (w / d).toInt()
        val hd = (h / d).toInt()
        try {
            @Suppress("DEPRECATION")
            updateAppWidgetSize(Bundle(), wd, hd, wd, hd)
        } catch (e: Exception) {
            // Some providers throw on resize; the widget still shows at its default layout.
        }
    }
}

/**
 * Several widgets in one spot. One shows at a time; swiping up or down on it (the home screen
 * passes that swipe here) flips to the next, which slides in. Pixel dots down the right edge
 * show where you are.
 */
class StackView(context: Context) : android.widget.FrameLayout(context) {
    var showing = 0
        private set
    var accent = Pixel.ACCENTS[0]
    /** Told the new index after a flip, so it can be saved. */
    var onFlip: ((Int) -> Unit)? = null

    private val dot = android.graphics.Paint()
    private val size = Pixel.dp(context, 5f)

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    fun setPages(views: List<android.view.View>, show: Int) {
        removeAllViews()
        views.forEach { addView(it, LayoutParams(-1, -1)) }
        showing = show.coerceIn(0, (views.size - 1).coerceAtLeast(0))
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.visibility = if (i == showing) VISIBLE else INVISIBLE // laid out at full size, so each knows its size
            c.translationY = 0f
            c.alpha = 1f
        }
        invalidate()
    }

    /** Flip by [dir] (+1 next, -1 previous), wrapping round. */
    fun flip(dir: Int) {
        val n = childCount
        if (n < 2) return
        val from = getChildAt(showing)
        showing = ((showing + dir) % n + n) % n
        val to = getChildAt(showing)
        to.visibility = VISIBLE
        val travel = height * 0.35f
        if (Pixel.motion(context)) {
            to.translationY = travel * dir
            to.alpha = 0f
            to.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(Ease.move).start()
            from.animate().translationY(-travel * dir).alpha(0f).setDuration(200).setInterpolator(Ease.exit).withEndAction {
                from.visibility = INVISIBLE
                from.translationY = 0f
                from.alpha = 1f
            }.start()
        } else {
            from.visibility = INVISIBLE
        }
        invalidate()
        onFlip?.invoke(showing)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val n = childCount
        if (n < 2) return
        val gap = size
        val total = n * size + (n - 1) * gap
        val x = width - size * 2
        var y = (height - total) / 2
        for (i in 0 until n) {
            dot.color = Pixel.SHADOW
            canvas.drawRect(x - 1, y - 1, x + size + 1, y + size + 1, dot)
            dot.color = if (i == showing) accent else withAlpha(Pixel.INK, 140)
            canvas.drawRect(x, y, x + size, y + size, dot)
            y += size + gap
        }
    }
}
