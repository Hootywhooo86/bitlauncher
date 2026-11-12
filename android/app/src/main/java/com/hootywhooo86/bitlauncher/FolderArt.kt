package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Folder icons, drawn on the same 32x32 grid as app sprites so they're just as crisp.
 * Mini app sprites inside are drawn at 16 or 8 grid pixels: exactly half or a quarter of a
 * sprite, so every pixel stays square.
 */
object FolderArt {
    /** (key, name), in the order the menu offers them. */
    val STYLES = listOf(
        "grid" to "Grid",
        "cover" to "Big cover",
        "chest" to "Treasure chest",
        "bag" to "Inventory bag",
        "cartridge" to "Game cartridge",
        "stack" to "Card stack",
        "floppy" to "Floppy disk",
        "tv" to "CRT TV",
    )

    private val p = Paint().apply { isAntiAlias = false }
    private val sprite = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = RectF()

    /** Everything a folder icon needs to draw itself. */
    class Look(
        val style: String,
        val sprites: List<Bitmap?>,
        val cover: Bitmap?,
        val tint: Int?,
        /** For the TV: which app is on screen. */
        val channel: Int = 0,
        /** For the chest: 0 shut, 1 lid fully up. */
        val lid: Float = 0f,
    )

    /**
     * Draw [look] into [r] (a square whose side is a whole multiple of 32). Returns false for
     * "grid", which the icon view draws itself as before.
     */
    fun draw(c: Canvas, r: Rect, look: Look): Boolean {
        val u = r.width() / 32f
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
            p.color = color
            c.drawRect(r.left + x0 * u, r.top + y0 * u, r.left + x1 * u, r.top + y1 * u, p)
        }
        fun app(i: Int, x: Float, y: Float, size: Float) {
            val bmp = look.sprites.getOrNull(i) ?: return
            dst.set(r.left + x * u, r.top + y * u, r.left + (x + size) * u, r.top + (y + size) * u)
            c.drawBitmap(bmp, null, dst, sprite)
        }
        val outline = Pixel.SHADOW
        when (look.style) {
            "cover" -> {
                val bmp = look.cover ?: look.sprites.firstOrNull() ?: return true
                dst.set(r)
                c.drawBitmap(bmp, null, dst, sprite)
                // A little folder tab on the corner says "there's more inside".
                rect(22f, 24f, 31f, 31f, outline)
                rect(23f, 25f, 30f, 30f, look.tint ?: Pixel.MUTED)
                rect(24f, 26f, 29f, 27f, Pixel.PANEL)
                rect(24f, 28f, 29f, 29f, Pixel.PANEL)
            }
            "chest" -> {
                val wood = look.tint ?: 0xFF9A5A2C.toInt()
                val dark = blend(wood, 0xFF000000.toInt(), 0.35f)
                val gold = 0xFFF2C14E.toInt()
                // An app peeking out, behind the lid.
                val lift = look.lid * 7f
                app(0, 8f, 3f - lift * 0.4f, 16f)
                // Body.
                rect(3f, 17f, 29f, 30f, outline)
                rect(4f, 18f, 28f, 29f, wood)
                rect(4f, 22f, 28f, 23f, dark)
                rect(4f, 26f, 28f, 27f, dark)
                // Lid: lifts on open.
                rect(3f, 11f - lift, 29f, 18f - lift, outline)
                rect(4f, 12f - lift, 28f, 17f - lift, wood)
                rect(4f, 12f - lift, 28f, 13f - lift, blend(wood, 0xFFFFFFFF.toInt(), 0.2f))
                // Gold bands and the lock.
                rect(7f, 12f - lift, 9f, 17f - lift, gold)
                rect(23f, 12f - lift, 25f, 17f - lift, gold)
                rect(7f, 18f, 9f, 29f, gold)
                rect(23f, 18f, 25f, 29f, gold)
                rect(14f, 16f, 18f, 21f, outline)
                rect(15f, 17f, 17f, 20f, gold)
            }
            "bag" -> {
                val cloth = look.tint ?: 0xFF7B5E3B.toInt()
                val shade = blend(cloth, 0xFF000000.toInt(), 0.3f)
                // Two apps sticking out of the top.
                app(0, 3f, 1f, 16f)
                app(1, 13f, 0f, 16f)
                // The sack: wide at the bottom, gathered at the neck.
                rect(4f, 11f, 28f, 14f, outline)
                rect(5f, 12f, 27f, 13f, shade)
                rect(6f, 14f, 26f, 15f, outline)
                rect(3f, 15f, 29f, 31f, outline)
                rect(4f, 15f, 28f, 30f, cloth)
                rect(2f, 19f, 30f, 28f, outline)
                rect(3f, 19f, 29f, 28f, cloth)
                rect(4f, 27f, 28f, 30f, shade)
                // Drawstring and a patch.
                rect(9f, 13f, 23f, 14f, 0xFFE8D9B5.toInt())
                rect(12f, 20f, 20f, 25f, blend(cloth, 0xFFFFFFFF.toInt(), 0.25f))
                rect(13f, 21f, 19f, 24f, shade)
            }
            "cartridge" -> {
                val shell = look.tint ?: 0xFF8C8C9A.toInt()
                val shellDark = blend(shell, 0xFF000000.toInt(), 0.3f)
                rect(3f, 1f, 29f, 31f, outline)
                rect(4f, 2f, 28f, 30f, shell)
                // Grip ridges along the top, and the notched corner.
                for (x in 6 until 26 step 3) rect(x.toFloat(), 3f, x + 2f, 6f, shellDark)
                // The label, with four tiny apps on its art.
                rect(6f, 8f, 26f, 28f, outline)
                rect(7f, 9f, 25f, 27f, 0xFFF1E9D2.toInt())
                app(0, 8f, 10f, 8f)
                app(1, 16f, 10f, 8f)
                app(2, 8f, 18f, 8f)
                app(3, 16f, 18f, 8f)
                rect(4f, 29f, 28f, 30f, shellDark)
            }
            "stack" -> {
                // Three apps fanned like cards, the first on top.
                for ((i, at) in listOf(2 to 14f, 1 to 8f, 0 to 2f)) {
                    val bmp = look.sprites.getOrNull(i)
                    if (bmp == null) {
                        rect(at, at, at + 16f, at + 16f, Pixel.PANEL)
                    } else {
                        app(i, at, at, 16f)
                    }
                }
            }
            "floppy" -> {
                val body = look.tint ?: 0xFF2E4A8A.toInt()
                rect(2f, 2f, 30f, 30f, outline)
                rect(3f, 3f, 29f, 29f, body)
                rect(25f, 3f, 29f, 7f, outline) // the write-protect corner
                // Metal shutter with its window.
                rect(9f, 3f, 23f, 12f, 0xFFB9BDC7.toInt())
                rect(17f, 4f, 21f, 11f, outline)
                // Label, with two tiny apps and ruled lines.
                rect(6f, 15f, 26f, 29f, 0xFFF4F1E6.toInt())
                app(0, 7f, 16f, 8f)
                app(1, 16f, 16f, 8f)
                rect(7f, 25f, 25f, 26f, 0xFFB8B2C8.toInt())
                rect(7f, 27f, 25f, 28f, 0xFFB8B2C8.toInt())
            }
            "tv" -> {
                val case = look.tint ?: 0xFF6B4A33.toInt()
                // Rabbit-ear antenna.
                for (i in 0 until 6) {
                    rect(15f - i, 6f - i, 16f - i, 7f - i, outline)
                    rect(16f + i, 6f - i, 17f + i, 7f - i, outline)
                }
                rect(2f, 7f, 30f, 30f, outline)
                rect(3f, 8f, 29f, 29f, case)
                // The screen, showing one app at a time.
                rect(4f, 10f, 23f, 27f, outline)
                rect(5f, 11f, 22f, 26f, 0xFF1B2B26.toInt())
                val n = look.sprites.count { it != null }.coerceAtLeast(1)
                app(look.channel % n, 5.5f, 10.5f, 16f)
                // Scanlines over the picture.
                for (y in 12 until 26 step 2) {
                    p.color = withAlpha(0xFF000000.toInt(), 50)
                    c.drawRect(r.left + 5 * u, r.top + y * u, r.left + 22 * u, r.top + y * u + u / 2, p)
                }
                // Dials.
                rect(24f, 12f, 28f, 16f, outline)
                rect(25f, 13f, 27f, 15f, 0xFFD9C27A.toInt())
                rect(24f, 18f, 28f, 22f, outline)
                rect(25f, 19f, 27f, 21f, 0xFFD9C27A.toInt())
                rect(4f, 29f, 8f, 31f, outline)
                rect(24f, 29f, 28f, 31f, outline)
            }
            else -> return false
        }
        return true
    }
}
