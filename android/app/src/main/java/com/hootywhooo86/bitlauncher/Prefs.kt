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
        o.optJSONArray("hidden")?.let { a -> hidden = (0 until a.length()).map { a.getString(it) }.toSet() }
        touch()
    }
}
