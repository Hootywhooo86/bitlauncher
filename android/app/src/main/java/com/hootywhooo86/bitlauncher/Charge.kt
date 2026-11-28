package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The lock screen's charging animation, ticked at about eight frames a second and cheap, since it
 * only runs while plugged in and the lock screen is showing. Laid out on an 80×26 grid; with fine
 * pixel detail each grid pixel is drawn as 2×2 real ones and the art gets the extra detail
 * 128-style sprites have: lit edges that lean warm, shadows that lean cool, glints and bevels.
 */
class ChargeView(context: Context) : View(context) {
    companion object {
        /** (key, name). */
        val STYLES = listOf(
            "battery" to "Battery", "hearts" to "Heart containers", "xp" to "XP bar", "train" to "Freight train",
            "potion" to "Potion", "plant" to "Plant", "blocks" to "Block stack",
        )
        private const val GRID_W = 80 // virtual pixels across
        private const val GRID_H = 26

        private val HEART = arrayOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")
        private val L = arrayOf("#..", "#..", "#..", "#..", "###")
        private val V = arrayOf("#.#", "#.#", "#.#", "#.#", ".#.")
        private val LEAF_L = arrayOf("##.", ".##")
        private val LEAF_R = arrayOf(".##", "##.")
        private val FLOWER = arrayOf(".#.#.", "#####", ".###.", "#####", ".#.#.")
        /** A lightning bolt in fine pixels (half a grid pixel each). */
        private val BOLT = arrayOf(
            "....####", "...####.", "...###..", "..####..", "..###...", ".#######",
            "#######.", "...###..", "..###...", "..##....", ".##.....", ".#......",
        )

        /** Lit side of a colour: towards warm light. */
        fun hi(c: Int) = blend(c, 0xFFFFF1C9.toInt(), 0.38f)
        /** Shaded side: towards a cool violet, not black. */
        fun lo(c: Int) = blend(c, 0xFF2A1F4A.toInt(), 0.38f)
    }

    /** Fine pixel detail on (the default), or the original chunky grid. */
    var fine = IconCache.SIZE > 32
        set(v) { field = v; requestLayout(); invalidate() }
    private val k get() = if (fine) 2 else 1

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
        val u = (w / (GRID_W * k)).coerceAtLeast(1)
        setMeasuredDimension(w, u * k * GRID_H)
    }

    private var u = 1f
    private var ox = 0f

    private fun px(x: Float, y: Float, w: Float, h: Float, color: Int) {
        p.color = color
        canvas?.drawRect(ox + x * u, y * u, ox + (x + w) * u, (y + h) * u, p)
    }

    /** One fine pixel (half a grid pixel); nothing when detail is chunky. */
    private fun fpx(x: Float, y: Float, color: Int, w: Float = 1f, h: Float = 1f) {
        if (fine) px(x, y, w / 2, h / 2, color)
    }

    /** A filled block with a lit top edge and a shaded bottom edge, one fine pixel each. */
    private fun shaded(x: Float, y: Float, w: Float, h: Float, color: Int, bevel: Boolean = false) {
        px(x, y, w, h, color)
        if (!fine || w < 1 || h < 1) return
        fpx(x, y, hi(color), w * 2, 1f)
        fpx(x, y + h - 0.5f, lo(color), w * 2, 1f)
        if (bevel) {
            fpx(x, y, hi(color), 1f, h * 2 - 1)
            fpx(x + w - 0.5f, y + 0.5f, lo(color), 1f, h * 2 - 1)
        }
    }

    private fun fineSprite(map: Array<String>, x: Float, y: Float, color: Int) {
        if (!fine) return
        for (r in map.indices) for (c in map[r].indices) if (map[r][c] == '#') fpx(x + c / 2f, y + r / 2f, color)
    }

    private fun sprite(map: Array<String>, x: Float, y: Float, color: Int) {
        for (r in map.indices) for (c in map[r].indices) if (map[r][c] == '#') px(x + c, y + r, 1f, 1f, color)
    }

    private var canvas: Canvas? = null

    override fun onDraw(c: Canvas) {
        canvas = c
        // u is the size of one grid pixel in real pixels; with fine detail it's always even.
        u = ((width / (GRID_W * k)).coerceAtLeast(1) * k).toFloat()
        ox = ((width - u * GRID_W) / 2).toInt().toFloat()
        val blink = tick % 2 == 0
        when (style) {
            "hearts" -> hearts(blink)
            "xp" -> xp()
            "train" -> train()
            "potion" -> potion()
            "plant" -> plant()
            "blocks" -> blocks()
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
        if (fine) {
            // Round the case's corners and give it a lit top and a glint.
            for ((cx, cy) in listOf(x to y, x + 59.5f to y, x to y + 21.5f, x + 59.5f to y + 21.5f)) fpx(cx, cy, Pixel.BG)
            fpx(x + 1, y + 1, hi(Pixel.INK), 116f, 1f)
            fpx(x + 1, y + 20.5f, lo(Pixel.INK), 116f, 1f)
            fpx(x + 63.5f, y + 7, lo(Pixel.INK), 1f, 16f)
            fpx(x + 3, y + 2.5f, withAlpha(0xFFFFFFFF.toInt(), 70), 10f, 1f)
        }
        val full = level / 10
        // Low charge shows red: that red means something, so it isn't the accent.
        val cell = if (level < 20) 0xFFE5484D.toInt() else accent
        for (i in 0 until 10) {
            val on = i < full || (charging && i == full && blink && level < 100)
            if (on) shaded(x + 3 + i * 5.5f, y + 3, 4.5f, 16f, cell, bevel = true)
        }
        if (fine && charging) {
            // A bolt over the middle, outlined so it reads on any cell colour.
            val bx = x + 28f
            val by = y + 8f
            for ((dx, dy) in listOf(-0.5f to 0f, 0.5f to 0f, 0f to -0.5f, 0f to 0.5f)) fineSprite(BOLT, bx + dx, by + dy, Pixel.SHADOW)
            fineSprite(BOLT, bx, by, if (blink) 0xFFFFE27A.toInt() else 0xFFFFF4C2.toInt())
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
                i < full -> {
                    sprite(HEART, x, y, red)
                    // Lit lobe, glint and a cool shadow under the point.
                    fpx(x + 1, y + 0.5f, hi(red), 2f, 1f)
                    fpx(x + 1, y + 1, 0xFFFFFFFF.toInt())
                    fpx(x + 5.5f, y + 2, lo(red), 1f, 3f)
                    fpx(x + 3, y + 5.5f, lo(red), 2f, 1f)
                }
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
        shaded(bx + 1, by + 1, fill, 7f, accent)
        // A shine sweeping along the filled part.
        if (charging && fill > 4) {
            val s = (tick * 3) % (fill.toInt() + 6) - 3
            if (s >= 0 && s < fill - 2) px(bx + 1 + s, by + 2, 2f, 5f, withAlpha(0xFFFFFFFF.toInt(), 150))
        }
        px(bx + 1, by + 1, fill, 1f, withAlpha(0xFFFFFFFF.toInt(), 80))
        // Tick marks every 10%.
        for (i in 1 until 10) px(bx + 1 + i * 5.2f, by + 8, 0.5f, 2f, Pixel.MUTED)
    }

    /** A round flask filling up; bubbles rise through it while charging. */
    private fun potion() {
        val cx = 40f
        val top = 2f
        // Neck and cork.
        px(cx - 3, top, 6f, 2f, 0xFF9A6B3F.toInt())
        px(cx - 3, top + 2, 1f, 5f, Pixel.INK)
        px(cx + 2, top + 2, 1f, 5f, Pixel.INK)
        // The bulb: rows of a circle, 20 wide, 16 tall.
        val rows = 16
        val bulbTop = top + 7
        val fillRows = (rows * level / 100f).toInt()
        for (r in 0 until rows) {
            val dy = r - rows / 2f + 0.5f
            val half = kotlin.math.sqrt((rows / 2f) * (rows / 2f) - dy * dy) * 1.25f
            val y = bulbTop + r
            px(cx - half - 1, y, 1f, 1f, Pixel.INK)
            px(cx + half, y, 1f, 1f, Pixel.INK)
            val filled = r >= rows - fillRows
            val color = if (filled) (if (r == rows - fillRows) blend(accent, 0xFFFFFFFF.toInt(), 0.35f) else accent) else withAlpha(Pixel.INK, 25)
            px(cx - half, y, half * 2, 1f, color)
        }
        px(cx - 6, bulbTop + rows, 12f, 1f, Pixel.INK)
        // Shine on the glass.
        px(cx - 6, bulbTop + 3, 1f, 3f, withAlpha(0xFFFFFFFF.toInt(), 120))
        if (fine) {
            fpx(cx - 5, bulbTop + 2, withAlpha(0xFFFFFFFF.toInt(), 160), 2f, 1f)
            fpx(cx - 7, bulbTop + 6.5f, withAlpha(0xFFFFFFFF.toInt(), 90), 1f, 3f)
            if (fillRows > 1) fpx(cx - 8, bulbTop + rows - 2, lo(accent), 32f, 2f) // deeper colour at the bottom
            fpx(cx - 2.5f, top, hi(0xFF9A6B3F.toInt()), 10f, 1f) // the cork's lit top
        }
        if (charging && fillRows > 2) for (k in 0 until 3) {
            val age = (tick + k * 4) % 12
            val y = bulbTop + rows - 2 - age
            if (y > bulbTop + rows - fillRows) px(cx - 4 + k * 4 + (if (age % 4 < 2) 0 else 1), y, 1f, 1f, withAlpha(0xFFFFFFFF.toInt(), 170))
        }
        // The level beside it, in blocky digits.
        digits(level, 66f, 9f)
    }

    /** A sprout that grows with the charge: leaves every 20%, a flower when it's full. Sways while charging. */
    private fun plant() {
        val base = 23f
        val cx = 40f
        // Pot.
        px(cx - 6, base - 4, 12f, 1f, 0xFF9A5B3A.toInt())
        shaded(cx - 5, base - 3, 10f, 3f, 0xFF7E4A2F.toInt())
        px(cx - 6, base, 12f, 1f, Pixel.SHADOW)
        val green = 0xFF5FAF4E.toInt()
        val height = (2 + 16 * level / 100f).toInt()
        val sway = if (charging && Pixel.motion(context)) (if ((tick / 4) % 2 == 0) 0f else 1f) else 0f
        for (h in 0 until height) {
            val x = cx + (if (h > height / 2) sway else 0f)
            px(x, base - 5 - h, 1f, 1f, green)
        }
        val leaves = (level / 20).coerceAtMost(4)
        for (i in 0 until leaves) {
            val y = base - 7 - i * 3.5f
            val x = cx + (if (i * 3.5f + 2 > height / 2) sway else 0f)
            if (i % 2 == 0) sprite(LEAF_L, x - 3, y, green) else sprite(LEAF_R, x + 1, y, green)
            fpx(if (i % 2 == 0) x - 3 else x + 2, y, hi(green), 2f, 1f)
            fpx(if (i % 2 == 0) x - 1 else x + 1, y + 1.5f, lo(green), 2f, 1f)
        }
        val tipX = cx + sway
        val tipY = base - 5 - height
        if (level >= 100) {
            sprite(FLOWER, tipX - 2, tipY - 3, 0xFFE58FB0.toInt())
            px(tipX, tipY - 1, 1f, 1f, 0xFFFFE27A.toInt())
        } else {
            px(tipX, tipY, 1f, 1f, blend(green, 0xFFFFFFFF.toInt(), 0.4f)) // a bud
        }
        digits(level, 64f, 9f)
    }

    /** A well filling with blocks, one per 2%; while charging the next one drops in. */
    private fun blocks() {
        val wx = 14f
        val wy = 1f
        val cols = 10
        val rowsN = 5
        val cell = 4f
        px(wx - 1, wy, 1f, rowsN * cell + 1, Pixel.INK)
        px(wx + cols * cell, wy, 1f, rowsN * cell + 1, Pixel.INK)
        px(wx - 1, wy + rowsN * cell, cols * cell + 2, 1f, Pixel.INK)
        val shades = intArrayOf(accent, blend(accent, 0xFFFFFFFF.toInt(), 0.3f), blend(accent, 0xFF000000.toInt(), 0.25f))
        val count = (level * cols * rowsN / 100f).toInt()
        for (i in 0 until count) {
            val r = i / cols
            val c = i % cols
            val x = wx + c * cell
            val y = wy + (rowsN - 1 - r) * cell
            if (fine) shaded(x, y, cell - 0.5f, cell - 0.5f, shades[(r + c) % shades.size], bevel = true)
            else {
                px(x, y, cell - 0.5f, cell - 0.5f, shades[(r + c) % shades.size])
                px(x, y, cell - 0.5f, 0.5f, withAlpha(0xFFFFFFFF.toInt(), 90))
            }
        }
        if (charging && count < cols * rowsN) {
            // The next block falls to its spot.
            val r = count / cols
            val c = count % cols
            val landY = wy + (rowsN - 1 - r) * cell
            val y = minOf(wy + (tick % 8) * (landY - wy) / 7f, landY)
            px(wx + c * cell, y, cell - 0.5f, cell - 0.5f, withAlpha(accent, 170))
        }
        digits(level, 66f, 9f)
    }

    /** The percentage in clock digits, right of the art. */
    private fun digits(value: Int, @Suppress("UNUSED_PARAMETER") x: Float, y: Float) {
        // Right-aligned to the grid's edge, so 100 fits.
        var dx = GRID_W - value.toString().length * 6f
        value.toString().forEach { ch ->
            sprite(PixelGlyphs.DIGITS[ch - '0'], dx, y, Pixel.INK)
            dx += 6f
        }
    }

    private fun train() {
        val base = 22f
        // Rail and ties.
        px(0f, base, GRID_W.toFloat(), 1f, Pixel.MUTED)
        for (t in 0 until GRID_W step 4) px(t.toFloat(), base + 1, 2f, 1f, withAlpha(Pixel.MUTED, 140))
        val x = (GRID_W - TrainArt.length(3)) * level / 100f
        TrainArt.draw({ x0, y0, w, h, c -> px(x0, y0, w, h, c) }, x, base, accent, 3, if (charging) tick else 0, charging && Pixel.motion(context))
    }
}

/**
 * A freight train in pixels: boxcars behind, a diesel leading to the right. Drawn with whatever
 * rectangle function it's given, so the charging animation and the railway scene share it.
 * Units are grid pixels; [base] is the top of the rail.
 */
object TrainArt {
    private val CAR_COLORS = intArrayOf(0xFF8E3A2C.toInt(), 0xFF3E5C76.toInt(), 0xFF6B6F2A.toInt(), 0xFF5C4A6E.toInt())

    fun length(cars: Int) = cars * 13 + 20

    /** [shade] darkens colours for night; [headlight] shines ahead when it's dark. */
    fun draw(
        rect: (Float, Float, Float, Float, Int) -> Unit,
        x: Float,
        base: Float,
        accent: Int,
        cars: Int,
        tick: Int,
        puffing: Boolean,
        shade: (Int) -> Int = { it },
        headlight: Boolean = false,
    ) {
        val outline = Pixel.SHADOW
        fun wheels(wx: Float) {
            rect(wx, base - 1f, 1f, 1f, outline)
            rect(wx + 2, base - 1f, 1f, 1f, outline)
        }
        for (i in 0 until cars) {
            val cx = x + i * 13
            val body = shade(CAR_COLORS[i % CAR_COLORS.size])
            rect(cx, base - 6, 12f, 5f, outline)
            rect(cx + 0.5f, base - 5.5f, 11f, 4f, body)
            rect(cx + 0.5f, base - 5.5f, 11f, 1f, blend(body, 0xFFFFFFFF.toInt(), 0.2f))
            rect(cx + 4.5f, base - 4.5f, 3f, 3f, blend(body, 0xFF000000.toInt(), 0.35f))
            rect(cx, base - 1.5f, 12f, 0.5f, outline)
            wheels(cx + 1)
            wheels(cx + 8)
            rect(cx + 12, base - 2f, 1f, 0.5f, outline)
        }
        // The diesel: long hood, cab at the front with its window, two trucks.
        val lx = x + cars * 13
        val body = shade(accent)
        val dark = blend(body, 0xFF000000.toInt(), 0.4f)
        rect(lx, base - 7, 20f, 6f, outline)
        rect(lx + 0.5f, base - 6f, 13f, 4.5f, body)
        rect(lx + 13.5f, base - 7.5f, 5.5f, 6f, body)
        rect(lx + 19f, base - 5f, 1f, 3.5f, body)
        rect(lx + 15f, base - 6.5f, 3f, 2f, if (headlight) 0xFFFFE29A.toInt() else Pixel.BG)
        rect(lx + 0.5f, base - 6f, 13f, 0.5f, blend(body, 0xFFFFFFFF.toInt(), 0.25f))
        for (v in 0 until 4) rect(lx + 2 + v * 3f, base - 4.5f, 1.5f, 1.5f, dark)
        rect(lx + 3, base - 7.5f, 1.5f, 1.5f, outline)
        rect(lx, base - 1.5f, 20f, 0.5f, outline)
        val rock = if (tick % 2 == 0) 0.5f else 0f
        wheels(lx + 1 + rock)
        wheels(lx + 14 + rock)
        if (headlight) {
            rect(lx + 20f, base - 4.5f, 1f, 1f, 0xFFFFE29A.toInt())
            // A soft beam on the rails ahead.
            for (b in 0 until 6) rect(lx + 21f + b * 2, base - 4f + b * 0.5f, 2f, 1f, withAlpha(0xFFFFE29A.toInt(), 120 - b * 18))
        }
        if (puffing) for (k in 0 until 3) {
            val age = (tick + k * 3) % 9
            rect(lx + 3 - age * 1.5f, base - 9 - age * 1.1f, 1.5f, 1.5f, withAlpha(Pixel.MUTED, (220 - age * 24).coerceAtLeast(0)))
        }
    }
}

/** "Full in 1h 12m", from the phone's own estimate; null when it doesn't have one. */
fun fullIn(millis: Long): String? {
    if (millis <= 0) return null
    val minutes = (millis / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "Full in ${minutes / 60}h ${minutes % 60}m" else "Full in ${minutes}m"
}
