package com.hootywhooo86.bitlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User settings. Small and flat, so a backup is one JSON object. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var grid: GridSize
        get() = GridSize.fromKey(sp.getString("grid", null))
        set(v) = sp.edit().putString("grid", v.key).apply()

    /** 0 small, 1 medium, 2 large. */
    var iconSize: Int
        get() = sp.getInt("iconSize", 1)
        set(v) = sp.edit().putInt("iconSize", v.coerceIn(0, 2)).apply()

    var labels: Boolean
        get() = sp.getBoolean("labels", true)
        set(v) = sp.edit().putBoolean("labels", v).apply()

    var accent: Int
        get() = sp.getInt("accent", Pixel.ACCENTS[0])
        set(v) = sp.edit().putInt("accent", v).apply()

    /** Open the keyboard as soon as the app drawer opens. */
    var drawerKeyboard: Boolean
        get() = sp.getBoolean("drawerKeyboard", false)
        set(v) = sp.edit().putBoolean("drawerKeyboard", v).apply()

    /** Animations. The phone's own "remove animations" turns them off too. */
    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()

    /** The pixel clock above the home grid. */
    var homeClock: Boolean
        get() = sp.getBoolean("homeClock", true)
        set(v) = sp.edit().putBoolean("homeClock", v).apply()

    /** Notification dots on home icons (needs notification access). */
    var dots: Boolean
        get() = sp.getBoolean("dots", true)
        set(v) = sp.edit().putBoolean("dots", v).apply()

    /** The pixel lock screen, shown in front of the system one. Off until the user turns it on. */
    var lockScreen: Boolean
        get() = sp.getBoolean("lockScreen", false)
        set(v) = sp.edit().putBoolean("lockScreen", v).apply()

    /** What notifications show on the lock screen: 0 app names only, 1 titles, 2 full text. */
    var lockDetail: Int
        get() = sp.getInt("lockDetail", 0)
        set(v) = sp.edit().putInt("lockDetail", v.coerceIn(0, 2)).apply()

    /** Set when the phone stopped the lock screen from opening, so Settings can say so. */
    var lockBlocked: Boolean
        get() = sp.getBoolean("lockBlocked", false)
        set(v) = sp.edit().putBoolean("lockBlocked", v).apply()

    /** Drawer search also looks through contacts (needs the contacts permission). */
    var searchContacts: Boolean
        get() = sp.getBoolean("searchContacts", false)
        set(v) = sp.edit().putBoolean("searchContacts", v).apply()

    /** Double-tap empty home space to lock (needs the accessibility service turned on). */
    var doubleTapLock: Boolean
        get() = sp.getBoolean("doubleTapLock", false)
        set(v) = sp.edit().putBoolean("doubleTapLock", v).apply()

        /** Colour theme key (see Pixel.THEMES). */
    var theme: String
        get() = sp.getString("theme", "default")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Package of the icon pack in use, or empty for the apps' own icons. */
    var iconPack: String
        get() = sp.getString("iconPack", "")!!
        set(v) = sp.edit().putString("iconPack", v).apply()

    /** Names you gave apps, by app id. */
    var renamed: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString("renamed", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) = sp.edit().putString("renamed", JSONObject(v as Map<*, *>).toString()).apply()

        /** Pixel scene behind home (see SceneView.SCENES); "none" shows the phone's wallpaper. */
    var scene: String
        get() = sp.getString("scene", "none")!!
        set(v) = sp.edit().putString("scene", v).apply()

    /** Live weather (Open-Meteo, rough location). Off until turned on. */
    var weather: Boolean
        get() = sp.getBoolean("weather", false)
        set(v) = sp.edit().putBoolean("weather", v).apply()

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", WeatherCodes.usesFahrenheit())
        set(v) = sp.edit().putBoolean("fahrenheit", v).apply()

        /** Widgets on the Today page, top to bottom: id, provider, height in dp. */
    var todayWidgets: List<Triple<Int, String, Int>>
        get() = try {
            val a = JSONArray(sp.getString("todayWidgets", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Triple(it.getInt("id"), it.getString("provider"), it.getInt("h")) } }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("todayWidgets", JSONArray().also { a ->
            v.forEach { (id, provider, h) -> a.put(JSONObject().put("id", id).put("provider", provider).put("h", h)) }
        }.toString()).apply()

        /** Which layout is showing: "home" or "work". */
    var mode: String
        get() = sp.getString("mode", Shift.HOME)!!
        set(v) = sp.edit().putString("mode", v).apply()

    /** A hand (or family app) switch holds until this time; then the schedule decides again. */
    var modeHeldUntil: Long
        get() = sp.getLong("modeHeldUntil", 0L)
        set(v) = sp.edit().putLong("modeHeldUntil", v).apply()

    var shiftSchedule: Boolean
        get() = sp.getBoolean("shiftSchedule", false)
        set(v) = sp.edit().putBoolean("shiftSchedule", v).apply()

    /** Work days, as Calendar.SUNDAY..SATURDAY. Monday to Friday until changed. */
    var shiftDays: Set<Int>
        get() = sp.getStringSet("shiftDays", setOf("2", "3", "4", "5", "6"))!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("shiftDays", v.map { it.toString() }.toSet()).apply()

    /** Minutes after midnight. */
    var shiftStart: Int
        get() = sp.getInt("shiftStart", 9 * 60)
        set(v) = sp.edit().putInt("shiftStart", v).apply()

    var shiftEnd: Int
        get() = sp.getInt("shiftEnd", 17 * 60)
        set(v) = sp.edit().putInt("shiftEnd", v).apply()

        /** Set when something outside home (a pinned shortcut) changed the saved layout. */
    var layoutChanged: Long
        get() = sp.getLong("layoutChanged", 0L)
        set(v) = sp.edit().putLong("layoutChanged", v).apply()

        /** App ids hidden from the drawer. */
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    /** Bumped on every change so home knows to redraw when you come back from Settings. */
    val version: Int get() = sp.getInt("version", 0)

    fun touch() = sp.edit().putInt("version", version + 1).apply()

    /** Icon size in dp for the setting. */
    fun iconDp(): Int = when (iconSize) {
        0 -> 40
        2 -> 60
        else -> 50
    }

    fun toJson(): JSONObject = JSONObject()
        .put("iconSize", iconSize)
        .put("labels", labels)
        .put("accent", accent)
        .put("drawerKeyboard", drawerKeyboard)
        .put("animations", animations)
        .put("homeClock", homeClock)
        .put("dots", dots)
        .put("lockScreen", lockScreen)
        .put("lockDetail", lockDetail)
        .put("hidden", JSONArray(hidden.toList()))
        .put("theme", theme)
        .put("shiftSchedule", shiftSchedule)
        .put("shiftDays", JSONArray(shiftDays.toList()))
        .put("shiftStart", shiftStart)
        .put("shiftEnd", shiftEnd)
        .put("scene", scene)
        .put("weather", weather)
        .put("fahrenheit", fahrenheit)
        .put("iconPack", iconPack)
        .put("renamed", JSONObject(renamed as Map<*, *>))

    fun restore(o: JSONObject) {
        iconSize = o.optInt("iconSize", iconSize)
        labels = o.optBoolean("labels", labels)
        accent = o.optInt("accent", accent)
        drawerKeyboard = o.optBoolean("drawerKeyboard", drawerKeyboard)
        animations = o.optBoolean("animations", animations)
        homeClock = o.optBoolean("homeClock", homeClock)
        dots = o.optBoolean("dots", dots)
        lockScreen = o.optBoolean("lockScreen", lockScreen)
        lockDetail = o.optInt("lockDetail", lockDetail)
        theme = o.optString("theme", theme)
        shiftSchedule = o.optBoolean("shiftSchedule", shiftSchedule)
        o.optJSONArray("shiftDays")?.let { a -> shiftDays = (0 until a.length()).map { a.getInt(it) }.toSet() }
        shiftStart = o.optInt("shiftStart", shiftStart)
        shiftEnd = o.optInt("shiftEnd", shiftEnd)
        scene = o.optString("scene", scene)
        weather = o.optBoolean("weather", weather)
        fahrenheit = o.optBoolean("fahrenheit", fahrenheit)
        iconPack = o.optString("iconPack", iconPack)
        o.optJSONObject("renamed")?.let { r -> renamed = r.keys().asSequence().associateWith { r.getString(it) } }
        o.optJSONArray("hidden")?.let { a -> hidden = (0 until a.length()).map { a.getString(it) }.toSet() }
        touch()
    }
}
