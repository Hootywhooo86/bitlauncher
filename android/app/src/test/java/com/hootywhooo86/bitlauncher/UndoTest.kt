package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoTest {
    private fun app(n: Int) = AppKey("demo.app$n", "Main", 0)

    private fun findText(v: View, text: String): android.widget.TextView? {
        if (v is android.widget.TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test
    fun removingShowsUndoAndUndoPutsItBack() {
        val ctx = RuntimeEnvironment.getApplication()
        val start = HomeLayout.empty(GridSize.MEDIUM)
        start.pages[0].add(FolderItem("Games", mutableListOf(app(1), app(2), app(3)), 2, 1))
        LayoutStore(ctx).save(start)
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val layoutField = LauncherActivity::class.java.getDeclaredField("layout").apply { isAccessible = true }
        fun layout() = layoutField.get(home) as HomeLayout

        // Remove the folder the way its menu does.
        val folder = layout().pages[0].single() as FolderItem
        LauncherActivity::class.java.getDeclaredMethod("removing", String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(home, "Games removed", { layout().remove(folder); Unit })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(layout().pages[0].isEmpty())
        val undo = findText(home.window.decorView, "UNDO")!!
        assertEquals(View.VISIBLE, (undo.parent as View).visibility)

        undo.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val back = layout().pages[0].single() as FolderItem
        assertEquals(listOf(app(1), app(2), app(3)), back.apps)
        assertEquals(2, back.x)
        // Saved, too: a restart keeps it.
        assertEquals(1, LayoutStore(ctx).load(GridSize.MEDIUM).pages[0].size)
    }

    @Test
    fun undoBarGoesAwayAfterFiveSeconds() {
        val ctx = RuntimeEnvironment.getApplication()
        val start = HomeLayout.empty(GridSize.MEDIUM)
        start.pages[0].add(AppItem(app(1), 0, 0))
        LayoutStore(ctx).save(start)
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val layoutField = LauncherActivity::class.java.getDeclaredField("layout").apply { isAccessible = true }
        LauncherActivity::class.java.getDeclaredMethod("removing", String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(home, "Removed", { (layoutField.get(home) as HomeLayout).pages[0].clear(); Unit })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5600))
        assertEquals(View.GONE, (findText(home.window.decorView, "UNDO")!!.parent as View).visibility)
    }

    @Test
    fun newBadgeRule() {
        val day = 24 * 3600_000L
        val firstRun = 1_000_000L
        val now = firstRun + 10 * day
        assertTrue(newApp(now - day, firstRun, now, 0)) // installed yesterday, never opened
        assertFalse(newApp(now - day, firstRun, now, 1)) // opened once: not new any more
        assertFalse(newApp(now - 3 * day, firstRun, now, 0)) // three days old
        assertFalse(newApp(firstRun - day, firstRun, firstRun + 1000, 0)) // was there before bitlauncher
    }
}
