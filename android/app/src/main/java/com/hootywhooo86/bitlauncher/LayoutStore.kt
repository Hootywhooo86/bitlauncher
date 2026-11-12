package com.hootywhooo86.bitlauncher

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Keeps the home layout on disk. Writes go to a temp file and are renamed into place, and the
 * previous good copy is kept, so a crash mid-write can't lose anyone's home screen.
 */
class LayoutStore(context: Context, mode: String = Shift.HOME) {
    // Home keeps its original file name, so layouts from before shift mode carry straight on.
    private val name = if (mode == Shift.HOME) "layout" else "layout.$mode"
    private val file = File(context.filesDir, "$name.json")
    private val previous = File(context.filesDir, "$name.prev.json")

    fun load(grid: GridSize): HomeLayout {
        for (f in listOf(file, previous)) {
            try {
                if (f.exists()) return HomeLayout.fromJson(JSONObject(f.readText())).also { it.resize(grid) }
            } catch (e: Exception) {
                // Corrupt: fall through to the previous copy.
            }
        }
        return HomeLayout.empty(grid)
    }

    /** True if there's no saved layout yet (first run). */
    fun isFirstRun(): Boolean = !file.exists() && !previous.exists()

    fun save(layout: HomeLayout) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(layout.toJson().toString())
        if (file.exists()) file.copyTo(previous, overwrite = true)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** A backup is both layouts (Home and, if you've made one, Work) plus settings. */
        fun backupJson(context: Context, prefs: Prefs): JSONObject {
            val home = LayoutStore(context, Shift.HOME)
            val work = LayoutStore(context, Shift.WORK)
            return JSONObject()
                .put("app", "bitlauncher")
                .put("backup", 1)
                .put("layout", home.load(prefs.grid).toJson())
                .put("settings", prefs.toJson())
                .also { o -> if (!work.isFirstRun()) o.put("work", work.load(prefs.grid).toJson()) }
        }

        fun backup(context: Context, prefs: Prefs): String = backupJson(context, prefs).toString(2)

        /** Put a backup in place: layouts saved, settings applied. Returns how many widgets need adding again. */
        fun restore(context: Context, prefs: Prefs, text: String): Int {
            val (layout, settings) = parseBackup(text)
            val o = JSONObject(text)
            var dropped = dropDeadWidgets(context, layout)
            prefs.grid = layout.grid
            prefs.restore(settings)
            LayoutStore(context, Shift.HOME).save(layout)
            o.optJSONObject("work")?.let {
                val work = HomeLayout.fromJson(it)
                dropped += dropDeadWidgets(context, work)
                LayoutStore(context, Shift.WORK).save(work)
            }
            return dropped
        }

        /** Widget ids only mean something on the phone that made them. Drop the ones that don't exist here. */
        private fun dropDeadWidgets(context: Context, layout: HomeLayout): Int {
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            var dropped = 0
            for (page in layout.pages) {
                val dead = page.filterIsInstance<WidgetItem>().filter { manager.getAppWidgetInfo(it.widgetId) == null }
                dropped += dead.size
                page.removeAll(dead)
            }
            return dropped
        }

        /** Parse a backup. Throws with a readable message if it isn't one. */
        fun parseBackup(text: String): Pair<HomeLayout, JSONObject> {
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("That file isn't a bitlauncher backup.")
            }
            if (o.optString("app") != "bitlauncher" || !o.has("layout")) {
                throw IllegalArgumentException("That file isn't a bitlauncher backup.")
            }
            return HomeLayout.fromJson(o.getJSONObject("layout")) to (o.optJSONObject("settings") ?: JSONObject())
        }
    }
}
