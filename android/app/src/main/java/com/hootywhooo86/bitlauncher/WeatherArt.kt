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

    private val gw = 64 // virtual pixels across: big, chunky pixels
    private val gh = 18
    private val ground = 13 // first row of ground
    private var frame = 0
    private val p = Paint()
    private val rnd = Random(7)

    private val drops = Array(34) { floatArrayOf(rnd.nextFloat() * 64, rnd.nextFloat() * 13) }
    private val flakes = Array(26) { floatArrayOf(rnd.nextFloat() * 64, rnd.nextFloat() * 13, rnd.nextFloat() * 6.28f) }
    private val stars = Array(12) { intArrayOf(rnd.nextInt(64), rnd.nextInt(9), rnd.nextInt(20)) }
    private val ripples = IntArray(5) { -1 } // x of a puddle ripple, or -1
    private var rippleAt = IntArray(5)

    // (x, y, width, layer). Layer 0 far and slow, 2 near and fast.
    private val cloudsFew = arrayOf(floatArrayOf(4f, 2f, 12f, 0f), floatArrayOf(30f, 4f, 10f, 1f), floatArrayOf(50f, 1f, 8f, 2f))
    private val cloudsMany = arrayOf(
        floatArrayOf(0f, 1f, 16f, 0f), floatArrayOf(20f, 0f, 14f, 0f), floatArrayOf(42f, 2f, 16f, 0f),
        floatArrayOf(10f, 4f, 13f, 1f), floatArrayOf(34f, 5f, 12f, 1f), floatArrayOf(56f, 3f, 12f, 1f),
        floatArrayOf(22f, 7f, 10f, 2f),
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
    private val push get() = (0.12f + windKmh.toFloat() / 40f).coerceIn(0.12f, 1.6f)

    private val dark get() = sky == Sky.RAIN || sky == Sky.STORM

    // ---------------------------------------------------------------- the scene

    private fun step() {
        if (sky == Sky.RAIN || sky == Sky.STORM) {
            for (d in drops) {
                d[1] += 1.3f
                d[0] += push * 0.5f
                if (d[1] >= ground) {
                    val slot = ripples.indexOfFirst { it < 0 }
                    if (slot >= 0 && rnd.nextInt(3) == 0) { ripples[slot] = d[0].toInt() % gw; rippleAt[slot] = frame }
                    d[1] = rnd.nextFloat() * 3
                    d[0] = rnd.nextFloat() * gw
                }
                if (d[0] > gw) d[0] -= gw
            }
            for (i in ripples.indices) if (ripples[i] >= 0 && frame - rippleAt[i] > 4) ripples[i] = -1
        }
        if (sky == Sky.SNOW) for (f in flakes) {
            f[1] += 0.35f
            f[0] += push * 0.4f + sin(frame / 6f + f[2]) * 0.25f
            if (f[1] >= ground) { f[1] = 0f; f[0] = rnd.nextFloat() * gw }
            if (f[0] > gw) f[0] -= gw
        }
    }

    override fun onDraw(c: Canvas) {
        canvas = c
        u = floor(minOf(width / gw.toFloat(), height / gh.toFloat())).coerceAtLeast(1f)
        ox = (width - u * gw) / 2
        oy = (height - u * gh) / 2
        if (!moving()) Unit else step()
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
        frameBorder()
        canvas = null
    }

    /** The sky in three bands with dithered seams, tinted for the weather and the hour. */
    private fun skyBands() {
        val (top, mid, low) = when {
            sky == Sky.STORM -> Triple(0xFF1E2230.toInt(), 0xFF2A3040.toInt(), 0xFF3A4152.toInt())
            night -> Triple(0xFF0B1030.toInt(), 0xFF141A44.toInt(), 0xFF22285A.toInt())
            dark || sky == Sky.FOG -> Triple(0xFF4A5363.toInt(), 0xFF5D6676.toInt(), 0xFF737C8B.toInt())
            overcast -> Triple(0xFF5E6A7E.toInt(), 0xFF717D90.toInt(), 0xFF8893A4.toInt())
            sky == Sky.SNOW -> Triple(0xFF7D8BA6.toInt(), 0xFF97A4BC.toInt(), 0xFFB3BDD0.toInt())
            else -> Triple(0xFF3C6FC4.toInt(), 0xFF5A8BD8.toInt(), 0xFF86ADE6.toInt())
        }
        px(0f, 0f, gw.toFloat(), 4f, top)
        px(0f, 4f, gw.toFloat(), 4f, mid)
        px(0f, 8f, gw.toFloat(), ground - 8f, low)
        dither(0, 3, gw, 1, mid)
        dither(0, 7, gw, 1, low)
    }

    private fun sun() {
        val cx = 9
        val cy = 4
        val yellow = 0xFFFFD34D.toInt()
        val pale = 0xFFFFF1B0.toInt()
        // Rays turn: straight ones and diagonal ones take turns.
        val straight = (frame / 6) % 2 == 0
        val rays = if (straight) listOf(0 to -4, 0 to 4, -4 to 0, 4 to 0) else listOf(-3 to -3, 3 to -3, -3 to 3, 3 to 3)
        for ((dx, dy) in rays) px((cx + dx).toFloat(), (cy + dy).toFloat(), 1f, 1f, yellow)
        px(cx - 1f, cy - 2f, 3f, 5f, yellow)
        px(cx - 2f, cy - 1f, 5f, 3f, yellow)
        px(cx - 1f, cy - 1f, 1f, 1f, pale)
    }

    private fun starsAndMoon() {
        for (s in stars) if (((frame + s[2]) / 5) % 4 != 0) px(s[0].toFloat(), s[1].toFloat(), 1f, 1f, 0xFFE8E8F2.toInt())
        val cx = 50f
        val cy = 4f
        val moon = 0xFFE8E8F2.toInt()
        px(cx - 1, cy - 2, 3f, 1f, moon)
        px(cx - 2, cy - 1, 2f, 3f, moon)
        px(cx - 1, cy + 2, 3f, 1f, moon)
        px(cx, cy - 1, 1f, 1f, withAlpha(moon, 120))
        // A soft glow that breathes.
        if ((frame / 10) % 2 == 0) dither(cx.toInt() - 4, cy.toInt() - 3, 8, 7, withAlpha(moon, 40), frame / 10)
    }

    /** A chunky cloud: light top, dithered shaded belly, darker underside. */
    private fun cloud(x: Float, y: Float, w: Int, light: Int, shade: Int) {
        val h = (w / 3).coerceAtLeast(3)
        px(x + w * 0.25f, y, w * 0.3f, 1f, light)
        px(x + w * 0.55f, y + 1, w * 0.25f, 1f, light)
        px(x + 1, y + 1, w - 2f, h - 1f, light)
        px(x, y + 2, w.toFloat(), h - 2f, light)
        for (yy in (y + h - 2).toInt() until (y + h).toInt()) for (xx in x.toInt() until (x + w).toInt()) {
            if ((xx + yy) % 2 == 0) px(xx.toFloat(), yy.toFloat(), 1f, 1f, shade)
        }
        px(x + 1, y + h - 1f, w - 2f, 1f, shade)
    }

    private fun clouds(list: Array<FloatArray>, many: Boolean) {
        val light = when {
            sky == Sky.STORM -> 0xFF4B5263.toInt()
            dark -> 0xFF8A93A3.toInt()
            night -> 0xFF5A6280.toInt()
            many -> 0xFFC9CFD9.toInt()
            else -> 0xFFF4F6FA.toInt()
        }
        val shade = blend(light, 0xFF000000.toInt(), 0.28f)
        for (cl in list) {
            val w = cl[2].toInt()
            val speed = push * (0.35f + cl[3] * 0.35f)
            val span = gw + w + 4
            val x = ((cl[0] + frame * speed) % span) - w - 2
            val bob = if (((frame / 9) + cl[3].toInt()) % 2 == 0) 0f else 1f
            cloud(x, cl[1] + bob, w, blend(light, 0xFF000000.toInt(), 0.12f * (2 - cl[3])), shade)
        }
    }

    /** Two birds flapping across now and then. */
    private fun birds() {
        val cycle = frame % 160
        if (cycle > 90) return
        val x = cycle * 0.8f - 6
        val up = (frame / 3) % 2 == 0
        val c = 0xFF2A2F3D.toInt()
        for ((bx, by) in listOf(x to 5f, x - 5 to 7f)) {
            px(bx, by, 1f, 1f, c)
            px(bx - 1, if (up) by - 1 else by + 0f, 1f, 1f, c)
            px(bx + 1, if (up) by - 1 else by + 0f, 1f, 1f, c)
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
        // A far ridge, then the near ground with dithered grass.
        for (x in 0 until gw) {
            val hgt = (2 + 1.5f * sin(x / 7f) + sin(x / 3.1f) * 0.6f).toInt().coerceAtLeast(1)
            px(x.toFloat(), ground - hgt.toFloat(), 1f, hgt.toFloat(), far)
        }
        px(0f, ground.toFloat(), gw.toFloat(), (gh - ground).toFloat(), near)
        dither(0, ground, gw, 1, blend(near, 0xFFFFFFFF.toInt(), 0.15f))
        // Grass tufts that lean with the wind.
        if (!snowy) for (x in 3 until gw step 7) {
            val lean = if ((frame / 4 + x) % 3 == 0) 1f else 0f
            px(x + lean, ground - 1f, 1f, 1f, blend(near, 0xFFFFFFFF.toInt(), 0.25f))
        }
    }

    private fun cabin() {
        val x = 40f
        val y = ground - 4f
        val wall = if (night) 0xFF5A3E2B.toInt() else 0xFF8A5A3A.toInt()
        val roof = if (sky == Sky.SNOW) 0xFFF4F6FA.toInt() else 0xFF7A2E2E.toInt()
        px(x, y, 8f, 4f, wall)
        px(x - 1, y - 1, 10f, 1f, roof)
        px(x, y - 2, 8f, 1f, roof)
        px(x + 2, y - 3, 4f, 1f, roof)
        // Window: lit at night or in gloom.
        val lit = night || dark || sky == Sky.FOG
        px(x + 2, y + 1, 2f, 2f, if (lit) 0xFFFFD86B.toInt() else 0xFF9CC7E8.toInt())
        px(x + 5, y + 1, 2f, 3f, blend(wall, 0xFF000000.toInt(), 0.35f))
        // Chimney and smoke drifting downwind.
        px(x + 6, y - 4, 1f, 2f, 0xFF5B5B66.toInt())
        for (k in 0 until 4) {
            val age = (frame / 3 + k * 3) % 12
            val sx = x + 6 + age * (0.3f + push * 0.8f)
            val sy = y - 5 - age * 0.6f
            if (sy > 0) px(sx, sy, 1f, 1f, withAlpha(0xFFDADDE6.toInt(), (200 - age * 16).coerceAtLeast(0)))
        }
    }

    private fun tree() {
        val x = 54f
        val y = ground - 1f
        val trunk = 0xFF5A3A24.toInt()
        val leaves = when {
            sky == Sky.SNOW -> 0xFFE8EEF6.toInt()
            night -> 0xFF1F4A2C.toInt()
            else -> 0xFF2E7D3E.toInt()
        }
        px(x, y - 2, 1f, 3f, trunk)
        // The crown sways: further in stronger wind.
        val reach = if (windKmh >= 15) 2 else 1
        val sway = when ((frame / 4) % 4) { 1 -> 1; 3 -> -1; else -> 0 } * (if (frame / 4 % 8 < 4) 1 else reach)
        val cx = x + sway
        px(cx - 2, y - 5, 5f, 3f, leaves)
        px(cx - 1, y - 7, 3f, 2f, leaves)
        px(cx, y - 8, 1f, 1f, leaves)
        px(cx - 2, y - 3, 5f, 1f, blend(leaves, 0xFF000000.toInt(), 0.25f))
    }

    private fun rain() {
        val blue = if (sky == Sky.STORM) 0xFF8FB8F0.toInt() else 0xFF7EB2F2.toInt()
        for (d in drops) px(d[0], d[1], 1f, 2f, blue)
    }

    private fun ripples() {
        val c = withAlpha(0xFFBFD6F5.toInt(), 200)
        for (i in ripples.indices) {
            val x = ripples[i]
            if (x < 0) continue
            val r = (frame - rippleAt[i]).coerceIn(0, 3)
            px(x - r.toFloat(), ground + 1f, 1f, 1f, c)
            px(x + r.toFloat(), ground + 1f, 1f, 1f, c)
        }
    }

    private fun snow() {
        for (f in flakes) px(f[0], f[1], 1f, 1f, 0xFFFFFFFF.toInt())
    }

    private fun lightning() {
        val cycle = frame % 40
        if (cycle != 0 && cycle != 2) return
        px(0f, 0f, gw.toFloat(), ground.toFloat(), withAlpha(0xFFFFFFFF.toInt(), 70))
        val bx = 16f + (frame / 40 % 3) * 9
        val bolt = listOf(0 to 2, 1 to 3, 0 to 4, -1 to 5, 0 to 6, 1 to 7, 2 to 8, 1 to 9, 0 to 10)
        for ((dx, y) in bolt) px(bx + dx, y.toFloat(), 1f, 1f, 0xFFFFE27A.toInt())
    }

    private fun fog() {
        // Bands of mist roll across in both directions, thickest low down where the cabin is.
        for (band in 0 until 6) {
            val y = 3f + band * 2.2f
            val dir = if (band % 2 == 0) 1 else -1
            val speed = (0.25f + push) * (0.6f + (band % 3) * 0.3f) * dir
            val alpha = (110 + 50 * sin(frame / 9f + band)).toInt().coerceIn(60, 190) + band * 6
            val c = withAlpha(0xFFDDE1EA.toInt(), alpha.coerceAtMost(220))
            var x = ((frame * speed) % 20 + 20) % 20 - 20
            while (x < gw) {
                px(x, y, 12f, 1f, c)
                px(x + 3, y + 1, 7f, 1f, withAlpha(0xFFDDE1EA.toInt(), alpha / 2))
                x += 20
            }
        }
    }

    private fun wind() {
        val c = withAlpha(0xFFFFFFFF.toInt(), 120)
        for (k in 0 until 2) {
            val y = 3f + k * 5
            val len = 6f + k * 2
            val x = ((frame * push * 2.5f + k * 29) % (gw + 20)) - 12
            px(x, y, len, 1f, c)
            px(x + len, y - 1, 2f, 1f, c)
        }
    }

    /** A thin pixel frame, like a window onto the weather. */
    private fun frameBorder() {
        // Notched corners keep the panel's pixel look.
        val bg = Pixel.PANEL
        px(0f, 0f, 1f, 1f, bg)
        px(gw - 1f, 0f, 1f, 1f, bg)
        px(0f, gh - 1f, 1f, 1f, bg)
        px(gw - 1f, gh - 1f, 1f, 1f, bg)
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
