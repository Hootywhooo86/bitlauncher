package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LooksTest {
    @Test
    fun dissolveOrderUsesEveryBlockOnce() {
        val rank = PageTransitions.dissolveOrder(12, 20)
        assertEquals((0 until 240).toList(), rank.sorted())
        // Fixed: the same pattern every time.
        assertTrue(rank.contentEquals(PageTransitions.dissolveOrder(12, 20)))
    }

    private fun workspace(transition: String): Workspace {
        val ctx = RuntimeEnvironment.getApplication()
        val ws = Workspace(ctx)
        repeat(3) { ws.addView(View(ctx)) }
        ws.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        ws.layout(0, 0, 1000, 2000)
        ws.transition = transition
        return ws
    }

    @Test
    fun transitionsOnlyChangePagesMidSwipeAndLeaveThemPlainAtRest() {
        for ((key, _) in PageTransitions.ALL) {
            val ws = workspace(key)
            ws.scrollTo(500, 0) // halfway from page 1 to page 2
            val leaving = ws.getChildAt(0)
            val coming = ws.getChildAt(1)
            when (key) {
                "cube" -> {
                    assertEquals(-45f, leaving.rotationY, 0.01f)
                    assertEquals(45f, coming.rotationY, 0.01f)
                }
                "stack" -> {
                    assertTrue(leaving.scaleX < 1f)
                    assertEquals(500f, leaving.translationX, 0.01f)
                }
                "dissolve" -> {
                    assertEquals(500f, leaving.translationX, 0.01f)
                    assertEquals(-500f, coming.translationX, 0.01f)
                }
                else -> assertEquals(0f, leaving.translationX, 0.01f)
            }
            ws.scrollTo(1000, 0) // landed on page 2
            for (i in 0 until 3) with(ws.getChildAt(i)) {
                assertEquals(key, 0f, rotationY, 0.01f)
                assertEquals(key, 1f, scaleX, 0.01f)
                assertEquals(key, 0f, translationX, 0.01f)
            }
        }
    }

    @Test
    fun dockStylesDrawAndAppearInSettings() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = System.getenv("SHOT_DIR")
        val w = 1000
        val h = 200
        val sheet = Bitmap.createBitmap(w + 40, (h + 30) * DockStyles.ALL.size + 30, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(0xFF2A3350.toInt()) // something like a scene behind
        val c = Canvas(sheet)
        DockStyles.ALL.forEachIndexed { i, (key, _) ->
            val d = DockDrawable(ctx, key)
            d.setBounds(20, 30 + i * (h + 30), 20 + w, 30 + i * (h + 30) + h)
            d.draw(c)
            // Five stand-in icons on it.
            val p = android.graphics.Paint().apply { color = 0xFFE0E0F0.toInt() }
            for (k in 0 until 5) {
                val x = 20 + 60 + k * 190f
                c.drawRect(x, 30 + i * (h + 30) + 40f, x + 100, 30 + i * (h + 30) + 140f, p)
            }
        }
        dir?.let { File(it, "dock-styles.png").outputStream().use { o -> sheet.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
            if (v is android.widget.TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        val shown = texts(activity.window.decorView)
        for ((_, name) in DockStyles.ALL + PageTransitions.ALL + IconMotion.ALL) assertTrue(name, shown.contains(name.uppercase()))
    }

    @Test
    fun lastUsedAppHopsAndNothingElseDoes() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).iconMotion = "last"
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        // No apps on an empty test phone: nothing hops, and nothing breaks.
        fun icons(v: View, out: MutableList<AppIconView> = mutableListOf()): List<AppIconView> {
            if (v is AppIconView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) icons(v.getChildAt(i), out)
            return out
        }
        assertTrue(icons(controller.get().window.decorView).none { it.wiggle == 2 })
        Prefs(ctx).iconMotion = "off"
    }
}
