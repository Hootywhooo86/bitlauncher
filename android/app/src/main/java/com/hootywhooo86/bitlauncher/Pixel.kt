package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.Toast

/**
 * A palette: background, panel, text and quiet text. The accent is chosen separately, and the
 * dark outline around icons ([Pixel.SHADOW]) never changes, so every theme keeps icons legible.
 */
class PixelTheme(val key: String, val name: String, val bg: Int, val panel: Int, val ink: Int, val muted: Int)

/** The family look: navy, ink, muted grey, one accent the user picks. Colour only for meaning. */
object Pixel {
    /** Every theme is free. Default first. */
    val THEMES = listOf(
        PixelTheme("default", "128bit", 0xFF0B0B16.toInt(), 0xFF141428.toInt(), 0xFFF4F1FF.toInt(), 0xFF9A97B8.toInt()),
        PixelTheme("gameboy", "Game Boy", 0xFF0F380F.toInt(), 0xFF1F4A1F.toInt(), 0xFFC4DF6B.toInt(), 0xFF8BAC0F.toInt()),
        PixelTheme("nes", "NES", 0xFF0A0A0A.toInt(), 0xFF24245C.toInt(), 0xFFFCFCFC.toInt(), 0xFFA4A4A4.toInt()),
        PixelTheme("pico8", "PICO-8", 0xFF1D2B53.toInt(), 0xFF2E1F47.toInt(), 0xFFFFF1E8.toInt(), 0xFFC2C3C7.toInt()),
        PixelTheme("amber", "CRT Amber", 0xFF120900.toInt(), 0xFF261600.toInt(), 0xFFFFB000.toInt(), 0xFFB37A00.toInt()),
    )

    var theme: PixelTheme = THEMES[0]
        private set

    /** Pick up the saved theme; screens call this when they start. */
    fun load(context: Context) {
        val prefs = Prefs(context)
        val key = prefs.theme
        theme = if (key == "custom") custom(prefs) else THEMES.firstOrNull { it.key == key } ?: THEMES[0]
    }

    /** The user's own theme, from their four colours; starts as a copy of the default. */
    fun custom(prefs: Prefs): PixelTheme {
        val d = THEMES[0]
        return PixelTheme("custom", "My theme", prefs.customColor("bg", d.bg), prefs.customColor("panel", d.panel),
            prefs.customColor("ink", d.ink), prefs.customColor("muted", d.muted))
    }

    /** The theme for [key], the custom one included. */
    fun themeFor(prefs: Prefs, key: String): PixelTheme = if (key == "custom") custom(prefs) else THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    val BG: Int get() = theme.bg
    val PANEL: Int get() = theme.panel
    val INK: Int get() = theme.ink
    val MUTED: Int get() = theme.muted
    const val SHADOW = 0xFF05050B.toInt()

    /** Free accent colours. The first is the family violet. */
    val ACCENTS = intArrayOf(
        0xFFA78BFA.toInt(), // violet
        0xFF2DD4BF.toInt(), // teal
        0xFFFF9F1C.toInt(), // orange
        0xFF60A5FA.toInt(), // blue
        0xFFF472B6.toInt(), // pink
        0xFFF4F1FF.toInt(), // white
    )

    private var font: Typeface? = null

    fun font(context: Context): Typeface =
        font ?: context.resources.getFont(R.font.silkscreen).also { font = it }

    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
    fun dpi(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /** A pixel-font label. */
    fun label(context: Context, text: CharSequence, sizeSp: Float = 11f, color: Int = INK): TextView =
        TextView(context).apply {
            this.text = text
            typeface = font(context)
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
            letterSpacing = 0.04f
        }

    /** A chunky button: bordered panel, pixel label. */
    fun button(context: Context, text: CharSequence, accent: Int, onClick: (View) -> Unit): TextView =
        label(context, text, 11f).apply {
            gravity = Gravity.CENTER
            val p = dpi(context, 12)
            setPadding(p, p, p, p)
            background = PanelDrawable(context, PANEL, accent)
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
        }

    /**
     * Whether to animate: the user's own switch, and the phone's "remove animations"
     * (developer options and accessibility) both get a say.
     */
    fun motion(context: Context): Boolean = Prefs(context).animations && ValueAnimator.areAnimatorsEnabled()

    /** One honest sentence, never a silent no-op. */
    fun say(context: Context, sentence: String) {
        Toast.makeText(context, sentence, Toast.LENGTH_SHORT).show()
    }
}

/**
 * A pixel panel: square corners notched by one block, a 2-block border and a hard drop shadow.
 * Drawn with plain rects, so it's cheap and stays crisp at any size.
 */
class PanelDrawable(context: Context, var fill: Int, var border: Int, private val shadow: Boolean = true) : Drawable() {
    private val px = Pixel.dp(context, 2f)
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right - if (shadow) px else 0f
        val bt = b.bottom - if (shadow) px else 0f
        if (shadow) {
            paint.color = Pixel.SHADOW
            notched(canvas, l + px, t + px, r + px, bt + px)
        }
        paint.color = border
        notched(canvas, l, t, r, bt)
        paint.color = fill
        notched(canvas, l + px, t + px, r - px, bt - px)
    }

    /** A rectangle with each corner block cut out. */
    private fun notched(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l + px, t, r - px, b, paint)
        c.drawRect(l, t + px, r, b - px, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Mix [b] into [a] by [t] (0 = all a, 1 = all b). */
fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/**
 * Motion curves. The art is pixel; the movement is smooth. Things arriving start fast and
 * settle gently, things leaving ease out of the way, and anything already moving keeps going.
 */
object Ease {
    /** Arriving on screen: quick start, long soft landing. */
    val enter by lazy { PathInterpolator(0.05f, 0.7f, 0.1f, 1f) }

    /** Leaving: gets out of the way without a jolt. */
    val exit by lazy { PathInterpolator(0.3f, 0f, 0.8f, 0.15f) }

    /** Moving from one place to another, or finishing a gesture the finger started. */
    val move by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }

    /** How long to finish a gesture: shorter when the finger was fast or there's little left to go. */
    fun finishDuration(remainingPx: Float, totalPx: Float, velocityPxPerSec: Float): Long {
        val byDistance = 300f * (remainingPx / totalPx.coerceAtLeast(1f))
        val byVelocity = if (velocityPxPerSec > 1f) remainingPx / velocityPxPerSec * 1000f * 1.6f else Float.MAX_VALUE
        return minOf(byDistance, byVelocity).coerceIn(120f, 320f).toLong()
    }
}
