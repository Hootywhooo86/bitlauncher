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
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** The family look: navy, ink, muted grey, one accent the user picks. Colour only for meaning. */
object Pixel {
    const val BG = 0xFF0B0B16.toInt()
    const val PANEL = 0xFF141428.toInt()
    const val INK = 0xFFF4F1FF.toInt()
    const val MUTED = 0xFF9A97B8.toInt()
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

fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
