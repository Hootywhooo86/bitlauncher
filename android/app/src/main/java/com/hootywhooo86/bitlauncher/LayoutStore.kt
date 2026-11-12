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
        /** A backup is the layout plus settings in one file the user keeps. */
        fun backup(layout: HomeLayout, prefs: Prefs): String = JSONObject()
            .put("app", "bitlauncher")
            .put("backup", 1)
            .put("layout", layout.toJson())
            .put("settings", prefs.toJson())
            .toString(2)

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
