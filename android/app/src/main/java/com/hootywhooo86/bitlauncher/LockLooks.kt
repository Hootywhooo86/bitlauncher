package com.hootywhooo86.bitlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/** The lock screen's looks. Each is pixel art; they differ in how the same things are framed. */
object LockLooks {
    data class Look(val key: String, val name: String, val about: String)

    val ALL = listOf(
        Look("classic", "Classic", "Block clock, date and notification cards."),
        Look("rpg", "RPG", "Notifications arrive as dialog boxes, the date sits in a status window."),
        Look("arcade", "Arcade", "CRT scanlines, PLAYER 1, notifications as a high-score table."),
        Look("pocket", "Pocket", "Everything on a little LCD screen, dot-matrix and all."),
        Look("minimal", "Minimal", "A giant clock and one line saying what's new."),
        Look("terminal", "Terminal", "A command line: notifications as log lines, a blinking block cursor."),
        Look("desktop", "Desktop", "A retro computer desktop: each notification is a little window."),
        Look("departures", "Departures", "A station board: when it came in, from which app, and what it says."),
    )

    fun hint(key: String) = when (key) {
        "rpg" -> "SWIPE UP TO CONTINUE"
        "arcade" -> "SWIPE UP TO START"
        "pocket" -> "SWIPE UP TO POWER ON"
        "terminal" -> "> SWIPE UP TO LOG IN"
        "desktop" -> "SWIPE UP TO START"
        "departures" -> "SWIPE UP TO BOARD"
        else -> "SWIPE UP TO UNLOCK"
    }

    /** "3 NEW  ·  CHAT, MAIL" for the minimal look; null with nothing new. */
    fun summary(lines: List<LockLine>, names: (String) -> String): String? {
        if (lines.isEmpty()) return null
        val total = lines.sumOf { it.count }
        val apps = lines.take(3).joinToString(", ") { names(it.pkg).uppercase() } + if (lines.size > 3) " +${lines.size - 3}" else ""
        return "$total NEW  ·  $apps"
    }

    /** "1ST", "2ND", "3RD", "4TH"... */
    fun place(i: Int) = (i + 1).let { n -> "$n" + when (n) { 1 -> "ST"; 2 -> "ND"; 3 -> "RD"; else -> "TH" } }
}

/**
 * An RPG text box: thick light border, a gap, then a thin inner line, corners notched.
 * Drawn from rects in whole blocks so it stays crisp.
 */
class RpgBoxDrawable(context: Context, private val fill: Int, private val edge: Int, private val inner: Int) : Drawable() {
    private val b = Pixel.dp(context, 2f)
    private val p = Paint()

    override fun draw(c: Canvas) {
        val r = bounds
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val rr = r.right.toFloat()
        val bb = r.bottom.toFloat()
        // Hard shadow first.
        p.color = Pixel.SHADOW
        c.drawRect(l + b * 2, t + b * 2, rr, bb, p)
        val R = rr - b * 2
        val B = bb - b * 2
        // Outer border, notched by one block at each corner.
        p.color = edge
        c.drawRect(l + b, t, R - b, B, p)
        c.drawRect(l, t + b, R, B - b, p)
        p.color = fill
        c.drawRect(l + b * 2, t + b * 2, R - b * 2, B - b * 2, p)
        // Thin inner line.
        p.color = inner
        val i = b * 3.5f
        c.drawRect(l + i, t + i, R - i, t + i + b * 0.5f, p)
        c.drawRect(l + i, B - i - b * 0.5f, R - i, B - i, p)
        c.drawRect(l + i, t + i, l + i + b * 0.5f, B - i, p)
        c.drawRect(R - i - b * 0.5f, t + i, R - i, B - i, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A pocket console's screen: chunky bezel, a power light, and a dot-matrix grid over the LCD.
 * [lcd] is the screen's tint; the grid is drawn over whatever the children paint.
 */
class PocketDrawable(context: Context, private val accent: Int) : Drawable() {
    private val b = Pixel.dp(context, 2f)
    private val p = Paint()
    val bezel = (b * 7).toInt()

    override fun draw(c: Canvas) {
        val r = bounds
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val R = r.right.toFloat()
        val B = r.bottom.toFloat()
        p.color = Pixel.SHADOW
        c.drawRect(l + b * 2, t + b * 2, R, B, p)
        p.color = blend(Pixel.PANEL, Pixel.INK, 0.12f)
        c.drawRect(l + b, t, R - b * 3, B - b * 2, p)
        c.drawRect(l, t + b, R - b * 2, B - b * 3, p)
        // Screen well.
        val sl = l + bezel - b * 2
        val st = t + bezel
        val sr = R - bezel
        val sb = B - bezel
        p.color = Pixel.SHADOW
        c.drawRect(sl - b, st - b, sr + b, sb + b, p)
        p.color = blend(Pixel.BG, accent, 0.08f)
        c.drawRect(sl, st, sr, sb, p)
        // Power light, on.
        p.color = accent
        c.drawRect(l + b * 2, t + bezel + b * 4, l + b * 3.5f, t + bezel + b * 5.5f, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Fine lines: CRT scanlines, or a dot-matrix LCD grid. */
class TextureDrawable(context: Context, private val grid: Boolean) : Drawable() {
    private val step = Pixel.dp(context, 3f)
    private val line = Pixel.dp(context, 1f)
    private val p = Paint().apply { color = withAlpha(0xFF000000.toInt(), if (grid) 45 else 70) }

    override fun draw(c: Canvas) {
        val r = bounds
        var y = r.top.toFloat()
        while (y < r.bottom) {
            c.drawRect(r.left.toFloat(), y, r.right.toFloat(), y + line, p)
            y += step
        }
        if (grid) {
            var x = r.left.toFloat()
            while (x < r.right) {
                c.drawRect(x, r.top.toFloat(), x + line, r.bottom.toFloat(), p)
                x += step
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Scanlines over the whole screen. Draws only; never takes touches. */
class ScreenTexture(context: Context, grid: Boolean) : View(context) {
    init {
        background = TextureDrawable(context, grid)
    }
}

/** The RPG "more to read" arrow, breathing in and out. */
class MoreArrow(context: Context, private val color: Int) : View(context) {
    private val p = Paint().apply { this.color = color }
    private val u = Pixel.dp(context, 2f)
    private val pulse = ValueAnimator.ofFloat(1f, 0.25f).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((u * 5).toInt(), (u * 3).toInt())

    override fun onDraw(c: Canvas) {
        for (row in 0 until 3) c.drawRect(u * row, u * row, u * (5 - row), u * (row + 1), p)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Pixel.motion(context)) pulse.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulse.cancel()
    }
}

/** A text cursor: a solid block that fades in and out. */
class BlinkBlock(context: Context, private val color: Int) : View(context) {
    private val p = Paint().apply { this.color = color }
    private val pulse = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 530
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    override fun onDraw(c: Canvas) = c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Pixel.motion(context)) pulse.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulse.cancel()
    }
}
