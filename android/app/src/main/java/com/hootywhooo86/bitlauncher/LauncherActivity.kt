package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.ceil

class LauncherActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private lateinit var icons: IconCache
    private lateinit var apps: AppList
    private lateinit var layout: HomeLayout

    private lateinit var root: FrameLayout
    private lateinit var workspace: Workspace
    private lateinit var dots: PageDots
    private lateinit var dock: DockView
    private lateinit var removeBar: TextView
    private lateinit var drawer: DrawerView
    private lateinit var folderView: FolderView
    private lateinit var emptyHint: TextView
    private lateinit var menu: PixelMenu
    private lateinit var header: android.widget.LinearLayout
    private lateinit var clock: PixelClockView
    private lateinit var dateLabel: TextView
    private val onNotifications = { refreshDots() }

    private lateinit var widgetHost: PixelWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private val widgetViews = HashMap<Int, PixelWidgetView>()
    private var pendingWidget: AppWidgetProviderInfo? = null
    private var pendingWidgetId = -1

    private var openFolder: FolderItem? = null
    private var appliedPrefs = -1
    private var firstRun = false
    private var insetTop = 0
    private var insetBottom = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = LayoutStore(this)
        icons = IconCache(this)
        firstRun = store.isFirstRun()
        layout = store.load(prefs.grid)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = PixelWidgetHost(this)
        menu = PixelMenu(this, prefs.accent)

        edgeToEdge()
        buildViews()
        appliedPrefs = prefs.version

        apps = AppList(this, icons) { onAppsChanged() }
        apps.onRemoved = { ids ->
            layout.removeApps { it.id() in ids }
            save()
            rebuild()
        }
        rebuild()
        apps.start()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            // Can fail while the phone is still unlocking; widgets catch up on the next start.
        }
        NotificationHub.listen(onNotifications)
        refreshDots()
        updateDate()
    }

    override fun onStop() {
        super.onStop()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
        }
        NotificationHub.unlisten(onNotifications)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.version != appliedPrefs) applyPrefs()
    }

    override fun onPause() {
        super.onPause()
        menu.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        apps.stop()
    }

    /** The Home button. Close whatever is open; if nothing was, go to the first page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alreadyHome = hasWindowFocus()
        val closedSomething = closeOverlays()
        if (alreadyHome && !closedSomething) workspace.snapTo(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back on the home screen closes things, and never leaves.
        closeOverlays()
    }

    private fun closeOverlays(): Boolean {
        var closed = false
        if (menu.isShowing) {
            menu.dismiss()
            closed = true
        }
        if (openFolder != null) {
            closeFolder()
            closed = true
        }
        if (drawer.visibility == View.VISIBLE) {
            closeDrawer()
            closed = true
        }
        return closed
    }

    // ---------------------------------------------------------------- views

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 15 does this by itself; older versions need asking.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun buildViews() {
        root = FrameLayout(this)
        workspace = Workspace(this)
        workspace.listener = object : Workspace.Listener {
            override fun onSwipeUp() = openDrawer()
            override fun onSwipeDown() = openNotifications()
            override fun onEmptyLongPress(x: Float, y: Float) = showHomeMenu(x - workspace.scrollX, y)
            override fun onPageChanged(page: Int) {
                dots.current = page
            }
        }
        root.addView(workspace, FrameLayout.LayoutParams(-1, -1))

        // The pixel clock and date above the grid. Tap for the phone's alarms.
        header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clock = PixelClockView(this, blink = false)
        clock.onTick = { if (::dateLabel.isInitialized) updateDate() }
        clock.setOnClickListener {
            try {
                startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            } catch (e: Exception) {
                Pixel.say(this, "No clock app found to show alarms.")
            }
        }
        header.addView(clock, android.widget.LinearLayout.LayoutParams(0, -2))
        dateLabel = Pixel.label(this, "", 10f, Pixel.INK).apply {
            gravity = Gravity.CENTER
            setShadowLayer(0.01f, Pixel.dp(context, 1f), Pixel.dp(context, 1f), Pixel.SHADOW)
            setPadding(0, Pixel.dpi(context, 6), 0, 0)
        }
        header.addView(dateLabel, android.widget.LinearLayout.LayoutParams(-1, -2))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        emptyHint = Pixel.label(this, "SWIPE UP FOR ALL APPS\n\nHOLD AN APP TO PLACE IT HERE", 11f, Pixel.INK).apply {
            gravity = Gravity.CENTER
            setShadowLayer(0.01f, Pixel.dp(context, 1f), Pixel.dp(context, 1f), Pixel.SHADOW)
            visibility = View.GONE
        }
        root.addView(emptyHint, FrameLayout.LayoutParams(-1, -1))

        dots = PageDots(this)
        root.addView(dots, FrameLayout.LayoutParams(-1, Pixel.dpi(this, 20), Gravity.BOTTOM))

        dock = DockView(this)
        root.addView(dock, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))

        removeBar = Pixel.label(this, "DROP HERE TO REMOVE", 11f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(removeBar, FrameLayout.LayoutParams(-1, Pixel.dpi(this, 56), Gravity.TOP))

        drawer = DrawerView(this) { view, entry -> bindIcon(view, entry.key, Source.DRAWER) }
        drawer.visibility = View.GONE
        drawer.onOpenFirst = { launch(it.key, null) }
        drawer.onCloseRequest = { closeDrawer() }
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        folderView = FolderView(this)
        folderView.visibility = View.GONE
        folderView.onClose = { closeFolder() }
        root.addView(folderView, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                insetTop = bars.top
                insetBottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
            }
            positionChrome()
            insets
        }
        root.setOnDragListener { _, e -> onDrag(e) }
        setContentView(root)
    }

    /** Size the dock and pad the pages around the system bars. */
    private fun positionChrome() {
        val gap = Pixel.dpi(this, 8)
        val dockHeight = Pixel.dpi(this, prefs.iconDp() + 28)
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            height = dockHeight
            setMargins(gap, 0, gap, insetBottom + gap)
        }
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + gap + dockHeight
        (removeBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop

        // Clock: 55% of the width, so each block is a whole number of pixels.
        val clockWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        val block = clockWidth / (PixelGlyphs.CLOCK_W + 1)
        (clock.layoutParams as android.widget.LinearLayout.LayoutParams).width = block * (PixelGlyphs.CLOCK_W + 1)
        (header.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + gap * 2
        header.visibility = if (prefs.homeClock) View.VISIBLE else View.GONE
        clock.accent = prefs.accent
        val headerHeight = if (prefs.homeClock) block * (PixelGlyphs.H + 1) + Pixel.dpi(this, 30) + gap * 2 else 0

        workspace.setPadding(gap, insetTop + gap + headerHeight, gap, insetBottom + gap * 2 + dockHeight + Pixel.dpi(this, 20))
        header.requestLayout()
        drawer.setPadding(0, insetTop, 0, 0)
        drawer.setBottomInset(insetBottom)
        folderView.setPadding(0, insetTop, 0, insetBottom)
        dock.requestLayout()
        dots.requestLayout()
        removeBar.requestLayout()
    }

    private fun applyPrefs() {
        appliedPrefs = prefs.version
        refreshDots()
        menu = PixelMenu(this, prefs.accent)
        if (layout.grid != prefs.grid) {
            layout.resize(prefs.grid)
            save()
        }
        positionChrome()
        rebuild()
    }

    // ---------------------------------------------------------------- drawing the home screen

    /** Rebuild every page and the dock from [layout]. Cheap: hand-drawn icons, widget views reused. */
    private fun rebuild() {
        val accent = prefs.accent
        val keepPage = workspace.currentPage
        workspace.removeAllViews()
        val liveWidgets = HashSet<Int>()
        layout.pages.forEachIndexed { pageIndex, items ->
            val page = CellLayout(this, layout.cols(), layout.rows())
            for (item in items) {
                val view: View = when (item) {
                    is AppItem -> AppIconView(this).also { bindIcon(it, item.app, Source.HOME, item) }
                    is FolderItem -> AppIconView(this).also { bindFolder(it, item) }
                    is WidgetItem -> {
                        liveWidgets.add(item.widgetId)
                        widgetView(item)
                    }
                }
                (view.parent as? ViewGroup)?.removeView(view)
                page.addView(view, CellParams(item.x, item.y, item.spanX, item.spanY))
            }
            page.tag = pageIndex
            workspace.addView(page)
        }
        // Forget views for widgets that are gone.
        widgetViews.keys.retainAll(liveWidgets)
        workspace.snapTo(keepPage.coerceAtMost(layout.pages.size - 1), animate = false)

        dock.removeAllViews()
        dock.slots = layout.grid.dockSlots
        layout.dock.forEachIndexed { slot, key ->
            if (key != null) {
                val view = AppIconView(this)
                bindIcon(view, key, Source.DOCK, dockIndex = slot)
                view.showLabel = false
                dock.addView(view, CellParams(slot, 0))
            }
        }

        dots.accent = accent
        dots.count = layout.pages.size
        dots.current = workspace.currentPage
        drawer.setAccent(accent)
        drawer.setColumns(layout.cols())
        drawer.setApps(visibleApps())
        folderView.setAccent(accent)
        emptyHint.visibility = if (layout.pages.all { it.isEmpty() }) View.VISIBLE else View.GONE
        refreshDots()
    }

    private fun updateDate() {
        dateLabel.text = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date()).uppercase()
    }

    /** Dots on every icon (and folder) whose app has something new. Only redraws what changed. */
    private fun refreshDots() {
        val with = if (prefs.dots) NotificationHub.appsWithDots() else emptySet()
        fun dotFor(tag: Any?): Boolean = when (tag) {
            is AppKey -> "${tag.pkg}/${tag.user}" in with
            is FolderItem -> tag.apps.any { "${it.pkg}/${it.user}" in with }
            else -> false
        }
        for (i in 0 until workspace.pageCount) {
            val page = workspace.page(i) ?: continue
            for (j in 0 until page.childCount) (page.getChildAt(j) as? AppIconView)?.let { it.hasDot = dotFor(it.tagItem) }
        }
        for (j in 0 until dock.childCount) (dock.getChildAt(j) as? AppIconView)?.let { it.hasDot = dotFor(it.tagItem) }
    }

    /** A landed icon hops one block up and back: pixel feedback that the drop worked. */
    private fun hop(view: View) {
        if (!Pixel.motion(this)) return
        val up = -Pixel.dp(this, 6f)
        view.animate().translationY(up).setDuration(70).setInterpolator(StepInterpolator(2)).withEndAction {
            view.animate().translationY(0f).setDuration(70).setInterpolator(StepInterpolator(2)).start()
        }.start()
    }

    private fun hopLanded(tag: Any) {
        val views = mutableListOf<View>()
        for (i in 0 until workspace.pageCount) workspace.page(i)?.let { p -> for (j in 0 until p.childCount) views.add(p.getChildAt(j)) }
        for (j in 0 until dock.childCount) views.add(dock.getChildAt(j))
        views.firstOrNull { (it as? AppIconView)?.tagItem.let { t -> t === tag || (tag is AppKey && t == tag) } }?.let { hop(it) }
    }

    private fun visibleApps(): List<AppEntry> {
        val hidden = prefs.hidden
        return apps.apps.filter { it.key.id() !in hidden }
    }

    private var pruned = false

    private fun onAppsChanged() {
        if (!pruned && apps.loaded && apps.apps.isNotEmpty()) {
            // Apps uninstalled while the launcher wasn't running.
            pruned = true
            val before = layout.allApps().size
            layout.removeApps { apps.find(it) == null }
            if (layout.allApps().size != before) save()
        }
        if (firstRun && apps.loaded) {
            firstRun = false
            fillDefaultDock()
            save()
        }
        rebuild()
        drawer.refresh()
    }

    /**
     * First run only: put the phone's own dialer, messages, browser and camera in the dock.
     * These are real installed apps, found by asking Android what handles each job.
     */
    private fun fillDefaultDock() {
        val me = getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
        val jobs = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        var slot = 0
        for (intent in jobs) {
            if (slot >= layout.dock.size) break
            val pkg = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: continue
            val entry = apps.apps.firstOrNull { it.key.pkg == pkg && it.key.user == me } ?: continue
            if (layout.dock.contains(entry.key)) continue
            layout.dock[slot++] = entry.key
        }
    }

    private enum class Source { HOME, DOCK, DRAWER, FOLDER }

    /** What's being dragged, and where it came from. */
    private class DragInfo(
        val source: Source,
        val app: AppKey? = null,
        val item: Item? = null,
        val folder: FolderItem? = null,
        val dockIndex: Int = -1,
        val view: View? = null,
    ) {
        val size: Pair<Int, Int> get() = (item?.spanX ?: 1) to (item?.spanY ?: 1)
    }

    private fun bindIcon(view: AppIconView, key: AppKey, source: Source, item: Item? = null, folder: FolderItem? = null, dockIndex: Int = -1) {
        view.tagItem = key
        view.sprite = icons.get(key)
        view.folderSprites = null
        view.hasDot = false
        view.label = apps.label(key)
        view.showLabel = prefs.labels && source != Source.DOCK
        view.iconSizeDp = prefs.iconDp()
        view.accent = prefs.accent
        view.setOnClickListener { launch(key, view) }
        view.setOnLongClickListener {
            showAppMenu(view, key, source, item, folder, dockIndex)
            true
        }
        view.onDragOut = { startDrag(it, DragInfo(source, key, item, folder, dockIndex, it)) }
        view.invalidate()
    }

    private fun bindFolder(view: AppIconView, folder: FolderItem) {
        view.tagItem = folder
        view.sprite = null
        view.folderSprites = folder.apps.take(4).map { icons.get(it) }
        view.label = folder.name
        view.showLabel = prefs.labels
        view.iconSizeDp = prefs.iconDp()
        view.setOnClickListener { openFolder(folder) }
        view.setOnLongClickListener {
            menu.show(view, listOf(
                PixelMenu.Entry("Open") { openFolder(folder) },
                PixelMenu.Entry("Remove folder") {
                    layout.remove(folder)
                    save()
                    rebuild()
                    Pixel.say(this, "Folder removed. The apps are still in the app drawer.")
                },
            ), view.iconRect())
            true
        }
        view.onDragOut = { startDrag(it, DragInfo(Source.HOME, item = folder, view = it)) }
    }

    private fun launch(key: AppKey, from: View?) {
        val bounds = from?.let { v ->
            val r = (v as? AppIconView)?.iconRect() ?: Rect(0, 0, v.width, v.height)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            r.offset(loc[0], loc[1])
            r
        }
        val opts = from?.let { v ->
            val r = (v as? AppIconView)?.iconRect() ?: Rect(0, 0, v.width, v.height)
            ActivityOptions.makeScaleUpAnimation(v, r.left, r.top, r.width(), r.height()).toBundle()
        }
        try {
            val user = apps.userFor(key) ?: throw IllegalStateException()
            apps.launcher.startMainActivity(apps.component(key), user, bounds, opts)
        } catch (e: Exception) {
            Pixel.say(this, "${apps.label(key)} can't be opened. It may have been uninstalled or disabled.")
        }
    }

    // ---------------------------------------------------------------- drawer and folders

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        menu.dismiss()
        drawer.setApps(visibleApps())
        drawer.cellHeight = Pixel.dpi(this, prefs.iconDp() + 40)
        drawer.reset()
        drawer.visibility = View.VISIBLE
        if (!Pixel.motion(this)) {
            drawer.translationY = 0f
            drawer.alpha = 1f
            if (prefs.drawerKeyboard) drawer.showKeyboard()
            return
        }
        // Slides up in four chunky steps.
        drawer.translationY = root.height * 0.2f
        drawer.alpha = 0f
        drawer.animate().translationY(0f).alpha(1f).setDuration(160).setInterpolator(StepInterpolator(4)).withEndAction {
            if (prefs.drawerKeyboard) drawer.showKeyboard()
        }.start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawer.hideKeyboard()
        if (!Pixel.motion(this)) {
            drawer.visibility = View.GONE
            drawer.reset()
            return
        }
        drawer.animate().translationY(root.height * 0.2f).alpha(0f).setDuration(120).setInterpolator(StepInterpolator(3)).withEndAction {
            drawer.visibility = View.GONE
            drawer.reset()
        }.start()
    }

    private fun openFolder(folder: FolderItem) {
        openFolder = folder
        folderView.title.setText(folder.name)
        val cell = Pixel.dpi(this, prefs.iconDp() + 40)
        folderView.setIcons(folder.apps.map { key ->
            AppIconView(this).also { bindIcon(it, key, Source.FOLDER, folder = folder) }
        }, cell)
        folderView.visibility = View.VISIBLE
        if (Pixel.motion(this)) folderView.popIn()
        refreshFolderDots(folder)
    }

    private fun refreshFolderDots(folder: FolderItem) {
        val with = if (prefs.dots) NotificationHub.appsWithDots() else emptySet()
        folderView.icons().forEach { v -> (v.tagItem as? AppKey)?.let { v.hasDot = "${it.pkg}/${it.user}" in with } }
    }

    private fun closeFolder() {
        val folder = openFolder ?: return
        val name = folderView.title.text.toString().trim()
        if (name.isNotEmpty() && name != folder.name) {
            folder.name = name
            save()
            rebuild()
        }
        folderView.hideKeyboard()
        folderView.visibility = View.GONE
        openFolder = null
    }

    @Suppress("WrongConstant")
    private fun openNotifications() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        } catch (e: Exception) {
            Pixel.say(this, "This phone doesn't let launchers open notifications. Swipe down from the top edge instead.")
        }
    }

    // ---------------------------------------------------------------- menus

    private fun showHomeMenu(x: Float, y: Float) {
        menu.show(workspace, listOf(
            PixelMenu.Entry("Widgets") { showWidgetPicker() },
            PixelMenu.Entry("Wallpaper") {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                } catch (e: Exception) {
                    Pixel.say(this, "No wallpaper picker found on this phone.")
                }
            },
            PixelMenu.Entry("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ), Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt()))
    }

    private fun showAppMenu(view: AppIconView, key: AppKey, source: Source, item: Item?, folder: FolderItem?, dockIndex: Int) {
        val entries = mutableListOf<PixelMenu.Entry>()
        entries += shortcutEntries(key)
        when (source) {
            Source.DRAWER -> {
                entries += PixelMenu.Entry("Add to home") {
                    val page = layout.placeAnywhere(AppItem(key, 0, 0), workspace.currentPage)
                    save()
                    closeDrawer()
                    rebuild()
                    workspace.snapTo(page)
                }
                entries += PixelMenu.Entry("Hide from drawer") {
                    prefs.hidden = prefs.hidden + key.id()
                    drawer.setApps(visibleApps())
                    Pixel.say(this, "${apps.label(key)} hidden. Show it again in Settings.")
                }
            }
            Source.HOME -> entries += PixelMenu.Entry("Remove from home") {
                item?.let { layout.remove(it) }
                save()
                rebuild()
            }
            Source.DOCK -> entries += PixelMenu.Entry("Remove from dock") {
                layout.dock[dockIndex] = null
                save()
                rebuild()
            }
            Source.FOLDER -> entries += PixelMenu.Entry("Remove from folder") {
                removeFromFolder(folder!!, key)
                closeFolder()
                save()
                rebuild()
            }
        }
        entries += PixelMenu.Entry("App info") { apps.openAppInfo(key, null) }
        if (canUninstall(key)) entries += PixelMenu.Entry("Uninstall") { uninstall(key) }
        menu.show(view, entries, view.iconRect())
    }

    /** The app's own shortcuts ("New message"). Android only allows this for the default home app. */
    private fun shortcutEntries(key: AppKey): List<PixelMenu.Entry> {
        val launcher = apps.launcher
        return try {
            if (!launcher.hasShortcutHostPermission()) return emptyList()
            val user = apps.userFor(key) ?: return emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(key.pkg)
                .setActivity(apps.component(key))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (launcher.getShortcuts(query, user) ?: emptyList())
                .filter { it.isEnabled }
                .sortedBy { (if (it.isDeclaredInManifest) 0 else 1000) + it.rank }
                .take(4)
                .map { sc ->
                    PixelMenu.Entry((sc.shortLabel ?: sc.id).toString()) {
                        try {
                            launcher.startShortcut(sc, null, null)
                        } catch (e: Exception) {
                            Pixel.say(this, "That shortcut couldn't be opened.")
                        }
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun canUninstall(key: AppKey): Boolean = try {
        val user = apps.userFor(key) ?: throw IllegalStateException("profile gone")
        val info = apps.launcher.getApplicationInfo(key.pkg, 0, user)
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0 && key.pkg != packageName
    } catch (e: Exception) {
        false
    }

    private fun uninstall(key: AppKey) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", key.pkg, null))
                .putExtra(Intent.EXTRA_USER, apps.userFor(key)))
        } catch (e: Exception) {
            Pixel.say(this, "Couldn't start the uninstall for ${apps.label(key)}.")
        }
    }

    private fun removeFromFolder(folder: FolderItem, key: AppKey) {
        folder.apps.remove(key)
        layout.pages.firstOrNull { it.contains(folder) }?.let { layout.dissolveSmallFolders(it) }
    }

    // ---------------------------------------------------------------- drag and drop

    private var dragTarget: DropTarget = DropTarget.None
    private var landed: Any? = null
    private val pageFlip = Runnable { flipPageDuringDrag() }
    private var flipDirection = 0

    private sealed class DropTarget {
        object None : DropTarget()
        object Remove : DropTarget()
        class Dock(val slot: Int) : DropTarget()
        class Cell(val page: Int, val x: Int, val y: Int) : DropTarget()
    }

    private fun startDrag(view: View, info: DragInfo) {
        menu.dismiss()
        val shadow = (view as? AppIconView)?.shadowBuilder() ?: View.DragShadowBuilder(view)
        if (info.source == Source.DRAWER) {
            // Hide at once: the search box would otherwise take the drop as text mid-animation.
            drawer.hideKeyboard()
            drawer.animate().cancel()
            drawer.visibility = View.GONE
            drawer.reset()
        }
        if (info.source == Source.FOLDER) {
            folderView.hideKeyboard()
            folderView.visibility = View.GONE
        }
        val started = view.startDragAndDrop(null, shadow, info, View.DRAG_FLAG_OPAQUE)
        if (!started) return
        if (info.source != Source.DRAWER) {
            removeBar.background = PanelDrawable(this, withAlpha(Pixel.PANEL, 230), Pixel.MUTED)
            removeBar.visibility = View.VISIBLE
        }
        if (info.source == Source.HOME || info.source == Source.DOCK) view.alpha = 0.25f
    }

    private fun onDrag(e: DragEvent): Boolean {
        val info = e.localState as? DragInfo ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragTarget = targetAt(e.x, e.y, info)
                showTarget(dragTarget, info)
                val edge = Pixel.dp(this, 24f)
                val dir = when {
                    dragTarget is DropTarget.Cell && e.x < edge -> -1
                    dragTarget is DropTarget.Cell && e.x > root.width - edge -> 1
                    else -> 0
                }
                if (dir != flipDirection) {
                    flipDirection = dir
                    root.removeCallbacks(pageFlip)
                    if (dir != 0) root.postDelayed(pageFlip, 550)
                }
            }
            DragEvent.ACTION_DROP -> {
                landed = null
                val ok = drop(info, targetAt(e.x, e.y, info))
                if (ok && landed == null) landed = (info.item as? FolderItem) ?: info.singleApp()
                return ok
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                root.removeCallbacks(pageFlip)
                flipDirection = 0
                clearTargets()
                removeBar.visibility = View.GONE
                info.view?.alpha = 1f
                if (openFolder != null && info.source == Source.FOLDER) openFolder = null
                layout.trimEmptyPages()
                save()
                rebuild()
                landed?.let { tag -> root.post { hopLanded(tag) } }
                landed = null
            }
        }
        return true
    }

    private fun flipPageDuringDrag() {
        val next = workspace.currentPage + flipDirection
        if (next < 0) return
        if (next >= layout.pages.size) {
            // Dragging past the last page makes a new one.
            layout.pages.add(mutableListOf())
            workspace.addView(CellLayout(this, layout.cols(), layout.rows()))
            dots.count = layout.pages.size
        }
        workspace.snapTo(next)
        root.postDelayed(pageFlip, 900)
    }

    private fun targetAt(x: Float, y: Float, info: DragInfo): DropTarget {
        if (removeBar.visibility == View.VISIBLE && y < removeBar.bottom) return DropTarget.Remove
        if (y >= dock.top && y <= dock.bottom) return DropTarget.Dock(dock.slotAt(x - dock.left))
        val page = workspace.page(workspace.currentPage) ?: return DropTarget.None
        val loc = IntArray(2)
        page.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val px = x - (loc[0] - rootLoc[0])
        val py = y - (loc[1] - rootLoc[1])
        if (py < 0 || py > page.height) return DropTarget.None
        var (cx, cy) = page.cellAt(px, py)
        val (w, h) = info.size
        cx = (cx - (w - 1) / 2).coerceIn(0, layout.cols() - w)
        cy = (cy - (h - 1) / 2).coerceIn(0, layout.rows() - h)
        return DropTarget.Cell(workspace.currentPage, cx, cy)
    }

    private fun showTarget(target: DropTarget, info: DragInfo) {
        val accent = prefs.accent
        removeBar.setTextColor(if (target is DropTarget.Remove) accent else Pixel.INK)
        for (i in 0 until workspace.pageCount) {
            val page = workspace.page(i) ?: continue
            if (target is DropTarget.Cell && target.page == i) {
                val (w, h) = info.size
                page.showDropHint(target.x, target.y, w, h, accent)
            } else {
                page.clearDropHint()
            }
        }
        dock.panel.border = if (target is DropTarget.Dock) accent else withAlpha(Pixel.MUTED, 120)
        dock.invalidate()
    }

    private fun clearTargets() {
        for (i in 0 until workspace.pageCount) workspace.page(i)?.clearDropHint()
        dock.panel.border = withAlpha(Pixel.MUTED, 120)
        dock.invalidate()
    }

    /** The dragged app, if what's being dragged is a single app. */
    private fun DragInfo.singleApp(): AppKey? = when {
        item is AppItem -> item.app
        item == null -> app
        else -> null
    }

    /** Take the dragged thing out of where it came from. */
    private fun removeFromSource(info: DragInfo) {
        when (info.source) {
            Source.HOME -> info.item?.let { layout.remove(it) }
            Source.DOCK -> if (info.dockIndex >= 0) layout.dock[info.dockIndex] = null
            Source.FOLDER -> if (info.folder != null && info.app != null) removeFromFolder(info.folder, info.app)
            Source.DRAWER -> {}
        }
    }

    private fun drop(info: DragInfo, target: DropTarget): Boolean {
        when (target) {
            DropTarget.None -> return false
            DropTarget.Remove -> {
                removeFromSource(info)
                (info.item as? WidgetItem)?.let {
                    widgetHost.deleteAppWidgetId(it.widgetId)
                    widgetViews.remove(it.widgetId)
                }
                return true
            }
            is DropTarget.Dock -> {
                val app = info.singleApp() ?: run {
                    Pixel.say(this, "Only apps fit in the dock.")
                    return false
                }
                val occupant = layout.dock.getOrNull(target.slot)
                when {
                    occupant == null -> {
                        removeFromSource(info)
                        layout.dock[target.slot] = app
                    }
                    info.source == Source.DOCK -> {
                        layout.dock[info.dockIndex] = occupant
                        layout.dock[target.slot] = app
                    }
                    info.source == Source.HOME && info.item is AppItem -> {
                        val home = info.item
                        val page = layout.pages.first { it.contains(home) }
                        page.remove(home)
                        page.add(AppItem(occupant, home.x, home.y))
                        layout.dock[target.slot] = app
                    }
                    else -> {
                        Pixel.say(this, "That dock spot is taken. Drag that app out first.")
                        return false
                    }
                }
                return true
            }
            is DropTarget.Cell -> {
                val (w, h) = info.size
                val moving = info.item
                val occupant = layout.itemAt(target.page, target.x, target.y)?.takeIf { it !== moving }
                val app = info.singleApp()
                when {
                    layout.isFree(target.page, target.x, target.y, w, h, moving) -> {
                        removeFromSource(info)
                        val item = moving ?: AppItem(app!!, 0, 0)
                        item.x = target.x
                        item.y = target.y
                        layout.pages[target.page].add(item)
                    }
                    occupant is AppItem && app != null && w == 1 -> {
                        if (occupant.app == app) return false
                        removeFromSource(info)
                        val page = layout.pages[target.page]
                        page.remove(occupant)
                        val made = FolderItem("Folder", mutableListOf(occupant.app, app), occupant.x, occupant.y)
                        page.add(made)
                        landed = made
                    }
                    occupant is FolderItem && app != null -> {
                        if (occupant === info.folder || occupant.apps.contains(app)) return false
                        removeFromSource(info)
                        occupant.apps.add(app)
                        landed = occupant
                    }
                    else -> {
                        Pixel.say(this, "No room there.")
                        return false
                    }
                }
                return true
            }
        }
    }

    // ---------------------------------------------------------------- widgets

    private fun widgetView(item: WidgetItem): View {
        widgetViews[item.widgetId]?.let {
            it.tagItem = item
            return it
        }
        val info = widgetManager.getAppWidgetInfo(item.widgetId)
        if (info == null) {
            // The app that made this widget is gone. Say so; long-press removes it.
            return Pixel.label(this, "WIDGET UNAVAILABLE\nHOLD TO REMOVE", 9f, Pixel.MUTED).apply {
                gravity = Gravity.CENTER
                background = PanelDrawable(context, withAlpha(Pixel.PANEL, 200), Pixel.MUTED)
                setOnLongClickListener {
                    menu.show(this, listOf(PixelMenu.Entry("Remove") {
                        this@LauncherActivity.layout.remove(item)
                        widgetHost.deleteAppWidgetId(item.widgetId)
                        save()
                        rebuild()
                    }))
                    true
                }
            }
        }
        val view = widgetHost.createView(this, item.widgetId, info) as PixelWidgetView
        view.tagItem = item
        view.onLongPress = { showWidgetMenu(it) }
        view.onDragOut = { v -> v.tagItem?.let { startDrag(v, DragInfo(Source.HOME, item = it, view = v)) } }
        widgetViews[item.widgetId] = view
        return view
    }

    private fun showWidgetMenu(view: PixelWidgetView) {
        val item = view.tagItem ?: return
        menu.show(view, listOf(
            PixelMenu.Entry("Resize  ${item.w}x${item.h}") { showResizeMenu(view, item) },
            PixelMenu.Entry("Remove") {
                layout.remove(item)
                widgetHost.deleteAppWidgetId(item.widgetId)
                widgetViews.remove(item.widgetId)
                save()
                rebuild()
            },
        ))
    }

    /** One step at a time; the menu reopens so you can keep tapping. */
    private fun showResizeMenu(view: PixelWidgetView, item: WidgetItem) {
        val info = widgetManager.getAppWidgetInfo(item.widgetId)
        val page = layout.pages.indexOfFirst { it.contains(item) }
        if (page < 0) return
        val (minW, minH) = minCells(info)
        fun step(dw: Int, dh: Int) {
            val w = item.w + dw
            val h = item.h + dh
            when {
                w < minW || h < minH -> Pixel.say(this, "This widget can't get any smaller.")
                !layout.isFree(page, item.x, item.y, w, h, item) -> Pixel.say(this, "Not enough room. Move things out of the way first.")
                else -> {
                    item.w = w
                    item.h = h
                    save()
                    rebuild()
                }
            }
            root.post { widgetViews[item.widgetId]?.let { showResizeMenu(it, item) } }
        }
        menu.show(view, listOf(
            PixelMenu.Entry("Wider") { step(1, 0) },
            PixelMenu.Entry("Narrower") { step(-1, 0) },
            PixelMenu.Entry("Taller") { step(0, 1) },
            PixelMenu.Entry("Shorter") { step(0, -1) },
            PixelMenu.Entry("Done  ${item.w}x${item.h}") {},
        ))
    }

    private fun cellSize(): Pair<Float, Float> {
        val page = workspace.page(workspace.currentPage)
        val cw = page?.cellWidth()?.takeIf { it > 0 } ?: (root.width / layout.cols().toFloat())
        val ch = page?.cellHeight()?.takeIf { it > 0 } ?: (root.height * 0.7f / layout.rows())
        return cw to ch
    }

    /** How many cells a widget needs by default. */
    private fun defaultCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceAtMost(layout.cols()) to info.targetCellHeight.coerceAtMost(layout.rows())
        }
        val (cw, ch) = cellSize()
        val w = ceil(info.minWidth / cw).toInt().coerceIn(1, layout.cols())
        val h = ceil(info.minHeight / ch).toInt().coerceIn(1, layout.rows())
        return w to h
    }

    private fun minCells(info: AppWidgetProviderInfo?): Pair<Int, Int> {
        if (info == null) return 1 to 1
        val (cw, ch) = cellSize()
        val mw = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val mh = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        val resizable = info.resizeMode
        val w = if (resizable and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) ceil(mw / cw).toInt() else defaultCells(info).first
        val h = if (resizable and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) ceil(mh / ch).toInt() else defaultCells(info).second
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun showWidgetPicker() {
        val users = getSystemService(android.os.UserManager::class.java).userProfiles
        val providers = users.flatMap { widgetManager.getInstalledProvidersForProfile(it) }
        if (providers.isEmpty()) {
            Pixel.say(this, "None of your apps offer widgets.")
            return
        }
        WidgetPicker(this, prefs.accent, providers, { defaultCells(it) }) { addWidget(it) }.show()
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = info
        pendingWidgetId = id
        val allowed = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
        if (allowed) {
            configureWidget()
        } else {
            val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            @Suppress("DEPRECATION")
            startActivityForResult(ask, REQ_BIND)
        }
    }

    private fun configureWidget() {
        val info = pendingWidget ?: return
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONFIG, null)
                return
            } catch (e: Exception) {
                // No usable setup screen: place it with its defaults.
            }
        }
        placeWidget()
    }

    private fun placeWidget() {
        val info = pendingWidget ?: return
        val (w, h) = defaultCells(info)
        val item = WidgetItem(pendingWidgetId, info.provider.flattenToString(), 0, 0, w, h)
        val page = layout.placeAnywhere(item, workspace.currentPage)
        pendingWidget = null
        pendingWidgetId = -1
        save()
        rebuild()
        workspace.snapTo(page)
    }

    private fun cancelWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidget = null
        pendingWidgetId = -1
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureWidget() else {
                cancelWidget()
                Pixel.say(this, "The widget wasn't added because permission was declined.")
            }
            REQ_CONFIG -> if (resultCode == RESULT_OK) placeWidget() else cancelWidget()
        }
    }

    private fun save() {
        try {
            store.save(layout)
        } catch (e: Exception) {
            Pixel.say(this, "Couldn't save your home screen layout. The phone may be out of space.")
        }
    }

    companion object {
        private const val REQ_BIND = 11
        private const val REQ_CONFIG = 12
    }
}
