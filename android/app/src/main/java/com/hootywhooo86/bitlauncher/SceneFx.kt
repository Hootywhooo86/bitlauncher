package com.hootywhooo86.bitlauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Which season or event it is, by the phone's date and time. */
object Seasons {
    const val AUTUMN = "autumn"
    const val WINTER = "winter"
    const val SPRING = "spring"
    const val SUMMER = "summer"
    const val NEW_YEAR = "newyear"

    fun now(at: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val h = c.get(Calendar.HOUR_OF_DAY)
        if ((m == 12 && d == 31 && h >= 20) || (m == 1 && d == 1 && h < 2)) return NEW_YEAR
        return when {
            m == 12 || m <= 2 -> WINTER
            (m == 3 && d >= 20) || m == 4 || m == 5 -> SPRING
            m == 3 -> WINTER
            m in 6..8 || (m == 9 && d < 22) -> SUMMER
            else -> AUTUMN
        }
    }
}

/**
 * Little things that happen in the home scene, drawn on its pixel grid. Seasons and events by
 * date: leaves falling in autumn, blossom drifting in spring, fireflies on summer nights,
 * fireworks at New Year. And rare visitors: a shooting star or a plane's blinking light at night,
 * a deer by the tracks at dawn. All pixel art, all optional.
 */
class SceneFx {
    var season = Seasons.now()
    var seasonsOn = true
    var visitorsOn = true

    private val rnd = Random(42)
    private var t = 0
    private var vw = 160
    private var vh = 320
    private val p = Paint().apply { isAntiAlias = false }

    // Leaves or petals: x, y, phase, colour index.
    private var drift = FloatArray(0)
    // Fireflies: x, y, phase.
    private var flies = FloatArray(0)
    // Fireworks: cx, cy, age, colour index (age < 0 = waiting).
    internal val bursts = Array(4) { floatArrayOf(0f, 0f, -1f, 0f) }

    private enum class Kind { STAR, PLANE, DEER }

    private var visitor: Kind? = null
    private var vx = 0f
    private var vy = 0f
    private var vt = 0

    fun size(w: Int, h: Int) {
        if (w == vw && h == vh && drift.isNotEmpty()) return
        vw = w
        vh = h
        drift = FloatArray(22 * 4) { i -> when (i % 4) { 0 -> rnd.nextFloat() * w; 1 -> rnd.nextFloat() * h; 2 -> rnd.nextFloat() * 6.28f; else -> rnd.nextInt(3).toFloat() } }
        flies = FloatArray(14 * 3) { i -> when (i % 3) { 0 -> rnd.nextFloat() * w; 1 -> h * (0.62f + rnd.nextFloat() * 0.2f); else -> rnd.nextFloat() * 6.28f } }
    }

    /** Does anything need the animation running right now? */
    fun busy(night: Boolean): Boolean = visitor != null || (seasonsOn && when (season) {
        Seasons.AUTUMN, Seasons.SPRING, Seasons.WINTER, Seasons.NEW_YEAR -> true
        Seasons.SUMMER -> night
        else -> false
    })

    /** Maybe send a visitor along. Called now and then; true if one set off. */
    fun maybeVisit(night: Boolean, dawn: Boolean, scene: String): Boolean {
        if (!visitorsOn || visitor != null) return false
        val choices = buildList {
            if (night) { add(Kind.STAR); add(Kind.PLANE) }
            if (dawn && scene in setOf("railway", "prairie", "rockies")) add(Kind.DEER)
        }
        if (choices.isEmpty()) return false
        val k = choices[rnd.nextInt(choices.size)]
        visitor = k
        vt = 0
        when (k) {
            Kind.STAR -> { vx = rnd.nextFloat() * vw * 0.6f; vy = vh * (0.05f + rnd.nextFloat() * 0.15f) }
            Kind.PLANE -> { vx = -6f; vy = vh * (0.12f + rnd.nextFloat() * 0.12f) }
            Kind.DEER -> { vx = -10f; vy = vh * 0.62f + 12 }
        }
        return true
    }

    fun step(night: Boolean) {
        t++
        if (seasonsOn && (season == Seasons.AUTUMN || season == Seasons.SPRING || season == Seasons.WINTER)) {
            val petals = season == Seasons.SPRING
            val flurry = season == Seasons.WINTER
            for (i in drift.indices step 4) {
                drift[i] += sin(t * 0.05f + drift[i + 2]) * (if (flurry) 0.25f else 0.5f) + if (petals) 0.35f else if (flurry) 0.05f else 0.2f
                drift[i + 1] += if (petals) 0.35f else if (flurry) 0.25f else 0.55f
                if (drift[i + 1] > vh || drift[i] > vw + 4) { drift[i] = rnd.nextFloat() * vw - 10; drift[i + 1] = -4f }
            }
        }
        if (seasonsOn && season == Seasons.NEW_YEAR) for (b in bursts) {
            if (b[2] < 0) {
                if (rnd.nextInt(20) == 0) { b[0] = vw * (0.15f + rnd.nextFloat() * 0.7f); b[1] = vh * (0.1f + rnd.nextFloat() * 0.25f); b[2] = 0f; b[3] = rnd.nextInt(4).toFloat() }
            } else if (++b[2] > 26) b[2] = -1f
        }
        when (visitor) {
            Kind.STAR -> { vx += 3.2f; vy += 1.1f; if (++vt > 14) visitor = null }
            Kind.PLANE -> { vx += 0.25f; if (vx > vw + 6) visitor = null }
            Kind.DEER -> { if (vt < 140) vx += 0.35f else if (vt > 220) vx += 0.5f; vt++; if (vx > vw + 12) visitor = null }
            null -> {}
        }
    }

    private fun px(c: Canvas, x: Float, y: Float, color: Int, w: Float = 1f, h: Float = 1f) {
        p.color = color
        val sx = x.toInt().toFloat()
        val sy = y.toInt().toFloat()
        c.drawRect(sx, sy, sx + w, sy + h, p)
    }

    /** Draw on the scene's grid (the canvas is already scaled to it). */
    fun draw(c: Canvas, night: Boolean) {
        if (seasonsOn) when (season) {
            Seasons.AUTUMN -> {
                val leaf = intArrayOf(0xFFC0543A.toInt(), 0xFFFF9F1C.toInt(), 0xFFE5484D.toInt())
                for (i in drift.indices step 4) {
                    val col = leaf[drift[i + 3].toInt()]
                    val flip = ((t / 6) + i) % 2 == 0 // the leaf turns as it falls
                    px(c, drift[i], drift[i + 1], col, if (flip) 2f else 1f, if (flip) 1f else 2f)
                }
            }
            Seasons.WINTER -> {
                // A light flurry: a few flakes, whatever the forecast.
                for (i in 0 until minOf(drift.size, 12 * 4) step 4) px(c, drift[i], drift[i + 1], 0xE6F4F6FF.toInt())
            }
            Seasons.SPRING -> {
                val petal = intArrayOf(0xFFF472B6.toInt(), 0xFFFFD1E8.toInt(), 0xFFE2D8FF.toInt())
                for (i in drift.indices step 4) {
                    val col = petal[drift[i + 3].toInt()]
                    px(c, drift[i], drift[i + 1], col, 2f, 1f)
                    if (((t / 8) + i) % 3 == 0) px(c, drift[i] + 1, drift[i + 1] + 1, 0xFFFFFFFF.toInt())
                }
            }
            Seasons.SUMMER -> if (night) for (i in flies.indices step 3) {
                val glow = sin(t * 0.12f + flies[i + 2])
                if (glow > 0.2f) {
                    val x = flies[i] + sin(t * 0.03f + flies[i + 2]) * 6
                    val y = flies[i + 1] + cos(t * 0.04f + flies[i + 2]) * 3
                    px(c, x, y, Color.argb((glow * 255).toInt(), 214, 255, 120))
                }
            }
            Seasons.NEW_YEAR -> {
                val colours = intArrayOf(0xFFFFD34D.toInt(), 0xFFF472B6.toInt(), 0xFF2DD4BF.toInt(), 0xFFA78BFA.toInt())
                for (b in bursts) {
                    if (b[2] < 0) continue
                    val age = b[2]
                    if (age < 6) { // the rocket going up
                        px(c, b[0], b[1] + (6 - age) * 4, 0xFFFFE08A.toInt())
                        continue
                    }
                    val r = (age - 6) * 1.3f
                    val alpha = (255 * (1 - (age - 6) / 20f)).toInt().coerceIn(0, 255)
                    val col = colours[b[3].toInt()]
                    if (age < 9) px(c, b[0] - 1, b[1] - 1, 0xFFFFFFFF.toInt(), 3f, 3f) // the bang
                    for (k in 0 until 16) {
                        val a = k * Math.PI.toFloat() / 8
                        val drop = (age - 6) * 0.2f
                        // A bright spark with a fading trail behind it.
                        px(c, b[0] + cos(a) * r, b[1] + sin(a) * r + drop, Color.argb(alpha, Color.red(hi(col)), Color.green(hi(col)), Color.blue(hi(col))))
                        if (r > 3) px(c, b[0] + cos(a) * (r - 2), b[1] + sin(a) * (r - 2) + drop, Color.argb(alpha * 2 / 3, Color.red(col), Color.green(col), Color.blue(col)))
                        if (r > 6) px(c, b[0] + cos(a) * (r - 4), b[1] + sin(a) * (r - 4) + drop, Color.argb(alpha / 3, Color.red(col), Color.green(col), Color.blue(col)))
                    }
                }
            }
        }
        when (visitor) {
            Kind.STAR -> for (k in 0 until 6) px(c, vx - k * 3.2f, vy - k * 1.1f, Color.argb(255 - k * 40, 255, 250, 230))
            Kind.PLANE -> {
                px(c, vx, vy, 0xFF2A2F3D.toInt(), 5f, 1f)
                if ((t / 8) % 2 == 0) px(c, vx + 5, vy, 0xFFE5484D.toInt()) else px(c, vx - 1, vy, 0xFFF4F1FF.toInt())
            }
            Kind.DEER -> deer(c, vx, vy)
            null -> {}
        }
    }

    private fun hi(c: Int) = ChargeView.hi(c)

    /** A small deer, 9 wide: walks in, stops to graze, walks off. */
    private fun deer(c: Canvas, x: Float, y: Float) {
        val body = 0xFF8A5A3A.toInt()
        val dark = 0xFF5A3A24.toInt()
        val grazing = vt in 140..220
        px(c, x, y, body, 7f, 3f)
        px(c, x + 1, y + 3, dark, 1f, 3f)
        px(c, x + 5, y + 3, dark, 1f, 3f)
        val step = (t / 5) % 2 == 0 && !grazing
        px(c, x + (if (step) 2 else 3), y + 3, dark, 1f, 3f)
        px(c, x + (if (step) 4 else 6), y + 3, dark, 1f, 3f)
        if (grazing) {
            px(c, x + 7, y + 2, body, 1f, 3f)
            px(c, x + 8, y + 4, body, 2f, 1f)
        } else {
            px(c, x + 7, y - 2, body, 1f, 3f)
            px(c, x + 7, y - 3, body, 3f, 2f)
            px(c, x + 8, y - 5, dark, 1f, 2f) // antler
        }
        px(c, x - 1, y, 0xFFF4F1FF.toInt()) // white tail
    }
}
