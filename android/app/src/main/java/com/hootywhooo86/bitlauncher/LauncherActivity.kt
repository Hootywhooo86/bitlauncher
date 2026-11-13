package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
    /** Which layout is showing: Shift.HOME or Shift.WORK. */
    private var mode = Shift.HOME
    private var layoutLoadedAt = System.currentTimeMillis()
    private lateinit var icons: IconCache
    private lateinit var usage: Usage
    private lateinit var scene: SceneView
    private lateinit var today: TodayView
    private lateinit var media: MediaSource
    private val account by lazy { FamilyAccount(this) }
    private var familyEvents: List<FamilyEvent>? = null
    private var familyFetchedAt = 0L
    private var familyProblem: String? = null
    private var lastWeather: WeatherNow? = null
    private val todayWidgetViews = HashMap<Int, PixelWidgetView>()
    private var widgetForToday = false
    /** "Stack another widget": the widget spot the next picked widget joins. */
    private var widgetForStack: WidgetItem? = null
    private lateinit var weather: WeatherClient
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
    private lateinit var notePanel: NotificationPanel
    private lateinit var popupLayer: FrameLayout
    private val onArrival: (Note) -> Unit = { showPopup(it) }

    private lateinit var widgetHost: PixelWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private val widgetViews = HashMap<Int, PixelWidgetView>()
    private var pendingWidget: AppWidgetProviderInfo? = null
    private var pendingWidgetId = -1

    private var openFolder: FolderItem? = null
    private var appliedTheme = ""
    private var appliedPack = ""
    private var editingIconFor: AppKey? = null
    private var appliedPrefs = -1
    private var firstRun = false
    private var insetTop = 0
    private var insetBottom = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pixel.load(this)
        prefs = Prefs(this)
        prefs.firstSeen // the first run is remembered from now on
        IconStyles.current = prefs.iconStyle
        IconStyles.accent = prefs.accent
        appliedTheme = prefs.theme
        appliedPack = prefs.iconPack
        icons = IconCache(this)
        usage = Usage(this)
        weather = WeatherClient(this)
        firstRun = LayoutStore(this, Shift.HOME).isFirstRun()
        mode = Shift.current(prefs)
        store = LayoutStore(this, mode)
        layout = if (store.isFirstRun() && mode == Shift.WORK) workFromHome() else store.load(prefs.grid)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = PixelWidgetHost(this)
        menu = PixelMenu(this, prefs.accent)

        edgeToEdge()
        buildViews()
        appliedPrefs = prefs.version

        apps = AppList(this, icons) { onAppsChanged() }
        apps.onRemoved = { ids ->
            // Uninstalled apps go, and so do shortcuts they had pinned.
            layout.removeApps { it.id() in ids || (PinnedShortcuts.isShortcut(it) && !apps.exists(it)) }
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
        autoBackup()
        // Leaving home ends the chance to undo; removed widgets are deleted for real now.
        if (::undoBar.isInitialized) dismissUndo()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
        }
        NotificationHub.unlisten(onNotifications)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.version != appliedPrefs) applyPrefs()
        if (prefs.layoutChanged > layoutLoadedAt) {
            // Something was pinned while we were away: pick up the saved layout.
            layoutLoadedAt = System.currentTimeMillis()
            layout = store.load(prefs.grid)
            rebuild()
        }
        Shift.current(prefs).let { if (it != mode) switchMode(it) }
        applyScene()
        refreshWeather()
        Popups.homeShowing = true
        NotificationHub.onArrival(onArrival)
    }

    override fun onPause() {
        super.onPause()
        Popups.homeShowing = false
        NotificationHub.offArrival(onArrival)
        menu.dismiss()
    }

    /** Folding or unfolding (or rotating) re-lays out home in place; nothing is reloaded. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        menu.dismiss()
        positionChrome()
        rebuild()
    }

    /**
     * Two pages side by side when the screen is wide and not tall, like an unfolded Pixel Fold.
     * A phone, or a tablet held upright, gets one.
     */
    private fun panesFor(c: android.content.res.Configuration): Int =
        if (c.screenWidthDp >= 600 && c.screenWidthDp >= c.screenHeightDp * 0.9f) 2 else 1

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
        if (notePanel.isOpen) {
            notePanel.close()
            closed = true
        }
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
        if (today.visibility == View.VISIBLE) {
            settleToday(0f, 0f)
            closed = true
        }
        if (::pageOverview.isInitialized && pageOverview.visibility == View.VISIBLE) {
            closePages()
            closed = true
        }
        if (resizing != null) {
            endResize()
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
        // The pixel scene sits behind everything; with "phone wallpaper" it's simply hidden.
        scene = SceneView(this)
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        workspace = Workspace(this)
        workspace.onScrolled = { scene.setScroll(it) }
        workspace.panes = panesFor(resources.configuration)
        workspace.listener = object : Workspace.Listener {
            override fun onDrawerDrag(px: Float) = dragDrawer(px)
            override fun onDrawerRelease(px: Float, velocityY: Float) = releaseDrawer(velocityY)
            override fun onSwipeDown() = openNotifications()
            override fun onEmptyLongPress(x: Float, y: Float) = showHomeMenu(x - workspace.scrollX, y)
            override fun onPageChanged(page: Int) {
                dots.current = page / workspace.panes
            }

            override fun onTodayDrag(px: Float) = dragToday(px)
            override fun onTodayRelease(px: Float, velocityX: Float) = releaseToday(px, velocityX)
            override fun onDoubleTap() = doubleTap()
            override fun onPinchIn() = openPages()
            override fun stackAt(x: Float, y: Float) = stackFlipAt(x, y)
            override fun iconSwipeAt(x: Float, y: Float) = swipeActionAt(x, y)
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
        drawer.answersFor = { q, matches -> searchAnswers(q, matches) }
        drawer.recentFor = {
            val hidden = prefs.hidden
            usage.recent().mapNotNull { apps.find(it) }.filter { it.key.id() !in hidden }
        }
        // Swiping down at the top of the list pulls the drawer down with the finger.
        drawer.onPullDown = { px -> drawerAnim?.cancel(); setDrawerProgress(1f - px / drawerTravel()) }
        drawer.onPullRelease = { px, vy ->
            val close = vy > flingSpeed || (vy > -flingSpeed && px > drawerTravel() * 0.25f)
            if (close) drawer.hideKeyboard()
            settleDrawer(if (close) 0f else 1f, kotlin.math.abs(vy))
        }
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        today = TodayView(this)
        today.visibility = View.GONE
        today.onPull = { px -> todayAnim?.cancel(); setTodayProgress(1f - px / todayWidth()) }
        today.onRelease = { px, vx ->
            val close = vx < -flingSpeed || (vx < flingSpeed && px > todayWidth() * 0.3f)
            settleToday(if (close) 0f else 1f, kotlin.math.abs(vx))
        }
        root.addView(today, FrameLayout.LayoutParams(-1, -1, Gravity.START))
        media = MediaSource(this) { if (today.visibility == View.VISIBLE) renderToday() }

        folderView = FolderView(this)
        folderView.visibility = View.GONE
        folderView.onClose = { closeFolder() }
        root.addView(folderView, FrameLayout.LayoutParams(-1, -1))

        notePanel = NotificationPanel(this, icons) { openPhoneShade() }
        root.addView(notePanel, FrameLayout.LayoutParams(-1, -1))
        popupLayer = FrameLayout(this)
        root.addView(popupLayer, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

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
            notePanel.setInsets(insetTop, insetBottom)
            popupLayer.setPadding(Pixel.dpi(this, 10), insetTop + Pixel.dpi(this, 6), Pixel.dpi(this, 10), 0)
            insets
        }
        buildUndoBar()
        root.setOnDragListener { _, e -> onDrag(e) }
        setContentView(root)
    }

    /** Size the dock and pad the pages around the system bars. */
    private fun positionChrome() {
        val gap = Pixel.dpi(this, 8)
        val screenW = resources.displayMetrics.widthPixels
        val panes = panesFor(resources.configuration)
        val panesChanged = panes != workspace.panes
        workspace.panes = panes
        workspace.gutter = if (workspace.panes > 1) Pixel.dpi(this, 32) else 0
        // One page's width: everything on home is sized to this, so an unfolded phone looks
        // like two phones side by side, not one phone stretched.
        val paneW = (screenW - gap * 2 - workspace.gutter * (workspace.panes - 1)) / workspace.panes

        val dockHeight = Pixel.dpi(this, prefs.iconDp() + 28)
        val dockSlotW = Pixel.dpi(this, prefs.iconDp() + 64)
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            height = dockHeight
            // Centred, and no wider than its icons need.
            width = minOf(screenW - gap * 2, layout.grid.dockSlots * dockSlotW)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(gap, 0, gap, insetBottom + gap)
        }
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + gap + dockHeight
        (removeBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop

        // Clock: 55% of a page's width, so each block is a whole number of pixels.
        val clockWidth = (paneW * 0.55f).toInt()
        val block = clockWidth / (PixelGlyphs.CLOCK_W + 1)
        (clock.layoutParams as android.widget.LinearLayout.LayoutParams).width = block * (PixelGlyphs.CLOCK_W + 1)
        (header.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + gap * 2
        header.visibility = if (prefs.homeClock) View.VISIBLE else View.GONE
        clock.accent = prefs.accent
        clock.font = prefs.clockFont
        val headerHeight = if (prefs.homeClock) block * (PixelGlyphs.H + 1) + Pixel.dpi(this, 30) + gap * 2 else 0

        workspace.setPadding(gap, insetTop + gap + headerHeight, gap, insetBottom + gap * 2 + dockHeight + Pixel.dpi(this, 20))
        header.requestLayout()
        // A wide screen gets more drawer columns rather than bigger gaps.
        val drawerCols = maxOf(layout.cols(), screenW / Pixel.dpi(this, prefs.iconDp() + 56))
        drawer.setColumns(if (workspace.panes > 1) drawerCols else layout.cols())
        // Today covers the whole screen on a phone, the left page on an unfolded Fold.
        (today.layoutParams as FrameLayout.LayoutParams).width = if (workspace.panes > 1) paneW + gap * 2 else -1
        today.setInsets(insetTop, insetBottom)
        drawer.setPadding(0, insetTop, 0, 0)
        drawer.setBottomInset(insetBottom)
        folderView.setPadding(0, insetTop, 0, insetBottom)
        dock.requestLayout()
        dots.requestLayout()
        removeBar.requestLayout()
        if (panesChanged && ::apps.isInitialized) rebuild()
    }

    private fun applyPrefs() {
        appliedPrefs = prefs.version
        if (prefs.theme != appliedTheme) {
            // Every panel is coloured when it's made: simplest and surest to start fresh.
            Pixel.load(this)
            recreate()
            return
        }
        if (prefs.iconPack != appliedPack) {
            appliedPack = prefs.iconPack
            apps.reload()
        }
        refreshDots()
        IconStyles.current = prefs.iconStyle
        IconStyles.accent = prefs.accent
        redrawIcons(drawer)
        menu = PixelMenu(this, prefs.accent)
        if (layout.grid != prefs.grid) {
            layout.resize(prefs.grid)
            save()
        }
        positionChrome()
        rebuild()
    }

    /** Icons drawn somewhere home doesn't rebuild (the drawer) pick up a new look. */
    private fun redrawIcons(v: View) {
        if (v is AppIconView) v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) redrawIcons(v.getChildAt(i))
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
                    is AppItem -> AppIconView(this).also { bindIcon(it, item.app, Source.HOME, item, pageIndex = pageIndex) }
                    is FolderItem -> AppIconView(this).also { bindFolder(it, item, pageIndex) }
                    is WidgetItem -> {
                        item.all().forEach { liveWidgets.add(it.first) }
                        if (item.isStack) stackView(item) else widgetView(item)
                    }
                }
                (view.parent as? ViewGroup)?.removeView(view)
                page.addView(view, CellParams(item.x, item.y, item.spanX, item.spanY))
            }
            page.tag = pageIndex
            workspace.addView(page)
        }
        // Two panes: a lone last page gets an empty partner, ready to drop things on.
        while (workspace.pageCount % workspace.panes != 0) workspace.addView(CellLayout(this, layout.cols(), layout.rows()))
        // Forget views for widgets that are gone.
        widgetViews.keys.retainAll(liveWidgets)
        workspace.snapTo(keepPage.coerceAtMost(workspace.pageCount - 1), animate = false)

        dock.removeAllViews()
        dock.slots = layout.grid.dockSlots
        layout.dock.forEachIndexed { slot, item ->
            val view = AppIconView(this)
            when (item) {
                is AppItem -> bindIcon(view, item.app, Source.DOCK, item, dockIndex = slot)
                is FolderItem -> bindFolder(view, item, -1, Source.DOCK, slot)
                else -> return@forEachIndexed
            }
            view.showLabel = false
            dock.addView(view, CellParams(slot, 0))
        }

        dots.accent = accent
        dots.count = workspace.screenCount
        dots.current = workspace.currentPage / workspace.panes
        drawer.setAccent(accent)
        drawer.setApps(visibleApps())
        folderView.setAccent(accent)
        emptyHint.visibility = if (layout.pages.all { it.isEmpty() }) View.VISIBLE else View.GONE
        refreshDots()
    }

    private fun updateDate() {
        dateLabel.text = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date()).uppercase() +
            if (mode == Shift.WORK) "  ·  WORK" else ""
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

    /** A landed icon swells slightly and settles into place, so you can see the drop worked. */
    private fun hop(view: View) {
        if (!Pixel.motion(this)) return
        view.scaleX = 1.18f
        view.scaleY = 1.18f
        view.animate().scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
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
            layout.removeApps { !apps.exists(it) }
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
            if (layout.dock.any { (it as? AppItem)?.app == entry.key }) continue
            layout.setDock(slot++, AppItem(entry.key, 0, 0))
        }
    }

    internal enum class Source { HOME, DOCK, DRAWER, FOLDER }

    /** What's being dragged, and where it came from. */
    internal class DragInfo(
        val source: Source,
        val app: AppKey? = null,
        val item: Item? = null,
        val folder: FolderItem? = null,
        val dockIndex: Int = -1,
        val view: View? = null,
    ) {
        val size: Pair<Int, Int> get() = (item?.spanX ?: 1) to (item?.spanY ?: 1)
    }

    private fun bindIcon(
        view: AppIconView,
        key: AppKey,
        source: Source,
        item: Item? = null,
        folder: FolderItem? = null,
        dockIndex: Int = -1,
        pageIndex: Int = -1,
    ) {
        view.tagItem = key
        view.sprite = icons.get(key)
        view.isNew = isNewApp(key)
        view.folderSprites = null
        view.hasDot = false
        view.label = apps.label(key)
        view.showLabel = prefs.labels && when (source) {
            Source.DOCK -> false
            Source.HOME -> pageIndex !in layout.unlabelledPages
            Source.FOLDER -> folder?.labels != false
            Source.DRAWER -> true
        }
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

    private fun bindFolder(view: AppIconView, folder: FolderItem, pageIndex: Int, source: Source = Source.HOME, dockIndex: Int = -1) {
        view.tagItem = folder
        view.sprite = null
        // The TV flips through up to a dozen apps; every other style shows the first four.
        view.folderSprites = folder.apps.take(if (folder.style == "tv") 12 else 4).map { icons.get(it) }
        view.folderCover = folder.cover?.let { icons.get(it) }
        view.folderTint = folder.color
        view.folderStyle = folder.style
        view.folderCount = folder.apps.size
        view.showCount = folder.showCount
        view.label = folder.name
        view.showLabel = prefs.labels && pageIndex !in layout.unlabelledPages
        view.iconSizeDp = prefs.iconDp()
        view.setOnClickListener { view.playOpen { openFolder(folder) } }
        view.setOnLongClickListener {
            showFolderMenu(view, folder)
            true
        }
        view.onDragOut = { startDrag(it, DragInfo(source, item = folder, dockIndex = dockIndex, view = it)) }
    }

    private fun showFolderMenu(view: AppIconView, folder: FolderItem) {
        if (prefs.layoutLocked) {
            menu.show(view, listOf(PixelMenu.Entry("Open") { openFolder(folder) }, PixelMenu.Entry("Layout locked") { locked() }), view.iconRect())
            return
        }
        val entries = mutableListOf(
            PixelMenu.Entry("Open") { openFolder(folder) },
            PixelMenu.Entry("Style") { showFolderStyles(view, folder) },
            PixelMenu.Entry("Colour") { showFolderColours(view, folder) },
            PixelMenu.Entry("Sort A-Z") {
                val collator = java.text.Collator.getInstance()
                folder.apps.sortWith { a, b -> collator.compare(apps.label(a), apps.label(b)) }
                saveAndRebuild()
            },
            PixelMenu.Entry("Sort by most used") {
                // Ties keep their current order, so a fresh folder doesn't shuffle.
                val order = folder.apps.sortedByDescending { usage.count(it) }
                folder.apps.clear()
                folder.apps.addAll(order)
                saveAndRebuild()
            },
            PixelMenu.Entry(if (folder.labels) "Hide labels inside" else "Show labels inside") {
                folder.labels = !folder.labels
                saveAndRebuild()
            },
        )
        entries += PixelMenu.Entry(if (folder.showCount) "Hide app count" else "Show app count") {
            folder.showCount = !folder.showCount
            saveAndRebuild()
        }
        entries += PixelMenu.Entry("Remove folder") {
            removing("${folder.name} removed. Its apps stay in the drawer") {
                layout.remove(folder)
                layout.dock.indexOfFirst { it === folder }.takeIf { it >= 0 }?.let { layout.setDock(it, null) }
            }
        }
        menu.show(view, entries, view.iconRect())
    }

    private fun showFolderStyles(view: AppIconView, folder: FolderItem) {
        menu.show(view, FolderArt.STYLES.map { (key, name) ->
            PixelMenu.Entry(if (folder.style == key) "$name *" else name) {
                folder.style = key
                // A big cover needs an app to show: the first, until you pick another.
                if (key == "cover" && folder.cover == null) folder.cover = folder.apps.firstOrNull()
                saveAndRebuild()
            }
        }, view.iconRect())
    }

    private fun showFolderColours(view: AppIconView, folder: FolderItem) {
        val names = listOf("Violet", "Teal", "Orange", "Blue", "Pink", "White")
        val entries = mutableListOf(PixelMenu.Entry("Plain") {
            folder.color = null
            saveAndRebuild()
        })
        Pixel.ACCENTS.forEachIndexed { i, color ->
            entries += PixelMenu.Entry(names.getOrElse(i) { "Colour ${i + 1}" }) {
                folder.color = color
                saveAndRebuild()
            }
        }
        menu.show(view, entries, view.iconRect())
    }

    /**
     * Installed in the last two days, after you started using bitlauncher, and not opened yet.
     * (So installing bitlauncher doesn't make every app on the phone say NEW.)
     */
    private fun isNewApp(key: AppKey): Boolean {
        if (PinnedShortcuts.isShortcut(key)) return false
        val installed = apps.find(key)?.installedAt ?: return false
        return newApp(installed, prefs.firstSeen, System.currentTimeMillis(), usage.count(key))
    }

    private fun saveAndRebuild() {
        save()
        rebuild()
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
        if (PinnedShortcuts.isShortcut(key)) {
            if (PinnedShortcuts.launch(this, key, bounds, opts)) usage.record(key)
            else Pixel.say(this, "${apps.label(key)} no longer works; its app removed it. Hold it to remove it.")
            return
        }
        try {
            val user = apps.userFor(key) ?: throw IllegalStateException()
            apps.launcher.startMainActivity(apps.component(key), user, bounds, opts)
            usage.record(key)
        } catch (e: Exception) {
            Pixel.say(this, "${apps.label(key)} can't be opened. It may have been uninstalled or disabled.")
        }
    }

    // ---------------------------------------------------------------- 128bit account

    /** The family feed for Today, fetched at most every five minutes. */
    private fun refreshFamily() {
        if (!account.signedIn || System.currentTimeMillis() - familyFetchedAt < 5 * 60_000L) return
        familyFetchedAt = System.currentTimeMillis()
        account.events(80) { r ->
            r.onSuccess { familyEvents = it; familyProblem = null }
            r.onFailure { familyProblem = it.message; familyFetchedAt = 0L }
            if (today.visibility == View.VISIBLE) renderToday()
        }
    }

    /** Leaving home after a change: back it up, at most every ten minutes. */
    private fun autoBackup() {
        if (!prefs.autoBackup || !account.signedIn) return
        val now = System.currentTimeMillis()
        if (changedAt <= account.lastBackup || now - account.lastBackup < 10 * 60_000L) return
        account.backUp(LayoutStore.backupJson(this, prefs)) { r ->
            account.backupProblem = r.exceptionOrNull()?.message
        }
    }

    // ---------------------------------------------------------------- page overview

    private lateinit var pageOverview: PageOverview

    private fun openPages() {
        menu.dismiss()
        if (locked()) return
        if (!::pageOverview.isInitialized) {
            pageOverview = PageOverview(this)
            root.addView(pageOverview, FrameLayout.LayoutParams(-1, -1))
            pageOverview.onClose = { closePages() }
            pageOverview.onGoTo = { i ->
                closePages()
                workspace.snapTo(i)
            }
            pageOverview.onMove = { from, to ->
                layout.movePage(from, to)
                saveAndRebuild()
                showPages()
            }
            pageOverview.onDelete = { i ->
                if (layout.pages.getOrNull(i).isNullOrEmpty()) {
                    layout.removePage(i)
                    saveAndRebuild()
                } else {
                    removing("Page ${i + 1} deleted") {
                        layout.removePage(i).filterIsInstance<WidgetItem>().forEach { w -> w.all().forEach { deleteWidgetLater(it.first) } }
                    }
                }
                showPages()
            }
            pageOverview.onAdd = {
                layout.pages.add(mutableListOf())
                saveAndRebuild()
                showPages()
            }
        }
        pageOverview.setInsets(insetTop, insetBottom)
        showPages()
        pageOverview.visibility = View.VISIBLE
        pageOverview.bringToFront()
        if (::undoBar.isInitialized) undoBar.bringToFront()
        if (Pixel.motion(this)) {
            pageOverview.alpha = 0f
            pageOverview.scaleX = 1.08f
            pageOverview.scaleY = 1.08f
            pageOverview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Ease.enter).start()
        }
    }

    /** A picture of each page, small enough to keep memory low. */
    private fun showPages() {
        val cols = if (workspace.panes > 1) 4 else 3
        val cellW = (root.width - Pixel.dpi(this, 24)) / cols
        val first = workspace.page(0)
        val ratio = if (first != null && first.width > 0) first.height / first.width.toFloat() else 1.6f
        val pictures = layout.pages.indices.map { i ->
            val page = workspace.page(i) ?: return@map null
            if (page.width == 0 || page.height == 0) return@map null
            val scale = (cellW / page.width.toFloat()).coerceAtMost(1f)
            Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp)
                c.scale(scale, scale)
                page.draw(c)
            }
        }
        pageOverview.show(pictures, prefs.accent, cols, ratio, cellW)
    }

    private fun closePages() {
        if (Pixel.motion(this)) pageOverview.animate().alpha(0f).setDuration(150).withEndAction { pageOverview.visibility = View.GONE }.start()
        else pageOverview.visibility = View.GONE
    }

    // ---------------------------------------------------------------- resizing widgets

    private var resizing: ResizeFrame? = null

    private fun startResize(item: WidgetItem) {
        menu.dismiss()
        val pageIndex = layout.pages.indexOfFirst { it.contains(item) }
        val page = workspace.page(pageIndex) ?: return
        val loc = IntArray(2)
        page.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val (minW, minH) = minCells(widgetManager.getAppWidgetInfo(item.widgetId))
        val frame = ResizeFrame(this).apply {
            cellLeft = (loc[0] - rootLoc[0] + page.paddingLeft).toFloat()
            cellTop = (loc[1] - rootLoc[1] + page.paddingTop).toFloat()
            cellW = page.cellWidth()
            cellH = page.cellHeight()
            cols = layout.cols()
            rows = layout.rows()
            this.minW = minW.coerceAtMost(cols)
            this.minH = minH.coerceAtMost(rows)
            accent = prefs.accent
            x = item.x; y = item.y; w = item.w; h = item.h
            canFit = { x, y, w, h -> layout.isFree(pageIndex, x, y, w, h, item) }
            onResize = { x, y, w, h ->
                item.x = x; item.y = y; item.w = w; item.h = h
                saveAndRebuild()
                bringToFront()
            }
            onDone = { endResize() }
        }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        resizing = frame
    }

    private fun endResize() {
        resizing?.let { root.removeView(it) }
        resizing = null
    }

    // ---------------------------------------------------------------- undo

    private lateinit var undoBar: android.widget.LinearLayout
    private lateinit var undoText: TextView
    private var undoSnapshot: String? = null
    private var undoAfterDrag: String? = null
    private var undoAfterDragText = ""
    /** Widgets removed but not yet deleted: deleting one can't be undone, so it waits for the undo bar. */
    private val widgetsToDelete = mutableListOf<Int>()
    private val hideUndo = Runnable { dismissUndo() }

    private fun buildUndoBar() {
        undoText = Pixel.label(this, "", 10f, Pixel.INK)
        undoBar = android.widget.LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, prefs.accent)
            val p = Pixel.dpi(context, 12)
            setPadding(p + Pixel.dpi(context, 4), p / 2, p / 2, p / 2 + Pixel.dpi(context, 2))
            visibility = View.GONE
            isClickable = true
            addView(undoText, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(Pixel.button(context, "UNDO", prefs.accent) { undo() })
        }
        root.addView(undoBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** Make a removal undoable for five seconds. */
    private fun removing(what: String, change: () -> Unit) {
        commitWidgetDeletes()
        val before = layout.toJson().toString()
        change()
        save()
        rebuild()
        showUndo(what, before)
    }

    private fun deleteWidgetLater(id: Int) {
        widgetsToDelete += id
        widgetViews.remove(id)
    }

    private fun commitWidgetDeletes() {
        widgetsToDelete.forEach { widgetHost.deleteAppWidgetId(it) }
        widgetsToDelete.clear()
    }

    private fun showUndo(what: String, snapshot: String) {
        undoSnapshot = snapshot
        undoText.text = what.uppercase()
        val lp = undoBar.layoutParams as FrameLayout.LayoutParams
        val gap = Pixel.dpi(this, 8)
        lp.setMargins(gap, 0, gap, insetBottom + gap * 2 + dock.height)
        undoBar.layoutParams = lp
        undoBar.background = PanelDrawable(this, Pixel.PANEL, prefs.accent)
        undoBar.visibility = View.VISIBLE
        if (Pixel.motion(this)) {
            undoBar.alpha = 0f
            undoBar.translationY = Pixel.dp(this, 16f)
            undoBar.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(Ease.enter).start()
        }
        root.removeCallbacks(hideUndo)
        root.postDelayed(hideUndo, 5000)
    }

    private fun dismissUndo() {
        root.removeCallbacks(hideUndo)
        undoSnapshot = null
        commitWidgetDeletes()
        if (undoBar.visibility != View.VISIBLE) return
        if (Pixel.motion(this)) undoBar.animate().alpha(0f).setDuration(150).withEndAction { undoBar.visibility = View.GONE }.start()
        else undoBar.visibility = View.GONE
    }

    /** Put the layout back exactly as it was before the removal. Widgets still exist, so they come back too. */
    private fun undo() {
        val snap = undoSnapshot ?: return
        widgetsToDelete.clear()
        undoSnapshot = null
        layout = HomeLayout.fromJson(org.json.JSONObject(snap))
        layout.resize(prefs.grid)
        save()
        rebuild()
        dismissUndo()
    }

    // ---------------------------------------------------------------- shift mode

    /** The first time Work is used, it starts as a copy of Home (without widgets: each layout owns its own). */
    private fun workFromHome(): HomeLayout {
        val copy = HomeLayout.fromJson(org.json.JSONObject(LayoutStore(this, Shift.HOME).load(prefs.grid).toJson().toString()))
        copy.pages.forEach { page -> page.removeAll { it is WidgetItem } }
        LayoutStore(this, Shift.WORK).save(copy)
        Pixel.say(this, "Work layout made from a copy of Home. Change it however you like.")
        return copy
    }

    private fun switchMode(newMode: String) {
        if (::undoBar.isInitialized) dismissUndo() // an undo belongs to the layout it came from
        save()
        closeOverlays()
        mode = newMode
        prefs.mode = newMode
        store = LayoutStore(this, newMode)
        layout = if (store.isFirstRun() && newMode == Shift.WORK) workFromHome() else store.load(prefs.grid)
        positionChrome()
        rebuild()
        updateDate()
        workspace.snapTo(0, animate = false)
    }

    // ---------------------------------------------------------------- scene and weather

    private fun applyScene() {
        val key = prefs.scene
        scene.visibility = if (key == "none") View.GONE else View.VISIBLE
        scene.scene = key
        scene.accent = prefs.accent
        scene.timeOfDay = prefs.sceneTime
        LockWallpaper.sync(this)
    }

    /** Fetch weather if it's on and stale; then let everything that shows it update. */
    private fun refreshWeather() {
        if (!prefs.weather) {
            scene.sky = null
            onWeather(null)
            return
        }
        weather.cached()?.let { applyWeather(it) }
        weather.refreshIfStale { it?.let(::applyWeather) ?: onWeather(null) }
    }

    private fun applyWeather(w: WeatherNow) {
        scene.sunrise = w.sunrise
        scene.sunset = w.sunset
        scene.sky = w.sky
        onWeather(w)
    }

    private fun onWeather(w: WeatherNow?) {
        lastWeather = w
        if (today.visibility == View.VISIBLE) renderToday()
    }

    // ---------------------------------------------------------------- Today

    private var todayAnim: android.animation.ValueAnimator? = null

    private fun todayWidth() = (if (today.width > 0) today.width else root.width).toFloat().coerceAtLeast(1f)

    private fun setTodayProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        today.translationX = -todayWidth() * (1f - p)
        today.alpha = 0.4f + 0.6f * p
        for (v in listOf(workspace, dock, dots, header)) v.alpha = 1f - 0.5f * p
    }

    private fun todayProgress() = if (today.visibility != View.VISIBLE) 0f else 1f + today.translationX / todayWidth()

    private fun prepareToday() {
        todayAnim?.cancel()
        if (today.visibility == View.VISIBLE) return
        menu.dismiss()
        refreshFamily()
        renderToday()
        media.start()
        setTodayProgress(0f)
        today.visibility = View.VISIBLE
    }

    private fun dragToday(px: Float) {
        prepareToday()
        setTodayProgress(px / todayWidth())
    }

    private fun releaseToday(px: Float, velocityX: Float) {
        val open = velocityX > flingSpeed || (velocityX > -flingSpeed && px / todayWidth() > 0.3f)
        settleToday(if (open) 1f else 0f, kotlin.math.abs(velocityX))
    }

    private fun settleToday(target: Float, velocity: Float) {
        todayAnim?.cancel()
        val from = todayProgress()
        val done = {
            if (target == 0f) {
                today.visibility = View.GONE
                media.stop()
                setTodayProgress(0f)
                for (v in listOf(workspace, dock, dots, header)) v.alpha = 1f
            }
        }
        if (!Pixel.motion(this) || from == target) {
            setTodayProgress(target)
            done()
            return
        }
        val w = todayWidth()
        todayAnim = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = Ease.finishDuration(kotlin.math.abs(target - from) * w, w, velocity)
            interpolator = if (target == 1f) Ease.move else Ease.exit
            addUpdateListener { setTodayProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    /** Fill Today: date, weather, calendar, now playing, then your widgets and the add button. */
    private fun renderToday() {
        val accent = prefs.accent
        val cards = today.cards
        // Widget views are kept and moved, not re-made, so they don't flicker or lose state.
        for (v in todayWidgetViews.values) (v.parent as? ViewGroup)?.removeView(v)
        cards.removeAllViews()
        cards.addView(Pixel.label(this, java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date()).uppercase(), 11f, accent))
        cards.addView(Pixel.label(this, java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault()).format(java.util.Date()).uppercase(), 20f, Pixel.INK).apply {
            setPadding(0, Pixel.dpi(context, 6), 0, Pixel.dpi(context, 16))
        })
        cards.addView(TodayCards.weather(this, accent, lastWeather.takeIf { prefs.weather },
            if (prefs.weather) weather.problem else null, prefs.fahrenheit) { startActivity(Intent(this, SettingsActivity::class.java)) })
        cards.addView(TodayCards.events(this, accent,
            if (CalendarSource.hasPermission(this)) CalendarSource.upcoming(this) else null,
            onAsk = { requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), REQ_CALENDAR) },
            onOpen = { e ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, e.end))
                } catch (ex: Exception) {
                    Pixel.say(this, "No calendar app found to open that event.")
                }
            }))
        media.current()?.let { cards.addView(TodayCards.media(this, accent, it)) }
        Tickets.next(this)?.let { cards.addView(TicketView.make(this, it, accent), android.widget.LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Pixel.dpi(this@LauncherActivity, 14)
        }) }
        cards.addView(TodayCards.family(this, accent, account.signedIn, familyEvents, familyProblem) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        val stored = prefs.todayWidgets
        for ((id, _, hDp) in stored) {
            val v = todayWidgetView(id) ?: continue
            cards.addView(v, android.widget.LinearLayout.LayoutParams(-1, Pixel.dpi(this, hDp)).apply { bottomMargin = Pixel.dpi(this@LauncherActivity, 10) })
        }
        // Forget widgets whose app is gone.
        val live = stored.filter { widgetManager.getAppWidgetInfo(it.first) != null }
        if (live.size != stored.size) {
            stored.filterNot { it in live }.forEach { widgetHost.deleteAppWidgetId(it.first); todayWidgetViews.remove(it.first) }
            prefs.todayWidgets = live
        }
        cards.addView(Pixel.button(this, "+ ADD WIDGET", accent) {
            widgetForToday = true
            showWidgetPicker()
        }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = Pixel.dpi(this@LauncherActivity, 4) })
        if (stored.isEmpty()) cards.addView(Pixel.label(this, "PUT RAILTIME, FIT OR ANY WIDGET HERE", 9f, Pixel.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Pixel.dpi(context, 10), 0, 0)
        })
    }

    private fun todayWidgetView(id: Int): PixelWidgetView? {
        todayWidgetViews[id]?.let { return it }
        val info = widgetManager.getAppWidgetInfo(id) ?: return null
        val v = widgetHost.createView(this, id, info) as PixelWidgetView
        v.onLongPress = { showTodayWidgetMenu(it, id) }
        v.onDragOut = {}
        todayWidgetViews[id] = v
        return v
    }

    private fun showTodayWidgetMenu(view: View, id: Int) {
        if (locked()) return
        fun edit(change: (MutableList<Triple<Int, String, Int>>, Int) -> Unit) {
            val list = prefs.todayWidgets.toMutableList()
            val i = list.indexOfFirst { it.first == id }
            if (i < 0) return
            change(list, i)
            prefs.todayWidgets = list
            renderToday()
        }
        menu.show(view, listOf(
            PixelMenu.Entry("Move up") { edit { l, i -> if (i > 0) l.add(i - 1, l.removeAt(i)) } },
            PixelMenu.Entry("Move down") { edit { l, i -> if (i < l.size - 1) l.add(i + 1, l.removeAt(i)) } },
            PixelMenu.Entry("Taller") { edit { l, i -> l[i] = l[i].copy(third = (l[i].third + 40).coerceAtMost(600)) } },
            PixelMenu.Entry("Shorter") { edit { l, i -> l[i] = l[i].copy(third = (l[i].third - 40).coerceAtLeast(60)) } },
            PixelMenu.Entry("Remove") {
                edit { l, i -> l.removeAt(i) }
                widgetHost.deleteAppWidgetId(id)
                todayWidgetViews.remove(id)
            },
        ))
    }

    // ---------------------------------------------------------------- gestures

    private fun doubleTap() {
        if (!prefs.doubleTapLock) return
        if (LockService.lock()) return
        if (Build.VERSION.SDK_INT < 28) {
            Pixel.say(this, "Double-tap to lock needs Android 9 or newer.")
            return
        }
        Pixel.say(this, "To lock with a double tap, turn on bitlauncher in Accessibility.")
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    /** The swipe-up action of the home icon under (x, y) in the workspace, if it has one. */
    private fun swipeActionAt(x: Float, y: Float): (() -> Unit)? {
        val wsLoc = IntArray(2)
        workspace.getLocationInWindow(wsLoc)
        val loc = IntArray(2)
        for (i in workspace.visiblePages()) {
            val page = workspace.page(i) ?: continue
            for (j in 0 until page.childCount) {
                val v = page.getChildAt(j) as? AppIconView ?: continue
                v.getLocationInWindow(loc)
                val left = loc[0] - wsLoc[0]
                val top = loc[1] - wsLoc[1]
                if (x < left || x > left + v.width || y < top || y > top + v.height) continue
                val folder = v.tagItem as? FolderItem
                if (folder != null) return folder.apps.firstOrNull()?.let { first -> { launch(first, v) } }
                val item = layout.pages.getOrNull(i)?.firstOrNull { it is AppItem && it.app == v.tagItem } as? AppItem
                val shortcut = item?.swipe ?: return null
                return { runShortcut(item.app, shortcut) }
            }
        }
        return null
    }

    private fun shortcutsOf(key: AppKey): List<android.content.pm.ShortcutInfo> = try {
        val user = apps.userFor(key)
        if (user == null || !apps.launcher.hasShortcutHostPermission()) emptyList()
        else apps.launcher.getShortcuts(
            LauncherApps.ShortcutQuery().setPackage(key.pkg).setActivity(apps.component(key))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST),
            user,
        )?.filter { it.isEnabled } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun runShortcut(key: AppKey, id: String) {
        val sc = shortcutsOf(key).firstOrNull { it.id == id }
        if (sc == null) {
            Pixel.say(this, "${apps.label(key)} no longer offers that shortcut. Pick another in its menu.")
            return
        }
        try {
            apps.launcher.startShortcut(sc, null, null)
        } catch (e: Exception) {
            Pixel.say(this, "That shortcut couldn't be opened.")
        }
    }

    private fun showSwipeMenu(view: AppIconView, item: AppItem) {
        val shortcuts = shortcutsOf(item.app)
        if (shortcuts.isEmpty()) {
            Pixel.say(this, if (apps.launcher.hasShortcutHostPermission()) "${apps.label(item.app)} doesn't offer shortcuts to swipe to."
            else "Set bitlauncher as your home app first; Android only shares app shortcuts with it.")
            return
        }
        val entries = mutableListOf(PixelMenu.Entry(if (item.swipe == null) "None (opens drawer) *" else "None (opens drawer)") {
            item.swipe = null
            save()
        })
        for (sc in shortcuts.take(6)) {
            val name = (sc.shortLabel ?: sc.id).toString()
            entries += PixelMenu.Entry(if (item.swipe == sc.id) "$name *" else name) {
                item.swipe = sc.id
                save()
                Pixel.say(this, "Swipe up on ${apps.label(item.app)} now runs $name.")
            }
        }
        menu.show(view, entries, view.iconRect())
    }

    // ---------------------------------------------------------------- your names and icons

    private fun showEditMenu(view: AppIconView, key: AppKey) {
        val entries = mutableListOf(
            PixelMenu.Entry("Rename") { renameApp(key) },
            PixelMenu.Entry("Pick icon from photos") {
                editingIconFor = key
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(pick, REQ_ICON)
                } catch (e: Exception) {
                    Pixel.say(this, "No photo picker found on this phone.")
                }
            },
        )
        if (key.id() in prefs.renamed || icons.hasCustom(key)) entries += PixelMenu.Entry("Reset name & icon") {
            prefs.renamed = prefs.renamed - key.id()
            icons.clearCustom(key)
            apps.reload()
        }
        menu.show(view, entries, view.iconRect())
    }

    private fun renameApp(key: AppKey) {
        val field = android.widget.EditText(this).apply {
            setText(apps.label(key))
            setSelectAllOnFocus(true)
            isSingleLine = true
            setTextColor(Pixel.INK)
            background = PanelDrawable(context, Pixel.BG, prefs.accent)
            val p = Pixel.dpi(context, 12)
            setPadding(p, p, p, p)
        }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, prefs.accent)
            val p = Pixel.dpi(context, 18)
            setPadding(p, p, p, p)
            addView(Pixel.label(context, "RENAME", 12f, prefs.accent).apply { setPadding(0, 0, 0, Pixel.dpi(context, 12)) })
            addView(field)
            addView(Pixel.button(context, "SAVE", prefs.accent) {
                val name = field.text.toString().trim()
                prefs.renamed = if (name.isEmpty()) prefs.renamed - key.id() else prefs.renamed + (key.id() to name)
                dialog.dismiss()
                apps.reload()
            }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = Pixel.dpi(context, 12) })
        }
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt().coerceAtMost(Pixel.dpi(this, 420)), -2)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    /** A picked photo becomes the app's icon: scaled down on load, then pixelated like any other. */
    private fun useIconFrom(uri: Uri) {
        val key = editingIconFor ?: return
        editingIconFor = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 192) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IllegalArgumentException()
            // Centre-crop to a square so a photo fills the icon.
            val side = minOf(bmp.width, bmp.height)
            val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            icons.setCustom(key, android.graphics.drawable.BitmapDrawable(resources, square))
            rebuild()
            drawer.refresh()
        } catch (e: Exception) {
            Pixel.say(this, "That picture couldn't be read. Try a JPG or PNG.")
        }
    }

    // ---------------------------------------------------------------- search

    /** Everything search can do besides open apps, best first. */
    private fun searchAnswers(q: String, appMatches: Int): List<DrawerView.Answer> {
        val out = mutableListOf<DrawerView.Answer>()
        Search.calculate(q)?.let { out += DrawerView.Answer("= $it", "Calculator  ·  tap to copy") { copy(it) } }
        Search.convert(q)?.let { out += DrawerView.Answer("= $it", "Conversion  ·  tap to copy") { copy(it.substringBefore(' ')) } }
        out += contactAnswers(q)
        for (page in Search.settings(q)) out += DrawerView.Answer(page.label, "Phone settings") {
            try {
                startActivity(Intent(page.action))
                closeDrawer()
            } catch (e: Exception) {
                Pixel.say(this, "This phone doesn't have a ${page.label} settings page.")
            }
        }
        // The web is the fallback: only offered when few apps match, so app names stay tidy.
        if (appMatches < 3 && q.length >= 2) out += webAnswer(q)
        return out
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("bitlauncher", text))
        Pixel.say(this, "Copied $text")
    }

    private fun contactAnswers(q: String): List<DrawerView.Answer> {
        if (!prefs.searchContacts || q.length < 2) return emptyList()
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<DrawerView.Answer>()
        try {
            val uri = Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
            val cols = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.LOOKUP_KEY,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            )
            contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 3) {
                    val lookup = android.provider.ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    val name = c.getString(2) ?: continue
                    out += DrawerView.Answer(name, "Contact") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, lookup))
                            closeDrawer()
                        } catch (e: Exception) {
                            Pixel.say(this, "No contacts app found to open $name.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Contacts unavailable right now; search still works for everything else.
        }
        return out
    }

    /** Search the web, in Bit Browser if it's installed, otherwise the phone's usual search. */
    private fun webAnswer(q: String): DrawerView.Answer {
        val bitBrowser = "com.pixelbrowser.app"
        val hasBit = try {
            packageManager.getPackageInfo(bitBrowser, 0)
            true
        } catch (e: Exception) {
            false
        }
        return DrawerView.Answer("Search the web for \"$q\"", if (hasBit) "Bit Browser" else "Web") {
            val url = Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))
            val tries = buildList {
                if (hasBit) {
                    add(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).setPackage(bitBrowser))
                    add(Intent(Intent.ACTION_VIEW, url).setPackage(bitBrowser))
                }
                add(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
                add(Intent(Intent.ACTION_VIEW, url))
            }
            val intent = tries.firstOrNull { it.resolveActivity(packageManager) != null }
            if (intent == null) {
                Pixel.say(this, "No browser found to search the web.")
            } else {
                startActivity(intent)
                closeDrawer()
            }
        }
    }

    // ---------------------------------------------------------------- drawer and folders

    private var drawerAnim: android.animation.ValueAnimator? = null
    private val flingSpeed by lazy { Pixel.dp(this, 500f) }

    /** How far the drawer travels on its way in. It also fades, so it needn't cross the whole screen. */
    private fun drawerTravel() = (root.height * 0.45f).coerceAtLeast(1f)

    /** Place the drawer at [progress]: 0 hidden, 1 open. Home recedes a little behind it. */
    private fun setDrawerProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        drawer.translationY = drawerTravel() * (1f - p)
        drawer.alpha = p
        val scale = 1f - 0.05f * p
        workspace.scaleX = scale
        workspace.scaleY = scale
        for (v in listOf(workspace, dock, dots, header)) v.alpha = 1f - 0.6f * p
    }

    private fun drawerProgress() = drawer.alpha

    private fun prepareDrawer() {
        drawerAnim?.cancel()
        if (drawer.visibility == View.VISIBLE) return
        menu.dismiss()
        drawer.setApps(visibleApps())
        drawer.cellHeight = Pixel.dpi(this, prefs.iconDp() + 40)
        drawer.reset()
        setDrawerProgress(0f)
        drawer.visibility = View.VISIBLE
    }

    /** The finger is pulling the drawer up from home: it follows exactly. */
    private fun dragDrawer(px: Float) {
        prepareDrawer()
        setDrawerProgress(px / drawerTravel())
    }

    /** Let go: open if it went far enough or was flung up, otherwise slide back. */
    private fun releaseDrawer(velocityY: Float) {
        val open = velocityY < -flingSpeed || (velocityY < flingSpeed && drawerProgress() > 0.3f)
        settleDrawer(if (open) 1f else 0f, kotlin.math.abs(velocityY))
    }

    /** Finish the drawer's journey from wherever it is, at a speed that matches the finger. */
    private fun settleDrawer(target: Float, velocity: Float) {
        drawerAnim?.cancel()
        val from = drawerProgress()
        val done = {
            if (target == 0f) {
                drawer.visibility = View.GONE
                drawer.reset()
                setDrawerProgress(0f)
            } else if (prefs.drawerKeyboard) {
                drawer.showKeyboard()
            }
        }
        if (!Pixel.motion(this) || from == target) {
            setDrawerProgress(target)
            done()
            return
        }
        val travel = drawerTravel()
        drawerAnim = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = Ease.finishDuration(kotlin.math.abs(target - from) * travel, travel, velocity)
            interpolator = if (target == 1f) Ease.move else Ease.exit
            addUpdateListener { setDrawerProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    private fun openDrawer() {
        prepareDrawer()
        settleDrawer(1f, 0f)
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawer.hideKeyboard()
        settleDrawer(0f, 0f)
    }

    /** Gone at once, no animation (a drag is taking over). */
    private fun hideDrawerNow() {
        drawerAnim?.cancel()
        drawer.hideKeyboard()
        drawer.visibility = View.GONE
        drawer.reset()
        setDrawerProgress(0f)
    }

    private fun openFolder(folder: FolderItem) {
        openFolder = folder
        folderView.title.setText(folder.name)
        fillFolder(folder)
        folderView.visibility = View.VISIBLE
        if (Pixel.motion(this)) folderView.popIn()
    }

    /** (Re)lay the open folder's icons, dimming the one being dragged around inside it. */
    private fun fillFolder(folder: FolderItem) {
        val cell = Pixel.dpi(this, prefs.iconDp() + if (folder.labels) 40 else 20)
        folderView.setAccent(prefs.accent, folder.color)
        folderView.setInventory(folder.style == "bag")
        folderView.locked = prefs.layoutLocked
        folderView.setIcons(folder.apps.map { key -> AppIconView(this).also { bindIcon(it, key, Source.FOLDER, folder = folder) } }, cell)
        // Dragged to a new spot inside: that's the folder's order now (and its cover, if the first four changed).
        folderView.onReordered = { icons ->
            val order = icons.mapNotNull { it.tagItem as? AppKey }
            if (order != folder.apps && order.size == folder.apps.size) {
                folder.apps.clear()
                folder.apps.addAll(order)
                saveAndRebuild()
            }
        }
        folderView.onDragStart = { menu.dismiss() }
        // Dragged out past the edge: the folder closes and the same drag carries on to home or the dock.
        folderView.onDragLeave = { v ->
            val key = v.tagItem as? AppKey
            menu.dismiss()
            closeFolder()
            if (key != null) startDrag(v, DragInfo(Source.FOLDER, key, null, folder, -1, v))
        }
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
        openFolder = null
        if (Pixel.motion(this)) folderView.popOut { folderView.visibility = View.GONE } else folderView.visibility = View.GONE
    }

    /** Swipe down on home: bitlauncher's pixel notifications, or the phone's own shade if chosen. */
    private fun openNotifications() {
        if (prefs.swipeDown == "shade") return openPhoneShade()
        menu.dismiss()
        notePanel.accent = prefs.accent
        notePanel.open()
    }

    /** A new notification while home is in front: a pixel card slides in at the top. */
    private fun showPopup(note: Note) {
        if (prefs.popups == "off" || notePanel.isOpen || !Popups.phoneInUse(this)) return
        popupLayer.removeAllViews()
        lateinit var card: PopupCard
        card = PopupCard(this, icons, note, prefs.accent) { popupLayer.removeView(card) }
        popupLayer.addView(card, FrameLayout.LayoutParams(minOf(root.width - Pixel.dpi(this, 20), Pixel.dpi(this, 480)).coerceAtLeast(1), -2, Gravity.CENTER_HORIZONTAL))
        card.show()
    }

    @Suppress("WrongConstant")
    private fun openPhoneShade() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        } catch (e: Exception) {
            Pixel.say(this, "This phone doesn't let launchers open notifications. Swipe down from the top edge instead.")
        }
    }

    // ---------------------------------------------------------------- menus

    private fun showHomeMenu(x: Float, y: Float) {
        // The page under the finger: the right one of a pair if it was on the right half.
        val page = workspace.currentPage + if (workspace.panes > 1 && x > workspace.width / 2f) 1 else 0
        val labelsHere = page !in layout.unlabelledPages
        val at = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
        val switchLayout = PixelMenu.Entry(if (mode == Shift.WORK) "Switch to Home layout" else "Switch to Work layout") {
            val next = if (mode == Shift.WORK) Shift.HOME else Shift.WORK
            Shift.hold(prefs, next)
            appliedPrefs = prefs.version
            switchMode(next)
        }
        if (prefs.layoutLocked) {
            menu.show(workspace, listOf(
                PixelMenu.Entry("Unlock layout") {
                    prefs.layoutLocked = false
                    folderView.locked = false
                    Pixel.say(this, "Layout unlocked.")
                },
                switchLayout,
                PixelMenu.Entry("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
            ), at)
            return
        }
        menu.show(workspace, listOf(
            PixelMenu.Entry("Widgets") { showWidgetPicker() },
            PixelMenu.Entry("Pages") { openPages() },
            PixelMenu.Entry("Lock layout") {
                prefs.layoutLocked = true
                folderView.locked = true
                Pixel.say(this, "Layout locked. Apps still open as normal.")
            },
            switchLayout,
            PixelMenu.Entry(if (labelsHere) "Hide labels on this page" else "Show labels on this page") {
                while (page >= layout.pages.size) layout.pages.add(mutableListOf())
                if (labelsHere) layout.unlabelledPages.add(page) else layout.unlabelledPages.remove(page)
                saveAndRebuild()
            },
            PixelMenu.Entry("Wallpaper") {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                } catch (e: Exception) {
                    Pixel.say(this, "No wallpaper picker found on this phone.")
                }
            },
            PixelMenu.Entry("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ), at)
    }

    /** True (and says so) if the layout is locked and can't be changed right now. */
    private fun locked(): Boolean {
        if (!prefs.layoutLocked) return false
        Pixel.say(this, "The layout is locked. Unlock it from the home screen's menu (hold empty space).")
        return true
    }

    private fun showAppMenu(view: AppIconView, key: AppKey, source: Source, item: Item?, folder: FolderItem?, dockIndex: Int) {
        val entries = mutableListOf<PixelMenu.Entry>()
        val pinned = PinnedShortcuts.isShortcut(key)
        if (!pinned) entries += shortcutEntries(key)
        if (prefs.layoutLocked) {
            // Locked: open its shortcuts or info, nothing that changes home.
            if (!pinned) entries += PixelMenu.Entry("App info") { apps.openAppInfo(key, null) }
            entries += PixelMenu.Entry("Layout locked") { locked() }
            menu.show(view, entries, view.iconRect())
            return
        }
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
            Source.HOME -> entries += PixelMenu.Entry("Swipe up action") {
                (item as? AppItem)?.let { showSwipeMenu(view, it) }
            }
            else -> {}
        }
        when (source) {
            Source.HOME -> entries += PixelMenu.Entry("Remove from home") {
                removing("${apps.label(key)} removed") { item?.let { layout.remove(it) } }
            }
            Source.DOCK -> entries += PixelMenu.Entry("Remove from dock") {
                removing("${apps.label(key)} removed from the dock") { layout.dock[dockIndex] = null }
            }
            Source.FOLDER -> entries += if (folder?.cover == key && folder.style == "cover") PixelMenu.Entry("Stop using as cover") {
                folder.cover = null
                folder.style = "grid"
                saveAndRebuild()
            } else PixelMenu.Entry("Use as folder cover") {
                folder?.cover = key
                folder?.style = "cover"
                saveAndRebuild()
            }
            Source.DRAWER -> {}
        }
        if (source == Source.FOLDER) {
            entries += PixelMenu.Entry("Remove from folder") {
                closeFolder()
                removing("${apps.label(key)} taken out of ${folder!!.name}") { removeFromFolder(folder, key) }
            }
        }
        entries += PixelMenu.Entry("Edit name & icon") { showEditMenu(view, key) }
        entries += PixelMenu.Entry("App info") {
            if (pinned) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", key.pkg, null)))
                } catch (e: Exception) {
                    Pixel.say(this, "Couldn't open app info.")
                }
            } else {
                apps.openAppInfo(key, null)
            }
        }
        if (!pinned && canUninstall(key)) entries += PixelMenu.Entry("Uninstall") { uninstall(key) }
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
        layout.dissolveIfSmall(folder)
    }

    // ---------------------------------------------------------------- drag and drop

    private var dragTarget: DropTarget = DropTarget.None
    private var landed: Any? = null
    private val pageFlip = Runnable { flipPageDuringDrag() }
    private var flipDirection = 0

    internal sealed class DropTarget {
        object None : DropTarget()
        object Remove : DropTarget()
        class Dock(val slot: Int) : DropTarget()
        class Cell(val page: Int, val x: Int, val y: Int) : DropTarget()
    }

    private fun startDrag(view: View, info: DragInfo) {
        menu.dismiss()
        if (locked()) return
        val shadow = (view as? AppIconView)?.shadowBuilder() ?: View.DragShadowBuilder(view)
        if (info.source == Source.DRAWER) {
            // Hide at once: the search box would otherwise take the drop as text mid-animation.
            hideDrawerNow()
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
                layout.trimEmptyPages()
                save()
                rebuild()
                landed?.let { tag -> root.post { hopLanded(tag) } }
                landed = null
                undoAfterDrag?.let { showUndo(undoAfterDragText, it) }
                undoAfterDrag = null
            }
        }
        return true
    }

    private fun flipPageDuringDrag() {
        val next = workspace.currentPage + flipDirection * workspace.panes
        if (next < 0) return
        // Dragging past the last screen makes a new one (a page, or a pair of pages).
        while (next + workspace.panes > workspace.pageCount) workspace.addView(CellLayout(this, layout.cols(), layout.rows()))
        dots.count = workspace.screenCount
        workspace.snapTo(next)
        root.postDelayed(pageFlip, 900)
    }

    private fun targetAt(x: Float, y: Float, info: DragInfo): DropTarget {
        if (removeBar.visibility == View.VISIBLE && y < removeBar.bottom) return DropTarget.Remove
        if (y >= dock.top && y <= dock.bottom) return DropTarget.Dock(dock.slotAt(x - dock.left))
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val loc = IntArray(2)
        // With two pages on screen, the one under the finger (or the nearer one, over the gutter).
        val index = workspace.visiblePages().minByOrNull { i ->
            val p = workspace.page(i)!!
            p.getLocationInWindow(loc)
            val left = loc[0] - rootLoc[0]
            when {
                x < left -> left - x
                x > left + p.width -> x - left - p.width
                else -> 0f
            }
        } ?: return DropTarget.None
        val page = workspace.page(index)!!
        page.getLocationInWindow(loc)
        val px = (x - (loc[0] - rootLoc[0])).coerceIn(0f, page.width - 1f)
        val py = y - (loc[1] - rootLoc[1])
        if (py < 0 || py > page.height) return DropTarget.None
        var (cx, cy) = page.cellAt(px, py)
        val (w, h) = info.size
        cx = (cx - (w - 1) / 2).coerceIn(0, layout.cols() - w)
        cy = (cy - (h - 1) / 2).coerceIn(0, layout.rows() - h)
        return DropTarget.Cell(index, cx, cy)
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

    internal fun drop(info: DragInfo, target: DropTarget): Boolean {
        when (target) {
            DropTarget.None -> return false
            DropTarget.Remove -> {
                // The undo bar shows once the drag has finished (see ACTION_DRAG_ENDED).
                commitWidgetDeletes()
                undoAfterDrag = layout.toJson().toString()
                undoAfterDragText = when (val it = info.item) {
                    is WidgetItem -> "Widget removed"
                    is FolderItem -> "${it.name} removed"
                    else -> info.singleApp()?.let { "${apps.label(it)} removed" } ?: "Removed"
                }
                removeFromSource(info)
                (info.item as? WidgetItem)?.all()?.forEach { deleteWidgetLater(it.first) }
                return true
            }
            is DropTarget.Dock -> {
                val slot = target.slot
                val occupant = layout.dock.getOrNull(slot)
                val draggedFolder = info.item as? FolderItem
                val app = info.singleApp()
                if (draggedFolder == null && app == null) {
                    Pixel.say(this, "Only apps and folders fit in the dock.")
                    return false
                }
                // What goes in the slot: the same item if it was already one (keeps its settings).
                val piece: Item = draggedFolder ?: (info.item as? AppItem) ?: AppItem(app!!, slot, 0)
                when {
                    occupant === piece -> return false // dropped back where it was
                    occupant == null -> {
                        removeFromSource(info)
                        layout.setDock(slot, piece)
                    }
                    // Re-arranging the dock: the two swap.
                    info.source == Source.DOCK -> {
                        layout.setDock(info.dockIndex, occupant)
                        layout.setDock(slot, piece)
                    }
                    // From a folder onto a dock app: they trade places.
                    info.source == Source.FOLDER && occupant is AppItem && app != null && info.folder != null -> {
                        val folder = info.folder
                        val at = folder.apps.indexOf(app)
                        if (at < 0) return false
                        folder.apps[at] = occupant.app
                        layout.setDock(slot, AppItem(app, slot, 0))
                    }
                    // An app onto a dock app: a new folder, right there in the dock.
                    occupant is AppItem && app != null -> {
                        if (occupant.app == app) return false
                        removeFromSource(info)
                        val made = FolderItem("Folder", mutableListOf(occupant.app, app), slot, 0)
                        layout.setDock(slot, made)
                        landed = made
                    }
                    // An app onto a dock folder: in it goes.
                    occupant is FolderItem && app != null -> {
                        if (occupant === info.folder || occupant.apps.contains(app)) return false
                        removeFromSource(info)
                        occupant.apps.add(app)
                        landed = occupant
                    }
                    else -> {
                        Pixel.say(this, "That dock spot is taken. Move what's there first.")
                        return false
                    }
                }
                return true
            }
            is DropTarget.Cell -> {
                while (target.page >= layout.pages.size) layout.pages.add(mutableListOf())
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
                        removing("Widget removed") {
                            this@LauncherActivity.layout.remove(item)
                            item.all().forEach { deleteWidgetLater(it.first) }
                        }
                    }))
                    true
                }
            }
        }
        val view = hostView(item.widgetId)!!
        view.tagItem = item
        view.onLongPress = { showWidgetMenu(it, item) }
        view.onDragOut = { v -> startDrag(v, DragInfo(Source.HOME, item = item, view = v)) }
        return view
    }

    /** The hosted view for one widget id, made once and kept. Null if its app is gone. */
    private fun hostView(id: Int): PixelWidgetView? {
        widgetViews[id]?.let { return it }
        val info = widgetManager.getAppWidgetInfo(id) ?: return null
        return (widgetHost.createView(this, id, info) as PixelWidgetView).also { widgetViews[id] = it }
    }

    /** A stack: every widget in it, one showing, flipped by swiping up and down on it. */
    private fun stackView(item: WidgetItem): View {
        val stack = StackView(this)
        stack.tag = item
        stack.accent = prefs.accent
        val pages = item.all().map { (id, _) ->
            val v: View = hostView(id)?.also { hv ->
                hv.tagItem = item
                hv.onLongPress = { showWidgetMenu(it, item, id) }
                hv.onDragOut = { startDrag(stack, DragInfo(Source.HOME, item = item, view = stack)) }
            } ?: Pixel.label(this, "WIDGET UNAVAILABLE\nHOLD TO REMOVE", 9f, Pixel.MUTED).apply {
                gravity = Gravity.CENTER
                background = PanelDrawable(context, withAlpha(Pixel.PANEL, 200), Pixel.MUTED)
                setOnLongClickListener { showWidgetMenu(this, item, id); true }
            }
            (v.parent as? ViewGroup)?.removeView(v)
            v
        }
        stack.setPages(pages, item.showing)
        stack.onFlip = { i ->
            item.showing = i
            save()
        }
        return stack
    }

    /** [shownId] is the widget the finger is on (for stacks: the one showing). */
    private fun showWidgetMenu(view: View, item: WidgetItem, shownId: Int = item.widgetId) {
        if (locked()) return
        val entries = mutableListOf(
            PixelMenu.Entry("Resize") { startResize(item) },
            PixelMenu.Entry("Stack another widget") {
                widgetForStack = item
                showWidgetPicker()
            },
        )
        if (item.isStack) {
            entries += PixelMenu.Entry("Remove this one") {
                removing("Widget taken off the stack") {
                    item.without(shownId)?.let { layout.replace(item, it) }
                    deleteWidgetLater(shownId)
                }
            }
            entries += PixelMenu.Entry("Remove whole stack") {
                removing("Stack removed") {
                    layout.remove(item)
                    item.all().forEach { deleteWidgetLater(it.first) }
                }
            }
        } else {
            entries += PixelMenu.Entry("Remove") {
                removing("Widget removed") {
                    layout.remove(item)
                    item.all().forEach { deleteWidgetLater(it.first) }
                }
            }
        }
        menu.show(view, entries)
    }

    /** The stack under (x, y) in the workspace, as a way to flip it. */
    private fun stackFlipAt(x: Float, y: Float): ((Int) -> Unit)? {
        val wsLoc = IntArray(2)
        workspace.getLocationInWindow(wsLoc)
        val loc = IntArray(2)
        for (i in workspace.visiblePages()) {
            val page = workspace.page(i) ?: continue
            for (j in 0 until page.childCount) {
                val v = page.getChildAt(j) as? StackView ?: continue
                v.getLocationInWindow(loc)
                val left = loc[0] - wsLoc[0]
                val top = loc[1] - wsLoc[1]
                if (x >= left && x <= left + v.width && y >= top && y <= top + v.height) return { dir -> v.flip(dir) }
            }
        }
        return null
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
        WidgetPicker(this, prefs.accent, providers, { defaultCells(it) }, cellSize(), icons) { addWidget(it) }.show()
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
        widgetForStack?.let { target ->
            widgetForStack = null
            layout.replace(target, target.with(pendingWidgetId, info.provider.flattenToString()))
            pendingWidget = null
            pendingWidgetId = -1
            saveAndRebuild()
            return
        }
        if (widgetForToday) {
            widgetForToday = false
            val (_, h) = defaultCells(info)
            val hDp = ((h * cellSize().second) / resources.displayMetrics.density).toInt().coerceIn(80, 400)
            prefs.todayWidgets = prefs.todayWidgets + Triple(pendingWidgetId, info.provider.flattenToString(), hDp)
            pendingWidget = null
            pendingWidgetId = -1
            renderToday()
            return
        }
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
        widgetForToday = false
        widgetForStack = null
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidget = null
        pendingWidgetId = -1
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) Pixel.say(this, "Calendar stays hidden: bitlauncher wasn't allowed to read it.")
            if (today.visibility == View.VISIBLE) renderToday()
        }
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
            REQ_ICON -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { useIconFrom(it) }
        }
    }

    private var changedAt = 0L

    private fun save() {
        changedAt = System.currentTimeMillis()
        try {
            store.save(layout)
        } catch (e: Exception) {
            Pixel.say(this, "Couldn't save your home screen layout. The phone may be out of space.")
        }
    }

    companion object {
        private const val REQ_BIND = 11
        private const val REQ_CONFIG = 12
        private const val REQ_ICON = 13
        private const val REQ_CALENDAR = 14
    }
}
