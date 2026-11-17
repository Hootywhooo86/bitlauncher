package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The weather as a little pixel diorama: a sky shaded for the weather and time of day, hills, a
 * cabin with a smoking chimney and a tree, and the weather happening to them. Clouds drift in
 * three layers, the tree sways and the smoke leans with the wind, birds cross on fair days, rain
 * ripples the puddles, snow settles on the roof, fog swallows the cabin, lightning lights the sky.
 *
 * About eleven frames a second, only while it's on screen, animations are on, and the phone
 * isn't saving power. Otherwise it holds still on one frame.
 */
class WeatherArtView(context: Context) : View(context) {
    var sky = Sky.CLOUDY
        set(v) { field = v; invalidate() }
    var windKmh = 0.0
    var night = false
    var accent = Pixel.ACCENTS[0]

    /** True for overcast, not just a few clouds: the sky fills up. */
    var overcast = false

    private val gw = 128 // virtual pixels across: the 128 style
    private val gh = 36
    private val ground = 27 // first row of ground
    private var frame = 0
    private val p = Paint()
    private val rnd = Random(7)

    private val drops = Array(60) { floatArrayOf(rnd.nextFloat() * 128, rnd.nextFloat() * 27) }
    private val flakes = Array(46) { floatArrayOf(rnd.nextFloat() * 128, rnd.nextFloat() * 27, rnd.nextFloat() * 6.28f) }
    private val stars = Array(22) { intArrayOf(rnd.nextInt(128), rnd.nextInt(18), rnd.nextInt(30)) }
    private val ripples = IntArray(7) { -1 } // x of a puddle ripple, or -1
    private var rippleAt = IntArray(7)

    // (x, y, width, layer). Layer 0 far and slow, 2 near and fast.
    private val cloudsFew = arrayOf(floatArrayOf(6f, 3f, 24f, 0f), floatArrayOf(58f, 7f, 20f, 1f), floatArrayOf(98f, 2f, 16f, 2f))
    private val cloudsMany = arrayOf(
        floatArrayOf(0f, 1f, 32f, 0f), floatArrayOf(40f, 0f, 28f, 0f), floatArrayOf(84f, 3f, 32f, 0f),
        floatArrayOf(20f, 7f, 26f, 1f), floatArrayOf(68f, 9f, 24f, 1f), floatArrayOf(110f, 6f, 24f, 1f),
        floatArrayOf(44f, 13f, 20f, 2f),
    )

    private val tick = object : Runnable {
        override fun run() {
            frame++
            invalidate()
            postDelayed(this, 90)
        }
    }

    /** Move time on by [n] frames (for previews and tests). */
    internal fun advance(n: Int) {
        repeat(n) { frame++; step() }
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

    // ---------------------------------------------------------------- drawing helpers

    private var u = 1f
    private var ox = 0f
    private var oy = 0f
    private var canvas: Canvas? = null

    private fun px(x: Float, y: Float, w: Float, h: Float, c: Int) {
        val cv = canvas ?: return
        p.color = c
        val sx = floor(x)
        val sy = floor(y)
        val l = (ox + sx * u).coerceAtLeast(ox)
        val r = (ox + (sx + w) * u).coerceAtMost(ox + gw * u)
        if (r <= l) return
        cv.drawRect(l, oy + sy * u, r, oy + (sy + h) * u, p)
    }

    /** A one-pixel checker of [c] over a rect: pixel-art shading. */
    private fun dither(x: Int, y: Int, w: Int, h: Int, c: Int, phase: Int = 0) {
        for (yy in y until y + h) for (xx in x until x + w) if ((xx + yy + phase) % 2 == 0) px(xx.toFloat(), yy.toFloat(), 1f, 1f, c)
    }

    /** Wind push in virtual pixels per frame; even a still day drifts a little. */
    private val push get() = (0.25f + windKmh.toFloat() / 20f).coerceIn(0.25f, 3.2f)

    private val dark get() = sky == Sky.RAIN || sky == Sky.STORM

    // ---------------------------------------------------------------- the scene

    private fun step() {
        if (sky == Sky.RAIN || sky == Sky.STORM) {
            for (d in drops) {
                d[1] += 2.6f
                d[0] += push * 0.5f
                if (d[1] >= ground) {
                    val slot = ripples.indexOfFirst { it < 0 }
                    if (slot >= 0 && rnd.nextInt(3) == 0) { ripples[slot] = d[0].toInt() % gw; rippleAt[slot] = frame }
                    d[1] = rnd.nextFloat() * 6
                    d[0] = rnd.nextFloat() * gw
                }
                if (d[0] > gw) d[0] -= gw
            }
            for (i in ripples.indices) if (ripples[i] >= 0 && frame - rippleAt[i] > 5) ripples[i] = -1
        }
        if (sky == Sky.SNOW) for (f in flakes) {
            f[1] += 0.6f
            f[0] += push * 0.4f + sin(frame / 6f + f[2]) * 0.4f
            if (f[1] >= ground + 2) { f[1] = 0f; f[0] = rnd.nextFloat() * gw }
            if (f[0] > gw) f[0] -= gw
        }
    }

    override fun onDraw(c: Canvas) {
        canvas = c
        u = floor(minOf(width / gw.toFloat(), height / gh.toFloat())).coerceAtLeast(1f)
        ox = (width - u * gw) / 2
        oy = (height - u * gh) / 2
        if (moving()) step()
        skyBands()
        if (night) starsAndMoon() else if (sky == Sky.CLEAR || (sky == Sky.CLOUDY && !overcast)) sun()
        val many = overcast || dark
        clouds(if (many) cloudsMany else cloudsFew, many)
        if (!night && (sky == Sky.CLEAR || (sky == Sky.CLOUDY && !overcast))) birds()
        hills()
        cabin()
        tree()
        when (sky) {
            Sky.RAIN -> { rain(); ripples() }
            Sky.STORM -> { rain(); ripples(); lightning() }
            Sky.SNOW -> snow()
            Sky.FOG -> fog()
            else -> {}
        }
        if (windKmh >= 15 && sky != Sky.FOG) wind()
        corners()
        canvas = null
    }

    /** The sky in bands with dithered seams, tinted for the weather and the hour. */
    private fun skyBands() {
        val (top, mid, low) = when {
            sky == Sky.STORM -> Triple(0xFF1E2230.toInt(), 0xFF2A3040.toInt(), 0xFF3A4152.toInt())
            night -> Triple(0xFF0B1030.toInt(), 0xFF141A44.toInt(), 0xFF22285A.toInt())
            dark || sky == Sky.FOG -> Triple(0xFF4A5363.toInt(), 0xFF5D6676.toInt(), 0xFF737C8B.toInt())
            overcast -> Triple(0xFF5E6A7E.toInt(), 0xFF717D90.toInt(), 0xFF8893A4.toInt())
            sky == Sky.SNOW -> Triple(0xFF7D8BA6.toInt(), 0xFF97A4BC.toInt(), 0xFFB3BDD0.toInt())
            else -> Triple(0xFF3C6FC4.toInt(), 0xFF5A8BD8.toInt(), 0xFF86ADE6.toInt())
        }
        px(0f, 0f, gw.toFloat(), 9f, top)
        px(0f, 9f, gw.toFloat(), 8f, mid)
        px(0f, 17f, gw.toFloat(), ground - 17f, low)
        // Two rows of dither at each seam: a soft, pixel-art gradient.
        dither(0, 7, gw, 2, mid)
        dither(0, 8, gw, 1, mid, 1)
        dither(0, 15, gw, 2, low)
        dither(0, 16, gw, 1, low, 1)
    }

    private fun circle(cx: Int, cy: Int, r: Int, c: Int) {
        for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r + r) px((cx + x).toFloat(), (cy + y).toFloat(), 1f, 1f, c)
    }

    private fun sun() {
        val cx = 18
        val cy = 9
        val yellow = 0xFFFFD34D.toInt()
        val deep = 0xFFF2A93B.toInt()
        val pale = 0xFFFFF1B0.toInt()
        // Rays turn: straight ones and diagonal ones take turns, each two pixels long.
        val straight = (frame / 6) % 2 == 0
        val dirs = if (straight) listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0) else listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
        for ((dx, dy) in dirs) for (s in 7..9) px((cx + dx * s).toFloat(), (cy + dy * s).toFloat(), 1f, 1f, if (s == 9) deep else yellow)
        circle(cx, cy, 5, deep)
        circle(cx, cy, 4, yellow)
        px(cx - 2f, cy - 3f, 2f, 1f, pale)
        px(cx - 3f, cy - 2f, 1f, 2f, pale)
    }

    private fun starsAndMoon() {
        for (s in stars) {
            val phase = ((frame + s[2]) / 5) % 6
            if (phase != 0) px(s[0].toFloat(), s[1].toFloat(), 1f, 1f, 0xFFE8E8F2.toInt())
            if (phase == 3) { // a twinkle: a little cross
                px(s[0] - 1f, s[1].toFloat(), 1f, 1f, withAlpha(0xFFE8E8F2.toInt(), 120))
                px(s[0] + 1f, s[1].toFloat(), 1f, 1f, withAlpha(0xFFE8E8F2.toInt(), 120))
            }
        }
        val cx = 100
        val cy = 9
        val moon = 0xFFE8E8F2.toInt()
        val shade = 0xFFB8BBD0.toInt()
        // A soft glow that breathes, then the crescent with a couple of craters.
        if ((frame / 10) % 2 == 0) for (y in -8..8) for (x in -8..8) if (x * x + y * y in 50..64 && (x + y) % 2 == 0) px((cx + x).toFloat(), (cy + y).toFloat(), 1f, 1f, withAlpha(moon, 50))
        for (y in -6..6) for (x in -6..6) {
            val inMoon = x * x + y * y <= 36 + 6
            val bite = (x - 3) * (x - 3) + (y + 2) * (y + 2) <= 26
            if (inMoon && !bite) px((cx + x).toFloat(), (cy + y).toFloat(), 1f, 1f, if (x < -3 || y > 3) shade else moon)
        }
        px(cx - 3f, cy + 1f, 1f, 1f, shade)
        px(cx - 1f, cy + 3f, 1f, 1f, shade)
    }

    /** A cloud: puffy top, light face, dithered shaded belly, darker underside. */
    private fun cloud(x: Float, y: Float, w: Int, light: Int, shade: Int, highlight: Int) {
        val h = (w / 3).coerceAtLeast(5)
        // Three puffs along the top.
        val puffs = listOf(0.28f to 0.18f, 0.55f to 0.24f, 0.78f to 0.14f)
        for ((at, r) in puffs) {
            val cx = x + w * at
            val pr = (w * r).toInt().coerceAtLeast(2)
            for (yy in -pr..0) for (xx in -pr..pr) if (xx * xx + yy * yy <= pr * pr) px(cx + xx, y + pr + yy, 1f, 1f, light)
        }
        val body = y + (w * 0.18f).toInt().coerceAtLeast(2)
        px(x + 1, body, w - 2f, h - 1f, light)
        px(x, body + 1, w.toFloat(), h - 3f, light)
        px(x + w * 0.3f, y + 1, w * 0.25f, 1f, highlight)
        for (yy in (body + h - 3).toInt() until (body + h - 1).toInt()) for (xx in x.toInt() until (x + w).toInt()) {
            if ((xx + yy) % 2 == 0) px(xx.toFloat(), yy.toFloat(), 1f, 1f, shade)
        }
        px(x + 1, body + h - 2f, w - 2f, 1f, shade)
    }

    private fun clouds(list: Array<FloatArray>, many: Boolean) {
        val light = when {
            sky == Sky.STORM -> 0xFF4B5263.toInt()
            dark -> 0xFF8A93A3.toInt()
            night -> 0xFF5A6280.toInt()
            many -> 0xFFC9CFD9.toInt()
            else -> 0xFFF4F6FA.toInt()
        }
        val shade = blend(light, 0xFF000000.toInt(), 0.25f)
        val highlight = blend(light, 0xFFFFFFFF.toInt(), 0.35f)
        for (cl in list) {
            val w = cl[2].toInt()
            val speed = push * (0.35f + cl[3] * 0.35f)
            val span = gw + w + 8
            val x = ((cl[0] + frame * speed) % span) - w - 4
            val bob = if (((frame / 9) + cl[3].toInt()) % 2 == 0) 0f else 1f
            val tone = blend(light, 0xFF000000.toInt(), 0.1f * (2 - cl[3]))
            cloud(x, cl[1] + bob, w, tone, shade, highlight)
        }
    }

    /** Two birds flapping across now and then. */
    private fun birds() {
        val cycle = frame % 180
        if (cycle > 110) return
        val x = cycle * 1.3f - 10
        val up = (frame / 3) % 2 == 0
        val c = 0xFF2A2F3D.toInt()
        for ((bx, by) in listOf(x to 11f, x - 8 to 14f)) {
            px(bx, by, 1f, 1f, c)
            if (up) {
                px(bx - 1, by - 1, 1f, 1f, c); px(bx - 2, by - 2, 1f, 1f, c)
                px(bx + 1, by - 1, 1f, 1f, c); px(bx + 2, by - 2, 1f, 1f, c)
            } else {
                px(bx - 1, by, 1f, 1f, c); px(bx - 2, by + 1, 1f, 1f, c)
                px(bx + 1, by, 1f, 1f, c); px(bx + 2, by + 1, 1f, 1f, c)
            }
        }
    }

    private fun hills() {
        val snowy = sky == Sky.SNOW
        val far = when {
            snowy -> 0xFFDCE3EE.toInt()
            night -> 0xFF1F3326.toInt()
            else -> 0xFF3F7A4A.toInt()
        }
        val near = when {
            snowy -> 0xFFF2F5FA.toInt()
            night -> 0xFF172A1E.toInt()
            else -> 0xFF2F6239.toInt()
        }
        val farDark = blend(far, 0xFF000000.toInt(), 0.15f)
        // A far ridge with a shaded lower edge, then the near ground with dithered grass.
        for (x in 0 until gw) {
            val hgt = (4 + 3f * sin(x / 14f) + sin(x / 6.2f) * 1.2f).toInt().coerceAtLeast(2)
            px(x.toFloat(), ground - hgt.toFloat(), 1f, hgt.toFloat(), far)
            if (x % 2 == 0) px(x.toFloat(), ground - 2f, 1f, 1f, farDark)
        }
        px(0f, ground.toFloat(), gw.toFloat(), (gh - ground).toFloat(), near)
        dither(0, ground, gw, 2, blend(near, 0xFFFFFFFF.toInt(), 0.14f))
        dither(0, gh - 3, gw, 3, blend(near, 0xFF000000.toInt(), 0.18f))
        // Grass tufts that lean with the wind.
        if (!snowy) for (x in 4 until gw step 9) {
            val lean = if ((frame / 4 + x) % 3 == 0) 1f else 0f
            val blade = blend(near, 0xFFFFFFFF.toInt(), 0.3f)
            px(x + lean, ground - 2f, 1f, 1f, blade)
            px(x.toFloat(), ground - 1f, 1f, 1f, blade)
            px(x + 2f, ground - 1f, 1f, 1f, blade)
        }
    }

    private fun cabin() {
        val x = 78f
        val y = ground - 9f
        val wall = if (night) 0xFF5A3E2B.toInt() else 0xFF8A5A3A.toInt()
        val plank = blend(wall, 0xFF000000.toInt(), 0.2f)
        val snowy = sky == Sky.SNOW
        val roof = if (snowy) 0xFFF4F6FA.toInt() else 0xFF7A2E2E.toInt()
        val roofDark = blend(roof, 0xFF000000.toInt(), 0.25f)
        // Walls with planks.
        px(x, y, 16f, 9f, wall)
        for (k in 1 until 9 step 2) px(x, y + k, 16f, 1f, plank)
        // A peaked roof, eaves overhanging, shaded underside.
        for (k in 0 until 6) px(x - 2 + k, y - 1 - k, 20f - k * 2, 1f, roof)
        px(x - 2, y - 1, 20f, 1f, roofDark)
        // Window with a cross; lit at night or in gloom.
        val lit = night || dark || sky == Sky.FOG
        val glass = if (lit) 0xFFFFD86B.toInt() else 0xFF9CC7E8.toInt()
        px(x + 2, y + 2, 5f, 4f, glass)
        px(x + 4, y + 2, 1f, 4f, plank)
        px(x + 2, y + 3, 5f, 1f, plank)
        if (lit && (frame / 12) % 5 == 0) px(x + 2, y + 2, 2f, 1f, 0xFFFFF1B0.toInt()) // a flicker
        // Door with a knob.
        px(x + 10, y + 3, 4f, 6f, blend(wall, 0xFF000000.toInt(), 0.4f))
        px(x + 13, y + 6, 1f, 1f, 0xFFD9B44A.toInt())
        // Chimney, and smoke drifting downwind and fading.
        px(x + 12, y - 8, 2f, 4f, 0xFF5B5B66.toInt())
        px(x + 12, y - 8, 2f, 1f, 0xFF44444E.toInt())
        for (k in 0 until 6) {
            val age = (frame / 2 + k * 4) % 24
            val sx = x + 12 + age * (0.2f + push * 0.6f) + sin(age / 3f) * 0.8f
            val sy = y - 10 - age * 0.55f
            val a = (210 - age * 9).coerceAtLeast(0)
            if (sy > 0) {
                px(sx, sy, 1f, 1f, withAlpha(0xFFDADDE6.toInt(), a))
                if (age > 8) px(sx + 1, sy, 1f, 1f, withAlpha(0xFFDADDE6.toInt(), a / 2))
            }
        }
    }

    private fun tree() {
        val x = 106f
        val base = ground.toFloat()
        val trunk = 0xFF5A3A24.toInt()
        val leaves = when {
            sky == Sky.SNOW -> 0xFF2E6B3E.toInt()
            night -> 0xFF1F4A2C.toInt()
            else -> 0xFF2E7D3E.toInt()
        }
        val dark = blend(leaves, 0xFF000000.toInt(), 0.3f)
        val light = blend(leaves, 0xFFFFFFFF.toInt(), 0.2f)
        px(x, base - 4, 2f, 4f, trunk)
        // A pine of three tiers; each sways a little more than the one below.
        val reach = if (windKmh >= 15) 2 else 1
        val beat = when ((frame / 4) % 4) { 1 -> 1; 3 -> -1; else -> 0 }
        for (tier in 0 until 3) {
            val w = 12 - tier * 3
            val top = base - 7 - tier * 5
            val sway = beat * (if (tier == 2) reach else if (tier == 1) 1 else 0)
            for (row in 0 until 5) {
                val rw = (w * (row + 1) / 5).coerceAtLeast(1)
                val rx = x + 1 - rw / 2f + sway
                px(rx, top + row, rw.toFloat(), 1f, leaves)
                px(rx, top + row, 1f, 1f, light)
                px(rx + rw - 1, top + row, 1f, 1f, dark)
            }
            if (sky == Sky.SNOW) px(x + 1 - w / 4f + sway, top + 1, (w / 2f).coerceAtLeast(1f), 1f, 0xFFF4F6FA.toInt())
        }
    }

    private fun rain() {
        val blue = if (sky == Sky.STORM) 0xFF8FB8F0.toInt() else 0xFF7EB2F2.toInt()
        for (d in drops) {
            px(d[0], d[1], 1f, 3f, blue)
            px(d[0], d[1], 1f, 1f, withAlpha(0xFFFFFFFF.toInt(), 120))
        }
    }

    private fun ripples() {
        val c = withAlpha(0xFFBFD6F5.toInt(), 200)
        for (i in ripples.indices) {
            val x = ripples[i]
            if (x < 0) continue
            val r = (frame - rippleAt[i]).coerceIn(0, 4)
            px(x - r.toFloat(), ground + 2f, 1f, 1f, c)
            px(x + r.toFloat(), ground + 2f, 1f, 1f, c)
            if (r < 2) px(x.toFloat(), ground + 1f, 1f, 1f, c)
        }
    }

    private fun snow() {
        for ((i, f) in flakes.withIndex()) {
            px(f[0], f[1], 1f, 1f, 0xFFFFFFFF.toInt())
            if (i % 4 == 0) { // a few bigger flakes
                px(f[0] + 1, f[1], 1f, 1f, withAlpha(0xFFFFFFFF.toInt(), 150))
                px(f[0], f[1] + 1, 1f, 1f, withAlpha(0xFFFFFFFF.toInt(), 150))
            }
        }
    }

    private fun lightning() {
        val cycle = frame % 40
        if (cycle != 0 && cycle != 2) return
        px(0f, 0f, gw.toFloat(), ground.toFloat(), withAlpha(0xFFFFFFFF.toInt(), 70))
        val bx = 34f + (frame / 40 % 3) * 16
        var x = bx
        for (y in 4 until 22) {
            x += when (y % 5) { 0 -> 1f; 2 -> -1f; 3 -> 1f; else -> 0f }
            px(x, y.toFloat(), 1f, 1f, 0xFFFFE27A.toInt())
            if (y % 5 == 0) px(x + 1, y.toFloat(), 1f, 1f, withAlpha(0xFFFFE27A.toInt(), 150))
        }
    }

    private fun fog() {
        // Bands of mist roll across in both directions, thickest low down where the cabin is.
        for (band in 0 until 9) {
            val y = 6f + band * 2.6f
            val dir = if (band % 2 == 0) 1 else -1
            val speed = (0.4f + push) * (0.6f + (band % 3) * 0.3f) * dir
            val alpha = ((110 + 50 * sin(frame / 9f + band)).toInt() + band * 6).coerceIn(60, 220)
            val c = withAlpha(0xFFDDE1EA.toInt(), alpha)
            var x = ((frame * speed) % 36 + 36) % 36 - 36
            while (x < gw) {
                px(x, y, 22f, 1f, c)
                px(x + 5, y + 1, 13f, 1f, withAlpha(0xFFDDE1EA.toInt(), alpha / 2))
                x += 36
            }
        }
    }

    private fun wind() {
        val c = withAlpha(0xFFFFFFFF.toInt(), 120)
        for (k in 0 until 3) {
            val y = 6f + k * 7
            val len = 12f + k * 4
            val x = ((frame * push * 4f + k * 43) % (gw + 30)) - 20
            px(x, y, len, 1f, c)
            px(x + len, y - 1, 2f, 1f, c)
            px(x + len + 2, y - 2, 1f, 1f, c)
        }
    }

    /** Notched corners keep the panel's pixel look. */
    private fun corners() {
        val bg = Pixel.PANEL
        for ((x, y) in listOf(0 to 0, gw - 1 to 0, 0 to gh - 1, gw - 1 to gh - 1)) px(x.toFloat(), y.toFloat(), 1f, 1f, bg)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep the diorama's shape: as wide as it's given, and tall in proportion.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val unit = floor(w / gw.toFloat()).coerceAtLeast(1f)
        setMeasuredDimension(w, (unit * gh).toInt())
    }
}

/**
 * A small two-tone weather sprite for the hourly strip, with its own two-frame loop: rain
 * falls, snow drifts, rays twinkle, clouds shift, storms flash.
 */
class MiniWeatherView(context: Context, private val code: Int, private val night: Boolean) : View(context) {
    private val p = Paint()
    private var frame = 0
    private val tick = object : Runnable {
        override fun run() {
            frame++
            invalidate()
            postDelayed(this, 420)
        }
    }

    private fun moving() = isAttachedToWindow && windowVisibility == VISIBLE && Pixel.motion(context)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        if (moving()) postDelayed(tick, 420 + (code % 5) * 60L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    // 10 x 8. '#' light, '+' shade, 'y' sun, 'b' rain, 'w' snow, 'z' bolt, 'm' moon.
    private fun rows(): Array<String> {
        val a = frame % 2 == 0
        return when (WeatherCodes.sky(code)) {
            Sky.CLEAR -> if (night) arrayOf("..........", "....mm....", "...mm.....", "...m......", "...mm.....", "....mm....", "..........", "..........")
                else if (a) arrayOf("....y.....", ".y.....y..", "...yyy....", "..yyyyy...", "..yyyyy..y", "...yyy....", ".y.....y..", "....y.....")
                else arrayOf("..........", "..y...y...", "...yyy....", ".yyyyyyy..", "..yyyyy...", "...yyy....", "..y...y...", "..........")
            Sky.RAIN -> arrayOf(if (a) "...###...." else "....###...", "..#####...", ".#######..", ".+++++++..", if (a) "..b..b..b." else ".b..b..b..", if (a) ".b..b..b.." else "..b..b..b.", if (a) "..b..b..b." else ".b..b..b..", "..........")
            Sky.SNOW -> arrayOf(if (a) "...###...." else "....###...", "..#####...", ".#######..", ".+++++++..", if (a) "..w...w..." else "...w...w..", if (a) ".....w...." else "..w...w...", if (a) "..w....w.." else ".....w....", "..........")
            Sky.STORM -> arrayOf("...+++....", "..+++++...", ".+++++++..", ".+++++++..", if (a) "....z....." else "..........", if (a) "...zz....." else "..........", if (a) "....z....." else "..........", if (a) "...z......" else "..........")
            Sky.FOG -> if (a) arrayOf("..........", "#######...", "..........", "..#######.", "..........", ".######...", "..........", "..........")
                else arrayOf("..........", ".#######..", "..........", ".#######..", "..........", "..######..", "..........", "..........")
            else -> arrayOf("..........", if (a) "...###...." else "....###...", if (a) "..#####..." else "...#####..", if (a) ".#######.." else "..#######.", if (a) "#########." else ".#########", if (a) "+++++++++." else ".+++++++++", "..........", "..........")
        }
    }

    override fun onDraw(c: Canvas) {
        val r = rows()
        val u = floor(minOf(width / 10f, height / 8f)).coerceAtLeast(1f)
        val ox = (width - u * 10) / 2
        val oy = (height - u * 8) / 2
        for (y in r.indices) for (x in r[y].indices) {
            p.color = when (r[y][x]) {
                '#' -> Pixel.INK
                '+' -> Pixel.MUTED
                'y' -> 0xFFFFD34D.toInt()
                'b' -> 0xFF7EB2F2.toInt()
                'w' -> 0xFFFFFFFF.toInt()
                'z' -> 0xFFFFE27A.toInt()
                'm' -> 0xFFE8E8F2.toInt()
                else -> continue
            }
            c.drawRect(ox + x * u, oy + y * u, ox + (x + 1) * u, oy + (y + 1) * u, p)
        }
    }
}
