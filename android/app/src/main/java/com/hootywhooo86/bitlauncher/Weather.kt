package com.hootywhooo86.bitlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.Executors

/** What the sky is doing, as far as the scene and the cards care. */
enum class Sky { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

/** One reading from Open-Meteo. Temperatures in °C; times in epoch milliseconds. */
data class WeatherNow(
    val tempC: Double,
    val feelsC: Double,
    val code: Int,
    val highC: Double,
    val lowC: Double,
    val sunrise: Long,
    val sunset: Long,
    val fetchedAt: Long,
    val place: String? = null,
) {
    val sky: Sky get() = WeatherCodes.sky(code)
}

/** WMO weather codes, as Open-Meteo reports them. */
object WeatherCodes {
    fun sky(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2, 3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Cloudy"
    }

    /** "8°" in the user's unit. */
    fun temp(c: Double, fahrenheit: Boolean): String =
        "${Math.round(if (fahrenheit) c * 9 / 5 + 32 else c)}°"

    /** Parse Open-Meteo's forecast response (current + daily, timezone=auto). */
    fun parse(json: String, now: Long, place: String? = null): WeatherNow {
        val o = JSONObject(json)
        val offset = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
        val current = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        fun localTime(s: String) = LocalDateTime.parse(s).toEpochSecond(offset) * 1000
        return WeatherNow(
            tempC = current.getDouble("temperature_2m"),
            feelsC = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            code = current.getInt("weather_code"),
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            sunrise = localTime(daily.getJSONArray("sunrise").getString(0)),
            sunset = localTime(daily.getJSONArray("sunset").getString(0)),
            fetchedAt = now,
            place = place,
        )
    }

    /** Fahrenheit for the US and a few others; Celsius for everyone else (Canada included). */
    fun usesFahrenheit(locale: Locale = Locale.getDefault()): Boolean = locale.country in setOf("US", "LR", "MM", "BS", "KY", "PW")
}

/**
 * Weather for the scene, the Today page and the lock screen. Opt-in: it only runs once you turn
 * weather on, and then sends just a rough location to Open-Meteo (free, no account, no key).
 * The last reading is kept on the phone, so no signal just means slightly old weather.
 */
class WeatherClient(private val context: Context) {
    private val file = File(context.filesDir, "weather.json")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Why there's no weather, in one honest sentence; null when there is. */
    var problem: String? = null
        private set

    fun enabled() = Prefs(context).weather

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The last reading, however old. */
    fun cached(): WeatherNow? = try {
        if (!file.exists()) null
        else JSONObject(file.readText()).let { o ->
            WeatherCodes.parse(o.getString("raw"), o.getLong("at"), o.optString("place").takeIf { it.isNotEmpty() })
        }
    } catch (e: Exception) {
        null
    }

    /** Fetch if the reading is older than 30 minutes; [done] runs on the main thread either way. */
    fun refreshIfStale(done: (WeatherNow?) -> Unit) {
        val have = cached()
        if (!enabled()) {
            problem = "Weather is off."
            done(null)
            return
        }
        if (!hasPermission()) {
            problem = "Weather needs location (rough is enough). Turn it on in Settings."
            done(have)
            return
        }
        if (have != null && System.currentTimeMillis() - have.fetchedAt < 30 * 60_000L) {
            problem = null
            done(have)
            return
        }
        worker.execute {
            val result = try {
                val loc = location() ?: throw IllegalStateException("no location")
                val place = placeName(loc)
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, loc.latitude, loc.longitude) +
                        "&current=temperature_2m,apparent_temperature,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                        "&timezone=auto&forecast_days=1",
                )
                val raw = (url.openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000
                    readTimeout = 8000
                    try {
                        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
                        inputStream.bufferedReader().readText()
                    } finally {
                        disconnect()
                    }
                }
                val now = System.currentTimeMillis()
                val reading = WeatherCodes.parse(raw, now, place)
                file.writeText(JSONObject().put("raw", raw).put("at", now).put("place", place ?: "").toString())
                problem = null
                reading
            } catch (e: IllegalStateException) {
                problem = if (e.message == "no location") "Couldn't find where you are yet. Weather will try again." else "Weather service didn't answer. Showing the last reading."
                have
            } catch (e: Exception) {
                problem = "No connection. Showing the last weather reading."
                have
            }
            main.post { done(result) }
        }
    }

    /** The newest location any provider already has. No GPS warm-up: rough is all we need. */
    private fun location(): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val known = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
        if (known != null) return known
        if (Build.VERSION.SDK_INT < 30) return null
        // Nothing cached: ask the network provider once and wait briefly.
        val latch = java.util.concurrent.CountDownLatch(1)
        var got: Location? = null
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.mainExecutor) { l ->
                got = l
                latch.countDown()
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
        return got
    }

    private fun placeName(loc: Location): String? = try {
        @Suppress("DEPRECATION")
        android.location.Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.locality
    } catch (e: Exception) {
        null
    }
}
