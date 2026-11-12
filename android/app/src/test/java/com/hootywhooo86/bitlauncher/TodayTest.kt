package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Swipe right on the first page: Today slides in with its cards, and swipes back out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayTest {
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    private fun hasText(v: View, text: String): Boolean {
        if (v is android.widget.TextView && v.text.toString().contains(text)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), text)) return true
        return false
    }

    @Test
    fun todaySlidesInWithCardsAndHonestEmptyStates() {
        val ctx = RuntimeEnvironment.getApplication()
        // A real-looking reading in the cache, as if weather were on.
        Prefs(ctx).weather = true
        File(ctx.filesDir, "weather.json").writeText(org.json.JSONObject()
            .put("raw", """{"utc_offset_seconds":-25200,"current":{"temperature_2m":8.2,"apparent_temperature":5.9,"weather_code":3},
                "daily":{"temperature_2m_max":[8.6],"temperature_2m_min":[-1.4],"sunrise":["2026-11-12T08:05"],"sunset":["2026-11-12T16:40"]}}""")
            .put("at", System.currentTimeMillis()).put("place", "Fort Saskatchewan").toString())

        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val root = home.window.decorView
        val ws = find(root, Workspace::class.java)!!
        val today = find(root, TodayView::class.java)!!
        assertEquals(View.GONE, today.visibility)

        ws.listener!!.onTodayDrag(300f)
        ws.listener!!.onTodayRelease(300f, 4000f)
        idle(600)
        assertEquals(View.VISIBLE, today.visibility)
        assertEquals(0f, today.translationX, 0.5f)
        assertTrue(hasText(today, "Overcast"))
        assertTrue(hasText(today, "FORT SASKATCHEWAN"))
        assertTrue(hasText(today, "SHOW MY CALENDAR")) // asks, rather than showing nothing or fake events
        assertTrue(hasText(today, "+ ADD WIDGET"))

        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(0xFF3B2F6B.toInt()); root.draw(it) }
            File(dir, "today.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        today.onRelease!!(today.width * 0.6f, 0f)
        idle(600)
        assertEquals(View.GONE, today.visibility)
        Prefs(ctx).weather = false
    }
}
