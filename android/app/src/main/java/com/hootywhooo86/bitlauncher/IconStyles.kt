package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Color
import java.util.WeakHashMap
import kotlin.math.sqrt

/**
 * Icon looks: every app icon reshaped to match. Each look is drawn on the same 32x32 pixel grid
 * as the sprites, so pixels stay the same size everywhere. Results are cached per sprite.
 */
object IconStyles {
    /** (key, name). */
    val ALL = listOf(
        "sprite" to "Plain sprites",
        "block" to "Blocks",
        "coin" to "Coins",
        "cartridge" to "Cartridges",
        "framed" to "Framed",
        "mono" to "Mono",
        "accent" to "Accent tint",
    )

    /** The look in use, set from Prefs when home applies them. */
    @Volatile
    var current = "sprite"

    @Volatile
    var accent = Pixel.ACCENTS[0]

    /** One colour for every tile, or 0 to take each icon's own. */
    @Volatile
    var tile = 0

    private const val S = IconCache.SIZE
    private val cache = WeakHashMap<Bitmap, Pair<String, Bitmap>>()

    /** [sprite] in the current look. */
    fun styled(sprite: Bitmap): Bitmap {
        val look = current
        if (look == "sprite") return sprite
        val key = "$look|$accent|$tile|${Pixel.INK}|${Pixel.BG}"
        synchronized(cache) { cache[sprite]?.let { if (it.first == key) return it.second } }
        val out = Bitmap.createBitmap(render(pixels(sprite), look, accent, tile), S, S, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[sprite] = key to out }
        return out
    }

    private fun pixels(b: Bitmap): IntArray {
        val src = if (b.width == S && b.height == S) b else Bitmap.createScaledBitmap(b, S, S, false)
        return IntArray(S * S).also { src.getPixels(it, 0, S, 0, 0, S, S) }
    }

    /** The pixel maths, on plain arrays so it can be tested. */
    fun render(sprite: IntArray, look: String, accent: Int, tile: Int = 0): IntArray = when (look) {
        "mono" -> tint(sprite, Pixel.INK)
        "accent" -> tint(sprite, accent)
        "block" -> block(sprite, tile)
        "coin" -> coin(sprite, tile)
        "cartridge" -> cartridge(sprite, tile)
        "framed" -> framed(sprite)
        else -> sprite.copyOf()
    }

    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** Every pixel in shades of one colour by its brightness; the outline stays. */
    private fun tint(sprite: IntArray, color: Int): IntArray = IntArray(sprite.size) { i ->
        val p = sprite[i]
        when {
            Color.alpha(p) == 0 -> 0
            p == Pixel.SHADOW -> p
            else -> {
                // Three steps, so it still reads as pixel art.
                val l = lum(p)
                val t = when {
                    l > 0.66f -> 1f
                    l > 0.33f -> 0.72f
                    else -> 0.45f
                }
                blend(Pixel.BG, color, t)
            }
        }
    }

    /** The sprite's own average colour (of its opaque, non-outline pixels). */
    private fun average(sprite: IntArray): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (p in sprite) if (Color.alpha(p) != 0 && p != Pixel.SHADOW) {
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0) return Pixel.MUTED
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * The sprite shrunk to [n]x[n]: nearest pixels so it stays crisp, then a fresh one-pixel
     * outline, since shrinking drops parts of the old one.
     */
    fun shrink(sprite: IntArray, n: Int): IntArray {
        val body = IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            val sx = ((x + 0.5f) * S / n).toInt().coerceIn(0, S - 1)
            val sy = ((y + 0.5f) * S / n).toInt().coerceIn(0, S - 1)
            sprite[sy * S + sx].takeIf { it != Pixel.SHADOW } ?: 0
        }
        val out = body.copyOf()
        for (y in 0 until n) for (x in 0 until n) {
            if (body[y * n + x] != 0) continue
            val touches = (x > 0 && body[y * n + x - 1] != 0) || (x < n - 1 && body[y * n + x + 1] != 0) ||
                (y > 0 && body[(y - 1) * n + x] != 0) || (y < n - 1 && body[(y + 1) * n + x] != 0)
            if (touches) out[y * n + x] = Pixel.SHADOW
        }
        return out
    }

    private fun paste(out: IntArray, small: IntArray, n: Int, ox: Int, oy: Int) {
        for (y in 0 until n) for (x in 0 until n) {
            val p = small[y * n + x]
            if (Color.alpha(p) != 0) out[(oy + y) * S + ox + x] = p
        }
    }

    private fun rect(out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0..y1) for (x in x0..x1) if (x in 0 until S && y in 0 until S) out[y * S + x] = c
    }

    /** A bevelled tile in the icon's own colour, notched corners, the sprite on top. */
    /** The tile colour: the one picked, or the icon's own, darkened. */
    private fun base(sprite: IntArray, picked: Int) = if (picked != 0) picked else blend(average(sprite), Pixel.BG, 0.45f)

    private fun block(sprite: IntArray, picked: Int): IntArray {
        val out = IntArray(S * S)
        val tile = base(sprite, picked)
        rect(out, 1, 0, 30, 31, Pixel.SHADOW)
        rect(out, 0, 1, 31, 30, Pixel.SHADOW)
        rect(out, 2, 1, 29, 30, tile)
        rect(out, 1, 2, 30, 29, tile)
        rect(out, 2, 1, 29, 1, blend(tile, Color.WHITE, 0.3f)) // light top edge
        rect(out, 1, 2, 1, 29, blend(tile, Color.WHITE, 0.18f))
        rect(out, 2, 30, 29, 30, blend(tile, Color.BLACK, 0.35f)) // dark bottom edge
        rect(out, 30, 2, 30, 29, blend(tile, Color.BLACK, 0.25f))
        paste(out, shrink(sprite, 22), 22, 5, 5)
        return out
    }

    /** A round coin: rim, a shine at the top left, the sprite stamped in the middle. */
    private fun coin(sprite: IntArray, picked: Int): IntArray {
        val out = IntArray(S * S)
        val face = base(sprite, picked)
        val rim = blend(face, Color.BLACK, 0.35f)
        val c = 15.5f
        for (y in 0 until S) for (x in 0 until S) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
            out[y * S + x] = when {
                d <= 13.6f -> if ((x - c) + (y - c) < -14f && d > 10.5f) blend(face, Color.WHITE, 0.35f) else face
                d <= 15.2f -> rim
                d <= 16f -> Pixel.SHADOW
                else -> 0
            }
        }
        paste(out, shrink(sprite, 20), 20, 6, 6)
        return out
    }

    /** A game cartridge: grey shell, grip ridges at the top, the sprite on a label. */
    private fun cartridge(sprite: IntArray, picked: Int): IntArray {
        val out = IntArray(S * S)
        val shell = if (picked != 0) picked else blend(Pixel.MUTED, Pixel.INK, 0.2f)
        val dark = blend(shell, Color.BLACK, 0.35f)
        val label = blend(average(sprite), Color.WHITE, 0.45f)
        rect(out, 3, 0, 28, 31, Pixel.SHADOW)
        rect(out, 4, 1, 27, 30, shell)
        // Corner cut at the top right, like a real cartridge.
        rect(out, 25, 0, 28, 2, 0)
        rect(out, 24, 1, 27, 1, Pixel.SHADOW)
        rect(out, 27, 2, 27, 3, Pixel.SHADOW)
        for (y in 2..5 step 2) rect(out, 7, y, 21, y, dark) // grip ridges
        rect(out, 6, 8, 25, 28, Pixel.SHADOW)
        rect(out, 7, 9, 24, 27, label)
        paste(out, shrink(sprite, 16), 16, 8, 10)
        rect(out, 4, 30, 27, 30, dark)
        return out
    }

    /** A picture frame: thick border with corner studs, a dark mat, the sprite inside. */
    private fun framed(sprite: IntArray): IntArray {
        val out = IntArray(S * S)
        val frame = blend(Pixel.MUTED, Pixel.INK, 0.35f)
        rect(out, 0, 0, 31, 31, Pixel.SHADOW)
        rect(out, 1, 1, 30, 30, frame)
        rect(out, 1, 1, 30, 1, blend(frame, Color.WHITE, 0.3f))
        rect(out, 3, 3, 28, 28, Pixel.SHADOW)
        rect(out, 4, 4, 27, 27, Pixel.PANEL)
        for ((x, y) in listOf(1 to 1, 29 to 1, 1 to 29, 29 to 29)) rect(out, x, y, x + 1, y + 1, Pixel.INK)
        paste(out, shrink(sprite, 22), 22, 5, 5)
        return out
    }
}
