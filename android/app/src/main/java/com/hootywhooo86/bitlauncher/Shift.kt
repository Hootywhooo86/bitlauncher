package com.hootywhooo86.bitlauncher

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.util.Calendar

/**
 * Shift mode: two home layouts, Home and Work. Which one shows is decided by, in order: a switch
 * you (or a 128bit app like Railtime) made that still holds, then your schedule, then Home.
 */
object Shift {
    const val HOME = "home"
    const val WORK = "work"

    /** The broadcast a family app sends to switch layouts: extra "mode" = "work" or "home". */
    const val ACTION_SET_MODE = "com.hootywhooo86.bitlauncher.SET_MODE"

    /**
     * Is [now] inside the work schedule? [days] uses Calendar.SUNDAY..SATURDAY; times are minutes
     * after midnight. An end before the start is an overnight shift, counted from the day it starts.
     */
    fun isWorkTime(now: Calendar, days: Set<Int>, start: Int, end: Int): Boolean {
        if (days.isEmpty() || start == end) return false
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = now.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1
        return if (start < end) {
            today in days && minute in start until end
        } else {
            (today in days && minute >= start) || (yesterday in days && minute < end)
        }
    }

    /** The layout that should show right now. */
    fun current(prefs: Prefs, now: Long = System.currentTimeMillis()): String {
        if (now < prefs.modeHeldUntil) return prefs.mode
        if (prefs.shiftSchedule) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            return if (isWorkTime(cal, prefs.shiftDays, prefs.shiftStart, prefs.shiftEnd)) WORK else HOME
        }
        return prefs.mode
    }

    /**
     * Switch by hand (or from a family app). It holds until the schedule next changes over;
     * with no schedule, it simply stays.
     */
    fun hold(prefs: Prefs, mode: String, now: Long = System.currentTimeMillis()) {
        prefs.mode = mode
        prefs.modeHeldUntil = if (!prefs.shiftSchedule) Long.MAX_VALUE else nextChange(prefs, now)
        prefs.touch()
    }

    /** The next minute the schedule flips, looking up to a week ahead. */
    fun nextChange(prefs: Prefs, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val was = isWorkTime(cal, prefs.shiftDays, prefs.shiftStart, prefs.shiftEnd)
        repeat(7 * 24 * 60) {
            cal.add(Calendar.MINUTE, 1)
            if (isWorkTime(cal, prefs.shiftDays, prefs.shiftStart, prefs.shiftEnd) != was) return cal.timeInMillis
        }
        return Long.MAX_VALUE
    }
}

/** Lets a 128bit app (Railtime going on or off duty) switch the layout. Only switches between your own two layouts. */
class ShiftReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Shift.ACTION_SET_MODE) return
        val mode = intent.getStringExtra("mode")?.lowercase()
        if (mode != Shift.HOME && mode != Shift.WORK) return
        val prefs = Prefs(context)
        // A family app's switch holds until it sends the next one.
        prefs.mode = mode
        prefs.modeHeldUntil = Long.MAX_VALUE
        prefs.touch()
    }
}

/**
 * The 128bit family's shared look, for sibling apps' widgets to match the launcher.
 * See docs/FAMILY_THEME.md. Changes are pushed with a broadcast; this is for reading on start.
 */
object FamilyTheme {
    const val ACTION_CHANGED = "app.bit128.THEME_CHANGED"
    const val AUTHORITY = "com.hootywhooo86.bitlauncher.theme"

    /** Tell every installed app that listens for it (explicitly, so Android delivers it). */
    fun broadcast(context: Context) {
        val prefs = Prefs(context)
        val probe = Intent(ACTION_CHANGED)
        val listeners = try {
            context.packageManager.queryBroadcastReceivers(probe, 0).map { it.activityInfo.packageName }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
        for (pkg in listeners) {
            if (pkg == context.packageName) continue
            context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(pkg).apply { putExtras(extras(prefs)) })
        }
    }

    fun extras(prefs: Prefs) = android.os.Bundle().apply {
        putInt("accent", prefs.accent)
        putString("theme", prefs.theme)
        val t = Pixel.themeFor(prefs, prefs.theme)
        putInt("background", t.bg)
        putInt("panel", t.panel)
        putInt("ink", t.ink)
        putInt("muted", t.muted)
    }
}

/** Read-only: one row with the current accent and theme colours. Nothing personal. */
class FamilyThemeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf())
        val b = FamilyTheme.extras(Prefs(ctx))
        val cols = arrayOf("accent", "theme", "background", "panel", "ink", "muted")
        return MatrixCursor(cols).apply {
            addRow(arrayOf<Any?>(b.getInt("accent"), b.getString("theme"), b.getInt("background"), b.getInt("panel"), b.getInt("ink"), b.getInt("muted")))
        }
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.bit128.theme"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
