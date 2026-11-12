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
    override fun onServiceConnected() {
        running = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = null
        return super.onUnbind(intent)
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

        /** Lock now. False if the service isn't on (or the phone is too old to allow it). */
        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }
    }
}
