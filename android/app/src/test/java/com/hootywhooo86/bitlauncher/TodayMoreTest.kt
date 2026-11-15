package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayMoreTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** An Open-Meteo reply with hourly data starting at the current hour (UTC). */
    private fun reply(rainAtHour: Int): String {
        val start = LocalDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0)
        val times = (0 until 12).joinToString(",") { "\"${start.plusHours(it.toLong())}\"" }
        val temps = (0 until 12).joinToString(",") { "${-1 + it % 3}" }
        val codes = (0 until 12).joinToString(",") { if (it == rainAtHour) "61" else "3" }
        val rain = (0 until 12).joinToString(",") { if (it == rainAtHour) "80" else "10" }
        return """{"utc_offset_seconds":0,"current":{"temperature_2m":-1,"apparent_temperature":-4,"weather_code":45},
            "daily":{"temperature_2m_max":[3],"temperature_2m_min":[-1],"sunrise":["2026-11-15T08:58"],"sunset":["2026-11-15T17:36"]},
            "hourly":{"time":[$times],"temperature_2m":[$temps],"weather_code":[$codes],"precipitation_probability":[$rain]}}"""
    }

    @Test
    fun hourlyForecastAndRainWarning() {
        val now = System.currentTimeMillis()
        val w = WeatherCodes.parse(reply(rainAtHour = 3), now)
        assertTrue(w.hours.size >= 6)
        val warn = Hourly.warning(w.hours, now) { "3 PM" }
        assertEquals("Rain likely around 3 PM. Take a jacket.", warn)
        assertNull("dry hours: no warning", Hourly.warning(WeatherCodes.parse(reply(rainAtHour = 11), now).hours, now) { "x" })
        // Old cached readings without hourly data still parse.
        assertTrue(WeatherCodes.parse("""{"utc_offset_seconds":0,"current":{"temperature_2m":1,"weather_code":3},
            "daily":{"temperature_2m_max":[3],"temperature_2m_min":[-1],"sunrise":["2026-11-15T08:58"],"sunset":["2026-11-15T17:36"]}}""", now).hours.isEmpty())
    }

    @Test
    fun glanceOnlySaysWhatsReal() {
        assertNull(Glance.line(listOf(null, "", null)))
        assertEquals("Fog -1°  ·  BBB Catch Up Mon 13:30", Glance.line(listOf("Fog -1°", "BBB Catch Up Mon 13:30", null)))
    }

    @Test
    fun cardOrderMovesAndKeepsNewCards() {
        val prefs = Prefs(ctx)
        prefs.todayOrder = listOf("notes", "weather") // an older order missing some cards
        val order = TodayOrder.order(prefs)
        assertEquals(listOf("notes", "weather"), order.take(2))
        assertEquals(TodayOrder.CARDS.size, order.size)
        TodayOrder.move(prefs, "weather", -1)
        assertEquals(listOf("weather", "notes"), TodayOrder.order(prefs).take(2))
        prefs.todayOrder = emptyList()
    }

    @Test
    fun notesSaveAndComeBackFromABackup() {
        QuickNotes.save(ctx, "eggs\nmilk")
        val prefs = Prefs(ctx)
        val backup = LayoutStore.backup(ctx, prefs)
        QuickNotes.save(ctx, "")
        LayoutStore.restore(ctx, prefs, backup)
        assertEquals("eggs\nmilk", QuickNotes.text(ctx))
        QuickNotes.save(ctx, "")
    }

    @Test
    fun photosArePixelatedSquares() {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val p = OnThisDay.pixelate(b, 48)
        assertEquals(p.width, p.height)
    }

    @Test
    fun todayShowsTheNewCardsInYourOrder() {
        val prefs = Prefs(ctx).apply { weather = true; fahrenheit = false }
        QuickNotes.save(ctx, "Grocery: eggs, milk, coffee")
        File(ctx.filesDir, "weather.json").writeText(org.json.JSONObject()
            .put("raw", reply(rainAtHour = 3)).put("at", System.currentTimeMillis()).put("place", "Fort Saskatchewan").toString())
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = home.window.decorView
        fun <T : View> find(v: View, type: Class<T>): T? {
            if (type.isInstance(v)) return type.cast(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
            return null
        }
        fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
            if (v is android.widget.TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        val ws = find(root, Workspace::class.java)!!
        val today = find(root, TodayView::class.java)!!
        ws.listener!!.onTodayDrag(300f)
        ws.listener!!.onTodayRelease(300f, 4000f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        val shown = texts(today)
        assertTrue(shown.any { it.startsWith("FOG -1°") })
        assertTrue(shown.contains("NOW"))
        assertTrue(shown.any { it.startsWith("Rain likely around") })
        assertTrue(shown.contains("NOTES"))
        assertTrue(shown.contains("Grocery: eggs, milk, coffee"))
        assertTrue(shown.contains("ON THIS DAY"))
        assertTrue(shown.contains("EDIT CARDS"))
        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(Pixel.BG); root.draw(it) }
            File(dir, "today-new.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // The editor: tap EDIT CARDS, hide Notes, tap DONE: Notes is gone, the rest stay.
        fun click(v: View, text: String): Boolean {
            if (v is android.widget.TextView && v.text.toString() == text && v.isClickable) return v.performClick()
            if (v is ViewGroup) for (i in 0 until v.childCount) if (click(v.getChildAt(i), text)) return true
            return false
        }
        assertTrue(click(today, "EDIT CARDS"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(texts(today).contains("DAY AT A GLANCE"))
        // The Notes row's HIDE is the sixth HIDE (cards in default order).
        val hides = mutableListOf<View>()
        fun collect(v: View) {
            if (v is android.widget.TextView && v.text.toString() == "HIDE") hides.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(today)
        hides[TodayOrder.order(prefs).indexOf("notes")].performClick()
        assertTrue(click(today, "DONE"))
        shadowOf(Looper.getMainLooper()).idle()
        val after = texts(today)
        assertFalse(after.contains("NOTES"))
        assertTrue(after.contains("ON THIS DAY"))
        prefs.todayHidden = emptySet()
        prefs.weather = false
        QuickNotes.save(ctx, "")
    }
}
