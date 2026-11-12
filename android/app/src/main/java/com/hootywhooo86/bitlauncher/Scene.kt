package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hand-built pixel landscapes for behind the home screen. Drawn at low resolution and scaled up
 * with no smoothing, in layers that slide at different speeds as you swipe pages. The sky follows
 * the real time of day, and (if weather is on) rain, snow, fog or storms fill it.
 *
 * Cheap on purpose: layers are regenerated at most every five minutes, on a background thread;
 * frames are only animated while something is falling, the screen is showing, and the phone
 * isn't saving power.
 */
class SceneView(context: Context) : View(context) {
    companion object {
        /** (key, name). "none" uses the phone's own wallpaper. */
        val SCENES = listOf("none" to "Phone wallpaper", "prairie" to "Prairie", "railway" to "Railway", "rockies" to "Rockies", "city" to "Night City", "desert" to "Desert")

        private const val MAX_SCREENS = 8
        private val FACTORS = floatArrayOf(0.12f, 0.3f, 0.6f)
    }

    var scene = "prairie"
        set(v) { if (field != v) { field = v; regenerate() } }

    /** Weather to show; null when weather is off or unknown (then the sky is just the time of day). */
    var sky: Sky? = null
        set(v) { if (field != v) { field = v; regenerate(); updateAnimation() } }

    /** "auto" follows the clock; "dawn", "day", "dusk" or "night" holds the scene there. */
    var timeOfDay = "auto"
        set(v) { if (field != v) { field = v; regenerate() } }

    /** Today's sunrise and sunset, if known; otherwise a typical day is assumed for the colours. */
    var sunrise: Long? = null
    var sunset: Long? = null

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val particlePaint = Paint()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var block = 4
    private var vw = 1 // virtual width (one screen)
    private var vh = 1
    private var skyBmp: Bitmap? = null
    private var layers: List<Bitmap> = emptyList()
    private var scroll = 0f // in screens
    private var bucket = -1L
    private var generation = 0

    private var drops = FloatArray(0) // x, y pairs, virtual px

    // The railway scene's train: where it is (virtual px, screen-relative), and when the next one comes.
    private var trainX = Float.NaN
    private var trainTick = 0
    private var light = Light(1f, 0f, 0.5f, 0f)
    private val trainCars = 6

    /** The locomotive wears the accent colour. */
    var accent = Pixel.ACCENTS[0]
    private val trainFrame = object : Runnable {
        override fun run() {
            if (trainX.isNaN()) return
            trainX += 1f // about 30 virtual px a second
            trainTick++
            if (trainX > vw + 4) {
                trainX = Float.NaN
                scheduleTrain(60_000L + Random.nextLong(60_000L))
            } else {
                main.postDelayed(this, 33)
            }
            invalidate()
        }
    }
    private val nextTrain = Runnable { startTrain() }

    private fun scheduleTrain(delay: Long) {
        main.removeCallbacks(nextTrain)
        if (scene == "railway") main.postDelayed(nextTrain, delay)
    }

    /** Send a train through, unless it's off-screen time or the phone is saving power. */
    fun startTrain() {
        if (scene != "railway" || windowVisibility != VISIBLE || !Pixel.motion(context) || savingPower() || !trainX.isNaN()) return
        trainX = -TrainArt.length(trainCars).toFloat()
        main.post(trainFrame)
    }

    private fun stopTrain() {
        main.removeCallbacks(trainFrame)
        main.removeCallbacks(nextTrain)
        trainX = Float.NaN
    }

    private fun drawTrain(canvas: Canvas) {
        if (trainX.isNaN() || scene != "railway") return
        val factor = FACTORS[2]
        val x = trainX - scroll * factor * vw
        val base = (vh * 0.62f).toInt() + Painter.RAIL_DROP.toFloat()
        val night = light.day < 0.4f
        TrainArt.draw({ x0, y0, w, h, c ->
            particlePaint.color = c
            canvas.drawRect(x0, y0, x0 + w, y0 + h, particlePaint)
        }, x, base, accent, trainCars, trainTick, true, { Painter.lit(it, light) }, night)
    }
    private var flash = 0f
    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (animating) main.postDelayed(this, 50)
        }
    }
    private var animating = false
    private val tick = object : Runnable {
        override fun run() {
            if (timeBucket() != bucket) regenerate()
            main.postDelayed(this, 60_000)
        }
    }

    /** Page position, in screens (0 = first). Moves the layers for parallax. */
    fun setScroll(screens: Float) {
        if (screens != scroll) {
            scroll = screens.coerceIn(0f, (MAX_SCREENS - 1).toFloat())
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // Whole-number blocks, about 160 across, so every scene pixel is the same size.
        block = max(2, Math.round(w / 160f))
        vw = (w + block - 1) / block
        vh = (h + block - 1) / block
        regenerate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.postDelayed(tick, 60_000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacks(tick)
        stopAnimating()
        stopTrain()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (timeBucket() != bucket) regenerate()
            updateAnimation()
            // Coming home on the railway scene: a train shortly after.
            if (scene == "railway" && trainX.isNaN()) scheduleTrain(2500)
        } else {
            stopAnimating()
            stopTrain()
        }
    }

    private fun timeBucket() = System.currentTimeMillis() / (5 * 60_000L)

    /** Rebuild the sky and layers off the main thread, then swap them in. */
    fun regenerate() {
        if (vw <= 1) return
        bucket = timeBucket()
        val gen = ++generation
        val w = vw
        val h = vh
        val sceneKey = scene
        val weather = sky
        val now = System.currentTimeMillis()
        val light = Light.fixed(timeOfDay) ?: Light.at(now, sunrise, sunset)
        this.light = light
        worker.execute {
            val skyPixels = Painter.sky(w, h, light, weather)
            val layerBitmaps = Painter.layers(sceneKey, w, h, light, FACTORS, MAX_SCREENS).map { (lw, px) ->
                Bitmap.createBitmap(px, lw, h, Bitmap.Config.ARGB_8888)
            }
            val skyBitmap = Bitmap.createBitmap(skyPixels, w, h, Bitmap.Config.ARGB_8888)
            main.post {
                if (gen != generation) return@post
                skyBmp = skyBitmap
                layers = layerBitmaps
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val sky = skyBmp ?: return
        canvas.save()
        canvas.scale(block.toFloat(), block.toFloat())
        canvas.drawBitmap(sky, 0f, 0f, paint)
        layers.forEachIndexed { i, bmp ->
            val dx = -(scroll * FACTORS.getOrElse(i) { 0.5f } * vw)
            canvas.drawBitmap(bmp, Math.round(dx).toFloat(), 0f, paint)
        }
        drawTrain(canvas)
        drawWeather(canvas)
        canvas.restore()
    }

    // ---------------------------------------------------------------- weather particles

    private fun updateAnimation() {
        val falling = sky == Sky.RAIN || sky == Sky.SNOW || sky == Sky.STORM
        if (falling && windowVisibility == VISIBLE && Pixel.motion(context) && !savingPower()) startAnimating() else stopAnimating()
    }

    private fun savingPower(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return pm?.isPowerSaveMode == true || (level in 1..19 && battery?.isCharging != true)
    }

    private fun startAnimating() {
        if (animating) return
        val count = if (sky == Sky.SNOW) 70 else 90
        drops = FloatArray(count * 2) { i -> if (i % 2 == 0) Random.nextFloat() * vw else Random.nextFloat() * vh }
        animating = true
        main.post(frame)
    }

    private fun stopAnimating() {
        animating = false
        main.removeCallbacks(frame)
    }

    private var t = 0
    private fun step() {
        t++
        val snow = sky == Sky.SNOW
        for (i in drops.indices step 2) {
            if (snow) {
                drops[i] += sin((t + i) * 0.08f) * 0.3f
                drops[i + 1] += 0.45f
            } else {
                drops[i] -= 0.8f
                drops[i + 1] += 3.2f
            }
            if (drops[i + 1] > vh) {
                drops[i + 1] = -2f
                drops[i] = Random.nextFloat() * (vw + 20)
            }
            if (drops[i] < -2) drops[i] += vw + 4
        }
        if (sky == Sky.STORM) {
            flash = max(0f, flash - 0.15f)
            if (Random.nextInt(140) == 0) flash = 1f
        }
    }

    private fun drawWeather(canvas: Canvas) {
        when (sky) {
            Sky.RAIN, Sky.STORM -> {
                particlePaint.color = Color.argb(170, 170, 200, 255)
                for (i in drops.indices step 2) canvas.drawRect(drops[i], drops[i + 1], drops[i] + 1, drops[i + 1] + 3, particlePaint)
                if (flash > 0f) {
                    particlePaint.color = Color.argb((flash * 120).toInt(), 230, 235, 255)
                    canvas.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), particlePaint)
                }
            }
            Sky.SNOW -> {
                particlePaint.color = Color.argb(230, 245, 248, 255)
                for (i in drops.indices step 2) {
                    val s = if ((i / 2) % 4 == 0) 2f else 1f
                    canvas.drawRect(drops[i], drops[i + 1], drops[i] + s, drops[i + 1] + s, particlePaint)
                }
            }
            Sky.FOG -> {
                // Still bands of mist: no animation needed.
                val horizon = vh * 0.62f
                for (b in 0 until 4) {
                    particlePaint.color = Color.argb(70 - b * 12, 210, 214, 225)
                    canvas.drawRect(0f, horizon - 30 + b * 9, vw.toFloat(), horizon - 22 + b * 9 + 6, particlePaint)
                }
            }
            else -> {}
        }
    }
}

/**
 * How lit the world is: 0 at night, 1 in full day, with twilight between and a warm glow near
 * sunrise and sunset. Unknown sun times fall back to a typical 7:30 to 17:30 day.
 */
data class Light(val day: Float, val warm: Float, val dayProgress: Float, val nightProgress: Float) {
    companion object {
        /** (key, name) for the scene time setting. */
        val TIMES = listOf("auto" to "Follow the clock", "dawn" to "Always dawn", "day" to "Always day", "dusk" to "Always dusk", "night" to "Always night")

        /** A held time of day, or null for "auto". */
        fun fixed(key: String): Light? = when (key) {
            "dawn" -> Light(0.55f, 1f, 0.04f, 1f)
            "day" -> Light(1f, 0f, 0.45f, 0f)
            "dusk" -> Light(0.55f, 1f, 0.96f, 0f)
            "night" -> Light(0f, 0f, 1f, 0.4f)
            else -> null
        }

        fun at(now: Long, sunrise: Long?, sunset: Long?): Light {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            fun today(h: Int, m: Int) = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            }.timeInMillis
            // Only trust sun times for today; otherwise use the same clock times today.
            fun sameTimeToday(t: Long?, h: Int, m: Int): Long {
                if (t == null) return today(h, m)
                val c = Calendar.getInstance().apply { timeInMillis = t }
                return today(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            val rise = sameTimeToday(sunrise, 7, 30)
            val set = sameTimeToday(sunset, 17, 30)
            val twilight = 45 * 60_000f
            val up = ((now - (rise - twilight)) / (2 * twilight)).coerceIn(0f, 1f)
            val down = (((set + twilight) - now) / (2 * twilight)).coerceIn(0f, 1f)
            val day = smooth(min(up, down))
            val warm = max(1f - abs(now - rise) / (twilight * 1.5f), 1f - abs(now - set) / (twilight * 1.5f)).coerceIn(0f, 1f)
            val dayProgress = ((now - rise).toFloat() / (set - rise)).coerceIn(0f, 1f)
            val nightLength = (24 * 3600_000L - (set - rise)).toFloat()
            val sinceSet = ((now - set) % (24 * 3600_000L) + 24 * 3600_000L) % (24 * 3600_000L)
            val nightProgress = (sinceSet / nightLength).coerceIn(0f, 1f)
            return Light(day, warm, dayProgress, nightProgress)
        }

        private fun smooth(x: Float) = x * x * (3 - 2 * x)
    }
}

private typealias Fill = (Int, Int, Int, Int) -> Unit
private typealias Rect = (Int, Int, Int, Int, Int) -> Unit

/** Draws skies and landscapes into pixel arrays. Pure, deterministic per scene: the same hills every time. */
object Painter {
    private fun mix(a: Int, b: Int, t: Float) = blend(a, b, t.coerceIn(0f, 1f))

    fun sky(w: Int, h: Int, light: Light, weather: Sky?): IntArray {
        var top = mix(0xFF0A0B26.toInt(), 0xFF3F7FD0.toInt(), light.day)
        var bottom = mix(0xFF22204E.toInt(), 0xFFA6D6F2.toInt(), light.day)
        bottom = mix(bottom, 0xFFF4895F.toInt(), light.warm * 0.75f)
        top = mix(top, 0xFF5B3A78.toInt(), light.warm * 0.35f)
        val grey = when (weather) {
            Sky.CLOUDY, Sky.FOG -> 0.5f
            Sky.RAIN, Sky.SNOW -> 0.65f
            Sky.STORM -> 0.8f
            else -> 0f
        }
        if (grey > 0) {
            val overcast = mix(0xFF20222E.toInt(), 0xFF98A0B2.toInt(), light.day)
            top = mix(top, overcast, grey)
            bottom = mix(bottom, overcast, grey)
        }
        val px = IntArray(w * h)
        val horizon = (h * 0.62f).toInt().coerceAtLeast(1)
        // Ten flat bands with a checkerboard where they meet: dithered, like old hardware.
        for (y in 0 until h) {
            val t = (y.toFloat() / horizon).coerceIn(0f, 1f) * 10
            val band = t.toInt()
            val frac = t - band
            for (x in 0 until w) {
                val step = if (frac > 0.5f && (x + y) % 2 == 0) band + 1 else band
                px[y * w + x] = mix(top, bottom, step / 10f)
            }
        }
        val r = Random(7)
        // Stars, fading in as the light goes; hidden by cloud.
        val starAlpha = ((0.45f - light.day) / 0.45f).coerceIn(0f, 1f) * (1 - grey)
        if (starAlpha > 0.05f) {
            repeat(w * h / 260) {
                val x = r.nextInt(w)
                val y = r.nextInt((horizon * 0.85f).toInt().coerceAtLeast(1))
                px[y * w + x] = mix(px[y * w + x], 0xFFFFFFFF.toInt(), starAlpha * (0.5f + r.nextFloat() * 0.5f))
            }
        }
        // Sun by day, moon by night, riding an arc over the horizon.
        if (grey < 0.7f) {
            val isDay = light.day > 0.3f
            val p = if (isDay) light.dayProgress else light.nightProgress
            val cx = (p * w).toInt()
            val cy = (horizon - sin(p * PI).toFloat() * horizon * 0.7f).toInt()
            val radius = if (isDay) 6 else 5
            val color = if (isDay) mix(0xFFFFE27A.toInt(), 0xFFFF9C5A.toInt(), light.warm) else 0xFFE8E8F2.toInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                // The moon is a crescent: a second disc bites it.
                if (!isDay && (dx - 2) * (dx - 2) + (dy + 1) * (dy + 1) <= radius * radius - 3) continue
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            }
        }
        return px
    }

    /** Smooth rolling heights from a few seeded sine waves. */
    private class Ridge(seed: Int, val amps: FloatArray, val freqs: FloatArray) {
        private val phases = Random(seed).let { r -> FloatArray(amps.size) { r.nextFloat() * 6.28f } }
        fun at(x: Int): Float = amps.indices.sumOf { (amps[it] * sin(x * freqs[it] + phases[it])).toDouble() }.toFloat()
    }

    /** Darken and cool a colour for night. */
    fun lit(c: Int, light: Light): Int {
        val night = mix(c, 0xFF141633.toInt(), 0.72f)
        return mix(mix(night, c, light.day), 0xFFFF9C5A.toInt(), light.warm * 0.12f)
    }

    /** Far, middle and near layers as (width, pixels), each wide enough to slide across [screens]. */
    fun layers(scene: String, w: Int, h: Int, light: Light, factors: FloatArray, screens: Int): List<Pair<Int, IntArray>> {
        if (scene == "none") return emptyList()
        val horizon = (h * 0.62f).toInt()
        return factors.mapIndexed { i, f ->
            val lw = w + (f * w * (screens - 1)).toInt() + 2
            val px = IntArray(lw * h)
            fun fill(x: Int, y0: Int, y1: Int, c: Int) {
                for (y in max(0, y0) until min(h, y1)) px[y * lw + x] = c
            }
            fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
                for (x in max(0, x0) until min(lw, x1)) fill(x, y0, y1, c)
            }
            val r = Random(scene.hashCode() * 31 + i)
            when (scene) {
                "prairie" -> prairie(i, lw, h, horizon, light, r, ::fill, ::rect)
                "railway" -> railway(i, lw, h, horizon, light, r, ::fill, ::rect)
                "rockies" -> rockies(i, lw, h, horizon, light, r, ::fill, ::rect)
                "city" -> city(i, lw, h, horizon, light, r, ::fill, ::rect, px)
                "desert" -> desert(i, lw, h, horizon, light, r, ::fill, ::rect)
            }
            if (i == factors.size - 1) speckle(px, lw, h, horizon + 36, r)
            lw to px
        }
    }

    /** Short dashes a shade darker or lighter across the ground, like dirt and pebbles. Quiet on purpose. */
    private fun speckle(px: IntArray, lw: Int, h: Int, from: Int, r: Random) {
        repeat(lw * (h - from) / 70) {
            val x = r.nextInt(lw)
            val y = from + r.nextInt(max(1, h - from))
            val len = 1 + r.nextInt(3)
            val lighter = r.nextInt(3) == 0
            for (dx in 0 until len) {
                val i = y * lw + x + dx
                if (x + dx >= lw || px[i] == 0) continue
                px[i] = if (lighter) blend(px[i], 0xFFFFFFFF.toInt(), 0.08f) else blend(px[i], 0xFF000000.toInt(), 0.12f)
            }
        }
    }

    private fun prairie(layer: Int, lw: Int, h: Int, hz: Int, l: Light, r: Random, fill: Fill, rect: Rect) {
        when (layer) {
            0 -> { // distant hills
                val ridge = Ridge(11, floatArrayOf(5f, 2.5f), floatArrayOf(0.03f, 0.09f))
                val c = lit(0xFF5F7E58.toInt(), l)
                for (x in 0 until lw) fill(x, (hz - 10 + ridge.at(x)).toInt(), hz + 12, c)
            }
            1 -> { // fields, with grain elevators standing up out of them
                val ridge = Ridge(12, floatArrayOf(2.5f, 1.2f), floatArrayOf(0.02f, 0.07f))
                val field = lit(0xFFB4B65E.toInt(), l)
                val row = lit(0xFF9EA352.toInt(), l)
                for (x in 0 until lw) {
                    val top = (hz + ridge.at(x)).toInt()
                    for (y in top until h) fill(x, y, y + 1, if ((y - top) % 4 == 3) row else field)
                }
                var x = 30 + r.nextInt(40)
                while (x < lw - 14) {
                    val base = (hz + ridge.at(x)).toInt() + 1
                    val tall = 26 + r.nextInt(10)
                    rect(x, base - tall, x + 9, base, lit(0xFFB4412F.toInt(), l))
                    rect(x - 1, base - tall - 3, x + 10, base - tall, lit(0xFF5E2A24.toInt(), l))
                    rect(x + 3, base - tall + 6, x + 6, base - tall + 9, lit(0xFFF2E6C8.toInt(), l))
                    rect(x + 9, base - 12, x + 15, base, lit(0xFF8E3A2C.toInt(), l)) // the annex
                    x += 120 + r.nextInt(120)
                }
            }
            2 -> { // grass and a fence along the bottom
                val top = hz + 26
                val grass = lit(0xFF6E9244.toInt(), l)
                val tuft = lit(0xFF5A7C36.toInt(), l)
                for (x in 0 until lw) {
                    fill(x, top + if (r.nextInt(5) == 0) -1 else 0, h, grass)
                    if (r.nextInt(7) == 0) fill(x, top - 2, top, tuft)
                }
                val wood = lit(0xFF6B4A2E.toInt(), l)
                var x = 3
                while (x < lw) {
                    rect(x, top - 9, x + 2, top + 1, wood)
                    x += 14
                }
                rect(0, top - 7, lw, top - 6, wood)
                rect(0, top - 3, lw, top - 2, wood)
            }
        }
    }

    /** Where the rails sit in the railway scene (the top of the rail), from the horizon. */
    const val RAIL_DROP = 22

    private fun railway(layer: Int, lw: Int, h: Int, hz: Int, l: Light, r: Random, fill: Fill, rect: Rect) {
        when (layer) {
            0 -> { // far hills, and a distant elevator or two
                val ridge = Ridge(31, floatArrayOf(4f, 2f), floatArrayOf(0.025f, 0.08f))
                val c = lit(0xFF627F5A.toInt(), l)
                for (x in 0 until lw) fill(x, (hz - 8 + ridge.at(x)).toInt(), hz + 14, c)
                var x = 50 + r.nextInt(60)
                while (x < lw - 8) {
                    rect(x, hz - 18, x + 5, hz - 4, lit(0xFF7C5A4A.toInt(), l))
                    rect(x - 1, hz - 20, x + 6, hz - 18, lit(0xFF4E3A33.toInt(), l))
                    x += 160 + r.nextInt(120)
                }
            }
            1 -> { // fields, then telegraph poles with their wires along the line
                val field = lit(0xFFB0B25C.toInt(), l)
                val row = lit(0xFF9CA151.toInt(), l)
                for (x in 0 until lw) for (y in hz + 2 until h) fill(x, y, y + 1, if ((y - hz) % 4 == 1) row else field)
                val pole = lit(0xFF5A4030.toInt(), l)
                val wire = lit(0xFF2E2A2A.toInt(), l)
                val top = hz - 14
                var x = 4
                var last = -1
                while (x < lw) {
                    rect(x, top, x + 1, hz + RAIL_DROP - 6, pole)
                    rect(x - 2, top + 1, x + 3, top + 2, pole) // crossarm
                    if (last >= 0) {
                        // Wires sag between poles.
                        for (wx in last + 1 until x) {
                            val t = (wx - last).toFloat() / (x - last)
                            val sag = (4 * t * (1 - t) * 3).toInt()
                            fill(wx, top + 1 + sag, top + 2 + sag, wire)
                        }
                    }
                    last = x
                    x += 34
                }
            }
            2 -> { // ballast, ties and rails, then grass in front
                val rail = hz + RAIL_DROP
                val ballast = lit(0xFF7D7468.toInt(), l)
                val tie = lit(0xFF4B3A2C.toInt(), l)
                val steel = lit(0xFFB9BDC7.toInt(), l)
                val grass = lit(0xFF6A8E42.toInt(), l)
                rect(0, rail - 1, lw, rail + 4, ballast)
                for (x in 0 until lw step 3) rect(x, rail + 1, x + 2, rail + 2, tie)
                rect(0, rail, lw, rail + 1, steel)
                for (x in 0 until lw) {
                    fill(x, rail + 4, h, grass)
                    if (r.nextInt(6) == 0) fill(x, rail + 3, rail + 4, lit(0xFF57773A.toInt(), l))
                }
            }
        }
    }

    private fun rockies(layer: Int, lw: Int, h: Int, hz: Int, l: Light, r: Random, fill: Fill, rect: Rect) {
        when (layer) {
            0 -> { // peaks with snow
                val rock = lit(0xFF6D7BA0.toInt(), l)
                val snow = lit(0xFFEEF2F8.toInt(), l)
                var x = 0
                while (x < lw) {
                    val half = 18 + r.nextInt(22)
                    val peak = hz - 30 - r.nextInt(45)
                    for (dx in -half..half) {
                        val px = x + dx
                        if (px !in 0 until lw) continue
                        val top = peak + abs(dx) * (hz + 8 - peak) / half + if (r.nextInt(4) == 0) 1 else 0
                        fill(px, top, hz + 12, rock)
                        // Snow only on the upper third: a cap, with a ragged lower edge.
                        val snowLine = peak + (hz - peak) / 3 + if (r.nextInt(3) == 0) 1 else 0
                        if (top < snowLine) fill(px, top, snowLine, snow)
                    }
                    x += half + r.nextInt(half)
                }
            }
            1 -> { // pine forest
                val pine = lit(0xFF2E5B4C.toInt(), l)
                val dark = lit(0xFF244A3E.toInt(), l)
                for (x in 0 until lw) fill(x, hz + 4, hz + 14, dark)
                var x = 0
                while (x < lw) {
                    val tall = 9 + r.nextInt(10)
                    for (dy in 0 until tall) {
                        val half = dy / 3
                        for (dx in -half..half) if (x + dx in 0 until lw) fill(x + dx, hz + 6 - tall + dy, hz + 7 - tall + dy, pine)
                    }
                    x += 4 + r.nextInt(4)
                }
            }
            2 -> { // lake, then the near shore
                val water = lit(0xFF4F7FB0.toInt(), l)
                val glint = lit(0xFF9CC3E8.toInt(), l)
                val shore = lit(0xFF3D6B4E.toInt(), l)
                for (x in 0 until lw) {
                    fill(x, hz + 14, hz + 24, water)
                    if (r.nextInt(9) == 0) fill(x, hz + 16 + r.nextInt(7), hz + 17 + r.nextInt(7), glint)
                    fill(x, hz + 24 + if (r.nextInt(4) == 0) -1 else 0, h, shore)
                }
            }
        }
    }

    private fun city(layer: Int, lw: Int, h: Int, hz: Int, l: Light, r: Random, fill: Fill, rect: Rect, px: IntArray) {
        val night = l.day < 0.5f
        val windowOn = if (night) 0xFFFFD86B.toInt() else lit(0xFF7E86B8.toInt(), l)
        val windowOff = lit(0xFF3A3F66.toInt(), l)
        when (layer) {
            0, 1 -> {
                val wall = lit(if (layer == 0) 0xFF2A2D4F.toInt() else 0xFF393D68.toInt(), l)
                var x = 0
                while (x < lw) {
                    val bw = 8 + r.nextInt(10)
                    val tall = if (layer == 0) 30 + r.nextInt(45) else 14 + r.nextInt(24)
                    val base = hz + if (layer == 0) 6 else 16
                    rect(x, base - tall, x + bw, base, wall)
                    if (layer == 0 && r.nextInt(4) == 0) rect(x + bw / 2, base - tall - 6, x + bw / 2 + 1, base - tall, wall) // antenna
                    var wy = base - tall + 3
                    while (wy < base - 2) {
                        var wx = x + 2
                        while (wx < x + bw - 2) {
                            rect(wx, wy, wx + 1, wy + 1, if (r.nextInt(100) < (if (night) 45 else 20)) windowOn else windowOff)
                            wx += 3
                        }
                        wy += 4
                    }
                    x += bw + r.nextInt(3)
                }
            }
            2 -> { // street with lamps
                val road = lit(0xFF1E2036.toInt(), l)
                val curb = lit(0xFF4A4E70.toInt(), l)
                rect(0, hz + 24, lw, h, road)
                rect(0, hz + 23, lw, hz + 24, curb)
                var x = 6
                while (x < lw) {
                    rect(x, hz + 10, x + 1, hz + 24, curb)
                    rect(x - 1, hz + 9, x + 3, hz + 10, curb)
                    if (night) {
                        rect(x - 1, hz + 10, x + 3, hz + 11, 0xFFFFE29A.toInt())
                        // A soft pool of light on the road.
                        for (dx in -4..5) for (dy in 0..2) {
                            val i = (hz + 24 + dy) * lw + x + dx
                            if (x + dx in 0 until lw && hz + 24 + dy < h) px[i] = blend(px[i], 0xFFFFE29A.toInt(), 0.25f)
                        }
                    }
                    x += 26
                }
                for (dx in 0 until lw step 12) rect(dx, hz + 32, dx + 6, hz + 33, lit(0xFFD8D8E8.toInt(), l)) // lane marks
            }
        }
    }

    private fun desert(layer: Int, lw: Int, h: Int, hz: Int, l: Light, r: Random, fill: Fill, rect: Rect) {
        when (layer) {
            0 -> { // mesas
                val rock = lit(0xFFC17C55.toInt(), l)
                val shade = lit(0xFF9C5F43.toInt(), l)
                for (x in 0 until lw) fill(x, hz, hz + 12, rock)
                var x = r.nextInt(30)
                while (x < lw) {
                    val bw = 20 + r.nextInt(30)
                    val tall = 10 + r.nextInt(22)
                    rect(x, hz - tall, x + bw, hz + 1, rock)
                    rect(x + bw - 4, hz - tall, x + bw, hz + 1, shade)
                    x += bw + 30 + r.nextInt(60)
                }
            }
            1 -> { // dunes
                val ridge = Ridge(21, floatArrayOf(4f, 2f), floatArrayOf(0.035f, 0.11f))
                val sand = lit(0xFFE2B574.toInt(), l)
                for (x in 0 until lw) fill(x, (hz + 8 + ridge.at(x)).toInt(), h, sand)
            }
            2 -> { // near ground with cacti
                val ground = lit(0xFFD39E5E.toInt(), l)
                val cactus = lit(0xFF4F8A4B.toInt(), l)
                val pebble = lit(0xFFA9783F.toInt(), l)
                val top = hz + 28
                for (x in 0 until lw) {
                    fill(x, top, h, ground)
                    if (r.nextInt(11) == 0) fill(x, top + 3 + r.nextInt(12), top + 4 + r.nextInt(12), pebble)
                }
                var x = 20 + r.nextInt(30)
                while (x < lw - 8) {
                    val tall = 12 + r.nextInt(8)
                    rect(x, top - tall, x + 3, top + 1, cactus)
                    rect(x - 4, top - tall + 5, x - 2, top - tall + 10, cactus)
                    rect(x - 4, top - tall + 9, x, top - tall + 11, cactus)
                    rect(x + 5, top - tall + 3, x + 7, top - tall + 8, cactus)
                    rect(x + 3, top - tall + 7, x + 7, top - tall + 9, cactus)
                    x += 50 + r.nextInt(60)
                }
            }
        }
    }
}
