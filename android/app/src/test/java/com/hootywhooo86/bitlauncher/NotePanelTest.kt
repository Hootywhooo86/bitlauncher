package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class NotePanelTest {
    private val now = System.currentTimeMillis()

    private fun note(key: String, pkg: String, title: String, text: String = "", agoMin: Int = 0, clearable: Boolean = true, loud: Boolean = true, summary: Boolean = false) =
        Note(key, pkg, 0, title, text, android.app.Notification.VISIBILITY_PRIVATE, null, null, !clearable, summary, now - agoMin * 60_000L,
            clearable = clearable, loud = loud)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun <T : View> find(v: View, cls: Class<T>): List<T> {
        val out = mutableListOf<T>()
        fun walk(x: View) {
            if (cls.isInstance(x)) out.add(cls.cast(x)!!)
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun grantAccess(on: Boolean) {
        val ctx = RuntimeEnvironment.getApplication()
        val me = android.content.ComponentName(ctx, BitNotificationService::class.java).flattenToString()
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", if (on) me else "")
    }

    private fun shoot(v: View, name: String) = System.getenv("SHOT_DIR")?.let { dir ->
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        Canvas(b).also { it.drawColor(Pixel.BG); v.draw(it) }
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun clear() = NotificationHub.replaceAll(emptyList())

    @Test
    fun groupsByAppNewestFirstAndSkipsGroupHeaders() {
        val groups = NoteCards.groups(listOf(
            note("a1", "chat", "Sam", agoMin = 30),
            note("b1", "mail", "Invoice", agoMin = 5),
            note("a2", "chat", "Alex", agoMin = 1),
            note("a0", "chat", "2 new messages", summary = true),
        ))
        assertEquals(listOf("chat", "mail"), groups.map { it.first().pkg })
        assertEquals(listOf("a2", "a1"), groups[0].map { it.key })
    }

    @Test
    fun onlyNewLoudNotificationsPopUp() {
        val n = note("k", "chat", "Sam", "Hi")
        assertTrue(NotificationHub.worthPopping(null, n))
        assertFalse("silent", NotificationHub.worthPopping(null, n.copy(loud = false)))
        assertFalse("ongoing music", NotificationHub.worthPopping(null, n.copy(ongoing = true)))
        assertFalse("progress update of the same one", NotificationHub.worthPopping(n, n.copy(text = "Hi again", posted = n.posted + 200)))
        assertTrue("a real new message in the same thread", NotificationHub.worthPopping(n, n.copy(posted = n.posted + 60_000)))
        assertFalse("nothing to say", NotificationHub.worthPopping(null, n.copy(title = "", text = "")))
    }

    @Test
    fun agoIsShort() {
        assertEquals("now", NoteCards.ago(now, now))
        assertEquals("5m", NoteCards.ago(now - 5 * 60_000, now))
        assertEquals("3h", NoteCards.ago(now - 3 * 3_600_000, now))
        assertEquals("2d", NoteCards.ago(now - 49 * 3_600_000, now))
    }

    private fun panelIn(activity: Activity): NotificationPanel {
        val icons = IconCache(activity)
        val panel = NotificationPanel(activity, icons) {}
        activity.setContentView(FrameLayout(activity).apply { addView(panel, FrameLayout.LayoutParams(-1, -1)) })
        panel.setInsets(60, 40)
        return panel
    }

    @Test
    fun panelShowsNotificationsAndSwipingOneAwayRemovesIt() {
        grantAccess(true)
        NotificationHub.replaceAll(listOf(
            note("a1", "com.example.chat", "Sam", "Running late, 10 min", agoMin = 12),
            note("a2", "com.example.chat", "Alex", "Lunch tomorrow?", agoMin = 2),
            note("m1", "com.example.mail", "Your order shipped", "Arrives Friday", agoMin = 40),
            note("p1", "com.example.music", "Now playing", "Track 4", clearable = false),
        ))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel = panelIn(activity)
        panel.open()
        idle()
        val shown = texts(panel)
        assertTrue(shown.contains("NOTIFICATIONS"))
        assertTrue(shown.contains("CLEAR ALL"))
        assertTrue(shown.contains("PHONE SHADE"))
        assertTrue(shown.contains("Lunch tomorrow?"))
        assertTrue(shown.contains("Your order shipped"))
        shoot(activity.window.decorView, "notes-panel.png")

        // Music can't be swiped away; the others can.
        val cards = find(panel, SwipeCard::class.java)
        assertEquals(4, cards.size)
        val sam = cards.first { it.tag == "a1" }
        val w = sam.width.toFloat()
        val t = SystemClock.uptimeMillis()
        val loc = IntArray(2).also { sam.getLocationOnScreen(it) }
        val y = loc[1] + sam.height / 2f
        fun ev(action: Int, x: Float, dt: Long) = MotionEvent.obtain(t, t + dt, action, x, y, 0)
        activity.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 0))
        for (i in 1..10) activity.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 100f + i * w / 15, i * 16L))
        activity.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 100f + w * 0.7f, 180))
        idle()
        assertFalse(texts(panel).contains("Running late, 10 min"))
        assertTrue(texts(panel).contains("Lunch tomorrow?"))

        panel.close()
        idle()
        assertFalse(panel.isOpen)
    }

    @Test
    fun panelSaysSoWhenEmptyOrWithoutAccess() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel = panelIn(activity)
        grantAccess(false)
        panel.open()
        idle()
        assertTrue(texts(panel).any { it.contains("can't see your notifications yet") })
        assertTrue(texts(panel).contains("ALLOW NOTIFICATION ACCESS"))
        panel.close()
        idle()

        grantAccess(true)
        panel.open()
        idle()
        assertTrue(texts(panel).any { it.contains("all caught up") })
        assertFalse(texts(panel).contains("CLEAR ALL"))
    }

    @Test
    fun homeOpensThePixelListAndPopsUpNewOnes() {
        grantAccess(true)
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).apply { swipeDown = "pixel"; popups = "home" }
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        idle()
        NotificationHub.put(note("x1", "com.example.chat", "Sam", "Are you coming?"))
        idle()
        val root = activity.window.decorView
        val popup = find(root, PopupCard::class.java).firstOrNull()
        assertNotNull("a pop-up slid in", popup)
        assertTrue(texts(root).contains("Are you coming?"))
        shoot(root, "notes-popup.png")

        // Off means off.
        Prefs(ctx).popups = "off"
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        NotificationHub.put(note("x2", "com.example.chat", "Alex", "Hello?"))
        idle()
        assertNull(find(root, PopupCard::class.java).firstOrNull())
        Prefs(ctx).popups = "home"
    }

    @Test
    fun sceneCanBeHeldAtNight() {
        assertNull(Light.fixed("auto"))
        val night = Light.fixed("night")!!
        assertEquals(0f, night.day)
        assertEquals(1f, Light.fixed("day")!!.day)
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.sceneTime = "night"
        val restored = Prefs(ctx).apply { sceneTime = "auto"; restore(prefs.toJson().put("sceneTime", "night")) }
        assertEquals("night", restored.sceneTime)

        // Railway at night, whatever the clock says.
        val scene = SceneView(ctx).apply { scene = "railway"; timeOfDay = "night" }
        scene.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY))
        scene.layout(0, 0, 1233, 2673)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        Thread.sleep(1500)
        idle()
        shoot(scene, "railway-night.png")
        prefs.sceneTime = "auto"
    }
}
