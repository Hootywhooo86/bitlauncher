package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Real finger drags inside an open folder: hold an app, move it, let go. The order is what's
 * on screen and what's saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderDragTest {
    private val apps = (0 until 8).map { AppKey("demo.app$it", "Main", 0) }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    private fun openFolder(): Pair<Activity, FolderView> {
        val ctx = RuntimeEnvironment.getApplication()
        val layout = HomeLayout.empty(GridSize.MEDIUM)
        layout.pages[0].add(FolderItem("Games", apps.toMutableList(), 0, 0))
        LayoutStore(ctx).save(layout)
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val page = find(home.window.decorView, Workspace::class.java)!!.page(0)!!
        (page.getChildAt(0) as AppIconView).performClick()
        idle(400)
        val folder = find(home.window.decorView, FolderView::class.java)!!
        assertEquals(View.VISIBLE, folder.visibility)
        return home to folder
    }

    /** Screen position of the middle of slot [n] (0-based) in the folder. */
    private fun slotCentre(f: FolderView, n: Int): Pair<Float, Float> {
        val g = IntArray(2)
        f.grid.getLocationOnScreen(g)
        return (g[0] + f.grid.slotLeft(n) + f.grid.cellWidth() / 2) to (g[1] + f.grid.slotTop(n) + f.grid.cellHeight / 2f)
    }

    /** Press, hold past the long-press time, slide in small steps, let go. */
    private fun drag(home: Activity, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val root = home.window.decorView
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            root.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from.first, from.second)
        idle(700) // hold: the long press fires (the menu shows)
        val steps = 12
        for (i in 1..steps) {
            val x = from.first + (to.first - from.first) * i / steps
            val y = from.second + (to.second - from.second) * i / steps
            send(MotionEvent.ACTION_MOVE, x, y)
            idle(16)
        }
        send(MotionEvent.ACTION_UP, to.first, to.second)
        idle(500)
    }

    private fun savedOrder(): List<AppKey> {
        val o = JSONObject(File(RuntimeEnvironment.getApplication().filesDir, "layout.json").readText())
        return HomeLayout.fromJson(o).pages[0].filterIsInstance<FolderItem>().single().apps
    }

    @Test
    fun dragSpotOneToSpotThreeThenSpotThreeToSpotSeven() {
        val (home, folder) = openFolder()

        drag(home, slotCentre(folder, 0), slotCentre(folder, 2))
        val first = listOf(apps[1], apps[2], apps[0], apps[3], apps[4], apps[5], apps[6], apps[7])
        assertEquals(first, folder.icons().map { it.tagItem })
        assertEquals(first, savedOrder())
        // Every icon has settled back into its slot: nothing left floating.
        folder.icons().forEach { assertEquals(0f, it.translationX, 0.5f); assertEquals(0f, it.translationY, 0.5f) }

        drag(home, slotCentre(folder, 2), slotCentre(folder, 6))
        val second = listOf(apps[1], apps[2], apps[3], apps[4], apps[5], apps[6], apps[0], apps[7])
        assertEquals(second, folder.icons().map { it.tagItem })
        assertEquals(second, savedOrder())
        assertEquals(View.VISIBLE, folder.visibility) // still open, ready for the next one
    }

    @Test
    fun dragBackwardsToSpotOne() {
        val (home, folder) = openFolder()
        drag(home, slotCentre(folder, 5), slotCentre(folder, 0))
        assertEquals(listOf(apps[5], apps[0], apps[1], apps[2], apps[3], apps[4], apps[6], apps[7]), savedOrder())
    }

    @Test
    fun midDragLooksLifted() {
        val (home, folder) = openFolder()
        val root = home.window.decorView
        val from = slotCentre(folder, 0)
        val to = slotCentre(folder, 5)
        val down = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from.first, from.second, 0))
        idle(700)
        for (i in 1..8) {
            root.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                from.first + (to.first - from.first) * i / 10, from.second + (to.second - from.second) * i / 10, 0))
            idle(16)
        }
        idle(300)
        assertTrue(folder.icons().any { it.lifted })
        // The long-press menu got out of the way when the drag began.
        val menuField = LauncherActivity::class.java.getDeclaredField("menu").apply { isAccessible = true }
        assertTrue(!(menuField.get(home) as PixelMenu).isShowing)
        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(0xFF3B2F6B.toInt()); root.draw(it) }
            File(dir, "folderdrag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        root.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, to.first, to.second, 0))
        idle(500)
        assertTrue(folder.icons().none { it.lifted })
    }

    @Test
    fun draggingPastTheEdgeLeavesTheFolder() {
        val (home, folder) = openFolder()
        val from = slotCentre(folder, 1)
        // Down towards the dock, well outside the panel.
        val to = from.first to home.window.decorView.height - 80f
        drag(home, from, to)
        assertEquals(View.GONE, folder.visibility)
        // Nothing re-ordered on the way out.
        assertEquals(apps, savedOrder())
    }
}
