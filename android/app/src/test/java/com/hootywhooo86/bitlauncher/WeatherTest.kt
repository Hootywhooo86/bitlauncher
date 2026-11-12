package com.hootywhooo86.bitlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherTest {
    private val sample = """
        {"latitude":53.7,"longitude":-113.2,"utc_offset_seconds":-25200,
         "current":{"time":"2026-11-12T14:30","temperature_2m":8.2,"apparent_temperature":5.9,"weather_code":3},
         "daily":{"time":["2026-11-12"],"weather_code":[3],"temperature_2m_max":[8.6],"temperature_2m_min":[-1.4],
                  "sunrise":["2026-11-12T08:05"],"sunset":["2026-11-12T16:40"]}}
    """.trimIndent()

    @Test
    fun readsOpenMeteo() {
        val w = WeatherCodes.parse(sample, now = 1L, place = "Fort Saskatchewan")
        assertEquals(8.2, w.tempC, 0.01)
        assertEquals(Sky.CLOUDY, w.sky)
        assertEquals("Overcast", WeatherCodes.describe(w.code))
        assertEquals(-1.4, w.lowC, 0.01)
        // 08:05 at UTC-7 is 15:05 UTC.
        val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = w.sunrise }
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, c.get(Calendar.MINUTE))
    }

    @Test
    fun codesAndUnits() {
        assertEquals(Sky.SNOW, WeatherCodes.sky(73))
        assertEquals(Sky.RAIN, WeatherCodes.sky(81))
        assertEquals(Sky.STORM, WeatherCodes.sky(95))
        assertEquals("8°", WeatherCodes.temp(8.2, fahrenheit = false))
        assertEquals("47°", WeatherCodes.temp(8.2, fahrenheit = true))
        assertTrue(WeatherCodes.usesFahrenheit(Locale.US))
        assertTrue(!WeatherCodes.usesFahrenheit(Locale.CANADA))
    }

    @Test
    fun skyFollowsTheSun() {
        fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }.timeInMillis
        val rise = at(8, 0)
        val set = at(17, 0)
        assertEquals(1f, Light.at(at(12, 30), rise, set).day, 0.01f)
        assertEquals(0f, Light.at(at(23, 0), rise, set).day, 0.01f)
        val dusk = Light.at(at(17, 0), rise, set)
        assertTrue(dusk.day in 0.3f..0.7f)
        assertTrue(dusk.warm > 0.9f)
    }

    @Test
    fun railwaySceneHasItsLayersAndATrainFitsOnTheTrack() {
        val layers = Painter.layers("railway", 160, 340, Light(1f, 0f, 0.5f, 0f), floatArrayOf(0.12f, 0.3f, 0.6f), 8)
        assertEquals(3, layers.size)
        layers.forEach { (_, px) -> assertTrue(px.any { it != 0 }) }
        assertEquals(98, TrainArt.length(6)) // six boxcars and the diesel
        assertTrue(SceneView.SCENES.any { it.first == "railway" })
    }
}
