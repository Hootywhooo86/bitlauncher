package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.After
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitterSearchTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun clear() = NotificationHub.replaceAll(emptyList())

    private fun titles(q: String, apps: Int = 0) = BitterSearch(ctx) {}.answers(q, apps).map { it.title }

    @Test
    fun sumsConversionsAndTheWeb() {
        assertEquals("= 42", titles("6*7").first())
        assertTrue(titles("5 km to mi").first().startsWith("= 3.1"))
        val t = titles("pizza")
        assertTrue(t.contains("Search the web for \"pizza\""))
        assertTrue(t.contains("Find \"pizza\" in the Play Store"))
        assertTrue(t.contains("Find \"pizza\" on a map"))
        assertTrue(t.contains("Find \"pizza\" on YouTube"))
        // Lots of matching apps: the web stays out of the way, the rest still offered.
        assertFalse(titles("pizza", apps = 5).contains("Search the web for \"pizza\""))
        assertTrue(titles("").isEmpty())
    }

    @Test
    fun findsYourNotificationsAndSettings() {
        NotificationHub.replaceAll(listOf(
            Note("a", "chat", 0, "Sam", "Pizza at 7?", android.app.Notification.VISIBILITY_PUBLIC, null, null, false, false, 1L),
            Note("b", "music", 0, "Pizza song", "Playing", android.app.Notification.VISIBILITY_PUBLIC, null, null, true, false, 2L),
        ))
        val t = titles("pizza")
        assertTrue("a matching notification", t.contains("Sam"))
        assertFalse("ongoing ones (music) aren't results", t.contains("Pizza song"))
        assertTrue(titles("dock").contains("Dock style"))
        assertTrue(titles("wifi").any { it.contains("Wi", ignoreCase = true) })
    }

    @Test
    fun widgetsDrawCrispBitmapsAtTheirSize() {
        val search = SearchWidgets.render(ctx, 900, 150, "BITTER SEARCHER", arrayOf("##", "##"), Pixel.ACCENTS[0])
        assertEquals(900, search.width)
        assertEquals(150, search.height)
        System.getenv("SHOT_DIR")?.let { dir ->
            val apps = SearchWidgets.render(ctx, 420, 150, "ALL APPS", arrayOf("##.##", "##.##", ".....", "##.##", "##.##"), Pixel.ACCENTS[0])
            val sheet = Bitmap.createBitmap(940, 360, Bitmap.Config.ARGB_8888)
            sheet.eraseColor(0xFF2A3350.toInt())
            val c = android.graphics.Canvas(sheet)
            c.drawBitmap(SearchWidgets.render(ctx, 900, 150, "BITTER SEARCHER",
                arrayOf(".###..", "#...#.", "#...#.", "#...#.", ".###..", "....##", ".....#"), Pixel.ACCENTS[0]), 20f, 20f, null)
            c.drawBitmap(apps, 20f, 190f, null)
            File(dir, "search-widgets.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun searchScreenOpensWithAppsAndAnswers() {
        val intent = android.content.Intent(ctx, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_MODE, "search")
        val activity = Robolectric.buildActivity(SearchActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
            if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        assertTrue(texts(root).contains("BITTER SEARCHER"))
        fun edit(v: View): android.widget.EditText? {
            if (v is android.widget.EditText) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) edit(v.getChildAt(i))?.let { return it }
            return null
        }
        edit(root)!!.setText("12+30")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(texts(root).contains("= 42"))
        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(b))
            File(dir, "bitter-searcher.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
