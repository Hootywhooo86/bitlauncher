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
 * changes it slides down into place. On the lock screen the colon pulses.
 */
class PixelClockView(context: Context, private val blink: Boolean) : View(context) {
    var accent = Pixel.ACCENTS[0]
        set(v) { field = v; invalidate() }

    /** Digit style (see ClockFonts.ALL). */
    var font = "blocks"
        set(v) { field = v; invalidate() }
    private val ghost = Paint()

    /** Called every minute, for things that change with the time (like the date). */
    var onTick: (() -> Unit)? = null

    private val ink = Paint().apply { color = Pixel.INK }
    private val shadow = Paint().apply { color = Pixel.SHADOW }
    private val colon = Paint()
    private var digits = intArrayOf(-1, -1, -1, -1)
    private var leading = true // hide a leading zero in 12-hour time, like a real clock face
    private val drop = FloatArray(4) // 1 = fully above, 0 = landed
    private var dropAnim: ValueAnimator? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(animate = true)
    }
    /** The colon breathes once a second: fades down and back up, never snaps. */
    private val pulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
        duration = 1000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { colonLevel = it.animatedValue as Float; invalidate() }
    }
    private var colonLevel = 1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        update(animate = false)
        if (blink && Pixel.motion(context)) pulse.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(tick)
        pulse.cancel()
        dropAnim?.cancel()
    }

    /** Re-read the time. Digits that changed drop in. */
    fun update(animate: Boolean) {
        val now = Calendar.getInstance()
        val use24 = DateFormat.is24HourFormat(context)
        val next = PixelGlyphs.digitsFor(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), use24)
        leading = use24
        val changed = BooleanArray(4) { digits[it] != -1 && digits[it] != next[it] }
        previous = digits.copyOf()
        digits = next
        if (animate && changed.any { it } && Pixel.motion(context)) {
            for (i in 0..3) drop[i] = if (changed[i]) 1f else 0f
            dropAnim?.cancel()
            dropAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 420
                interpolator = Ease.enter
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
            if (!hidden) {
                if (font == "flap") flap(canvas, i, x, top, block) else drawDigit(canvas, digits[i], x, top - drop[i] * block * 3, block)
            }
            x += block * PixelGlyphs.W + block
            if (i == 1) {
                x += block // two-block gap before the colon
                colon.color = withAlpha(accent, (255 * colonLevel).toInt())
                canvas.drawRect(x, top + block * 2, x + block, top + block * 3, colon)
                canvas.drawRect(x, top + block * 4, x + block, top + block * 5, colon)
                x += block * 3
            }
        }
    }

    private var previous = intArrayOf(-1, -1, -1, -1)
    private val card = Paint()

    /**
     * A split-flap card, like a station departure board: a dark card with a hinge across the
     * middle. When the digit changes, the top flap falls: the old digit squashes to the hinge,
     * then the new one unfolds from it.
     */
    private fun flap(canvas: Canvas, i: Int, x: Float, top: Float, block: Float) {
        val l = x - block * 0.5f
        val r = x + block * (PixelGlyphs.W + 0.5f)
        // Inside the clock's own height (it's measured for the digits plus one block).
        val t = top
        val b = top + block * (PixelGlyphs.H + 0.6f)
        val mid = (t + b) / 2
        card.color = Pixel.SHADOW
        canvas.drawRect(l + block / 3, t + block / 3, r + block / 3, b + block / 3, card)
        card.color = blend(Pixel.PANEL, Pixel.BG, 0.3f)
        canvas.drawRect(l, t, r, b, card)
        card.color = blend(Pixel.PANEL, Pixel.INK, 0.06f)
        canvas.drawRect(l, t, r, mid, card)
        // The digit sits inside the card with a margin, like a printed flap.
        canvas.save()
        canvas.scale(0.82f, 0.82f, (l + r) / 2, mid)
        val f = drop[i]
        val old = previous[i]
        if (f > 0f && old >= 0) {
            // First half: the old digit's top half folds down to the hinge. Second half: the new one opens.
            val folding = f > 0.5f
            val d = if (folding) old else digits[i]
            val squash = if (folding) (f - 0.5f) * 2 else (0.5f - f) * 2
            canvas.save()
            canvas.scale(1f, squash.coerceAtLeast(0.05f), 0f, mid)
            drawDigit(canvas, d, x, top, block)
            canvas.restore()
        } else {
            drawDigit(canvas, digits[i], x, top, block)
        }
        canvas.restore()
        // The hinge: a dark line with a little pin at each end.
        card.color = Pixel.SHADOW
        canvas.drawRect(l, mid - block * 0.15f, r, mid + block * 0.15f, card)
        canvas.drawRect(l - block * 0.3f, mid - block * 0.5f, l, mid + block * 0.5f, card)
        canvas.drawRect(r, mid - block * 0.5f, r + block * 0.3f, mid + block * 0.5f, card)
    }

    private fun drawDigit(canvas: Canvas, d: Int, x: Float, y: Float, block: Float) {
        val rows = ClockFonts.digits(font)[d]
        val off = (block / 3f).coerceAtLeast(1f)
        if (font == "lcd") {
            // Unlit segments show faintly, like a real display.
            ghost.color = withAlpha(Pixel.INK, 22)
            val g = ClockFonts.LCD_GHOST
            for (r in 0 until PixelGlyphs.H) for (c in 0 until PixelGlyphs.W) if (g[r][c] == '#') {
                canvas.drawRect(x + c * block, y + r * block, x + (c + 1) * block, y + (r + 1) * block, ghost)
            }
        }
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
