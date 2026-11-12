package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenTest {
    private fun hasText(v: View, text: String): Boolean {
        if (v is android.widget.TextView && v.visibility == View.VISIBLE && v.text.toString().contains(text)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), text)) return true
        return false
    }

    @Test
    fun showsSceneAndWeatherThenDissolvesOnUnlock() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).apply { scene = "rockies"; weather = true; fahrenheit = false }
        File(ctx.filesDir, "weather.json").writeText(org.json.JSONObject()
            .put("raw", """{"utc_offset_seconds":0,"current":{"temperature_2m":-6,"apparent_temperature":-11,"weather_code":73},
                "daily":{"temperature_2m_max":[-2],"temperature_2m_min":[-12],"sunrise":["2026-11-12T08:05"],"sunset":["2026-11-12T16:40"]}}""")
            .put("at", System.currentTimeMillis()).put("place", "").toString())

        val controller = Robolectric.buildActivity(LockActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        val lock = controller.get()
        val root = lock.window.decorView
        assertTrue(hasText(root, "-6° SNOW"))

        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { root.draw(it) }
            File(dir, "lock2.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // Unlocked with the fingerprint sensor: it dissolves away and finishes.
        lock.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200))
        assertTrue(lock.isFinishing)
        Prefs(ctx).apply { scene = "none"; weather = false }
    }

    @Test
    fun unlockDissolveIsSlowEnoughToSee() {
        // The test phone runs animations instantly, so check the setting itself: most of a second.
        assertTrue(DissolveView.UNLOCK_MS in 700L..1000L)
    }

    @Test
    fun eventNamesStayPrivateByDefault() {
        // App-names privacy (the default) never shows what the next event is called.
        assertFalse(Prefs(RuntimeEnvironment.getApplication()).lockDetail > 0)
    }
}
