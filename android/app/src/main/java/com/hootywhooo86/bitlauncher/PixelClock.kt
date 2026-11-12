package com.hootywhooo86.bitlauncher

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import android.view.View
import java.util.Calendar

/** Hand-drawn 5x7 digits. '#' is a block. */
object PixelGlyphs {
    val DIGITS: Array<Array<String>> = arrayOf(
        arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    )
    const val W = 5
    const val H = 7

    /** Width in blocks of "HH:MM": digit, gap, digit, 2 gap, colon, 2 gap, digit, gap, digit. */
    const val CLOCK_W = W * 4 + 1 + 2 + 1 + 2 + 1

    /** The four digits for a time, honouring the phone's 12/24-hour setting. */
    fun digitsFor(hour24: Int, minute: Int, use24: Boolean): IntArray {
        val h = if (use24) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        return intArrayOf(h / 10, h % 10, minute / 10, minute % 10)
    }
}

/**
 * A chunky block clock. Each digit is drawn as squares with a hard drop shadow. When a digit
 * changes it drops in from above, a block at a time. On the lock screen the colon blinks.
 */
class PixelClockView(context: Context, private val blink: Boolean) : View(context) {
    var accent = Pixel.ACCENTS[0]
        set(v) { field = v; invalidate() }

    /** Called every minute, for things that change with the time (like the date). */
    var onTick: (() -> Unit)? = null

    private val ink = Paint().apply { color = Pixel.INK }
    private val shadow = Paint().apply { color = Pixel.SHADOW }
    private val colon = Paint()
    private var digits = intArrayOf(-1, -1, -1, -1)
    private var leading = true // hide a leading zero in 12-hour time, like a real clock face
    private val drop = FloatArray(4) // 1 = fully above, 0 = landed
    private var colonOn = true
    private var dropAnim: ValueAnimator? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(animate = true)
    }
    private val blinker = object : Runnable {
        override fun run() {
            colonOn = !colonOn
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        update(animate = false)
        if (blink) postDelayed(blinker, 1000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(tick)
        removeCallbacks(blinker)
        dropAnim?.cancel()
    }

    /** Re-read the time. Digits that changed drop in. */
    fun update(animate: Boolean) {
        val now = Calendar.getInstance()
        val use24 = DateFormat.is24HourFormat(context)
        val next = PixelGlyphs.digitsFor(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), use24)
        leading = use24
        val changed = BooleanArray(4) { digits[it] != -1 && digits[it] != next[it] }
        digits = next
        if (animate && changed.any { it } && Pixel.motion(context)) {
            for (i in 0..3) drop[i] = if (changed[i]) 1f else 0f
            dropAnim?.cancel()
            dropAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 280
                interpolator = StepInterpolator(4)
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    for (i in 0..3) if (changed[i]) drop[i] = v
                    invalidate()
                }
                start()
            }
        } else {
            drop.fill(0f)
        }
        onTick?.invoke()
        contentDescription = String.format("%d%d:%d%d", digits[0], digits[1], digits[2], digits[3])
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val block = (w / (PixelGlyphs.CLOCK_W + 1)).coerceAtLeast(1)
        // A little room above for digits dropping in, and below for the shadow.
        setMeasuredDimension(w, block * (PixelGlyphs.H + 1))
    }

    override fun onDraw(canvas: Canvas) {
        if (digits[0] < 0) return
        val block = (width / (PixelGlyphs.CLOCK_W + 1)).coerceAtLeast(1).toFloat()
        // A hidden leading zero (12-hour "8:11") shouldn't push the clock off-centre.
        val skip = !leading && digits[0] == 0
        val slot = block * (PixelGlyphs.W + 1)
        val drawn = block * PixelGlyphs.CLOCK_W - if (skip) slot else 0f
        val left = (width - drawn) / 2f - if (skip) slot else 0f
        val top = 0f
        var x = left
        for (i in 0..3) {
            val hidden = i == 0 && skip
            if (!hidden) drawDigit(canvas, digits[i], x, top - drop[i] * block * 3, block)
            x += block * PixelGlyphs.W + block
            if (i == 1) {
                x += block // two-block gap before the colon
                colon.color = if (colonOn) accent else withAlpha(accent, 60)
                canvas.drawRect(x, top + block * 2, x + block, top + block * 3, colon)
                canvas.drawRect(x, top + block * 4, x + block, top + block * 5, colon)
                x += block * 3
            }
        }
    }

    private fun drawDigit(canvas: Canvas, d: Int, x: Float, y: Float, block: Float) {
        val rows = PixelGlyphs.DIGITS[d]
        val off = (block / 3f).coerceAtLeast(1f)
        for (pass in 0..1) {
            val paint = if (pass == 0) shadow else ink
            val o = if (pass == 0) off else 0f
            for (r in 0 until PixelGlyphs.H) for (c in 0 until PixelGlyphs.W) {
                if (rows[r][c] == '#') {
                    canvas.drawRect(x + c * block + o, y + r * block + o, x + (c + 1) * block + o, y + (r + 1) * block + o, paint)
                }
            }
        }
    }
}

/** A chunky up-chevron, three blocks tall, for "swipe up". */
class PixelChevron(context: Context) : View(context) {
    private val paint = Paint().apply { color = Pixel.INK }
    private val shadow = Paint().apply { color = Pixel.SHADOW }
    private val rows = arrayOf("..#..", ".#.#.", "#...#")

    override fun onDraw(canvas: Canvas) {
        val block = (height / 4f).coerceAtLeast(1f)
        val left = (width - block * 5) / 2f
        for ((paintFor, o) in listOf(shadow to block / 3f, paint to 0f)) {
            for (r in rows.indices) for (c in 0 until 5) if (rows[r][c] == '#') {
                canvas.drawRect(left + c * block + o, r * block + o, left + (c + 1) * block + o, (r + 1) * block + o, paintFor)
            }
        }
    }
}
