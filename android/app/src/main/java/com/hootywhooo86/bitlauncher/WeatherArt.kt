package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The weather, alive, on a grid of big pixels: fog bands roll across, clouds drift with the wind
 * and bob, rain falls and splashes, snow sways down, storms flash, the sun's rays pulse, and at
 * night the moon glows over twinkling stars. Wind streaks blow through when it's breezy.
 *
 * About eleven frames a second, only while it's on screen, animations are on, and the phone
 * isn't saving power. Otherwise it holds still on one frame.
 */
class WeatherArtView(context: Context) : View(context) {
    var sky = Sky.CLOUDY
        set(v) { field = v; reset() }
    var windKmh = 0.0
    var night = false
    var accent = Pixel.ACCENTS[0]

    private val gw = 96 // virtual pixels across
    private val gh = 22
    private var frame = 0
    private val p = Paint()
    private val rnd = Random(7)

    private val drops = Array(46) { floatArrayOf(rnd.nextFloat() * 96, rnd.nextFloat() * 22) }
    private val flakes = Array(34) { floatArrayOf(rnd.nextFloat() * 96, rnd.nextFloat() * 22, rnd.nextFloat() * 6.28f) }
    private val stars = Array(14) { intArrayOf(rnd.nextInt(96), rnd.nextInt(14), rnd.nextInt(20)) }
    private val clouds = arrayOf(floatArrayOf(6f, 3f, 1f), floatArrayOf(44f, 7f, 0.8f), floatArrayOf(74f, 2f, 0.6f))

    private val tick = object : Runnable {
        override fun run() {
            frame++
            invalidate()
            postDelayed(this, 90)
        }
    }

    /** Move time on by [n] frames (for previews and tests). */
    internal fun advance(n: Int) {
        frame += n
        invalidate()
    }

    private fun reset() {
        frame = 0
        invalidate()
    }

    private fun moving(): Boolean {
        val saving = context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        return isAttachedToWindow && windowVisibility == VISIBLE && Pixel.motion(context) && !saving
    }

    private fun update() {
        removeCallbacks(tick)
        if (moving()) postDelayed(tick, 90)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    // ---------------------------------------------------------------- drawing

    private var u = 1f
    private var ox = 0f
    private var oy = 0f
    private var canvas: Canvas? = null

    private fun px(x: Float, y: Float, w: Float, h: Float, c: Int) {
        val cv = canvas ?: return
        p.color = c
        // Snap to whole virtual pixels so it always reads as pixel art.
        val sx = floor(x)
        val sy = floor(y)
        cv.drawRect(ox + sx * u, oy + sy * u, ox + (sx + w) * u, oy + (sy + h) * u, p)
    }

    /** How hard the wind pushes things along, in virtual pixels per frame. */
    private val push get() = (0.25f + windKmh.toFloat() / 25f).coerceIn(0.25f, 2.5f)

    override fun onDraw(c: Canvas) {
        canvas = c
        u = minOf(width / gw.toFloat(), height / gh.toFloat()).let { floor(it).coerceAtLeast(1f) }
        ox = (width - u * gw) / 2
        oy = (height - u * gh) / 2
        when (sky) {
            Sky.CLEAR -> if (night) moon() else sun()
            Sky.FOG -> fog()
            Sky.RAIN -> { cloudsDrift(dark = true); rain() }
            Sky.SNOW -> { cloudsDrift(dark = false); snow() }
            Sky.STORM -> storm()
            else -> { if (night) moon(faint = true) else sun(small = true); cloudsDrift(dark = false) }
        }
        if (windKmh >= 15 && sky != Sky.FOG) wind()
        canvas = null
    }

    private fun sun(small: Boolean = false) {
        val cx = if (small) 14f else 20f
        val cy = 10f
        val r = if (small) 4 else 5
        val yellow = 0xFFFFD34D.toInt()
        // Rays pulse: long and short in turn.
        val long = (frame / 5) % 2 == 0
        val l = if (long) r + 4 else r + 2
        for (k in 0 until 8) {
            val (dx, dy) = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)[k]
            val reach = if (k % 2 == 0) l else l - 1
            for (s in r + 1..reach) px(cx + dx * s, cy + dy * s, 1f, 1f, withAlpha(yellow, 200))
        }
        for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r) px(cx + x, cy + y, 1f, 1f, yellow)
        px(cx - r / 2f, cy - r / 2f, 2f, 1f, 0xFFFFF1B0.toInt())
    }

    private fun moon(faint: Boolean = false) {
        // Twinkling stars, then a crescent moon with a soft glow.
        for (s in stars) {
            val on = ((frame + s[2]) / 6) % 4 != 0
            if (on) px(s[0].toFloat(), s[1].toFloat(), 1f, 1f, withAlpha(Pixel.INK, if (faint) 90 else 200))
        }
        val cx = 22f
        val cy = 10f
        val glow = 0.5f + 0.5f * sin(frame / 8f)
        for (y in -8..8) for (x in -8..8) if (x * x + y * y in 37..64) {
            if ((x + y + frame / 4) % 3 == 0) px(cx + x, cy + y, 1f, 1f, withAlpha(Pixel.INK, (30 * glow).toInt()))
        }
        for (y in -5..5) for (x in -5..5) {
            val inMoon = x * x + y * y <= 25
            val bite = (x - 2) * (x - 2) + (y + 1) * (y + 1) <= 18
            if (inMoon && !bite) px(cx + x, cy + y, 1f, 1f, 0xFFE8E8F2.toInt())
        }
    }

    /** A blocky cloud, [w] wide, its top-left at x, y. */
    private fun cloud(x: Float, y: Float, w: Int, color: Int, shade: Int) {
        val h = (w / 3).coerceAtLeast(3)
        px(x + w * 0.2f, y, w * 0.35f, 1f, color)
        px(x + w * 0.5f, y - 1, w * 0.3f, 1f, color)
        px(x + w * 0.1f, y + 1, w * 0.8f, h - 2f, color)
        px(x, y + 2, w.toFloat(), h - 2f, color)
        px(x, y + h - 1f, w.toFloat(), 1f, shade)
    }

    private fun cloudsDrift(dark: Boolean) {
        val color = if (dark) blend(Pixel.MUTED, Pixel.PANEL, 0.25f) else Pixel.INK
        val shade = if (dark) blend(color, 0xFF000000.toInt(), 0.3f) else Pixel.MUTED
        for ((i, cl) in clouds.withIndex()) {
            val w = (22 * cl[2]).toInt()
            val x = ((cl[0] + frame * push * (0.35f + i * 0.15f)) % (gw + w + 4)) - w - 2
            val bob = if (((frame / 7) + i) % 2 == 0) 0f else 1f
            cloud(x, cl[1] + bob, w, color, shade)
        }
    }

    private fun fog() {
        // Bands of mist at different heights, each rolling at its own speed, thinning and thickening.
        for (band in 0 until 6) {
            val y = 2f + band * 3.4f
            val speed = push * (0.3f + (band % 3) * 0.25f) * if (band % 2 == 0) 1 else -1
            val alpha = (90 + 60 * sin(frame / 10f + band)).toInt().coerceIn(40, 170)
            val c = withAlpha(Pixel.INK, alpha)
            var x = ((frame * speed) % 24 + 24) % 24 - 24
            while (x < gw) {
                px(x, y, 14f, 1f, c)
                px(x + 3, y + 1, 8f, 1f, withAlpha(Pixel.INK, alpha / 2))
                x += 24
            }
        }
    }

    private fun rain() {
        val blue = 0xFF60A5FA.toInt()
        val slant = push * 0.6f
        for (d in drops) {
            d[1] += 1.6f
            d[0] += slant
            if (d[1] >= gh - 1) {
                // A splash on the ground, then back to the top.
                px(d[0] - 1, gh - 1f, 1f, 1f, withAlpha(blue, 160))
                px(d[0] + 1, gh - 1f, 1f, 1f, withAlpha(blue, 160))
                d[1] = 6f + rnd.nextFloat() * 3
                d[0] = rnd.nextFloat() * gw
            }
            if (d[0] > gw) d[0] -= gw
            if (d[1] > 7) px(d[0], d[1], 1f, 2f, blue)
        }
    }

    private fun snow() {
        for (f in flakes) {
            f[1] += 0.45f
            f[0] += push * 0.3f + sin(frame / 6f + f[2]) * 0.35f
            if (f[1] >= gh) {
                f[1] = 5f
                f[0] = rnd.nextFloat() * gw
            }
            if (f[0] > gw) f[0] -= gw
            if (f[1] > 6) px(f[0], f[1], 1f, 1f, Pixel.INK)
        }
    }

    private fun storm() {
        // Lightning: every few seconds a two-frame flash and a bolt.
        val cycle = frame % 38
        if (cycle == 0 || cycle == 2) {
            px(0f, 0f, gw.toFloat(), gh.toFloat(), withAlpha(Pixel.INK, 40))
            val bx = 30f + (frame / 38 % 4) * 12
            val bolt = listOf(0 to 6, 1 to 7, 0 to 8, -1 to 9, 0 to 10, 1 to 11, 2 to 12, 1 to 13, 0 to 14)
            for ((dx, y) in bolt) px(bx + dx, y.toFloat(), 1f, 1f, 0xFFFFE27A.toInt())
        }
        cloudsDrift(dark = true)
        rain()
    }

    private fun wind() {
        // Streaks that blow across, faster in stronger wind.
        val c = withAlpha(Pixel.INK, 110)
        for (k in 0 until 3) {
            val y = 5f + k * 6
            val len = 8f + k * 3
            val x = ((frame * push * 3 + k * 37) % (gw + 30)) - 20
            px(x, y, len, 1f, c)
            px(x + len, y - 1, 3f, 1f, c) // a little curl at the end
        }
    }
}
