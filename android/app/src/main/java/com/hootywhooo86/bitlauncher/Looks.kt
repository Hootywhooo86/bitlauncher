package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Dock styles: what the dock sits on. */
object DockStyles {
    /** (key, name). */
    val ALL = listOf(
        "panel" to "Pixel panel",
        "none" to "Nothing (icons float)",
        "glass" to "Glass shelf",
        "wood" to "Wooden shelf",
        "cartridge" to "Game cartridge",
        "rail" to "Railway track",
    )
}

/**
 * The dock's background in each style, drawn from rects in whole blocks. [highlight] lights the
 * edge in the accent while something is being dragged over it.
 */
class DockDrawable(context: Context, var style: String) : Drawable() {
    private val b = Pixel.dp(context, 2f)
    private val p = Paint()
    var highlight = false
        set(v) { field = v; invalidateSelf() }
    var accent = Pixel.ACCENTS[0]
    private val panel = PanelDrawable(context, withAlpha(Pixel.PANEL, 200), withAlpha(Pixel.MUTED, 120))

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, bt: Float, color: Int) {
        p.color = color
        c.drawRect(l, t, r, bt, p)
    }

    override fun draw(c: Canvas) {
        val r = bounds
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val R = r.right.toFloat()
        val B = r.bottom.toFloat()
        val edge = if (highlight) accent else null
        when (style) {
            "none" -> if (edge != null) rect(c, l, B - b, R, B, edge)
            "glass" -> {
                // A see-through shelf under the icons: tinted pane, bright top edge, a glint.
                val top = B - b * 7
                rect(c, l + b, top, R - b, B - b, withAlpha(Pixel.INK, 34))
                rect(c, l + b, top, R - b, top + b, edge ?: withAlpha(Pixel.INK, 150))
                rect(c, l + b * 4, top + b * 2, l + b * 14, top + b * 3, withAlpha(Pixel.INK, 90))
                rect(c, l + b * 2, B - b, R, B, withAlpha(Pixel.SHADOW, 160))
            }
            "wood" -> {
                // A plank with grain, on two brackets.
                val top = B - b * 7
                val wood = 0xFF8A5A33.toInt()
                val dark = 0xFF5E3B20.toInt()
                rect(c, l + b * 2, top + b * 2, R, B + 0f, withAlpha(Pixel.SHADOW, 180))
                rect(c, l, top, R - b * 2, B - b * 2, wood)
                rect(c, l, top, R - b * 2, top + b, edge ?: blend(wood, 0xFFFFFFFF.toInt(), 0.25f))
                val grain = (R - l) / 9f
                for (i in 0 until 9) {
                    val gx = l + i * grain + (i % 3) * b
                    rect(c, gx, top + b * 2 + (i % 2) * b, gx + grain * 0.5f, top + b * 3 + (i % 2) * b, dark)
                }
                for (bx in listOf(l + b * 6, R - b * 10)) rect(c, bx, B - b * 2, bx + b * 2, B, dark)
            }
            "cartridge" -> cartridge(c, l, t, R - b * 2, B - b * 2, edge)
            "rail" -> {
                // The icons ride a short stretch of track: ties, then two rails.
                val top = B - b * 6
                var x = l
                while (x < R) {
                    rect(c, x, top + b * 2, x + b * 4, B - b, 0xFF5E3B20.toInt())
                    x += b * 7
                }
                val rail = edge ?: blend(Pixel.MUTED, Pixel.INK, 0.3f)
                rect(c, l, top + b, R, top + b * 2, rail)
                rect(c, l, B - b * 2, R, B - b, rail)
            }
            else -> {
                panel.border = edge ?: withAlpha(Pixel.MUTED, 120)
                panel.bounds = r
                panel.draw(c)
            }
        }
    }

    /**
     * A whole game cartridge lying on its side: grey plastic shell with a cut corner and grip
     * ridges, the icons on its label, and gold contacts along the bottom edge.
     */
    private fun cartridge(c: Canvas, l: Float, t: Float, R: Float, B: Float, edge: Int?) {
        val shell = 0xFF9C9CA8.toInt()
        val light = 0xFFC4C4CE.toInt()
        val dark = 0xFF65656F.toInt()
        val cut = b * 6 // the corner cut, top right
        // Hard shadow, then the shell (stepped corner cut so it stays pixel art).
        rect(c, l + b * 2, t + b * 2, R + b * 2, B + b * 2, withAlpha(Pixel.SHADOW, 200))
        rect(c, l, t, R - cut, B, shell)
        var step = 0f
        while (step < cut) {
            rect(c, R - cut + step, t + step + b, R - cut + step + b, B, shell)
            rect(c, R - cut + step, t + step, R - cut + step + b, t + step + b, dark)
            step += b
        }
        rect(c, R - b, t + cut, R, B, shell)
        // Bevel: light top and left, dark bottom and right.
        rect(c, l, t, R - cut, t + b, light)
        rect(c, l, t, l + b, B, light)
        rect(c, R - b, t + cut, R, B, dark)
        rect(c, l, B - b, R, B, dark)
        // Grip ridges along the top.
        val gl = l + b * 6
        val gr = R - cut - b * 4
        for (k in 0 until 3) {
            val y = t + b * 2 + k * b * 2
            rect(c, gl, y, gr, y + b, dark)
            rect(c, gl, y + b, gr, y + b * 1.5f, light)
        }
        // The label: an accent band across the top, the dark face the icons sit on.
        val ll = l + b * 3
        val lr = R - b * 3
        val lt = t + b * 9
        val lb = B - b * 7
        rect(c, ll - b, lt - b, lr + b, lb + b, dark)
        rect(c, ll, lt, lr, lb, Pixel.PANEL)
        rect(c, ll, lt, lr, lt + b * 2, edge ?: accent)
        rect(c, ll, lt + b * 2, lr, lt + b * 3, withAlpha(Pixel.SHADOW, 160))
        // Gold contacts along the bottom edge.
        val pt = B - b * 5
        rect(c, l + b * 4, pt - b, R - b * 4, B - b, dark)
        var x = l + b * 5
        while (x < R - b * 6) {
            rect(c, x, pt, x + b * 2, B - b, 0xFFD9B44A.toInt())
            rect(c, x, pt, x + b * 2, pt + b, 0xFFF2D27A.toInt())
            x += b * 4
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Extra room a style needs above and below the icons, in dp: (top, bottom). */
        fun room(style: String): Pair<Int, Int> = if (style == "cartridge") 22 to 16 else 6 to 8
    }
}

/** Page transitions when swiping between home pages. */
object PageTransitions {
    /** (key, name). */
    val ALL = listOf("slide" to "Slide", "dissolve" to "Pixel dissolve", "cube" to "Cube", "stack" to "Card stack")

    /**
     * The order blocks appear in for the pixel dissolve: a fixed shuffle of [cols] x [rows]
     * blocks, so the same blocks go first every time and it reads as one pattern.
     */
    fun dissolveOrder(cols: Int, rows: Int): IntArray {
        val n = cols * rows
        val order = IntArray(n) { it }
        val r = java.util.Random(128L)
        for (i in n - 1 downTo 1) {
            val j = r.nextInt(i + 1)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        // rank[block] = when it flips, 0 first.
        val rank = IntArray(n)
        for (i in 0 until n) rank[order[i]] = i
        return rank
    }
}

/** Animated icons: OFF, the app you used last, or every icon now and then. */
object IconMotion {
    val ALL = listOf("off" to "Off", "last" to "App you used last", "all" to "Every icon, now and then")
}
