package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Everything you can change, on one screen. Built in code: no layout inflation, no libraries. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(body)
            setBackgroundColor(Pixel.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                val p = Pixel.dpi(context, 20)
                body.setPadding(p, top + p, p, bottom + p)
                insets
            }
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // the default-home row changes after the system dialog
    }

    private fun render() {
        body.removeAllViews()
        val accent = prefs.accent

        body.addView(Pixel.label(this, "BITLAUNCHER", 18f, accent))
        body.addView(note("Settings"))

        if (!isDefaultHome()) {
            section("HOME APP")
            body.addView(note("bitlauncher isn't your home app yet. Set it so the Home button comes here."))
            body.addView(Pixel.button(this, "SET AS HOME APP", accent) { askToBeHome() }.full())
        }

        section("GRID")
        row(GridSize.entries.map { g ->
            choice(g.key.replace("x", " x "), prefs.grid == g) {
                changed { prefs.grid = g }
            }
        })
        body.addView(note("Changing size never loses anything. Icons that don't fit move to the next free spot."))

        section("ICON SIZE")
        row(listOf("SMALL", "MEDIUM", "LARGE").mapIndexed { i, label ->
            choice(label, prefs.iconSize == i) { changed { prefs.iconSize = i } }
        })

        section("LABELS")
        row(listOf(
            choice("ON", prefs.labels) { changed { prefs.labels = true } },
            choice("OFF", !prefs.labels) { changed { prefs.labels = false } },
        ))

        section("ACCENT COLOUR")
        body.addView(note("Free, always."))
        row(Pixel.ACCENTS.map { color ->
            View(this).apply {
                background = PanelDrawable(context, color, if (color == accent) Pixel.INK else Pixel.PANEL)
                minimumHeight = Pixel.dpi(context, 40)
                setOnClickListener { changed { prefs.accent = color } }
            }
        })

        section("HOME CLOCK")
        row(listOf(
            choice("ON", prefs.homeClock) { changed { prefs.homeClock = true } },
            choice("OFF", !prefs.homeClock) { changed { prefs.homeClock = false } },
        ))

        section("ANIMATIONS")
        body.addView(note("Smooth motion: things follow your finger and settle gently. Also off whenever your phone's \"remove animations\" is on."))
        row(listOf(
            choice("ON", prefs.animations) { changed { prefs.animations = true } },
            choice("OFF", !prefs.animations) { changed { prefs.animations = false } },
        ))

        section("GESTURES")
        body.addView(note("Swipe up on a folder to open its first app. Swipe up on an app to run a shortcut you pick (hold the app, then Swipe up action)."))
        body.addView(Pixel.label(this, "DOUBLE-TAP TO LOCK", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 8), 0, Pixel.dpi(context, 6)) })
        row(listOf(
            choice("ON", prefs.doubleTapLock) { changed { prefs.doubleTapLock = true } },
            choice("OFF", !prefs.doubleTapLock) { changed { prefs.doubleTapLock = false } },
        ))
        if (prefs.doubleTapLock && !LockService.isEnabled(this)) {
            body.addView(note("One more step: turn on bitlauncher in Accessibility. It's only used to lock the screen, and keeps fingerprint unlock working."))
            body.addView(Pixel.button(this, "OPEN ACCESSIBILITY", accent) {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Pixel.say(this, "Open your phone's Settings, then Accessibility, and turn on bitlauncher.")
                }
            }.full())
        }

        notificationSection(accent)

        section("APP DRAWER")
        row(listOf(
            choice("KEYBOARD ON OPEN", prefs.drawerKeyboard) { changed { prefs.drawerKeyboard = !prefs.drawerKeyboard } },
        ))
        body.addView(note("Search also does sums (12*8), conversions (5 km to mi), phone settings and the web."))
        row(listOf(
            choice("SEARCH CONTACTS", prefs.searchContacts) {
                if (prefs.searchContacts) changed { prefs.searchContacts = false }
                else if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    changed { prefs.searchContacts = true }
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
                }
            },
        ))

        val hidden = prefs.hidden
        if (hidden.isNotEmpty()) {
            section("HIDDEN APPS")
            body.addView(note("Tap one to show it in the drawer again."))
            for (id in hidden.sorted()) {
                val pkg = id.substringBefore('/')
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                body.addView(Pixel.button(this, label, accent) {
                    changed { prefs.hidden = prefs.hidden - id }
                }.full())
            }
        }

        section("BACKUP")
        body.addView(note("Saves your layout and settings to a file you choose. Widgets may need adding again after a restore."))
        row(listOf(
            Pixel.button(this, "BACK UP", accent) { backup() },
            Pixel.button(this, "RESTORE", accent) { restore() },
        ))

        section("ABOUT")
        body.addView(note("bitlauncher ${versionName()}. Part of the 128bit family. No accounts, no ads, nothing leaves your phone."))
    }

    private fun changed(change: () -> Unit) {
        change()
        prefs.touch()
        (application as? BitApp)?.syncLockScreen()
        render()
    }

    private fun notificationSection(accent: Int) {
        val access = NotificationHub.hasAccess(this)
        section("NOTIFICATIONS")
        if (!access) {
            body.addView(note("Dots on icons and notifications on the pixel lock screen need notification access. Notifications stay on your phone; nothing is saved or sent."))
            body.addView(Pixel.button(this, "ALLOW NOTIFICATION ACCESS", accent) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Pixel.say(this, "Open Settings, then Notifications, then Device & app notifications, and allow bitlauncher.")
                }
            }.full())
        }
        body.addView(Pixel.label(this, "DOTS ON ICONS", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 8), 0, Pixel.dpi(context, 6)) })
        row(listOf(
            choice("ON", prefs.dots) { changed { prefs.dots = true } },
            choice("OFF", !prefs.dots) { changed { prefs.dots = false } },
        ))

        section("LOCK SCREEN")
        body.addView(note("A pixel clock and your notifications, shown in front of your phone's lock screen. Swipe up to unlock with your own PIN, pattern or fingerprint. It never replaces them."))
        row(listOf(
            choice("ON", prefs.lockScreen) { changed { prefs.lockScreen = true } },
            choice("OFF", !prefs.lockScreen) { changed { prefs.lockScreen = false } },
        ))
        if (prefs.lockScreen) {
            if (prefs.lockBlocked) {
                body.addView(note("Your phone stopped the pixel lock screen from opening last time. Keeping bitlauncher as your home app usually fixes this; some phones never allow it."))
            }
            body.addView(Pixel.label(this, "SHOW ON LOCK SCREEN", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            row(listOf("APP NAMES", "TITLES", "FULL TEXT").mapIndexed { i, label ->
                choice(label, prefs.lockDetail == i) { changed { prefs.lockDetail = i } }
            })
            if (prefs.lockDetail > 0 && NotificationHub.systemHidesSensitive(this)) {
                body.addView(note("Your phone is set to hide sensitive notification content on the lock screen, so only app names show. Change that in your phone's notification settings."))
            } else {
                body.addView(note("Apps that mark a notification secret never show here, and private ones only show what the app allows."))
            }
            body.addView(Pixel.button(this, "PREVIEW", accent) {
                startActivity(Intent(this, LockActivity::class.java))
            }.full())
        }
    }

    private fun section(title: String) {
        body.addView(Pixel.label(this, title, 11f, Pixel.MUTED).apply {
            setPadding(0, Pixel.dpi(context, 24), 0, Pixel.dpi(context, 8))
        })
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Pixel.MUTED)
        textSize = 13f
        setPadding(0, Pixel.dpi(context, 4), 0, Pixel.dpi(context, 8))
    }

    private fun choice(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        Pixel.label(this, label, 10f, if (selected) Pixel.BG else Pixel.INK).apply {
            gravity = Gravity.CENTER
            val p = Pixel.dpi(context, 12)
            setPadding(p / 2, p, p / 2, p)
            background = PanelDrawable(context, if (selected) prefs.accent else Pixel.PANEL, if (selected) prefs.accent else Pixel.MUTED)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun row(views: List<View>) {
        val row = LinearLayout(this)
        views.forEachIndexed { i, v ->
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = Pixel.dpi(this@SettingsActivity, 6)
            })
        }
        body.addView(row)
    }

    private fun View.full(): View = apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Pixel.dpi(this@SettingsActivity, 6)
        }
    }

    // ---------------------------------------------------------------- default home

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        return current == packageName
    }

    private fun askToBeHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            Pixel.say(this, "Open your phone's Settings, then Apps, then Default apps, and pick bitlauncher.")
        }
    }

    // ---------------------------------------------------------------- backup

    private fun backup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "bitlauncher-backup.json")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_BACKUP)
    }

    private fun restore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_RESTORE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_BACKUP -> try {
                val layout = LayoutStore(this).load(prefs.grid)
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(LayoutStore.backup(layout, prefs).toByteArray()) }
                Pixel.say(this, "Backed up.")
            } catch (e: Exception) {
                Pixel.say(this, "The backup couldn't be written to that file.")
            }
            REQ_RESTORE -> try {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val (layout, settings) = LayoutStore.parseBackup(text)
                val dropped = dropDeadWidgets(layout)
                prefs.grid = layout.grid
                prefs.restore(settings)
                LayoutStore(this).save(layout)
                // The home screen reads the restored layout fresh.
                startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                Pixel.say(this, if (dropped > 0) "Restored. $dropped widget${if (dropped == 1) "" else "s"} need adding again." else "Restored.")
                finish()
            } catch (e: IllegalArgumentException) {
                Pixel.say(this, e.message ?: "That file isn't a bitlauncher backup.")
            } catch (e: Exception) {
                Pixel.say(this, "That backup couldn't be read.")
            }
        }
    }

    /** Widget ids only mean something on the phone that made them. Drop the ones that don't exist here. */
    private fun dropDeadWidgets(layout: HomeLayout): Int {
        val manager = android.appwidget.AppWidgetManager.getInstance(this)
        var dropped = 0
        for (page in layout.pages) {
            val dead = page.filterIsInstance<WidgetItem>().filter { manager.getAppWidgetInfo(it.widgetId) == null }
            dropped += dead.size
            page.removeAll(dead)
        }
        return dropped
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CONTACTS) return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) changed { prefs.searchContacts = true }
        else Pixel.say(this, "Contact search stays off: bitlauncher wasn't allowed to read contacts.")
    }

    companion object {
        private const val REQ_CONTACTS = 24
        private const val REQ_HOME = 21
        private const val REQ_BACKUP = 22
        private const val REQ_RESTORE = 23
    }
}
