package com.hootywhooo86.bitlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What you open from bitlauncher, kept on the phone: how often (to sort folders by use) and
 * the last few (the recent row in the drawer). It only sees launches from the launcher itself,
 * so it needs no special permission and says nothing about the rest of your phone.
 */
class Usage(context: Context) {
    private val sp = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun record(key: AppKey) {
        val counts = counts().toMutableMap()
        counts[key.id()] = (counts[key.id()] ?: 0) + 1
        val recent = recent().filter { it != key }.toMutableList()
        recent.add(0, key)
        sp.edit()
            .putString("counts", JSONObject(counts as Map<*, *>).toString())
            .putString("recent", JSONArray().also { a -> recent.take(MAX_RECENT).forEach { a.put(it.toJson()) } }.toString())
            .apply()
    }

    fun count(key: AppKey): Int = counts()[key.id()] ?: 0

    /** Newest first. */
    fun recent(): List<AppKey> = try {
        val a = JSONArray(sp.getString("recent", "[]"))
        (0 until a.length()).map { AppKey.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun clear() = sp.edit().clear().apply()

    private fun counts(): Map<String, Int> = try {
        val o = JSONObject(sp.getString("counts", "{}")!!)
        o.keys().asSequence().associateWith { o.getInt(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        const val MAX_RECENT = 8
    }
}

/**
 * "NEW" on an icon: installed in the last two days, after bitlauncher's first run, and not yet
 * opened from bitlauncher.
 */
fun newApp(installedAt: Long, firstSeen: Long, now: Long, timesOpened: Int): Boolean =
    installedAt > firstSeen && now - installedAt < 2 * 24 * 3600_000L && timesOpened == 0
