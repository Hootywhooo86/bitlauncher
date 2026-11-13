package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class LockLooksTest {
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @After
    fun reset() {
        NotificationHub.replaceAll(emptyList())
        Prefs(RuntimeEnvironment.getApplication()).lockStyle = "classic"
    }

    @Test
    fun summaryAndPlaces() {
        assertNull(LockLooks.summary(emptyList()) { it })
        val lines = listOf(LockLine("chat", 0, 2, null, null, null), LockLine("mail", 0, 1, null, null, null))
        assertEquals("3 NEW  ·  CHAT, MAIL", LockLooks.summary(lines) { it })
        assertEquals(listOf("1ST", "2ND", "3RD", "4TH"), (0..3).map(LockLooks::place))
    }

    @Test
    fun everyLookRendersWithNotifications() {
        val ctx = RuntimeEnvironment.getApplication()
        val me = android.content.ComponentName(ctx, BitNotificationService::class.java).flattenToString()
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", me)
        Settings.Secure.putInt(ctx.contentResolver, "lock_screen_allow_private_notifications", 1)
        val now = System.currentTimeMillis()
        fun n(k: String, pkg: String, t: String, x: String, ago: Int) =
            Note(k, pkg, 0, t, x, android.app.Notification.VISIBILITY_PUBLIC, null, null, false, false, now - ago * 60_000L)
        NotificationHub.replaceAll(listOf(
            n("a", "chat", "Sam", "Running late, save me a seat", 2),
            n("b", "chat", "Alex", "Lunch?", 9),
            n("c", "mail", "Your order shipped", "Arrives Friday", 30),
        ))
        val prefs = Prefs(ctx).apply { lockDetail = 2; scene = "none" }
        for (look in LockLooks.ALL) {
            prefs.lockStyle = look.key
            val lock = Robolectric.buildActivity(LockActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            val root = lock.window.decorView
            val shown = texts(root)
            assertTrue(look.key, shown.contains(LockLooks.hint(look.key)))
            when (look.key) {
                "minimal" -> assertTrue(shown.contains("3 NEW  ·  CHAT, MAIL"))
                "arcade" -> assertTrue(shown.containsAll(listOf("PLAYER 1", "HIGH SCORES", "1ST")))
                "rpg" -> assertTrue(shown.any { it.startsWith("Sam: Running late") })
                "terminal" -> assertTrue(shown.any { it.startsWith("[CHAT x2] Sam: Running late") })
                "desktop" -> assertTrue(shown.contains("CHAT (2)"))
                "departures" -> assertTrue(shown.containsAll(listOf("DEPARTURES", "TIME", "CHAT x2")))
                else -> assertTrue(look.key, shown.any { it.contains("Running late") })
            }
            System.getenv("SHOT_DIR")?.let { dir ->
                val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                Canvas(b).also { root.draw(it) }
                File(dir, "look-${look.key}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            lock.finish()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeStylesTest {
    @Test
    fun everyChargeStyleDrawsAtEveryLevel() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = System.getenv("SHOT_DIR")
        val sheet = if (dir != null) Bitmap.createBitmap(1100, 140 * ChargeView.STYLES.size, Bitmap.Config.ARGB_8888) else null
        sheet?.eraseColor(Pixel.BG)
        ChargeView.STYLES.forEachIndexed { row, (key, _) ->
            listOf(5, 45, 80, 100).forEachIndexed { col, level ->
                val v = ChargeView(ctx).apply { style = key; this.level = level; charging = level < 100 }
                v.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                v.layout(0, 0, v.measuredWidth, v.measuredHeight)
                val b = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(b))
                sheet?.let { Canvas(it).drawBitmap(b, 20f + col * 270f, 20f + row * 140f, null) }
            }
        }
        sheet?.let { s -> File(dir, "charge-styles.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockExpandTest {
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun find(v: View, text: String): View? {
        if (v is android.widget.TextView && v.isShown && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), text)?.let { return it }
        return null
    }

    /** The clickable row holding [text]. */
    private fun clickableAbove(v: View): View {
        var x: View? = v
        while (x != null && !x.isClickable) x = x.parent as? View
        return x!!
    }

    @After
    fun reset() {
        NotificationHub.replaceAll(emptyList())
        Prefs(RuntimeEnvironment.getApplication()).apply { lockStyle = "classic"; lockDetail = 0 }
    }

    @Test
    fun tappingAnAppShowsEachOfItsNotifications() {
        val ctx = RuntimeEnvironment.getApplication()
        val me = android.content.ComponentName(ctx, BitNotificationService::class.java).flattenToString()
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", me)
        Settings.Secure.putInt(ctx.contentResolver, "lock_screen_allow_private_notifications", 1)
        val now = System.currentTimeMillis()
        fun n(k: String, pkg: String, t: String, x: String, ago: Int, vis: Int = android.app.Notification.VISIBILITY_PUBLIC) =
            Note(k, pkg, 0, t, x, vis, null, null, false, false, now - ago * 60_000L)
        NotificationHub.replaceAll(listOf(
            n("a", "chat", "Sam", "Running late, save me a seat", 2),
            n("b", "chat", "Alex", "Lunch?", 9),
            n("s", "chat", "Secret", "Never shown", 1, android.app.Notification.VISIBILITY_SECRET),
            n("c", "mail", "Your order shipped", "Arrives Friday", 30),
        ))
        for (look in LockLooks.ALL.map { it.key }) {
            Prefs(ctx).apply { lockDetail = 2; scene = "none"; lockStyle = look }
            val lock = Robolectric.buildActivity(LockActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            val root = lock.window.decorView
            if (look == "minimal") clickableAbove(find(root, "3 NEW  ·  CHAT, MAIL")!!).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(look, texts(root).any { it.contains("Lunch?") })
            // Tap the chat app's row: both its messages show on their own, the secret one doesn't.
            val chatRow = root.findClickable { it.contains("CHAT") }
            chatRow!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val shown = texts(root)
            assertTrue(look, shown.any { it.contains("Lunch?") })
            assertTrue(look, shown.any { it.contains("Running late, save me a seat") })
            assertFalse(look, shown.any { it.contains("Never shown") })
            for (b in listOf("CLEAR", "BACK", "OPEN")) assertTrue("$look $b", shown.any { it.contains(b) })
            assertFalse("only the opened app shows", shown.any { it.contains("Arrives Friday") })
            System.getenv("SHOT_DIR")?.let { dir ->
                val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                Canvas(b).also { root.draw(it) }
                File(dir, "open-$look.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            root.findClickable { it.contains("BACK") }!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(look, texts(root).any { it.contains("Lunch?") })
            lock.finish()
        }
    }

    /** First clickable view whose own texts match. */
    private fun View.findClickable(match: (String) -> Boolean): View? {
        if (isClickable && texts(this).any(match)) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findClickable(match)?.let { return it }
        return null
    }

    @Test
    fun appNamesOnlyStillListsThemButSaysPreviewsAreOff() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val notes = listOf(Note("a", "chat", 0, "Sam", "Hi", android.app.Notification.VISIBILITY_PUBLIC, null, null, false, false, now))
        val hidden = NotePrivacy.lockNotes(notes, "chat", 0, NotePrivacy.APPS_ONLY, false)
        assertEquals(1, hidden.size)
        assertNull(hidden[0].title)
        val full = NotePrivacy.lockNotes(notes, "chat", 0, NotePrivacy.FULL, false)
        assertEquals("Hi", full[0].text)
        assertNull("phone hides sensitive content", NotePrivacy.lockNotes(notes, "chat", 0, NotePrivacy.FULL, true)[0].text)
        assertTrue(ctx != null)
    }
}
