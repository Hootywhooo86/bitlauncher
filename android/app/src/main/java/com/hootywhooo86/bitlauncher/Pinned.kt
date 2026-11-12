package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.ceil

/**
 * Shortcuts other apps pin to the home screen ("Add to home screen" in Chrome, a contact, a
 * Maps destination). Each is an [AppKey] whose class is "#shortcut:<id>", so it can live
 * anywhere an app can: home, folders, the dock. Labels are kept here; sprites in the icon cache.
 */
object PinnedShortcuts {
    private const val PREFIX = "#shortcut:"

    fun key(pkg: String, id: String, user: Long) = AppKey(pkg, PREFIX + id, user)

    fun isShortcut(key: AppKey) = key.cls.startsWith(PREFIX)

    fun idOf(key: AppKey) = key.cls.removePrefix(PREFIX)

    private fun store(context: Context) = context.getSharedPreferences("pinnedShortcuts", Context.MODE_PRIVATE)

    fun label(context: Context, key: AppKey): String? = store(context).getString(key.id(), null)

    fun remember(context: Context, key: AppKey, label: String) = store(context).edit().putString(key.id(), label).apply()

    /** Open it. False (with nothing opened) if the app no longer offers it. */
    fun launch(context: Context, key: AppKey, bounds: android.graphics.Rect?, opts: Bundle?): Boolean {
        val users = context.getSystemService(UserManager::class.java)
        val user = users.getUserForSerialNumber(key.user) ?: return false
        return try {
            context.getSystemService(LauncherApps::class.java).startShortcut(key.pkg, idOf(key), bounds, opts, user)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Where Android sends "add this to the home screen" from other apps. Shows a small pixel card
 * asking first, then puts the shortcut or widget on the first home page.
 */
class AddItemActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pixel.load(this)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = try {
            launcherApps.getPinItemRequest(intent)
        } catch (e: Exception) {
            null
        }
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val prefs = Prefs(this)
        val accent = prefs.accent
        val density = resources.displayMetrics.densityDpi

        val (title, icon) = when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = request.shortcutInfo ?: return finishNow()
                (info.longLabel ?: info.shortLabel ?: "Shortcut").toString() to launcherApps.getShortcutIconDrawable(info, density)
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val info = request.getAppWidgetProviderInfo(this) ?: return finishNow()
                info.loadLabel(packageManager) to info.loadIcon(this, density)
            }
            else -> return finishNow()
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = PanelDrawable(context, Pixel.PANEL, accent)
            val p = Pixel.dpi(context, 20)
            setPadding(p, p, p, p)
            addView(Pixel.label(context, "ADD TO HOME SCREEN?", 11f, accent))
            if (icon != null) addView(ImageView(context).apply {
                setImageDrawable(BitmapDrawable(resources, IconCache.pixelate(icon)).apply { paint.isFilterBitmap = false })
            }, LinearLayout.LayoutParams(Pixel.dpi(context, 64), Pixel.dpi(context, 64)).apply {
                topMargin = Pixel.dpi(context, 16)
                bottomMargin = Pixel.dpi(context, 10)
            })
            addView(Pixel.label(context, title, 13f).apply { gravity = Gravity.CENTER })
            addView(LinearLayout(context).apply {
                setPadding(0, Pixel.dpi(context, 18), 0, 0)
                addView(Pixel.button(context, "CANCEL", accent) { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Pixel.button(context, "ADD", accent) { add(request) }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = Pixel.dpi(context, 8)
                })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(withAlpha(Pixel.BG, 170))
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - Pixel.dpi(context, 48), Pixel.dpi(context, 360)), -2, Gravity.CENTER))
            card.isClickable = true
        })
    }

    private fun finishNow(): Unit = finish()

    private fun add(request: LauncherApps.PinItemRequest) {
        val prefs = Prefs(this)
        val store = LayoutStore(this, prefs.mode)
        val layout = store.load(prefs.grid)
        val placed = when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> addShortcut(request, layout)
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> addWidget(request, layout)
            else -> false
        }
        if (!placed) {
            Pixel.say(this, "That couldn't be added. The app may have cancelled it.")
            finish()
            return
        }
        store.save(layout)
        // Home reads the saved layout again when you go back to it.
        prefs.layoutChanged = System.currentTimeMillis()
        prefs.touch()
        Pixel.say(this, "Added to your home screen.")
        finish()
    }

    private fun addShortcut(request: LauncherApps.PinItemRequest, layout: HomeLayout): Boolean {
        val info = request.shortcutInfo ?: return false
        if (!request.accept()) return false
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.userHandle)
        val key = PinnedShortcuts.key(info.`package`, info.id, serial)
        PinnedShortcuts.remember(this, key, (info.shortLabel ?: info.longLabel ?: "Shortcut").toString())
        val drawable = getSystemService(LauncherApps::class.java).getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)
        if (drawable != null) IconCache(this).ensure(key, info.lastChangedTimestamp) { drawable }
        if (layout.allApps().contains(key)) return true // already there
        layout.placeAnywhere(AppItem(key, 0, 0), 0)
        return true
    }

    private fun addWidget(request: LauncherApps.PinItemRequest, layout: HomeLayout): Boolean {
        val info = request.getAppWidgetProviderInfo(this) ?: return false
        val host = PixelWidgetHost(this)
        val id = host.allocateAppWidgetId()
        if (!request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })) {
            host.deleteAppWidgetId(id)
            return false
        }
        // No home screen to measure from here: estimate cells from a typical cell size for this grid.
        val dm = resources.displayMetrics
        val cellW = dm.widthPixels / layout.cols().toFloat()
        val cellH = dm.heightPixels * 0.7f / layout.rows()
        val w = ceil(info.minWidth / cellW).toInt().coerceIn(1, layout.cols())
        val h = ceil(info.minHeight / cellH).toInt().coerceIn(1, layout.rows())
        layout.placeAnywhere(WidgetItem(id, info.provider.flattenToString(), 0, 0, w, h), 0)
        return true
    }
}
