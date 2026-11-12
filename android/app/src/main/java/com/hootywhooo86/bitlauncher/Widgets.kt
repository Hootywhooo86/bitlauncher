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
