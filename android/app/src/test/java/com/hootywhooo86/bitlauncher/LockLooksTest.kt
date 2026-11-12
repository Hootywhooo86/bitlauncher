package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
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
