package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock screen as the accessibility service shows it on a real phone: an overlay window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockOverlayTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is android.widget.TextView) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun windows(service: LockService): List<View> {
        val wm = service.getSystemService(WindowManager::class.java)
        return org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views
    }

    @Test
    fun overlayGoesUpAboveTheLockScreenAndComesDownOnUnlock() {
        val service = Robolectric.setupService(LockService::class.java)
        for (look in LockLooks.ALL) {
            Prefs(service).lockStyle = look.key
            assertTrue(service.showOverlay())
            idle()
            val win = windows(service)
            assertEquals(look.key, 1, win.size)
            val lp = win[0].layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, lp.type)
            assertTrue(texts(win[0]).contains(LockLooks.hint(look.key)))

            // Showing again while it's up doesn't stack a second window.
            assertTrue(service.showOverlay())
            idle()
            assertEquals(1, windows(service).size)

            // Unlocked with the fingerprint: it dissolves and the window goes.
            service.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertFalse(look.key, windows(service).isNotEmpty())
        }
        Prefs(service).lockStyle = "classic"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class UnlockAndWallpaperTest {
    @Test
    fun swipingUpGoesStraightToThePinPad() {
        val service = Robolectric.setupService(LockService::class.java)
        val keyguard = service.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)
        val overlay = LockOverlay(service)
        overlay.show()
        shadowOf(Looper.getMainLooper()).idle()
        var opened = false
        overlay.dismissKeyguard({ opened = true }, {})
        val started = shadowOf(service.application).nextStartedActivity
        assertEquals(UnlockActivity::class.java.name, started.component!!.className)
        assertFalse("nothing opens before the phone is unlocked", opened)
        // If the unlock screen never got to run it, unlocking still opens it a moment later.
        service.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue(opened)
        shadowOf(keyguard).setKeyguardLocked(false)
    }

    @Test
    fun theUnlockScreenOpensTheTappedNotificationItself() {
        var opened = 0
        PendingOpen.set { opened++ }
        // Unlocked by the time it starts: it opens the notification straight away, once.
        Robolectric.buildActivity(UnlockActivity::class.java).setup()
        assertEquals(1, opened)
        PendingOpen.run()
        assertEquals(1, opened)
    }

    @Test
    fun openingANotificationReallyStartsItsScreen() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val target = android.content.Intent(app, SettingsActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(app, 0, target, android.app.PendingIntent.FLAG_IMMUTABLE)
        openNotification(pi)
        assertEquals(SettingsActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
    }

    @Test
    fun lockWallpaperIsAFullSizePixelSceneThatChangesWithTheNight() {
        val day = LockWallpaper.render("railway", 1080, 2400, Light.fixed("day")!!, null)
        val night = LockWallpaper.render("railway", 1080, 2400, Light.fixed("night")!!, null)
        assertEquals(1080, day.width)
        assertEquals(2400, day.height)
        // The sky at the top is much darker at night.
        fun brightness(c: Int) = android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)
        assertTrue(brightness(night.getPixel(540, 100)) < brightness(day.getPixel(540, 100)))
        System.getenv("SHOT_DIR")?.let { dir ->
            java.io.File(dir, "lock-wallpaper.png").outputStream().use { night.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val prefs = Prefs(org.robolectric.RuntimeEnvironment.getApplication()).apply { scene = "railway"; sceneTime = "night" }
        val a = LockWallpaper.key(prefs, Light.fixed("night")!!, null)
        prefs.sceneTime = "day"
        assertTrue(a != LockWallpaper.key(prefs, Light.fixed("day")!!, null))
        prefs.apply { scene = "none"; sceneTime = "auto" }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockExtrasTest {
    @Test
    fun alarmLineOnlyWithinTwelveHours() {
        val now = 1_000_000_000_000L
        assertEquals("WAKE 6:30 AM  ·  IN 7H 12M", alarmLine(now, now + (7 * 60 + 12) * 60_000L, "6:30 am"))
        assertEquals(null, alarmLine(now, now + 13 * 3_600_000L, "x"))
        assertEquals(null, alarmLine(now, null, "x"))
        assertEquals(null, alarmLine(now, now - 1, "x"))
    }

    @Test
    fun clockFontsAreAllFiveBySeven() {
        for ((key, _) in ClockFonts.ALL) {
            val d = ClockFonts.digits(key)
            assertEquals(key, 10, d.size)
            for (g in d) {
                assertEquals(key, 7, g.size)
                assertTrue(key, g.all { it.length == 5 })
            }
            // No two digits look the same.
            assertEquals(key, 10, d.map { it.joinToString() }.toSet().size)
        }
    }

    @Test
    fun ticketsShowOnlyRealThingsInTheNextTwelveHours() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        Tickets.clear(ctx)
        Prefs(ctx).shiftSchedule = false
        assertEquals("nothing seeded", null, Tickets.next(ctx, now))
        // Railway Time sends a train.
        TicketReceiver().onReceive(ctx, android.content.Intent(Tickets.ACTION_SET_TICKET).putExtra("what", "08:42 to Union").putExtra("at", now + 40 * 60_000L))
        val t = Tickets.next(ctx, now)!!
        assertEquals("TRAIN", t.kind)
        assertEquals("08:42 to Union", t.what)
        assertEquals(null, Tickets.next(ctx, now + 41 * 60_000L))
        // An empty send clears it.
        TicketReceiver().onReceive(ctx, android.content.Intent(Tickets.ACTION_SET_TICKET))
        assertEquals(null, Tickets.next(ctx, now))
    }

    @Test
    fun cornerButtonsAndTicketOnTheLockScreen() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        Tickets.save(ctx, "08:42 to Union", now + 30 * 60_000L)
        val lock = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = lock.window.decorView
        fun all(v: View, out: MutableList<View> = mutableListOf()): List<View> {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out)
            return out
        }
        assertEquals(2, all(root).count { it is CornerButton })
        val texts = all(root).filterIsInstance<android.widget.TextView>().filter { it.isShown }.map { it.text.toString() }
        assertTrue(texts.contains("TRAIN"))
        assertTrue(texts.contains("08:42 TO UNION"))
        Tickets.clear(ctx)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class IconStylesTest {
    /** A made-up 32x32 sprite: a red square with a dark outline. */
    private fun sample(color: Int): IntArray = IntArray(32 * 32) { i ->
        val x = i % 32
        val y = i / 32
        when {
            x in 7..24 && y in 7..24 -> color
            x in 6..25 && y in 6..25 -> Pixel.SHADOW
            else -> 0
        }
    }

    @org.junit.Before
    fun chunky() { IconCache.SIZE = 32 } // these samples are 32x32

    @org.junit.After
    fun back() { IconCache.SIZE = 64 }

    @Test
    fun fineLooksScaleTheSameShapes() {
        IconCache.SIZE = 64
        val red = IntArray(64 * 64) { i -> if ((i % 64) in 14..49 && (i / 64) in 14..49) 0xFFE04040.toInt() else 0 }
        for ((key, _) in IconStyles.ALL) assertEquals(key, 64 * 64, IconStyles.render(red, key, 0xFF8B7CF6.toInt()).size)
        // The coin's corner stays clear and its middle is filled at the bigger size too.
        val coin = IconStyles.render(red, "coin", 0)
        assertEquals(0, coin[0])
        assertTrue(coin[32 * 64 + 32] != 0)
    }

    @Test
    fun everyLookKeepsTheGridAndChangesTheIcon() {
        val red = sample(0xFFE04040.toInt())
        for ((key, _) in IconStyles.ALL) {
            val out = IconStyles.render(red, key, 0xFF8B7CF6.toInt())
            assertEquals(key, 32 * 32, out.size)
            if (key != "sprite") assertTrue(key, !out.contentEquals(red))
        }
        // Mono keeps no trace of the icon's own colour.
        assertTrue(IconStyles.render(red, "mono", 0).none { it == 0xFFE04040.toInt() })
        // Shaped looks fill their corners differently: the coin is round, the block is not.
        // A picked tile colour replaces the icon's own.
        val picked = IconStyles.render(red, "block", 0, 0xFF22AA44.toInt())
        assertEquals(0xFF22AA44.toInt(), picked[16 * 32 + 3])
        val coin = IconStyles.render(red, "coin", 0)
        val block = IconStyles.render(red, "block", 0)
        assertEquals(0, coin[0])
        assertTrue(block[2 * 32 + 2] != 0)
    }

    @Test
    fun previewSheet() {
        val dir = System.getenv("SHOT_DIR") ?: return
        val colors = intArrayOf(0xFFE04040.toInt(), 0xFF40A0E0.toInt(), 0xFF50C060.toInt(), 0xFFF0C040.toInt())
        val scale = 4
        val sheet = android.graphics.Bitmap.createBitmap((32 * scale + 12) * colors.size + 12, (32 * scale + 12) * IconStyles.ALL.size + 12, android.graphics.Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Pixel.BG)
        val c = android.graphics.Canvas(sheet)
        val paint = android.graphics.Paint().apply { isFilterBitmap = false }
        IconStyles.ALL.forEachIndexed { row, (key, _) ->
            colors.forEachIndexed { col, color ->
                val px = IconStyles.render(sample(color), key, 0xFF8B7CF6.toInt())
                val b = android.graphics.Bitmap.createBitmap(px, 32, 32, android.graphics.Bitmap.Config.ARGB_8888)
                val l = 12f + col * (32 * scale + 12)
                val t = 12f + row * (32 * scale + 12)
                c.drawBitmap(b, null, android.graphics.RectF(l, t, l + 32 * scale, t + 32 * scale), paint)
            }
        }
        java.io.File(dir, "icon-styles.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ColorsTest {
    @Test
    fun hexRoundTripsAndRejectsJunk() {
        assertEquals("#A78BFA", Colors.hex(0xFFA78BFA.toInt()))
        assertEquals(0xFFA78BFA.toInt(), Colors.parse("#a78bfa"))
        assertEquals(0xFFAABBCC.toInt(), Colors.parse("abc"))
        assertEquals(null, Colors.parse("#12345"))
        assertEquals(null, Colors.parse("zzzzzz"))
    }

    @Test
    fun contrastMatchesTheStandard() {
        assertEquals(21.0, Colors.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Colors.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.01)
    }

    @Test
    fun customThemeIsUsedEverywhereAndBackedUp() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.setCustomColor("bg", 0xFF102030.toInt())
        prefs.theme = "custom"
        Pixel.load(ctx)
        assertEquals(0xFF102030.toInt(), Pixel.BG)
        // Unset roles start from the default theme.
        assertEquals(Pixel.THEMES[0].ink, Pixel.INK)
        val saved = prefs.toJson()
        prefs.setCustomColor("bg", 0xFF000000.toInt())
        prefs.restore(saved)
        assertEquals(0xFF102030.toInt(), prefs.customColor("bg", 0))
        assertEquals(0xFF102030.toInt(), FamilyTheme.extras(prefs).getInt("background"))
        prefs.theme = "default"
        Pixel.load(ctx)
    }

    @Test
    fun pickerHandsBackWhatYouTyped() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var picked = 0
        val picker = PixelColorPicker(activity, "Accent", 0xFFA78BFA.toInt()) { picked = it }
        picker.show()
        fun find(v: View): android.widget.EditText? {
            if (v is android.widget.EditText) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(picker.window!!.decorView)!!.setText("#20C0A0")
        System.getenv("SHOT_DIR")?.let { dir ->
            val root = (picker.window!!.decorView as ViewGroup)
            root.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val b = android.graphics.Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            b.eraseColor(Pixel.BG)
            root.draw(android.graphics.Canvas(b))
            java.io.File(dir, "color-picker.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(0xFF20C0A0.toInt(), picker.current)
        fun click(v: View, text: String): Boolean {
            if (v is android.widget.TextView && v.text.toString() == text) return v.performClick()
            if (v is ViewGroup) for (i in 0 until v.childCount) if (click(v.getChildAt(i), text)) return true
            return false
        }
        assertTrue(click(picker.window!!.decorView, "USE"))
        assertEquals(0xFF20C0A0.toInt(), picked)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockCornersTest {
    private fun all(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out)
        return out
    }

    private fun touch(v: View, action: Int) = v.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, action, 10f, 10f, 0))

    @Test
    fun cornersSaveTheirChoice() {
        val key = AppKey("com.example.maps", "com.example.maps.Main", 0)
        assertEquals(key, Corners.appKey(Corners.app(key)))
        assertEquals(null, Corners.appKey(Corners.TORCH))
        val prefs = Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.lockRight = Corners.app(key)
        val restored = prefs.toJson()
        prefs.lockRight = Corners.CAMERA
        prefs.restore(restored)
        assertEquals(Corners.app(key), prefs.lockRight)
        prefs.lockRight = Corners.CAMERA
    }

    @Test
    fun holdingFillsAndFiresATapSaysHoldAndOffHidesIt() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs(ctx).apply { lockLeft = Corners.CAMERA; lockRight = Corners.OFF }
        val lock = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = lock.window.decorView
        val corners = all(root).filterIsInstance<CornerButton>()
        assertEquals("the off corner is hidden", 1, corners.count { it.visibility == View.VISIBLE })
        val camera = corners.first { it.visibility == View.VISIBLE }
        // A quick tap: no camera, and HOLD TO USE shows on the lock screen itself.
        touch(camera, android.view.MotionEvent.ACTION_DOWN)
        touch(camera, android.view.MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(all(root).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString() == "HOLD TO USE" })
        assertEquals(null, shadowOf(lock).nextStartedActivity)
        // Holding: it fills, then opens the camera.
        touch(camera, android.view.MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        val started = shadowOf(lock).nextStartedActivity
        assertTrue(started != null && started.action!!.contains("STILL_IMAGE_CAMERA"))
        touch(camera, android.view.MotionEvent.ACTION_UP)
        Prefs(ctx).apply { lockLeft = Corners.TORCH; lockRight = Corners.CAMERA }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class Release19Test {
    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun dotCountsCountOnlyRealNotifications() {
        NotificationHub.replaceAll(listOf(
            Note("a", "chat", 0, "Sam", "Hi", 0, null, null, false, false, 1L),
            Note("b", "chat", 0, "Alex", "Yo", 0, null, null, false, false, 2L),
            Note("c", "chat", 0, "2 new", "", 0, null, null, false, true, 3L), // group header: not counted
            Note("d", "music", 0, "Playing", "", 0, null, null, true, false, 4L), // ongoing: not counted
        ))
        assertEquals(mapOf("chat/0" to 2), NotificationHub.dotCounts())
        NotificationHub.replaceAll(emptyList())
    }

    @Test
    fun gesturesHaveDefaultsAndRemember() {
        val prefs = Prefs(ctx)
        assertEquals(Gestures.NOTIFICATIONS, prefs.gestureDown)
        assertEquals(Gestures.PAGES, prefs.gesturePinch)
        val key = AppKey("com.example.maps", "Main", 0)
        prefs.gestureDouble = Gestures.app(key)
        assertEquals(key, Gestures.appKey(prefs.gestureDouble))
        val saved = prefs.toJson()
        prefs.gestureDouble = Gestures.NONE
        prefs.restore(saved)
        assertEquals(Gestures.app(key), prefs.gestureDouble)
        prefs.gestureDouble = Gestures.NONE
    }

    @Test
    fun fileSearchStaysOffWithoutPermission() {
        assertTrue(FileSearch.find(ctx, "beach", on = true).isEmpty())
        assertTrue(FileSearch.find(ctx, "beach", on = false).isEmpty())
    }

    @Test
    fun hiddenVaultSaysHowToHideWhenEmpty() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        Prefs(ctx).hidden = emptySet()
        HiddenVault.open(activity) // nothing to show: says so, doesn't crash
        assertEquals("No hidden apps. In the app drawer, hold an app and choose Hide from drawer.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun splitFlapClockDrawsCardsAndFlips() {
        val v = PixelClockView(ctx, blink = false).apply { font = "flap" }
        v.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.update(animate = false)
        val b = android.graphics.Bitmap.createBitmap(v.width, v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(Pixel.BG)
        v.draw(android.graphics.Canvas(b))
        // The cards are drawn: there are card-coloured pixels, not just background.
        val cardColour = blend(Pixel.PANEL, Pixel.BG, 0.3f)
        var cards = 0
        for (x in 0 until b.width step 7) for (y in 0 until b.height step 7) if (b.getPixel(x, y) == cardColour) cards++
        assertTrue(cards > 10)
        System.getenv("SHOT_DIR")?.let { dir -> java.io.File(dir, "clock-flap.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun pixelIconsAreBundledAt64() {
        for (n in listOf("settings", "search", "folder", "notifications", "lock", "camera", "flashlight", "weather")) {
            val b = PixelIcons.get(ctx, n)!!
            assertEquals(n, 64, b.width)
            assertEquals(n, 64, b.height)
        }
    }
}
