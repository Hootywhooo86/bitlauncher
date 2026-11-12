package com.hootywhooo86.bitlauncher

import org.json.JSONArray
import org.json.JSONObject

/** The three grid sizes. Columns x rows of the home screen. */
enum class GridSize(val cols: Int, val rows: Int, val key: String) {
    SMALL(4, 5, "4x5"),
    MEDIUM(5, 6, "5x6"),
    LARGE(6, 7, "6x7");

    /** The dock has one slot per column, up to six. */
    val dockSlots: Int get() = minOf(cols, 6)

    companion object {
        fun fromKey(key: String?): GridSize = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** One launchable activity: package, activity class, and the user profile it belongs to. */
data class AppKey(val pkg: String, val cls: String, val user: Long) {
    fun id(): String = "$pkg/$cls/$user"

    fun toJson(): JSONObject = JSONObject().put("pkg", pkg).put("cls", cls).put("user", user)

    companion object {
        fun fromJson(o: JSONObject) = AppKey(o.getString("pkg"), o.getString("cls"), o.optLong("user", 0))
    }
}

sealed class Item {
    abstract var x: Int
    abstract var y: Int
    open val spanX: Int get() = 1
    open val spanY: Int get() = 1

    abstract fun toJson(): JSONObject
}

class AppItem(val app: AppKey, override var x: Int, override var y: Int) : Item() {
    /** Swipe up on the icon runs this shortcut of the app (its id), instead of opening the drawer. */
    var swipe: String? = null

    override fun toJson(): JSONObject = app.toJson().put("t", "app").put("x", x).put("y", y)
        .also { o -> swipe?.let { o.put("swipe", it) } }
}

class FolderItem(var name: String, val apps: MutableList<AppKey>, override var x: Int, override var y: Int) : Item() {
    /** Show this one app as the folder's icon instead of the 2x2 preview. */
    var cover: AppKey? = null

    /** Tint for the folder's frame; null is the plain panel. */
    var color: Int? = null

    /** Labels under the apps inside this folder. */
    var labels = true

    /** How the folder's icon looks (see FolderArt.STYLES). */
    var style = "grid"

    /** Show how many apps are inside, on the icon. */
    var showCount = false

    override fun toJson(): JSONObject = JSONObject()
        .put("t", "folder").put("name", name).put("x", x).put("y", y)
        .put("apps", JSONArray().also { a -> apps.forEach { a.put(it.toJson()) } })
        .put("labels", labels)
        .put("style", style)
        .put("count", showCount)
        .also { o ->
            cover?.let { o.put("cover", it.toJson()) }
            color?.let { o.put("color", it) }
        }

    /** Move the app at [from] to [to], shifting the others along. */
    fun move(from: Int, to: Int) {
        if (from !in apps.indices) return
        val app = apps.removeAt(from)
        apps.add(to.coerceIn(0, apps.size), app)
    }
}

/** A hosted Android widget. [provider] is the flattened ComponentName, kept so a restore can say what was lost. */
class WidgetItem(
    val widgetId: Int,
    val provider: String,
    override var x: Int,
    override var y: Int,
    var w: Int,
    var h: Int,
) : Item() {
    override val spanX: Int get() = w
    override val spanY: Int get() = h

    override fun toJson(): JSONObject = JSONObject()
        .put("t", "widget").put("id", widgetId).put("provider", provider)
        .put("x", x).put("y", y).put("w", w).put("h", h)
}

/**
 * Everything on the home screen: pages of items on a grid, plus the dock.
 * The dock holds apps or folders ([AppItem] / [FolderItem]); an item's x is its dock slot.
 * All placement rules live here so they can be tested off-device.
 */
class HomeLayout(var grid: GridSize, val pages: MutableList<MutableList<Item>>, val dock: MutableList<Item?>) {
    /** Pages (by index) whose icons show no labels. */
    val unlabelledPages: MutableSet<Int> = mutableSetOf()

    init {
        if (pages.isEmpty()) pages.add(mutableListOf())
        fitDock()
    }

    fun cols() = grid.cols
    fun rows() = grid.rows

    fun itemAt(page: Int, x: Int, y: Int): Item? =
        pages.getOrNull(page)?.firstOrNull { x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY }

    /** True if the rectangle is on the grid and empty, ignoring [ignore] (the item being moved). */
    fun isFree(page: Int, x: Int, y: Int, w: Int, h: Int, ignore: Item? = null): Boolean {
        if (x < 0 || y < 0 || x + w > cols() || y + h > rows()) return false
        val items = pages.getOrNull(page) ?: return true
        return items.none { it !== ignore && overlaps(it, x, y, w, h) }
    }

    /** First empty spot for a w x h item on [page], reading left to right, top to bottom. */
    fun findFree(page: Int, w: Int, h: Int, ignore: Item? = null): Pair<Int, Int>? {
        for (y in 0..rows() - h) for (x in 0..cols() - w) {
            if (isFree(page, x, y, w, h, ignore)) return x to y
        }
        return null
    }

    /**
     * Put [item] in the first free spot from [startPage] on, adding a page if every page is full.
     * Returns the page it landed on.
     */
    fun placeAnywhere(item: Item, startPage: Int = 0): Int {
        clampSpan(item)
        var page = startPage.coerceIn(0, pages.size - 1)
        while (true) {
            val spot = findFree(page, item.spanX, item.spanY)
            if (spot != null) {
                item.x = spot.first
                item.y = spot.second
                pages[page].add(item)
                return page
            }
            page++
            if (page == pages.size) pages.add(mutableListOf())
        }
    }

    fun remove(item: Item) {
        pages.forEach { it.remove(item) }
    }

    /** Move a whole page; "labels off" stays with the page it belongs to. */
    fun movePage(from: Int, to: Int) {
        if (from !in pages.indices) return
        val target = to.coerceIn(0, pages.size - 1)
        if (target == from) return
        val unlabelled = pages.indices.map { it in unlabelledPages }.toMutableList()
        pages.add(target, pages.removeAt(from))
        unlabelled.add(target, unlabelled.removeAt(from))
        unlabelledPages.clear()
        unlabelled.forEachIndexed { i, off -> if (off) unlabelledPages.add(i) }
    }

    /** Delete a page and everything on it (always leaving one page). Returns what was on it. */
    fun removePage(index: Int): List<Item> {
        if (index !in pages.indices) return emptyList()
        val gone = pages.removeAt(index)
        val shifted = unlabelledPages.filter { it != index }.map { if (it > index) it - 1 else it }
        unlabelledPages.clear()
        unlabelledPages.addAll(shifted)
        if (pages.isEmpty()) pages.add(mutableListOf())
        return gone
    }

        /** Drop trailing empty pages, but always keep one. */
    fun trimEmptyPages() {
        while (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.size - 1)
        unlabelledPages.removeAll { it >= pages.size }
    }

    /** Every app on home, in folders, and in the dock. */
    fun allApps(): List<AppKey> {
        val out = mutableListOf<AppKey>()
        pages.flatten().forEach {
            when (it) {
                is AppItem -> out.add(it.app)
                is FolderItem -> out.addAll(it.apps)
                is WidgetItem -> {}
            }
        }
        dock.filterNotNull().forEach {
            when (it) {
                is AppItem -> out.add(it.app)
                is FolderItem -> out.addAll(it.apps)
                is WidgetItem -> {}
            }
        }
        return out
    }

    /** Remove every trace of apps that no longer exist (uninstalled, profile removed). */
    fun removeApps(gone: (AppKey) -> Boolean) {
        for (page in pages) {
            page.removeAll { it is AppItem && gone(it.app) }
            for (folder in page.filterIsInstance<FolderItem>()) {
                folder.apps.removeAll(gone)
                if (folder.cover?.let(gone) == true) folder.cover = null
            }
            dissolveSmallFolders(page)
        }
        for (i in dock.indices) {
            when (val d = dock[i]) {
                is AppItem -> if (gone(d.app)) dock[i] = null
                is FolderItem -> {
                    d.apps.removeAll(gone)
                    if (d.cover?.let(gone) == true) d.cover = null
                }
                else -> {}
            }
        }
        dissolveDockFolders()
    }

    /** The app in a dock slot, if it holds a single app. */
    fun dockApp(slot: Int): AppKey? = (dock.getOrNull(slot) as? AppItem)?.app

    /** Put [item] in a dock slot (an app or a folder), noting the slot on it. */
    fun setDock(slot: Int, item: Item?) {
        item?.x = slot
        item?.y = 0
        dock[slot] = item
    }

    /** A dock folder down to one app becomes that app; an empty one goes away. */
    fun dissolveDockFolders() {
        for (i in dock.indices) {
            val f = dock[i] as? FolderItem ?: continue
            if (f.apps.size <= 1) setDock(i, f.apps.firstOrNull()?.let { AppItem(it, i, 0) })
        }
    }

    /** Wherever this folder lives (a page or the dock), fold it away if it's down to one app. */
    fun dissolveIfSmall(folder: FolderItem) {
        pages.firstOrNull { it.contains(folder) }?.let { dissolveSmallFolders(it) }
        if (dock.contains(folder)) dissolveDockFolders()
    }

    /** A folder with one app left becomes that app; an empty folder goes away. */
    fun dissolveSmallFolders(page: MutableList<Item>) {
        val small = page.filterIsInstance<FolderItem>().filter { it.apps.size <= 1 }
        for (f in small) {
            page.remove(f)
            f.apps.firstOrNull()?.let { page.add(AppItem(it, f.x, f.y)) }
        }
    }

    /**
     * Switch grid size without losing anything. Items that still fit stay put; the rest move to
     * the first free spot on their page or a later one. Widgets shrink to fit the new grid.
     * Dock apps that no longer fit move onto the home screen.
     */
    fun resize(newGrid: GridSize) {
        if (newGrid == grid) return
        grid = newGrid
        val displaced = mutableListOf<Pair<Int, Item>>()
        pages.forEachIndexed { index, page ->
            // Keep items that fit, larger ones first so widgets claim space before icons.
            val keep = mutableListOf<Item>()
            for (item in page.sortedWith(compareBy({ -it.spanX * it.spanY }, { it.y }, { it.x }))) {
                clampSpan(item)
                val fits = item.x + item.spanX <= cols() && item.y + item.spanY <= rows() &&
                    keep.none { overlaps(it, item.x, item.y, item.spanX, item.spanY) }
                if (fits) keep.add(item) else displaced.add(index to item)
            }
            page.clear()
            page.addAll(keep)
        }
        for ((page, item) in displaced) placeAnywhere(item, page)
        fitDock()
    }

    /** Make the dock exactly [GridSize.dockSlots] long; overflow goes onto the home screen. */
    private fun fitDock() {
        val slots = grid.dockSlots
        while (dock.size < slots) dock.add(null)
        while (dock.size > slots) {
            val item = dock.removeAt(dock.size - 1) ?: continue
            val empty = dock.indexOfFirst { it == null }
            if (empty >= 0) setDock(empty, item) else placeAnywhere(item)
        }
        dock.forEachIndexed { i, item -> item?.x = i; item?.y = 0 }
    }

    private fun clampSpan(item: Item) {
        if (item is WidgetItem) {
            item.w = item.w.coerceIn(1, cols())
            item.h = item.h.coerceIn(1, rows())
        }
    }

    private fun overlaps(it: Item, x: Int, y: Int, w: Int, h: Int) =
        x < it.x + it.spanX && it.x < x + w && y < it.y + it.spanY && it.y < y + h

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("grid", grid.key)
        .put("pages", JSONArray().also { a -> pages.forEach { p -> a.put(JSONArray().also { pa -> p.forEach { pa.put(it.toJson()) } }) } })
        .put("dock", JSONArray().also { a -> dock.forEach { a.put(it?.toJson() ?: JSONObject.NULL) } })
        .put("unlabelledPages", JSONArray(unlabelledPages.sorted()))

    companion object {
        fun empty(grid: GridSize) = HomeLayout(grid, mutableListOf(mutableListOf()), mutableListOf())

        fun fromJson(o: JSONObject): HomeLayout {
            val grid = GridSize.fromKey(o.optString("grid"))
            val pages = mutableListOf<MutableList<Item>>()
            val pa = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val page = mutableListOf<Item>()
                val items = pa.getJSONArray(i)
                for (j in 0 until items.length()) itemFromJson(items.getJSONObject(j))?.let(page::add)
                pages.add(page)
            }
            val dock = mutableListOf<Item?>()
            val da = o.optJSONArray("dock") ?: JSONArray()
            for (i in 0 until da.length()) {
                val d = da.optJSONObject(i)
                // Layouts from before folders could go in the dock saved bare apps there.
                dock.add(
                    when {
                        d == null -> null
                        d.has("t") -> itemFromJson(d)?.takeIf { it !is WidgetItem }
                        else -> AppItem(AppKey.fromJson(d), i, 0)
                    },
                )
            }
            return HomeLayout(grid, pages, dock).also { l ->
                o.optJSONArray("unlabelledPages")?.let { a -> for (i in 0 until a.length()) l.unlabelledPages.add(a.getInt(i)) }
            }
        }

        private fun itemFromJson(o: JSONObject): Item? = when (o.optString("t")) {
            "app" -> AppItem(AppKey.fromJson(o), o.getInt("x"), o.getInt("y")).apply {
                swipe = o.optString("swipe").takeIf { it.isNotEmpty() }
            }
            "folder" -> {
                val apps = mutableListOf<AppKey>()
                val a = o.getJSONArray("apps")
                for (i in 0 until a.length()) apps.add(AppKey.fromJson(a.getJSONObject(i)))
                FolderItem(o.optString("name", "Folder"), apps, o.getInt("x"), o.getInt("y")).apply {
                    cover = o.optJSONObject("cover")?.let(AppKey::fromJson)
                    color = if (o.has("color")) o.getInt("color") else null
                    labels = o.optBoolean("labels", true)
                    // Folders from before styles: one with a cover chosen was a "cover" folder.
                    style = o.optString("style").takeIf { it.isNotEmpty() } ?: if (cover != null) "cover" else "grid"
                    showCount = o.optBoolean("count", false)
                }
            }
            "widget" -> WidgetItem(o.getInt("id"), o.optString("provider"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"))
            else -> null
        }
    }
}
