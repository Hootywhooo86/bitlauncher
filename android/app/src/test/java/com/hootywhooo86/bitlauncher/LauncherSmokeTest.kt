package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Starts the real home screen and settings on a simulated phone, to catch crashes before a device does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherSmokeTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun findWorkspace(v: View): Workspace? {
        if (v is Workspace) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findWorkspace(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun homeStartsOnAnEmptyPhoneAndSaysWhatToDo() {
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        val workspace = findWorkspace(root)!!
        assertEquals(1, workspace.pageCount)
        val page = workspace.page(0)!!
        assertEquals(5, page.cols) // 5x6 by default
        assertEquals(6, page.rows)
        assertTrue(hasVisibleText(root, "SWIPE UP FOR ALL APPS"))
    }

    @Test
    fun gridSettingChangesThePagesWhenYouComeBack() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        idle()
        val prefs = Prefs(controller.get())
        prefs.grid = GridSize.LARGE
        prefs.touch()
        controller.pause().resume()
        idle()
        val page = findWorkspace(controller.get().window.decorView)!!.page(0)!!
        assertEquals(6, page.cols)
        assertEquals(7, page.rows)
    }

    @Test
    fun settingsRendersEveryGridSize() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        for (label in listOf("4 x 5", "5 x 6", "6 x 7")) assertTrue(label, hasVisibleText(root, label))
        assertTrue(hasVisibleText(root, "Free, always."))
    }

    @Test
    fun homeButtonAndBackNeverLeaveOrCrash() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        idle()
        @Suppress("DEPRECATION")
        controller.get().onBackPressed()
        controller.newIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME))
        idle()
        assertTrue(!controller.get().isFinishing)
    }

    @Test
    fun drawerFollowsTheFingerAndSettles() {
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        val workspace = findWorkspace(root)!!
        val drawer = findDrawer(root)!!
        val listener = workspace.listener!!

        // Halfway through a pull, the drawer is partly in.
        listener.onDrawerDrag(root.height * 0.2f)
        assertEquals(View.VISIBLE, drawer.visibility)
        assertTrue(drawer.alpha > 0f && drawer.alpha < 1f)

        // A flick up finishes opening it.
        listener.onDrawerRelease(root.height * 0.2f, -5000f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(1f, drawer.alpha)
        assertEquals(0f, drawer.translationY)

        // Pulled down a little and let go slowly: it stays open.
        drawer.onPullDown!!(20f)
        drawer.onPullRelease!!(20f, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(View.VISIBLE, drawer.visibility)

        // Pulled well down: it closes, and home comes back to full size.
        drawer.onPullDown!!(root.height * 0.4f)
        drawer.onPullRelease!!(root.height * 0.4f, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(View.GONE, drawer.visibility)
        assertEquals(1f, workspace.scaleX)
        assertEquals(1f, workspace.alpha)

        // A short, slow tug from home slides back closed.
        listener.onDrawerDrag(10f)
        listener.onDrawerRelease(10f, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(View.GONE, drawer.visibility)
    }

    @Test
    fun drawerSearchAnswersSumsSettingsAndTheWeb() {
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val drawer = findDrawer(activity.window.decorView)!!
        drawer.search.setText("12*8")
        idle()
        assertTrue(hasVisibleText(drawer, "= 96"))
        drawer.search.setText("wifi")
        idle()
        assertTrue(hasVisibleText(drawer, "Wi-Fi"))
        assertTrue(hasVisibleText(drawer, "Search the web for"))
    }

    private fun findDrawer(v: View): DrawerView? {
        if (v is DrawerView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findDrawer(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun lockScreenOpensWithClockAndUnlockHint() {
        val activity = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        assertTrue(hasVisibleText(root, "SWIPE UP TO UNLOCK"))
        assertTrue(findClock(root) != null)
        assertTrue(LockActivity.shownAt > 0)
    }

    @Test
    fun homeClockCanBeTurnedOff() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        idle()
        assertEquals(View.VISIBLE, (findClock(controller.get().window.decorView)!!.parent as View).visibility)
        Prefs(controller.get()).apply { homeClock = false; touch() }
        controller.pause().resume()
        idle()
        assertEquals(View.GONE, (findClock(controller.get().window.decorView)!!.parent as View).visibility)
    }

    @Test
    fun settingsOffersLockScreenAndNotificationAccess() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        assertTrue(hasVisibleText(root, "LOCK SCREEN"))
        assertTrue(hasVisibleText(root, "ALLOW NOTIFICATION ACCESS"))
        assertTrue(hasVisibleText(root, "ANIMATIONS"))
    }

    private fun findClock(v: View): PixelClockView? {
        if (v is PixelClockView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findClock(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun spriteIsCrispAndOutlined() {
        // A solid red 90x90 square becomes a 30x30 red block with a dark 1px border.
        val big = 90
        val red = 0xFFFF0000.toInt()
        val sprite = IconCache.spriteFrom(IntArray(big * big) { red }, big)
        assertEquals(32 * 32, sprite.size)
        assertEquals(Pixel.SHADOW, sprite[0 * 32 + 5]) // outline row
        assertEquals(red, sprite[1 * 32 + 1])
        assertEquals(0, sprite[0]) // corner stays clear
        // Half-transparent pixels snap to fully on or off: nothing in between.
        val faint = IconCache.spriteFrom(IntArray(big * big) { 0x40FF0000 }, big)
        assertTrue(faint.all { it == 0 })
        assertNotEquals(0, sprite.count { it == red })
    }

    private fun hasVisibleText(v: View, text: String): Boolean {
        if (v is android.widget.TextView && v.visibility == View.VISIBLE && v.text.toString().contains(text)) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (hasVisibleText(v.getChildAt(i), text)) return true
        return false
    }
}
