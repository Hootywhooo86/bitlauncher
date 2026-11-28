package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.content.pm.LauncherApps
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.UserManager
import android.view.Gravity
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hidden apps, behind your fingerprint (or PIN). Hidden apps don't show in the drawer or search;
 * this is the one place they live, and it asks who you are before it opens.
 */
object HiddenVault {
    /** Ask for a fingerprint, face or the phone's PIN, then run [then]. */
    fun unlock(activity: Activity, why: String, then: () -> Unit) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            Pixel.say(activity, "Your phone has no screen lock, so hidden apps can't be protected. Set one in your phone's settings.")
            then()
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            // Older phones: the system's own confirm-your-PIN screen.
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("Hidden apps", why)
            if (intent == null) then() else {
                pending = then
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST)
            }
            return
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Hidden apps")
            .setDescription(why)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = then()
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) Pixel.say(activity, message.toString())
            }
        })
    }

    const val REQUEST = 77
    private var pending: (() -> Unit)? = null

    /** For the old confirm-your-PIN path: call from onActivityResult. */
    fun onResult(requestCode: Int, ok: Boolean) {
        if (requestCode != REQUEST) return
        val then = pending
        pending = null
        if (ok) then?.invoke()
    }

    /** Unlock, then show the hidden apps: tap to open, or show one in the drawer again. */
    fun open(activity: Activity) {
        val prefs = Prefs(activity)
        if (prefs.hidden.isEmpty()) {
            Pixel.say(activity, "No hidden apps. In the app drawer, hold an app and choose Hide from drawer.")
            return
        }
        unlock(activity, "Show your hidden apps") { show(activity) }
    }

    private fun show(activity: Activity) {
        val prefs = Prefs(activity)
        Pixel.load(activity)
        val la = activity.getSystemService(LauncherApps::class.java)
        val users = activity.getSystemService(UserManager::class.java)
        val icons = IconCache(activity)
        val dialog = Dialog(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            list.removeAllViews()
            for (id in prefs.hidden.sorted()) {
                val parts = id.split('/')
                if (parts.size < 3) continue
                val key = AppKey(parts[0], parts.subList(1, parts.size - 1).joinToString("/"), parts.last().toLongOrNull() ?: 0L)
                val label = try {
                    activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(key.pkg, 0)).toString()
                } catch (e: Exception) {
                    key.pkg
                }
                list.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val p = Pixel.dpi(context, 10)
                    setPadding(p, p, p, p)
                    addView(ImageView(context).apply {
                        icons.get(key)?.let { setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, it).apply { paint.isFilterBitmap = false }) }
                    }, LinearLayout.LayoutParams(Pixel.dpi(context, 36), Pixel.dpi(context, 36)).apply { rightMargin = Pixel.dpi(context, 12) })
                    addView(TextView(context).apply {
                        text = label; setTextColor(Pixel.INK); textSize = 15f
                        isClickable = true
                        setOnClickListener {
                            try {
                                val user = users.getUserForSerialNumber(key.user) ?: throw IllegalStateException()
                                la.startMainActivity(android.content.ComponentName(key.pkg, key.cls), user, null, null)
                                dialog.dismiss()
                            } catch (e: Exception) {
                                Pixel.say(activity, "$label can't be opened. It may have been uninstalled.")
                            }
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Pixel.label(context, "UNHIDE", 9f, prefs.accent).apply {
                        val q = Pixel.dpi(context, 8)
                        setPadding(q, q, q, q)
                        isClickable = true
                        setOnClickListener {
                            prefs.hidden = prefs.hidden - id
                            prefs.touch()
                            if (prefs.hidden.isEmpty()) dialog.dismiss() else render()
                        }
                    })
                })
            }
        }
        render()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, prefs.accent)
            val p = Pixel.dpi(context, 12)
            setPadding(p, p, p, p)
            addView(Pixel.label(context, "HIDDEN APPS", 11f, prefs.accent), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 8) })
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
    }
}
