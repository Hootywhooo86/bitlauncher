package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagesTest {
    private fun app(n: Int) = AppKey("demo.app$n", "Main", 0)
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    private fun home(build: (HomeLayout) -> Unit): LauncherActivity {
        val ctx = RuntimeEnvironment.getApplication()
        val start = HomeLayout.empty(GridSize.MEDIUM)
        build(start)
        LayoutStore(ctx).save(start)
        val a = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        return a
    }

    private fun layout(a: LauncherActivity) =
        LauncherActivity::class.java.getDeclaredField("layout").apply { isAccessible = true }.get(a) as HomeLayout

    private fun touch(a: LauncherActivity, from: Pair<Float, Float>, to: Pair<Float, Float>, hold: Boolean) {
        val root = a.window.decorView
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { root.dispatchTouchEvent(it); it.recycle() }
        send(MotionEvent.ACTION_DOWN, from.first, from.second)
        if (hold) idle(700)
        for (i in 1..12) {
            send(MotionEvent.ACTION_MOVE, from.first + (to.first - from.first) * i / 12, from.second + (to.second - from.second) * i / 12)
            idle(16)
        }
        send(MotionEvent.ACTION_UP, to.first, to.second)
        idle(400)
    }

    private fun centre(v: View): Pair<Float, Float> {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return (l[0] + v.width / 2f) to (l[1] + v.height / 2f)
    }

    @Test
    fun pinchShowsPagesThatDragDeleteAndAdd() {
        val a = home { l ->
            l.pages[0].add(AppItem(app(0), 0, 0))
            l.pages.add(mutableListOf(AppItem(app(1), 0, 0)))
            l.pages.add(mutableListOf(AppItem(app(2), 0, 0)))
        }
        find(a.window.decorView, Workspace::class.java)!!.listener!!.onPinchIn()
        idle(400)
        val overview = find(a.window.decorView, PageOverview::class.java)!!
        assertEquals(View.VISIBLE, overview.visibility)
        val thumbs = overview.grid.order.filterIsInstance<PageThumb>()
        assertEquals(3, thumbs.size)

        System.getenv("SHOT_DIR")?.let { dir ->
            val root = a.window.decorView
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { root.draw(it) }
            File(dir, "pages.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // Hold page 3 and drag it to the front.
        touch(a, centre(thumbs[2]), centre(thumbs[0]), hold = true)
        assertEquals(listOf(app(2), app(0), app(1)), layout(a).pages.map { (it[0] as AppItem).app })

        // Tap the X on (what is now) page 2: deleted, with undo.
        val second = overview.grid.order.filterIsInstance<PageThumb>()[1]
        val l = IntArray(2)
        second.getLocationOnScreen(l)
        touch(a, (l[0] + second.width - Pixel.dp(a, 16f)) to (l[1] + Pixel.dp(a, 16f)), (l[0] + second.width - Pixel.dp(a, 16f)) to (l[1] + Pixel.dp(a, 16f)), hold = false)
        assertEquals(listOf(app(2), app(1)), layout(a).pages.map { (it[0] as AppItem).app })

        // + adds an empty page.
        val add = overview.grid.order.last()
        add.performClick()
        idle()
        assertEquals(3, layout(a).pages.size)
        assertTrue(layout(a).pages.last().isEmpty())
    }

    @Test
    fun dragAWidgetHandleToResizeIt() {
        val widget = WidgetItem(42, "a/B", 0, 0, 2, 2)
        val a = home { l -> l.pages[0].add(widget) }
        val item = layout(a).pages[0].single() as WidgetItem
        LauncherActivity::class.java.getDeclaredMethod("startResize", WidgetItem::class.java).apply { isAccessible = true }.invoke(a, item)
        idle()
        val frame = find(a.window.decorView, ResizeFrame::class.java)!!
        val rootLoc = IntArray(2)
        a.window.decorView.getLocationOnScreen(rootLoc)
        // The right-hand handle sits at the middle of the widget's right edge.
        val hx = frame.cellLeft + 2 * frame.cellW
        val hy = frame.cellTop + frame.cellH
        touch(a, hx to hy, (hx + frame.cellW) to hy, hold = false)
        assertEquals(3, item.w)
        assertEquals(2, item.h)
        // Tap outside to finish.
        touch(a, 10f to (a.window.decorView.height - 10f), 10f to (a.window.decorView.height - 10f), hold = false)
        assertTrue(find(a.window.decorView, ResizeFrame::class.java) == null)
    }
}
