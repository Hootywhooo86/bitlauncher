package com.hootywhooo86.bitlauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.WindowManager
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Puts the pixel scene on the phone's own lock screen wallpaper, so the system lock screen
 * matches the pixel one. Only the lock screen wallpaper: the home wallpaper is left alone.
 * Redrawn only when what it shows changes (scene, time of day, weather), never on a timer.
 */
object LockWallpaper {
    private val worker = Executors.newSingleThreadExecutor()

    /** One still frame of [scene], [w] by [h] pixels, as home would draw it on its first page. */
    fun render(scene: String, w: Int, h: Int, light: Light, sky: Sky?): Bitmap {
        val block = max(2, Math.round(w / 160f))
        val vw = (w + block - 1) / block
        val vh = (h + block - 1) / block
        val small = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.drawBitmap(Bitmap.createBitmap(Painter.sky(vw, vh, light, sky), vw, vh, Bitmap.Config.ARGB_8888), 0f, 0f, null)
        Painter.layers(scene, vw, vh, light, floatArrayOf(0.12f, 0.3f, 0.6f), 1).forEach { (lw, px) ->
            c.drawBitmap(Bitmap.createBitmap(px, lw, vh, Bitmap.Config.ARGB_8888), 0f, 0f, null)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            scale(block.toFloat(), block.toFloat())
            drawBitmap(small, 0f, 0f, Paint().apply { isFilterBitmap = false })
        }
        small.recycle()
        return out
    }

    /** What the wallpaper would show now; it's redrawn only when this changes. */
    fun key(prefs: Prefs, light: Light, sky: Sky?): String =
        "${prefs.scene}|${prefs.sceneTime}|$sky|${Math.round(light.day * 4)}|${Math.round(light.warm * 2)}"

    private fun sp(context: Context) = context.getSharedPreferences("lockWallpaper", Context.MODE_PRIVATE)

    /** Bring the lock screen wallpaper up to date if it's on and something changed. Never blocks the caller. */
    fun sync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.lockWallpaper || prefs.scene == "none") return
        val cached = if (prefs.weather) WeatherClient(app).cached() else null
        val sky = cached?.sky
        val light = Light.fixed(prefs.sceneTime) ?: Light.at(System.currentTimeMillis(), cached?.sunrise, cached?.sunset)
        val key = key(prefs, light, sky)
        if (!force && sp(app).getString("key", null) == key) return
        sp(app).edit().putString("key", key).apply()
        val scene = prefs.scene
        worker.execute {
            try {
                // The biggest the screen gets (a Fold unfolded), so it's never blurry.
                val bounds = app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                val bmp = render(scene, bounds.width(), bounds.height(), light, sky)
                WallpaperManager.getInstance(app).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                bmp.recycle()
            } catch (e: Exception) {
                sp(app).edit().remove("key").apply() // try again next time
            }
        }
    }

    /** Turned off: hand the lock screen back to the phone's own wallpaper. */
    fun clear(context: Context) {
        val app = context.applicationContext
        sp(app).edit().remove("key").apply()
        worker.execute {
            try {
                WallpaperManager.getInstance(app).clear(WallpaperManager.FLAG_LOCK)
            } catch (e: Exception) {
            }
        }
    }
}
