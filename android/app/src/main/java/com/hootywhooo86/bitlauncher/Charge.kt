package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The lock screen's charging animation. Drawn on a grid of big pixels and ticked at about eight
 * frames a second: chunky on purpose, and cheap, since it only runs while plugged in and the
 * lock screen is showing.
 */
class ChargeView(context: Context) : View(context) {
    companion object {
        /** (key, name). */
        val STYLES = listOf("battery" to "Battery", "hearts" to "Heart containers", "xp" to "XP bar", "train" to "Freight train")
        private const val GRID_W = 80 // virtual pixels across
        private const val GRID_H = 26

        private val HEART = arrayOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")
        private val L = arrayOf("#..", "#..", "#..", "#..", "###")
        private val V = arrayOf("#.#", "#.#", "#.#", "#.#", ".#.")
    }

    var style = "battery"
        set(v) { field = v; invalidate() }
    var accent = Pixel.ACCENTS[0]

    /** 0..100. */
    var level = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var charging = false
        set(v) { field = v; updateTicking() }

    private var tick = 0
    private val p = Paint()
    private val frame = object : Runnable {
        override fun run() {
            tick++
            invalidate()
            postDelayed(this, 125)
        }
    }

    private fun updateTicking() {
        removeCallbacks(frame)
        if (charging && isAttachedToWindow && windowVisibility == VISIBLE && Pixel.motion(context)) postDelayed(frame, 125)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTicking()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val u = (w / GRID_W).coerceAtLeast(1)
        setMeasuredDimension(w, u * GRID_H)
    }

    private var u = 1f
    private var ox = 0f

    private fun px(x: Float, y: Float, w: Float, h: Float, color: Int) {
        p.color = color
        canvas?.drawRect(ox + x * u, y * u, ox + (x + w) * u, (y + h) * u, p)
    }

    private fun sprite(map: Array<String>, x: Float, y: Float, color: Int) {
        for (r in map.indices) for (c in map[r].indices) if (map[r][c] == '#') px(x + c, y + r, 1f, 1f, color)
    }

    private var canvas: Canvas? = null

    override fun onDraw(c: Canvas) {
        canvas = c
        u = (width / GRID_W).coerceAtLeast(1).toFloat()
        ox = (width - u * GRID_W) / 2
        val blink = tick % 2 == 0
        when (style) {
            "hearts" -> hearts(blink)
            "xp" -> xp()
            "train" -> train()
            else -> battery(blink)
        }
        canvas = null
    }

    private fun battery(blink: Boolean) {
        val x = 8f
        val y = 2f
        px(x, y, 60f, 22f, Pixel.SHADOW)
        px(x + 1, y + 1, 58f, 20f, Pixel.INK)
        px(x + 2, y + 2, 56f, 18f, Pixel.BG)
        px(x + 60, y + 7, 4f, 8f, Pixel.INK) // the nub
        val full = level / 10
        // Low charge shows red: that red means something, so it isn't the accent.
        val cell = if (level < 20) 0xFFE5484D.toInt() else accent
        for (i in 0 until 10) {
            val on = i < full || (charging && i == full && blink && level < 100)
            if (on) px(x + 3 + i * 5.5f, y + 3, 4.5f, 16f, cell)
        }
    }

    private fun hearts(blink: Boolean) {
        val red = 0xFFE5484D.toInt()
        val empty = withAlpha(Pixel.INK, 60)
        val full = level / 10
        val part = level % 10
        for (i in 0 until 10) {
            val x = 1f + i * 8f
            val y = 10f
            sprite(HEART, x + 0.6f, y + 0.6f, Pixel.SHADOW)
            sprite(HEART, x, y, empty)
            when {
                i < full -> sprite(HEART, x, y, red)
                i == full && level < 100 -> {
                    // The heart being filled rises from the bottom, and pulses while charging.
                    val rows = ((part / 10f) * HEART.size).toInt().coerceAtLeast(if (charging && blink) 1 else 0)
                    val partial = HEART.mapIndexed { r, line -> if (r >= HEART.size - rows) line else ".".repeat(line.length) }.toTypedArray()
                    sprite(partial, x, y, red)
                }
            }
        }
    }

    private fun xp() {
        val lv = level / 10
        // "LV 6": small pixel letters, then blocky digits from the clock glyphs.
        sprite(L, 1f, 6f, Pixel.MUTED)
        sprite(V, 5f, 6f, Pixel.MUTED)
        val digits = if (lv >= 10) intArrayOf(1, 0) else intArrayOf(lv)
        var x = 10f
        for (d in digits) {
            sprite(PixelGlyphs.DIGITS[d], x, 4f, Pixel.INK)
            x += 6f
        }
        val bx = 24f
        val by = 6f
        px(bx, by, 54f, 9f, Pixel.SHADOW)
        px(bx + 1, by + 1, 52f, 7f, Pixel.PANEL)
        val fill = 52f * level / 100f
        px(bx + 1, by + 1, fill, 7f, accent)
        // A shine sweeping along the filled part.
        if (charging && fill > 4) {
            val s = (tick * 3) % (fill.toInt() + 6) - 3
            if (s >= 0 && s < fill - 2) px(bx + 1 + s, by + 2, 2f, 5f, withAlpha(0xFFFFFFFF.toInt(), 150))
        }
        px(bx + 1, by + 1, fill, 1f, withAlpha(0xFFFFFFFF.toInt(), 80))
        // Tick marks every 10%.
        for (i in 1 until 10) px(bx + 1 + i * 5.2f, by + 8, 0.5f, 2f, Pixel.MUTED)
    }

    /** A freight car: a boxcar body, door, roof line, frame and two wheel pairs. */
    private fun car(x: Float, base: Float, body: Int) {
        val dark = blend(body, 0xFF000000.toInt(), 0.35f)
        px(x, base - 6, 12f, 5f, Pixel.SHADOW)
        px(x + 0.5f, base - 5.5f, 11f, 4f, body)
        px(x + 0.5f, base - 5.5f, 11f, 1f, blend(body, 0xFFFFFFFF.toInt(), 0.2f)) // roof
        px(x + 4.5f, base - 4.5f, 3f, 3f, dark) // sliding door
        px(x, base - 1.5f, 12f, 0.5f, Pixel.SHADOW) // frame
        wheels(x + 1, base)
        wheels(x + 8, base)
        px(x + 12, base - 2f, 1f, 0.5f, Pixel.SHADOW) // coupler
    }

    private fun wheels(x: Float, base: Float) {
        px(x, base - 1f, 1f, 1f, Pixel.SHADOW)
        px(x + 2, base - 1f, 1f, 1f, Pixel.SHADOW)
    }

    private fun train() {
        val base = 22f
        // Rail and ties.
        px(0f, base, GRID_W.toFloat(), 1f, Pixel.MUTED)
        for (t in 0 until GRID_W step 4) px(t.toFloat(), base + 1, 2f, 1f, withAlpha(Pixel.MUTED, 140))
        val trainW = 3 * 13 + 20
        val x = (GRID_W - trainW) * level / 100f
        // Boxcars behind, the locomotive leading to the right.
        val carColors = intArrayOf(0xFF8E3A2C.toInt(), 0xFF3E5C76.toInt(), 0xFF6B6F2A.toInt())
        for (i in 0 until 3) car(x + i * 13, base, carColors[i])

        // A freight diesel: long hood, cab at the front with its window, frame, two trucks.
        val lx = x + 3 * 13
        val body = accent
        val dark = blend(body, 0xFF000000.toInt(), 0.4f)
        px(lx, base - 7, 20f, 6f, Pixel.SHADOW)
        px(lx + 0.5f, base - 6f, 13f, 4.5f, body) // long hood
        px(lx + 13.5f, base - 7.5f, 5.5f, 6f, body) // cab, taller
        px(lx + 19f, base - 5f, 1f, 3.5f, body) // short nose
        px(lx + 15f, base - 6.5f, 3f, 2f, Pixel.BG) // cab window
        px(lx + 0.5f, base - 6f, 13f, 0.5f, blend(body, 0xFFFFFFFF.toInt(), 0.25f)) // hood top
        for (v in 0 until 4) px(lx + 2 + v * 3f, base - 4.5f, 1.5f, 1.5f, dark) // hood vents
        px(lx + 3, base - 7.5f, 1.5f, 1.5f, Pixel.SHADOW) // exhaust stack
        px(lx, base - 1.5f, 20f, 0.5f, Pixel.SHADOW) // frame
        // Trucks, the wheels rocking as it rolls.
        val rock = if (charging && tick % 2 == 0) 0.5f else 0f
        wheels(lx + 1 + rock, base)
        wheels(lx + 14 + rock, base)
        // Exhaust puffs drifting back from the stack.
        if (charging && Pixel.motion(context)) for (k in 0 until 3) {
            val age = (tick + k * 3) % 9
            px(lx + 3 - age * 1.5f, base - 9 - age * 1.1f, 1.5f, 1.5f, withAlpha(Pixel.MUTED, (220 - age * 24).coerceAtLeast(0)))
        }
    }
}

/** "Full in 1h 12m", from the phone's own estimate; null when it doesn't have one. */
fun fullIn(millis: Long): String? {
    if (millis <= 0) return null
    val minutes = (millis / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "Full in ${minutes / 60}h ${minutes % 60}m" else "Full in ${minutes}m"
}
