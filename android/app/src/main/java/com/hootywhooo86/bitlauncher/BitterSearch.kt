package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews

/**
 * Bitter Searcher: one box that finds apps and everything else on the phone. Each source is
 * local and only reads what you've allowed; the web is only ever a link you tap.
 *
 * Answers, best first: sums and conversions, app shortcuts, contacts, your notifications, calendar
 * events, phone settings pages, bitlauncher settings, then the web, Play Store, Maps and YouTube.
 */
class BitterSearch(private val context: Context, private val done: () -> Unit) {
    private val prefs = Prefs(context)
    private fun say(s: String) = Pixel.say(context, s)

    private fun start(intent: Intent, what: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            done()
        } catch (e: Exception) {
            say("No app on this phone can open $what.")
        }
    }

    fun answers(q: String, appMatches: Int): List<DrawerView.Answer> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<DrawerView.Answer>()
        Search.calculate(query)?.let { out += DrawerView.Answer("= $it", "Calculator  ·  tap to copy") { copy(it) } }
        Search.convert(query)?.let { out += DrawerView.Answer("= $it", "Conversion  ·  tap to copy") { copy(it.substringBefore(' ')) } }
        if (query.length >= 2) {
            out += shortcuts(query)
            out += contacts(query)
            out += notifications(query)
            out += events(query)
            out += FileSearch.find(context, query, prefs.searchFiles).map { f ->
                DrawerView.Answer(f.name, "${f.kind}  ·  on this phone") {
                    start(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name)
                }
            }
        }
        for (page in Search.settings(query)) out += DrawerView.Answer(page.label, "Phone settings") {
            start(Intent(page.action), "the ${page.label} settings")
        }
        out += launcherSettings(query)
        // The web and friends come last, only once there's something to look for, and not for a sum.
        val answered = out.firstOrNull()?.title?.startsWith("= ") == true
        if (query.length >= 2 && !answered) {
            if (appMatches < 3) out += web(query)
            out += DrawerView.Answer("Find \"$query\" in the Play Store", "Apps to install") {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))), "the Play Store")
            }
            out += DrawerView.Answer("Find \"$query\" on a map", "Maps") {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))), "a map")
            }
            out += DrawerView.Answer("Find \"$query\" on YouTube", "Videos") {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))), "YouTube")
            }
        }
        return out
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bitter Searcher", text))
        say("Copied $text")
    }

    /** Things apps offer to jump straight to ("New message", "Scan"). Android only shares them with the home app. */
    private fun shortcuts(q: String): List<DrawerView.Answer> {
        val la = context.getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) return emptyList()
        val users = context.getSystemService(UserManager::class.java)
        val out = mutableListOf<DrawerView.Answer>()
        try {
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
            for (user in users.userProfiles) {
                val list = la.getShortcuts(query, user) ?: continue
                for (s in list) {
                    val label = (s.shortLabel ?: s.longLabel ?: continue).toString()
                    if (!label.contains(q, ignoreCase = true) || out.size >= 4) continue
                    val app = try {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(s.`package`, 0)).toString()
                    } catch (e: Exception) {
                        s.`package`
                    }
                    out += DrawerView.Answer(label, "Shortcut  ·  $app") {
                        try {
                            la.startShortcut(s, null, null)
                            done()
                        } catch (e: Exception) {
                            say("$app no longer offers that shortcut.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun contacts(q: String): List<DrawerView.Answer> {
        if (!prefs.searchContacts) return emptyList()
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<DrawerView.Answer>()
        try {
            val uri = Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
            val cols = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.LOOKUP_KEY,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            )
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 3) {
                    val lookup = android.provider.ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    val name = c.getString(2) ?: continue
                    out += DrawerView.Answer(name, "Contact") { start(Intent(Intent.ACTION_VIEW, lookup), name) }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Your notifications that mention it. Nothing is stored; this is what's on the phone right now. */
    private fun notifications(q: String): List<DrawerView.Answer> =
        NotificationHub.snapshot().filter { NotePrivacy.countable(it) && (it.title.contains(q, true) || it.text.contains(q, true)) }
            .sortedByDescending { it.posted }
            .take(3)
            .map { n ->
                DrawerView.Answer(n.title.ifBlank { n.text }, "Notification  ·  ${NoteCards.appName(context, n.pkg)}") {
                    val pi = n.intent
                    if (pi == null) {
                        context.packageManager.getLaunchIntentForPackage(n.pkg)?.let { start(it, NoteCards.appName(context, n.pkg)) }
                    } else try {
                        openNotification(pi)
                        done()
                    } catch (e: Exception) {
                        say("That notification can't be opened any more.")
                    }
                }
            }

    /** Calendar events in the next two months whose title matches. Only with calendar access. */
    private fun events(q: String): List<DrawerView.Answer> {
        if (!CalendarSource.hasPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - DateUtils.DAY_IN_MILLIS)
            ContentUris.appendId(it, now + 60 * DateUtils.DAY_IN_MILLIS)
        }.build()
        val out = mutableListOf<DrawerView.Answer>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.TITLE} LIKE ?",
                arrayOf("%$q%"),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 3) {
                    val e = UpcomingEvent(c.getLong(0), c.getString(1) ?: continue, c.getLong(2), c.getLong(3), c.getInt(4) == 1)
                    if (e.end < now) continue
                    out += DrawerView.Answer(e.title, "Calendar  ·  ${CalendarSource.whenText(context, e)}") {
                        start(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end), "that event")
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** bitlauncher's own settings: "dock", "accent", "lock screen"... */
    private fun launcherSettings(q: String): List<DrawerView.Answer> {
        if (q.length < 3) return emptyList()
        return LAUNCHER_SETTINGS.filter { (name, words) -> name.contains(q, true) || words.any { it.startsWith(q, true) } }
            .take(2)
            .map { (name, _) ->
                DrawerView.Answer(name, "bitlauncher settings") { start(Intent(context, SettingsActivity::class.java), "settings") }
            }
    }

    /** Search the web with the chosen engine; Bit Browser if chosen and installed. */
    private fun web(q: String): DrawerView.Answer {
        val bitBrowser = "com.pixelbrowser.app"
        val pm = context.packageManager
        val hasBit = try {
            pm.getPackageInfo(bitBrowser, 0)
            true
        } catch (e: Exception) {
            false
        }
        val engine = prefs.searchEngine
        val useBit = engine == "bit" && hasBit
        val label = when {
            useBit -> "Bit Browser"
            engine == "google" -> "Google"
            engine == "duck" -> "DuckDuckGo"
            else -> "Web"
        }
        return DrawerView.Answer("Search the web for \"$q\"", label) {
            val url = Uri.parse(
                if (engine == "google") "https://www.google.com/search?q=" + Uri.encode(q) else "https://duckduckgo.com/?q=" + Uri.encode(q),
            )
            val tries = buildList {
                if (useBit) {
                    add(Intent(Intent.ACTION_VIEW, url).setPackage(bitBrowser))
                    add(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).setPackage(bitBrowser))
                }
                if (engine == "bit") add(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
                add(Intent(Intent.ACTION_VIEW, url))
            }
            val intent = tries.firstOrNull { it.resolveActivity(pm) != null }
            if (intent == null) say("No browser found to search the web.") else start(intent, "the web")
        }
    }

    companion object {
        /** (name, words people might type). */
        val LAUNCHER_SETTINGS = listOf(
            "Accent colour" to listOf("accent", "colour", "color"),
            "Theme and my theme" to listOf("theme", "dark", "colours"),
            "Grid size" to listOf("grid", "columns", "rows", "7x9"),
            "Icon look" to listOf("icon", "coins", "cartridge", "blocks"),
            "Dock style" to listOf("dock", "shelf"),
            "Page transition" to listOf("transition", "cube", "dissolve"),
            "Lock screen" to listOf("lock", "clock", "charging"),
            "Scene and time of day" to listOf("scene", "wallpaper", "night", "railway"),
            "Notifications and pop-ups" to listOf("notification", "popup", "pop-up"),
        )
    }
}

/**
 * Bitter Searcher's own screen, opened by its widget (or the All Apps widget): every app A–Z with
 * the search box on top. Works whether or not bitlauncher is your home app.
 */
class SearchActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode" // "search" (keyboard up) or "apps"
    }

    private lateinit var apps: AppList
    private lateinit var drawer: DrawerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pixel.load(this)
        val prefs = Prefs(this)
        IconStyles.current = prefs.iconStyle
        IconStyles.accent = prefs.accent
        IconStyles.tile = prefs.iconTile
        val icons = IconCache(this)
        drawer = DrawerView(this) { view, entry ->
            view.tagItem = entry.key
            view.sprite = icons.get(entry.key)
            view.label = entry.label
            view.accent = prefs.accent
            view.iconSizeDp = prefs.iconDp()
            view.showLabel = prefs.labels
            view.setOnClickListener { open(entry) }
        }
        drawer.setAccent(prefs.accent)
        drawer.onOpenFirst = { open(it) }
        val search = BitterSearch(this) { finish() }
        drawer.answersFor = { q, matches -> search.answers(q, matches) }
        drawer.onPullRelease = { _, _ -> finish() }
        val title = Pixel.label(this, "BITTER SEARCHER", 10f, prefs.accent)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Pixel.BG)
            addView(drawer, FrameLayout.LayoutParams(-1, -1))
            addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = Pixel.dpi(context, 18) })
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            (title.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + Pixel.dpi(this, 12)
            title.requestLayout()
            drawer.setPadding(0, bars.top + Pixel.dpi(this, 36), 0, 0)
            drawer.setBottomInset(bars.bottom)
            insets
        }
        val searchMode = intent.getStringExtra(EXTRA_MODE) != "apps"
        val view = if (searchMode) BitterSearchView(this, icons, { visibleApps(prefs) }, ::open, search) else null
        if (view != null) {
            root.removeView(drawer)
            root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                (title.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + Pixel.dpi(this, 12)
                title.requestLayout()
                view.setPadding(view.paddingLeft, bars.top + Pixel.dpi(this, 36), view.paddingRight, bars.bottom)
                insets
            }
        }
        setContentView(root)
        apps = AppList(this, icons) {
            if (view != null) view.render() else drawer.setApps(visibleApps(prefs))
        }
        apps.start()
        if (view != null) {
            view.post { view.showKeyboard() }
        } else {
            drawer.visibility = View.VISIBLE
            drawer.reset()
        }
    }

    private fun visibleApps(prefs: Prefs): List<AppEntry> {
        if (!::apps.isInitialized) return emptyList()
        val hidden = prefs.hidden
        return apps.apps.filter { it.key.id() !in hidden }
    }

    private fun open(entry: AppEntry) {
        try {
            val user = apps.userFor(entry.key) ?: throw IllegalStateException()
            apps.launcher.startMainActivity(apps.component(entry.key), user, null, null)
            Usage(this).record(entry.key)
            finish()
        } catch (e: Exception) {
            Pixel.say(this, "${entry.label} can't be opened. It may have been uninstalled or disabled.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        apps.stop()
    }
}

/** Draws the two home-screen widgets as crisp pixel bitmaps, sized to the widget. */
object SearchWidgets {
    private val MAGNIFIER = arrayOf(".###..", "#...#.", "#...#.", "#...#.", ".###..", "....##", ".....#")
    private val GRID = arrayOf("##.##.##", "##.##.##", "........", "##.##.##", "##.##.##", "........", "##.##.##", "##.##.##")

    fun render(context: Context, wPx: Int, hPx: Int, label: String, sprite: Array<String>, accent: Int, icon: Bitmap? = null): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(8), hPx.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val panel = PanelDrawable(context, withAlpha(Pixel.PANEL, 235), accent)
        panel.setBounds(0, 0, bmp.width, bmp.height)
        panel.draw(c)
        val u = (bmp.height / 14).coerceAtLeast(2).toFloat()
        val p = Paint()
        val sx = u * 3
        if (icon != null) {
            // The 64x64 pixel icon, a whole-number size so its pixels stay square.
            val side = (bmp.height * 0.62f / 64).toInt().coerceAtLeast(1) * 64f
            val top = (bmp.height - side) / 2f
            c.drawBitmap(icon, null, android.graphics.RectF(sx, top, sx + side, top + side), Paint().apply { isFilterBitmap = false })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Pixel.font(context); color = Pixel.INK
                textSize = (bmp.height * 0.26f).coerceAtMost(Pixel.dp(context, 16f)); letterSpacing = 0.06f
            }
            val tx = sx + side + u * 3
            if (tx < bmp.width - u * 4) c.drawText(label, tx, bmp.height / 2f + text.textSize / 2.6f, text)
            return bmp
        }
        val sy = (bmp.height - sprite.size * u) / 2f - u * 0.5f
        p.color = accent
        for (r in sprite.indices) for (col in sprite[r].indices) if (sprite[r][col] == '#') {
            c.drawRect(sx + col * u, sy + r * u, sx + (col + 1) * u, sy + (r + 1) * u, p)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Pixel.font(context)
            color = Pixel.INK
            textSize = (bmp.height * 0.26f).coerceAtMost(Pixel.dp(context, 16f))
            letterSpacing = 0.06f
        }
        val tx = sx + sprite[0].length * u + u * 3
        if (tx < bmp.width - u * 4) c.drawText(label, tx, bmp.height / 2f + text.textSize / 2.6f, text)
        return bmp
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray, mode: String) {
        val prefs = Prefs(context)
        Pixel.load(context)
        val d = context.resources.displayMetrics.density
        for (id in ids) {
            val o = manager.getAppWidgetOptions(id)
            val w = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, if (mode == "apps") 110 else 280) * d).toInt()
            val h = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48) * d).toInt().coerceAtMost((72 * d).toInt())
            val bmp = if (mode == "apps") render(context, w, h, "ALL APPS", GRID, prefs.accent, PixelIcons.get(context, "folder"))
                else render(context, w, h, "BITTER SEARCHER", MAGNIFIER, prefs.accent, PixelIcons.get(context, "search"))
            val views = RemoteViews(context.packageName, R.layout.widget_image)
            views.setImageViewBitmap(R.id.widget_image, bmp)
            views.setContentDescription(R.id.widget_image, if (mode == "apps") "All apps" else "Bitter Searcher")
            val open = Intent(context, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            views.setOnClickPendingIntent(R.id.widget_image, PendingIntent.getActivity(context, if (mode == "apps") 2 else 1, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(id, views)
        }
    }

    /** Redraw every placed widget (after the accent or theme changes). */
    fun refreshAll(context: Context) {
        val m = AppWidgetManager.getInstance(context) ?: return
        update(context, m, m.getAppWidgetIds(ComponentName(context, BitterSearchWidget::class.java)), "search")
        update(context, m, m.getAppWidgetIds(ComponentName(context, AllAppsWidget::class.java)), "apps")
    }
}

/** The Bitter Searcher bar for any home screen. */
class BitterSearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = SearchWidgets.update(context, manager, ids, "search")
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        SearchWidgets.update(context, manager, intArrayOf(id), "search")
}

/** A button that opens every app, A–Z, for any home screen. */
class AllAppsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = SearchWidgets.update(context, manager, ids, "apps")
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        SearchWidgets.update(context, manager, intArrayOf(id), "apps")
}

/** Photos, videos and music by name, from the phone's media store. Only with permission, only on the phone. */
object FileSearch {
    data class Found(val name: String, val kind: String, val uri: Uri, val mime: String)

    fun permissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33)
        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    fun allowed(c: Context) = permissions().any { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun find(c: Context, q: String, on: Boolean, max: Int = 4): List<Found> {
        if (!on || q.length < 2 || !allowed(c)) return emptyList()
        val out = mutableListOf<Found>()
        val kinds = listOf(
            Triple(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", "image/*"),
            Triple(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", "video/*"),
            Triple(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music", "audio/*"),
        )
        for ((base, kind, mime) in kinds) {
            if (out.size >= max) break
            try {
                c.contentResolver.query(
                    base,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$q%"),
                    "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cur ->
                    while (cur.moveToNext() && out.size < max) {
                        out += Found(cur.getString(1) ?: continue, kind, ContentUris.withAppendedId(base, cur.getLong(0)), mime)
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }
}
