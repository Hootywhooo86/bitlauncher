package com.hootywhooo86.bitlauncher

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The drawer's search does more than filter apps: it answers sums, converts units and finds
 * phone settings. All of it is plain logic here, so it's tested off the phone.
 */
object Search {
    // ---------------------------------------------------------------- maths

    /**
     * A sum like "12*8", "(3+4)/2", "2^10" or "15% of 80". Null if [q] isn't a sum (a bare
     * number isn't one), or can't be worked out.
     */
    fun calculate(q: String): String? {
        val text = q.trim().lowercase()
            .replace('×', '*').replace('x', '*').replace('÷', '/').replace(",", "")
        percentOf.matchEntire(text)?.let { m ->
            val v = m.groupValues[1].toDouble() / 100 * m.groupValues[2].toDouble()
            return format(v)
        }
        if (!text.any { it in "+-*/^%(" } || !text.any { it.isDigit() }) return null
        if (!text.all { it.isDigit() || it in "+-*/^%(). " }) return null
        if (text.trim().removePrefix("-").all { it.isDigit() || it == '.' }) return null
        return try {
            val p = Parser(text.replace(" ", ""))
            val v = p.expression()
            if (!p.done() || v.isNaN() || v.isInfinite()) null else format(v)
        } catch (e: Exception) {
            null
        }
    }

    private val percentOf = Regex("""^\s*(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)\s*$""")

    /** Up to ten significant figures, no trailing zeros, no scientific notation. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** Recursive descent: + - lowest, then * / %, then ^, then unary minus and brackets. */
    private class Parser(val s: String) {
        var i = 0
        fun done() = i == s.length

        fun expression(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                // "50%" on its own means half, not a remainder.
                if (op == '%' && (i == s.length || s[i] in "+-*/)")) {
                    v /= 100
                    continue
                }
                val r = power()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(base, power()) // right-associative: 2^3^2 = 2^9
            }
            return base
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unary()
            }
            return atom()
        }

        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expression()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("unclosed")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("number expected")
            return s.substring(start, i).toDouble()
        }
    }

    // ---------------------------------------------------------------- units

    private class Unit(val kind: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double, val name: String)

    private fun linear(kind: String, factor: Double, name: String) = Unit(kind, { it * factor }, { it / factor }, name)

    /** Every spelling we accept, mapped to its unit. Base units: metre, kilogram, litre, kelvin, km/h. */
    private val units: Map<String, Unit> = buildMap {
        fun add(names: List<String>, u: Unit) = names.forEach { put(it, u) }
        add(listOf("mm", "millimetre", "millimetres", "millimeter", "millimeters"), linear("length", 0.001, "mm"))
        add(listOf("cm", "centimetre", "centimetres", "centimeter", "centimeters"), linear("length", 0.01, "cm"))
        add(listOf("m", "metre", "metres", "meter", "meters"), linear("length", 1.0, "m"))
        add(listOf("km", "kilometre", "kilometres", "kilometer", "kilometers", "kms"), linear("length", 1000.0, "km"))
        add(listOf("in", "inch", "inches", "\""), linear("length", 0.0254, "in"))
        add(listOf("ft", "foot", "feet", "'"), linear("length", 0.3048, "ft"))
        add(listOf("yd", "yard", "yards"), linear("length", 0.9144, "yd"))
        add(listOf("mi", "mile", "miles"), linear("length", 1609.344, "mi"))
        add(listOf("g", "gram", "grams"), linear("mass", 0.001, "g"))
        add(listOf("kg", "kilo", "kilos", "kilogram", "kilograms"), linear("mass", 1.0, "kg"))
        add(listOf("oz", "ounce", "ounces"), linear("mass", 0.028349523125, "oz"))
        add(listOf("lb", "lbs", "pound", "pounds"), linear("mass", 0.45359237, "lb"))
        add(listOf("ml", "millilitre", "millilitres", "milliliter", "milliliters"), linear("volume", 0.001, "ml"))
        add(listOf("l", "litre", "litres", "liter", "liters"), linear("volume", 1.0, "L"))
        add(listOf("cup", "cups"), linear("volume", 0.2365882365, "cups"))
        add(listOf("floz"), linear("volume", 0.0295735295625, "fl oz"))
        add(listOf("gal", "gallon", "gallons"), linear("volume", 3.785411784, "gal"))
        add(listOf("kmh", "km/h", "kph"), linear("speed", 1.0, "km/h"))
        add(listOf("mph"), linear("speed", 1.609344, "mph"))
        add(listOf("c", "°c", "celsius"), Unit("temp", { it + 273.15 }, { it - 273.15 }, "°C"))
        add(listOf("f", "°f", "fahrenheit"), Unit("temp", { (it - 32) * 5 / 9 + 273.15 }, { (it - 273.15) * 9 / 5 + 32 }, "°F"))
        add(listOf("k", "kelvin"), Unit("temp", { it }, { it }, "K"))
    }

    private val conversion = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([^\s\d][^\s]*)\s+(?:to|in|as|=)\s+([^\s]+)\s*$""")

    /** "5 km to mi", "-20 c in f", "150 lb to kg". Null if it isn't a conversion we know. */
    fun convert(q: String): String? {
        val m = conversion.matchEntire(q.trim().lowercase()) ?: return null
        val from = units[m.groupValues[2]] ?: return null
        val to = units[m.groupValues[3]] ?: return null
        if (from.kind != to.kind) return null
        val v = to.fromBase(from.toBase(m.groupValues[1].toDouble()))
        return "${format(Math.round(v * 100) / 100.0)} ${to.name}"
    }

    // ---------------------------------------------------------------- phone settings

    /** A settings screen: what to call it, words that find it, and the Android action that opens it. */
    class SettingsPage(val label: String, val words: List<String>, val action: String)

    val settingsPages = listOf(
        SettingsPage("Wi-Fi", listOf("wifi", "wi-fi", "wireless", "internet"), "android.settings.WIFI_SETTINGS"),
        SettingsPage("Bluetooth", listOf("bluetooth", "headphones", "pair"), "android.settings.BLUETOOTH_SETTINGS"),
        SettingsPage("Mobile network", listOf("mobile", "data", "cellular", "sim", "roaming"), "android.settings.DATA_ROAMING_SETTINGS"),
        SettingsPage("Hotspot & tethering", listOf("hotspot", "tethering", "share internet"), "android.settings.WIRELESS_SETTINGS"),
        SettingsPage("Airplane mode", listOf("airplane", "aeroplane", "flight"), "android.settings.AIRPLANE_MODE_SETTINGS"),
        SettingsPage("Display & brightness", listOf("display", "brightness", "dark mode", "dark theme", "screen", "font size"), "android.settings.DISPLAY_SETTINGS"),
        SettingsPage("Sound & vibration", listOf("sound", "volume", "ringtone", "vibration", "vibrate", "do not disturb", "dnd"), "android.settings.SOUND_SETTINGS"),
        SettingsPage("Notifications", listOf("notifications", "alerts"), "android.settings.NOTIFICATION_SETTINGS"),
        SettingsPage("Battery", listOf("battery", "power", "battery saver"), "android.intent.action.POWER_USAGE_SUMMARY"),
        SettingsPage("Location", listOf("location", "gps"), "android.settings.LOCATION_SOURCE_SETTINGS"),
        SettingsPage("Apps", listOf("apps", "applications", "app info", "storage used"), "android.settings.APPLICATION_SETTINGS"),
        SettingsPage("Default apps", listOf("default apps", "home app", "default browser", "launcher"), "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        SettingsPage("Storage", listOf("storage", "space", "memory"), "android.settings.INTERNAL_STORAGE_SETTINGS"),
        SettingsPage("Security & lock screen", listOf("security", "lock screen", "fingerprint", "face unlock", "pin", "password"), "android.settings.SECURITY_SETTINGS"),
        SettingsPage("Accessibility", listOf("accessibility", "talkback", "magnification"), "android.settings.ACCESSIBILITY_SETTINGS"),
        SettingsPage("Date & time", listOf("date", "time", "time zone", "timezone", "clock"), "android.settings.DATE_SETTINGS"),
        SettingsPage("Language & keyboard", listOf("language", "keyboard", "input"), "android.settings.LOCALE_SETTINGS"),
        SettingsPage("NFC & payments", listOf("nfc", "tap to pay", "payments"), "android.settings.NFC_SETTINGS"),
        SettingsPage("Developer options", listOf("developer", "usb debugging"), "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
        SettingsPage("About phone", listOf("about", "phone info", "model", "android version", "build number"), "android.settings.DEVICE_INFO_SETTINGS"),
    )

    /** Settings pages whose words start with (or contain, for 4+ letters) what was typed. */
    fun settings(q: String): List<SettingsPage> {
        val t = searchable(q.trim())
        if (t.length < 2) return emptyList()
        return settingsPages.filter { page ->
            (page.words + page.label.lowercase()).any { w -> w.startsWith(t) || (t.length >= 4 && w.contains(t)) }
        }.take(3)
    }
}
