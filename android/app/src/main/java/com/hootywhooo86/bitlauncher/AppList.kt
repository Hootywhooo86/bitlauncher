package com.hootywhooo86.bitlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.concurrent.Executors

class AppEntry(val key: AppKey, val label: String)

/**
 * Every launchable app on the phone, across the personal and work profiles. Labels are cached on
 * disk so home can draw on the first frame; the real list loads in the background and replaces it.
 */
class AppList(private val context: Context, val icons: IconCache, private val onChanged: () -> Unit) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val labelFile = File(context.filesDir, "labels.json")

    /** Sorted A–Z. */
    var apps: List<AppEntry> = emptyList()
        private set
    private var byId: Map<String, AppEntry> = emptyMap()

    /** True once the real list has loaded (not just the cached labels). */
    var loaded = false
        private set

    /** Called with the ids of apps that were removed from the phone. */
    var onRemoved: ((Set<String>) -> Unit)? = null

    private val reload = Runnable { load() }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
    }

    fun start() {
        readLabelCache()
        launcherApps.registerCallback(callback, main)
        load()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        worker.shutdown()
    }

    fun find(key: AppKey): AppEntry? = byId[key.id()]

    fun label(key: AppKey): String = byId[key.id()]?.label ?: key.pkg.substringAfterLast('.')

    private fun changed(pkg: String?) {
        if (pkg != null) worker.execute { icons.invalidate(pkg) }
        // Installs fire several callbacks in a row; reload once.
        main.removeCallbacks(reload)
        main.postDelayed(reload, 250)
    }

    /** Re-read everything: after the icon pack or a name changes. */
    fun reload() = load()

    private fun load() {
        worker.execute {
            val density = context.resources.displayMetrics.densityDpi
            val prefs = Prefs(context)
            val renamed = prefs.renamed
            val pack = IconPack.load(context, prefs.iconPack)
            val found = mutableListOf<Pair<AppEntry, LauncherActivityInfo>>()
            for (user in users.userProfiles) {
                val serial = users.getSerialNumberForUser(user)
                for (info in launcherApps.getActivityList(null, user)) {
                    val key = AppKey(info.componentName.packageName, info.componentName.className, serial)
                    val label = renamed[key.id()] ?: info.label.toString().trim()
                    found.add(AppEntry(key, label) to info)
                }
            }
            val collator = Collator.getInstance()
            val sorted = found.sortedWith { a, b -> collator.compare(a.first.label, b.first.label) }
            val entries = sorted.map { it.first }
            main.post { publish(entries) }

            // Sprites after the list is up, so the drawer shows straight away.
            var any = false
            for ((entry, info) in sorted) {
                // The pack is part of the stamp, so switching packs re-makes every sprite once.
                val stamp = (info.firstInstallTime xor lastUpdate(info)) * 31 + (pack?.pkg?.hashCode() ?: 0)
                val made = icons.ensure(entry.key, stamp) {
                    pack?.iconFor(info.componentName)?.let { context.packageManager.getUserBadgedIcon(it, info.user) }
                        ?: info.getBadgedIcon(density)
                }
                if (made) any = true
            }
            if (any) main.post(onChanged)
            writeLabelCache(entries)
        }
    }

    private fun lastUpdate(info: LauncherActivityInfo): Long = try {
        File(info.applicationInfo.sourceDir).lastModified()
    } catch (e: Exception) {
        0L
    }

    private fun publish(entries: List<AppEntry>) {
        val before = byId.keys
        apps = entries
        byId = entries.associateBy { it.key.id() }
        if (loaded) {
            val gone = before - byId.keys
            if (gone.isNotEmpty()) onRemoved?.invoke(gone)
        }
        loaded = true
        onChanged()
    }

    private fun readLabelCache() {
        try {
            if (!labelFile.exists()) return
            val o = JSONObject(labelFile.readText())
            val a = o.getJSONArray("apps")
            val list = (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                AppEntry(AppKey.fromJson(e), e.getString("label"))
            }
            apps = list
            byId = list.associateBy { it.key.id() }
        } catch (e: Exception) {
            // A bad cache only costs us the head start; the real list is on its way.
        }
    }

    private fun writeLabelCache(entries: List<AppEntry>) {
        val a = org.json.JSONArray()
        entries.forEach { a.put(it.key.toJson().put("label", it.label)) }
        val tmp = File(labelFile.path + ".tmp")
        tmp.writeText(JSONObject().put("apps", a).toString())
        tmp.renameTo(labelFile)
    }

    fun userFor(key: AppKey): UserHandle? = users.getUserForSerialNumber(key.user)

    fun component(key: AppKey) = ComponentName(key.pkg, key.cls)

    fun openAppInfo(key: AppKey, bounds: Rect?) {
        try {
            launcherApps.startAppDetailsActivity(component(key), userFor(key), bounds, null)
        } catch (e: Exception) {
            Pixel.say(context, "Couldn't open app info for ${label(key)}.")
        }
    }

    val launcher: LauncherApps get() = launcherApps
}
