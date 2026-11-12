package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
    private lateinit var icons: IconCache
    private lateinit var usage: Usage
    private lateinit var scene: SceneView
    private lateinit var today: TodayView
    private lateinit var media: MediaSource
    private var lastWeather: WeatherNow? = null
    private val todayWidgetViews = HashMap<Int, PixelWidgetView>()
    private var widgetForToday = false
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
        Shift.current(prefs).let { if (it != mode) switchMode(it) }
        applyScene()
        refreshWeather()
    }

    override fun onPause() {
        super.onPause()
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
                    is AppItem -> AppIconView(this).also { bindIcon(it, item.app, Source.HOME, item, pageIndex = pageIndex) }
                    is FolderItem -> AppIconView(this).also { bindFolder(it, item, pageIndex) }
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
        // Two panes: a lone last page gets an empty partner, ready to drop things on.
        while (workspace.pageCount % workspace.panes != 0) workspace.addView(CellLayout(this, layout.cols(), layout.rows()))
        // Forget views for widgets that are gone.
        widgetViews.keys.retainAll(liveWidgets)
        workspace.snapTo(keepPage.coerceAtMost(workspace.pageCount - 1), animate = false)

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

    private fun bindFolder(view: AppIconView, folder: FolderItem, pageIndex: Int) {
        view.tagItem = folder
        view.sprite = null
        view.folderSprites = folder.apps.take(4).map { icons.get(it) }
        view.folderCover = folder.cover?.let { icons.get(it) }
        view.folderTint = folder.color
        view.label = folder.name
        view.showLabel = prefs.labels && pageIndex !in layout.unlabelledPages
        view.iconSizeDp = prefs.iconDp()
        view.setOnClickListener { openFolder(folder) }
        view.setOnLongClickListener {
            showFolderMenu(view, folder)
            true
        }
        view.onDragOut = { startDrag(it, DragInfo(Source.HOME, item = folder, view = it)) }
    }

    private fun showFolderMenu(view: AppIconView, folder: FolderItem) {
        val entries = mutableListOf(
            PixelMenu.Entry("Open") { openFolder(folder) },
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
        if (folder.cover != null) entries += PixelMenu.Entry("Show 4 apps as cover") {
            folder.cover = null
            saveAndRebuild()
        }
        entries += PixelMenu.Entry("Remove folder") {
            layout.remove(folder)
            saveAndRebuild()
            Pixel.say(this, "Folder removed. The apps are still in the app drawer.")
        }
        menu.show(view, entries, view.iconRect())
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
        try {
            val user = apps.userFor(key) ?: throw IllegalStateException()
            apps.launcher.startMainActivity(apps.component(key), user, bounds, opts)
            usage.record(key)
        } catch (e: Exception) {
            Pixel.say(this, "${apps.label(key)} can't be opened. It may have been uninstalled or disabled.")
        }
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
        fillFolder(folder, dragging = null)
        folderView.visibility = View.VISIBLE
        if (Pixel.motion(this)) folderView.popIn()
    }

    /** (Re)lay the open folder's icons, dimming the one being dragged around inside it. */
    private fun fillFolder(folder: FolderItem, dragging: AppKey?) {
        val cell = Pixel.dpi(this, prefs.iconDp() + if (folder.labels) 40 else 20)
        folderView.setAccent(prefs.accent, folder.color)
        folderView.setIcons(folder.apps.map { key ->
            AppIconView(this).also {
                bindIcon(it, key, Source.FOLDER, folder = folder)
                if (key == dragging) it.alpha = 0.25f
            }
        }, cell)
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
        // The page under the finger: the right one of a pair if it was on the right half.
        val page = workspace.currentPage + if (workspace.panes > 1 && x > workspace.width / 2f) 1 else 0
        val labelsHere = page !in layout.unlabelledPages
        menu.show(workspace, listOf(
            PixelMenu.Entry("Widgets") { showWidgetPicker() },
            PixelMenu.Entry(if (mode == Shift.WORK) "Switch to Home layout" else "Switch to Work layout") {
                val next = if (mode == Shift.WORK) Shift.HOME else Shift.WORK
                Shift.hold(prefs, next)
                appliedPrefs = prefs.version
                switchMode(next)
            },
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
            Source.HOME -> entries += PixelMenu.Entry("Swipe up action") {
                (item as? AppItem)?.let { showSwipeMenu(view, it) }
            }
            else -> {}
        }
        when (source) {
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
            Source.FOLDER -> entries += if (folder?.cover == key) PixelMenu.Entry("Stop using as cover") {
                folder?.cover = null
                saveAndRebuild()
            } else PixelMenu.Entry("Use as folder cover") {
                folder?.cover = key
                saveAndRebuild()
            }
            Source.DRAWER -> {}
        }
        if (source == Source.FOLDER) {
            entries += PixelMenu.Entry("Remove from folder") {
                removeFromFolder(folder!!, key)
                closeFolder()
                save()
                rebuild()
            }
        }
        entries += PixelMenu.Entry("Edit name & icon") { showEditMenu(view, key) }
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
            hideDrawerNow()
        }
        if (info.source == Source.FOLDER) {
            // The folder stays open: drag inside it to re-order, or out of it to place elsewhere.
            folderView.hideKeyboard()
            view.alpha = 0.25f
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
                if (reorderInFolder(info, e.x, e.y)) return true
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
                // Let go inside the open folder: the re-order already happened as it moved.
                if (info.source == Source.FOLDER && openFolder != null && folderView.panelContains(e.x, e.y)) return true
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
                // Still inside the folder: show it in its new order, nothing dimmed.
                openFolder?.let { fillFolder(it, dragging = null) }
                landed?.let { tag -> root.post { hopLanded(tag) } }
                landed = null
            }
        }
        return true
    }

    /**
     * Dragging an app from an open folder. Over the panel, it re-orders live: the other apps
     * glide out of the way. Out past the panel, the folder closes and it's a normal drag.
     * Returns true while the folder is handling it.
     */
    private fun reorderInFolder(info: DragInfo, x: Float, y: Float): Boolean {
        val folder = openFolder ?: return false
        if (info.source != Source.FOLDER || info.folder !== folder) return false
        val app = info.app ?: return false
        if (!folderView.panelContains(x, y)) {
            removeBar.visibility = View.VISIBLE
            closeFolder()
            return false
        }
        removeBar.visibility = View.GONE
        val to = folderView.indexAt(x, y)
        val from = folder.apps.indexOf(app)
        if (from >= 0 && to != from) {
            val before = folderView.positions()
            folder.move(from, to)
            fillFolder(folder, dragging = app)
            folderView.glideFrom(before)
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
        private const val REQ_ICON = 13
        private const val REQ_CALENDAR = 14
    }
}
