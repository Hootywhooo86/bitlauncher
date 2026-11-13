package com.hootywhooo86.bitlauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Locks the screen on a double tap of the home screen. Android only lets an accessibility
 * service do this while keeping fingerprint and face unlock working, so that's what this is:
 * it listens for nothing and reads nothing.
 */
class LockService : AccessibilityService() {
    private var overlay: LockOverlay? = null

    override fun onServiceConnected() {
        running = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlay?.remove()
        overlay = null
        running = null
        return super.onUnbind(intent)
    }

    /** Put the pixel lock screen up in an overlay window, above the phone's own lock screen. */
    fun showOverlay(): Boolean = try {
        (overlay ?: LockOverlay(this).also { overlay = it }).show()
        true
    } catch (e: Exception) {
        false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile
        private var running: LockService? = null

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, LockService::class.java).flattenToString()
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /**
         * Show the pixel lock screen. Android blocks apps from opening screens in the background,
         * and draws ordinary overlays under its own lock screen; an accessibility service's
         * overlay goes on top. So this is the route that works. False if the service isn't on.
         */
        fun showLock(): Boolean = running?.showOverlay() == true

        /** Lock now. False if the service isn't on (or the phone is too old to allow it). */
        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }
    }
}

/**
 * The pixel lock screen as an accessibility overlay window. Swiping up dissolves it to reveal the
 * phone's own lock screen, where your PIN or fingerprint unlocks as usual; a notification you
 * tapped opens once you're in. It gets out of the way for calls and alarms.
 */
class LockOverlay(private val service: AccessibilityService) : LockHost {
    private val wm = service.getSystemService(android.view.WindowManager::class.java)
    private val audio = service.getSystemService(android.media.AudioManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var frame: android.widget.FrameLayout? = null
    private var screen: LockScreen? = null

    /** What to do once the phone is unlocked (open the notification that was tapped). */
    private var afterUnlock: (() -> Unit)? = null

    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val then = afterUnlock
            afterUnlock = null
            then?.invoke()
        }
    }

    private val playback = object : android.media.AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
            if (configs.any { ringing(it.audioAttributes.usage) }) remove()
        }
    }

    private val modes = if (Build.VERSION.SDK_INT >= 31) android.media.AudioManager.OnModeChangedListener { mode ->
        if (mode != android.media.AudioManager.MODE_NORMAL) main.post { remove() }
    } else null

    init {
        service.registerReceiver(unlocked, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
        audio.registerAudioPlaybackCallback(playback, main)
        if (Build.VERSION.SDK_INT >= 31) modes?.let { audio.addOnModeChangedListener(service.mainExecutor, it) }
    }

    private fun ringing(usage: Int) = usage == android.media.AudioAttributes.USAGE_ALARM ||
        usage == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
        usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION

    /** A call or alarm is going: never cover it. */
    private fun busy(): Boolean = audio.mode != android.media.AudioManager.MODE_NORMAL ||
        audio.activePlaybackConfigurations.any { ringing(it.audioAttributes.usage) }

    fun show() {
        afterUnlock = null
        if (busy()) return
        frame?.let { f ->
            if (screen?.root?.parent === f) {
                screen?.reshow()
                return
            }
            remove()
        }
        Pixel.load(service)
        val f = android.widget.FrameLayout(service)
        frame = f
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.OPAQUE,
        ).apply {
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(f, lp)
        val s = LockScreen(service, this)
        screen = s
        s.create()
        s.start()
    }

    override fun show(view: android.view.View) {
        val f = frame ?: return
        f.removeAllViews()
        f.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    override fun close() = remove()

    /**
     * Straight to the phone's PIN pad (fingerprint works there too), skipping its lock screen:
     * a see-through screen asks Android for it while the pixel one dissolves away. Whatever was
     * tapped opens once unlocked. If Android won't open that screen, its lock screen shows instead.
     */
    override fun dismissKeyguard(done: () -> Unit, failed: () -> Unit) {
        afterUnlock = done
        try {
            service.startActivity(android.content.Intent(service, UnlockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
        }
        screen?.leave()
    }

    fun remove() {
        screen?.let {
            it.stop()
            it.destroy()
        }
        screen = null
        val f = frame ?: return
        frame = null
        try {
            wm.removeView(f)
        } catch (e: Exception) {
        }
    }
}

/**
 * Invisible: asks Android for its PIN pad over the lock screen, then gets out of the way. Backing
 * out of the PIN pad brings the pixel lock screen back.
 */
class UnlockActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done()
            override fun onDismissCancelled() {
                done()
                LockService.showLock()
            }
            override fun onDismissError() = done()
        })
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
