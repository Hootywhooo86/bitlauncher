package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class StackTest {
    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    @Test
    fun swipingAStackFlipsItAndRemembers() {
        val ctx = RuntimeEnvironment.getApplication()
        val start = HomeLayout.empty(GridSize.MEDIUM)
        start.pages[0].add(WidgetItem(101, "rail/Clock", 0, 1, 4, 2).with(102, "nhl/Scores").also { it.showing = 0 })
        LayoutStore(ctx).save(start)
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val stack = find(home.window.decorView, StackView::class.java)!!
        assertEquals(2, stack.childCount)
        assertEquals(0, stack.showing)

        // Swipe up on it.
        val l = IntArray(2)
        stack.getLocationOnScreen(l)
        val x = l[0] + stack.width / 2f
        val y = l[1] + stack.height / 2f
        val root = home.window.decorView
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, yy: Float) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, yy, 0).also { root.dispatchTouchEvent(it); it.recycle() }
        send(MotionEvent.ACTION_DOWN, y)
        for (i in 1..8) { send(MotionEvent.ACTION_MOVE, y - i * 25f); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        send(MotionEvent.ACTION_UP, y - 200f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        assertEquals(1, stack.showing)
        // The drawer didn't open: the swipe belonged to the stack.
        assertEquals(View.GONE, find(root, DrawerView::class.java)!!.visibility)
        val saved = LayoutStore(ctx).load(GridSize.MEDIUM).pages[0].single() as WidgetItem
        assertEquals(1, saved.showing)
    }
}
