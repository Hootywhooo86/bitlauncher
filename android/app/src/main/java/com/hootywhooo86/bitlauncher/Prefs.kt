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
        .put("hidden", JSONArray(hidden.toList()))

    fun restore(o: JSONObject) {
        iconSize = o.optInt("iconSize", iconSize)
        labels = o.optBoolean("labels", labels)
        accent = o.optInt("accent", accent)
        drawerKeyboard = o.optBoolean("drawerKeyboard", drawerKeyboard)
        o.optJSONArray("hidden")?.let { a -> hidden = (0 until a.length()).map { a.getString(it) }.toSet() }
        touch()
    }
}
