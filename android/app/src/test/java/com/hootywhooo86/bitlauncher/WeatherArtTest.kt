package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherArtTest {
    private fun shot(v: WeatherArtView): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        b.eraseColor(Pixel.PANEL)
        v.draw(Canvas(b))
        return b
    }

    @Test
    fun everyWeatherMovesFromFrameToFrame() {
        val ctx = RuntimeEnvironment.getApplication()
        val cases = listOf(
            Triple(Sky.FOG, false, 5.0), Triple(Sky.CLOUDY, false, 2.0), Triple(Sky.CLOUDY, false, 20.0), Triple(Sky.RAIN, false, 10.0),
            Triple(Sky.SNOW, false, 5.0), Triple(Sky.STORM, false, 30.0), Triple(Sky.CLEAR, false, 0.0), Triple(Sky.CLEAR, true, 0.0),
        )
        val w = 1000
        val h = 282
        val sheet = Bitmap.createBitmap(w * 2 + 30, (h + 20) * cases.size + 20, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Pixel.BG)
        val c = Canvas(sheet)
        cases.forEachIndexed { row, (sky, night, wind) ->
            val v = WeatherArtView(ctx).apply { this.sky = sky; this.night = night; windKmh = wind; overcast = sky == Sky.CLOUDY && wind < 5 }
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val a = shot(v)
            v.advance(6)
            val b = shot(v)
            assertFalse("$sky should move", a.sameAs(b))
            c.drawBitmap(a, 10f, 20f + row * (h + 20), null)
            c.drawBitmap(b, 20f + w, 20f + row * (h + 20), null)
        }
        System.getenv("SHOT_DIR")?.let { dir -> File(dir, "weather-art.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun windComesFromTheForecast() {
        val json = """{"utc_offset_seconds":0,"current":{"temperature_2m":-1,"weather_code":45,"wind_speed_10m":18.5},
            "daily":{"temperature_2m_max":[3],"temperature_2m_min":[-1],"sunrise":["2026-11-15T08:58"],"sunset":["2026-11-15T17:36"]}}"""
        assertEquals(18.5, WeatherCodes.parse(json, 0L).windKmh, 0.01)
    }
}
