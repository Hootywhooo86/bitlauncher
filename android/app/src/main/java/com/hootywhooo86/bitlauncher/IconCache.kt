package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.File
import java.security.MessageDigest

/**
 * Turns app icons into 32x32 pixel sprites, once. Memory first, then a small PNG on disk;
 * the expensive step only runs when an app is installed or updated.
 */
class IconCache(context: Context) {
    private val dir = File(context.filesDir, "icons").apply { mkdirs() }
    private val stamps = context.getSharedPreferences("iconStamps", Context.MODE_PRIVATE)
    private val memory = HashMap<String, Bitmap>()

    /** The sprite for [key], or null if we have never seen it. Fast enough for the UI thread. */
    @Synchronized
    fun get(key: AppKey): Bitmap? {
        val id = key.id()
        memory[id]?.let { return it }
        val file = fileFor(id)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)?.also { memory[id] = it }
    }

    /** Make sure the sprite for [key] matches [stamp] (the package's last update). Call off the UI thread. */
    fun ensure(key: AppKey, stamp: Long, icon: () -> Drawable?): Boolean {
        val id = key.id()
        if (stamps.getLong(id, -1) == stamp && fileFor(id).exists()) return false
        val drawable = icon() ?: return false
        val sprite = pixelate(drawable)
        fileFor(id).outputStream().use { sprite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        synchronized(this) { memory[id] = sprite }
        stamps.edit().putLong(id, stamp).apply()
        return true
    }

    /** Forget a package's sprites so they're rebuilt (its icon may have changed). */
    @Synchronized
    fun invalidate(pkg: String) {
        val ids = stamps.all.keys.filter { it.startsWith("$pkg/") }
        val edit = stamps.edit()
        for (id in ids) {
            memory.remove(id)
            fileFor(id).delete()
            edit.remove(id)
        }
        edit.apply()
    }

    private fun fileFor(id: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    companion object {
        const val SIZE = 32
        private const val INNER = 30
        private const val SAMPLE = 3

        /**
         * Draw big, average down to 30x30 in 3x3 blocks, snap alpha to on/off, cut the colour to
         * 8 levels per channel, then add a 1-pixel dark outline so icons read on any wallpaper.
         */
        fun pixelate(drawable: Drawable): Bitmap {
            val big = INNER * SAMPLE
            val src = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, big, big)
            drawable.draw(Canvas(src))
            val pixels = IntArray(big * big)
            src.getPixels(pixels, 0, big, 0, 0, big, big)
            src.recycle()
            return Bitmap.createBitmap(spriteFrom(pixels, big), SIZE, SIZE, Bitmap.Config.ARGB_8888)
        }

        /** The pixel maths, separate from Android graphics so it can be unit tested. [pixels] is big x big ARGB. */
        fun spriteFrom(pixels: IntArray, big: Int): IntArray {
            val inner = big / SAMPLE
            val out = IntArray(SIZE * SIZE)
            for (ty in 0 until inner) for (tx in 0 until inner) {
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (sy in 0 until SAMPLE) for (sx in 0 until SAMPLE) {
                    val p = pixels[(ty * SAMPLE + sy) * big + tx * SAMPLE + sx]
                    val pa = (p ushr 24) and 0xFF
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                }
                val n = SAMPLE * SAMPLE
                if (a / n < 110) continue
                val color = (0xFF shl 24) or (level(r / a) shl 16) or (level(g / a) shl 8) or level(b / a)
                out[(ty + 1) * SIZE + tx + 1] = color
            }
            // Outline: empty pixels touching a filled one.
            val outlined = out.copyOf()
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                if (out[y * SIZE + x] != 0) continue
                val touches = (x > 0 && out[y * SIZE + x - 1] != 0) || (x < SIZE - 1 && out[y * SIZE + x + 1] != 0) ||
                    (y > 0 && out[(y - 1) * SIZE + x] != 0) || (y < SIZE - 1 && out[(y + 1) * SIZE + x] != 0)
                if (touches) outlined[y * SIZE + x] = Pixel.SHADOW
            }
            return outlined
        }

        private fun level(c: Long): Int {
            val step = Math.round(c.coerceIn(0, 255) * 7 / 255.0).toInt()
            return step * 255 / 7
        }
    }
}
