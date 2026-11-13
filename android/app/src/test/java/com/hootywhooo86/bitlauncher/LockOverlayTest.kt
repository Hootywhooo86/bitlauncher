package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock screen as the accessibility service shows it on a real phone: an overlay window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockOverlayTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is android.widget.TextView) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun windows(service: LockService): List<View> {
        val wm = service.getSystemService(WindowManager::class.java)
        return org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views
    }

    @Test
    fun overlayGoesUpAboveTheLockScreenAndComesDownOnUnlock() {
        val service = Robolectric.setupService(LockService::class.java)
        for (look in LockLooks.ALL) {
            Prefs(service).lockStyle = look.key
            assertTrue(service.showOverlay())
            idle()
            val win = windows(service)
            assertEquals(look.key, 1, win.size)
            val lp = win[0].layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, lp.type)
            assertTrue(texts(win[0]).contains(LockLooks.hint(look.key)))

            // Showing again while it's up doesn't stack a second window.
            assertTrue(service.showOverlay())
            idle()
            assertEquals(1, windows(service).size)

            // Unlocked with the fingerprint: it dissolves and the window goes.
            service.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertFalse(look.key, windows(service).isNotEmpty())
        }
        Prefs(service).lockStyle = "classic"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class UnlockAndWallpaperTest {
    @Test
    fun swipingUpGoesStraightToThePinPad() {
        val service = Robolectric.setupService(LockService::class.java)
        val keyguard = service.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)
        val overlay = LockOverlay(service)
        overlay.show()
        shadowOf(Looper.getMainLooper()).idle()
        var opened = false
        overlay.dismissKeyguard({ opened = true }, {})
        val started = shadowOf(service.application).nextStartedActivity
        assertEquals(UnlockActivity::class.java.name, started.component!!.className)
        assertFalse("nothing opens before the phone is unlocked", opened)
        service.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(opened)
        shadowOf(keyguard).setKeyguardLocked(false)
    }

    @Test
    fun lockWallpaperIsAFullSizePixelSceneThatChangesWithTheNight() {
        val day = LockWallpaper.render("railway", 1080, 2400, Light.fixed("day")!!, null)
        val night = LockWallpaper.render("railway", 1080, 2400, Light.fixed("night")!!, null)
        assertEquals(1080, day.width)
        assertEquals(2400, day.height)
        // The sky at the top is much darker at night.
        fun brightness(c: Int) = android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)
        assertTrue(brightness(night.getPixel(540, 100)) < brightness(day.getPixel(540, 100)))
        System.getenv("SHOT_DIR")?.let { dir ->
            java.io.File(dir, "lock-wallpaper.png").outputStream().use { night.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val prefs = Prefs(org.robolectric.RuntimeEnvironment.getApplication()).apply { scene = "railway"; sceneTime = "night" }
        val a = LockWallpaper.key(prefs, Light.fixed("night")!!, null)
        prefs.sceneTime = "day"
        assertTrue(a != LockWallpaper.key(prefs, Light.fixed("day")!!, null))
        prefs.apply { scene = "none"; sceneTime = "auto" }
    }
}
