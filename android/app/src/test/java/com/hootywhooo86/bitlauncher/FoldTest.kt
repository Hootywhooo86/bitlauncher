package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
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

/** An unfolded Pixel Fold shows two pages side by side, like the Pixel launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun find(v: View): Workspace? {
        if (v is Workspace) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp-xxhdpi")
    fun unfoldedShowsTwoPagesSideBySide() {
        val ctx = RuntimeEnvironment.getApplication()
        val icons = IconCache(ctx)
        val colors = intArrayOf(0xFF34D399.toInt(), 0xFF60A5FA.toInt(), 0xFFF87171.toInt(), 0xFFFBBF24.toInt(), 0xFFA78BFA.toInt())
        val keys = (0 until 30).map { AppKey("demo.$it", "Main", 0) }
        keys.forEachIndexed { i, k ->
            icons.ensure(k, 1) { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[i % colors.size]); setStroke(12, -1) } }
        }
        val layout = HomeLayout.empty(GridSize.MEDIUM)
        for (i in 4 until 34) layout.placeAnywhere(AppItem(keys[i % 30], 0, 0))
        for (i in 0 until 4) layout.setDock(i, AppItem(keys[i], i, 0))
        LayoutStore(ctx).save(layout)

        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        val ws = find(home.window.decorView)!!
        assertEquals(2, ws.panes)
        assertEquals(0 until 2, ws.visiblePages())
        val left = ws.page(0)!!
        val right = ws.page(1)!!
        // Side by side, each about half the screen, with a gap between.
        assertTrue(right.left > left.right)
        assertTrue(left.width < ws.width / 2)
        assertEquals(left.width, right.width)

        System.getenv("SHOT_DIR")?.let { dir ->
            val root = home.window.decorView
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(0xFF3B2F6B.toInt()); root.draw(it) }
            File(dir, "fold.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun foldingAndUnfoldingSwitchesLive() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        idle()
        val ws = find(controller.get().window.decorView)!!
        assertEquals(1, ws.panes)
        RuntimeEnvironment.setQualifiers("w841dp-h701dp")
        controller.configurationChange()
        idle()
        assertEquals(2, ws.panes)
        assertEquals(0, ws.pageCount % 2) // a lone page gets an empty partner
        RuntimeEnvironment.setQualifiers("w411dp-h891dp")
        controller.configurationChange()
        idle()
        assertEquals(1, ws.panes)
        assertTrue(!controller.get().isFinishing)
    }
}
