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
        Pixel.load(this)
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
        handleSignInReturn(intent)
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

        section("LOCK LAYOUT")
        body.addView(note("Locked, nothing on home can be moved, removed or added. Apps, folders and widgets still work."))
        row(listOf(
            choice("LOCKED", prefs.layoutLocked) { changed { prefs.layoutLocked = true } },
            choice("UNLOCKED", !prefs.layoutLocked) { changed { prefs.layoutLocked = false } },
        ))

        section("ICON LOOK")
        body.addView(note("Reshape every app icon to match. Folders keep their own style."))
        body.addView(iconPreview(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(this@SettingsActivity, 10) })
        for ((key, name) in IconStyles.ALL) {
            body.addView(choice(name.uppercase(), prefs.iconStyle == key) { changed { prefs.iconStyle = key } }.full())
        }
        if (prefs.iconStyle in setOf("block", "coin", "cartridge")) {
            body.addView(Pixel.label(this, if (prefs.iconStyle == "cartridge") "CARTRIDGE COLOUR" else "TILE COLOUR", 9f, Pixel.MUTED).apply {
                setPadding(0, Pixel.dpi(context, 10), 0, Pixel.dpi(context, 6))
            })
            row(listOf(
                choice("EACH APP'S OWN", prefs.iconTile == 0) { changed { prefs.iconTile = 0 } },
                choice(if (prefs.iconTile != 0) "MINE  ${Colors.hex(prefs.iconTile)}" else "PICK ONE", prefs.iconTile != 0) {
                    PixelColorPicker(this, "Tile colour", prefs.iconTile.takeIf { it != 0 } ?: Pixel.PANEL) { c -> changed { prefs.iconTile = c } }.show()
                },
            ))
        }

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
        val ownAccent = accent !in Pixel.ACCENTS
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Pixel.dpi(context, 8), 0, 0)
            if (ownAccent) addView(View(context).apply {
                background = PanelDrawable(context, accent, Pixel.INK)
            }, LinearLayout.LayoutParams(Pixel.dpi(context, 40), Pixel.dpi(context, 40)).apply { rightMargin = Pixel.dpi(context, 8) })
            addView(Pixel.button(context, if (ownAccent) "YOUR COLOUR  ${Colors.hex(accent)}" else "PICK ANY COLOUR", accent) {
                PixelColorPicker(context, "Accent colour", accent) { c -> changed { prefs.accent = c } }.show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))

        section("THEME")
        body.addView(note("Recolours panels and text everywhere. All free."))
        for (t in Pixel.THEMES) {
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = PanelDrawable(context, t.panel, if (prefs.theme == t.key) accent else t.muted)
                val p = Pixel.dpi(context, 12)
                setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
                isClickable = true
                setOnClickListener {
                    if (prefs.theme == t.key) return@setOnClickListener
                    prefs.theme = t.key
                    prefs.touch()
                    Pixel.load(context)
                    recreate()
                }
                // Three swatches: background, text, quiet text.
                for (c in listOf(t.bg, t.ink, t.muted)) addView(View(context).apply {
                    background = PanelDrawable(context, c, Pixel.SHADOW, shadow = false)
                }, LinearLayout.LayoutParams(Pixel.dpi(context, 18), Pixel.dpi(context, 18)).apply { rightMargin = Pixel.dpi(context, 6) })
                addView(Pixel.label(context, t.name.uppercase(), 11f, t.ink).apply {
                    setPadding(Pixel.dpi(context, 8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (prefs.theme == t.key) addView(Pixel.label(context, "ON", 10f, accent))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(this@SettingsActivity, 6) })
        }
        customThemeSection(accent)

        section("SCENE")
        body.addView(note("A pixel landscape behind your home screen. It slides as you swipe and follows the time of day."))
        for ((key, name) in SceneView.SCENES) {
            body.addView(choice(name.uppercase(), prefs.scene == key) { changed { prefs.scene = key } }.full())
        }
        if (prefs.scene != "none") {
            body.addView(note("Time of day. Hold the scene at one time, or let it follow the clock (and the real sunrise and sunset, with weather on)."))
            for ((key, name) in Light.TIMES) {
                body.addView(choice(name.uppercase(), prefs.sceneTime == key) { changed { prefs.sceneTime = key } }.full())
            }
            body.addView(Pixel.label(this, "ON THE PHONE'S LOCK SCREEN TOO", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            row(listOf(
                choice("ON", prefs.lockWallpaper) {
                    changed { prefs.lockWallpaper = true }
                },
                choice("OFF", !prefs.lockWallpaper) {
                    val was = prefs.lockWallpaper
                    changed { prefs.lockWallpaper = false }
                    if (was) LockWallpaper.clear(this)
                },
            ))
            body.addView(note("Sets your phone's own lock screen wallpaper to this scene, kept in step with the time of day and weather. Your home wallpaper isn't touched. Turning it off puts your phone's own wallpaper back on the lock screen."))
        }

        section("WEATHER")
        body.addView(note("Rain, snow and storms in the scene, and the forecast on Today and the lock screen. Sends only a rough location to Open-Meteo (free, no account). Off, nothing goes online."))
        row(listOf(
            choice("ON", prefs.weather) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    changed { prefs.weather = true }
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                }
            },
            choice("OFF", !prefs.weather) { changed { prefs.weather = false } },
        ))
        if (prefs.weather) {
            row(listOf(
                choice("°C", !prefs.fahrenheit) { changed { prefs.fahrenheit = false } },
                choice("°F", prefs.fahrenheit) { changed { prefs.fahrenheit = true } },
            ))
            val client = WeatherClient(this)
            val w = client.cached()
            body.addView(note(when {
                w != null -> "Last reading: ${WeatherCodes.describe(w.code)}, ${WeatherCodes.temp(w.tempC, prefs.fahrenheit)}" +
                    (w.place?.let { " in $it" } ?: "") + ", " + android.text.format.DateUtils.getRelativeTimeSpanString(w.fetchedAt) + "."
                else -> "No reading yet. It arrives the next time you go home."
            }))
        }

        section("ICON PACK")
        val packs = IconPack.installed(this)
        if (packs.isEmpty()) {
            body.addView(note("No icon packs installed. Any pack made for Nova or similar launchers works; its icons get pixelated to match."))
        } else {
            body.addView(note("Icons from the pack, pixelated. Apps it doesn't cover keep their own."))
            val current = prefs.iconPack.takeIf { p -> packs.any { it.first == p } } ?: ""
            body.addView(choice("APPS' OWN ICONS", current.isEmpty()) { changed { prefs.iconPack = "" } }.full())
            for ((pkg, name) in packs) {
                body.addView(choice(name.uppercase(), current == pkg) { changed { prefs.iconPack = pkg } }.full())
            }
        }
        body.addView(note("To change one app's icon or name, hold it on home and pick Edit name & icon."))

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

        shiftSection(accent)

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

        accountSection(accent)

        section("BACKUP")
        body.addView(note("Saves your layout and settings to a file you choose. Widgets may need adding again after a restore."))
        row(listOf(
            Pixel.button(this, "BACK UP", accent) { backup() },
            Pixel.button(this, "RESTORE", accent) { restore() },
        ))

        section("ABOUT")
        body.addView(note("bitlauncher ${versionName()}. Part of the 128bit family. No ads. Nothing goes online unless you turn on weather or sign in to your 128bit account."))
    }

    private fun changed(change: () -> Unit) {
        val look = prefs.accent to prefs.theme
        change()
        prefs.touch()
        LockWallpaper.sync(this)
        (application as? BitApp)?.syncLockScreen()
        if (look != (prefs.accent to prefs.theme)) FamilyTheme.broadcast(this)
        render()
    }

    private fun clockText(minutes: Int): String {
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, minutes / 60); set(java.util.Calendar.MINUTE, minutes % 60) }
        return android.text.format.DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun pickTime(current: Int, set: (Int) -> Unit) {
        android.app.TimePickerDialog(this, { _, h, m -> changed { set(h * 60 + m) } }, current / 60, current % 60,
            android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun shiftSection(accent: Int) {
        section("SHIFT MODE")
        body.addView(note("A second layout for work. Switch by holding empty space on home, on a schedule, or let Railtime switch it when you go on and off duty. Work starts as a copy of Home."))
        body.addView(note(if (prefs.mode == Shift.WORK) "Showing: Work layout." else "Showing: Home layout."))
        body.addView(Pixel.label(this, "SCHEDULE", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 8), 0, Pixel.dpi(context, 6)) })
        row(listOf(
            choice("ON", prefs.shiftSchedule) { changed { prefs.shiftSchedule = true; prefs.modeHeldUntil = 0L } },
            choice("OFF", !prefs.shiftSchedule) { changed { prefs.shiftSchedule = false } },
        ))
        if (!prefs.shiftSchedule) return
        val names = listOf("S", "M", "T", "W", "T", "F", "S")
        row((1..7).map { day ->
            choice(names[day - 1], day in prefs.shiftDays) {
                changed { prefs.shiftDays = if (day in prefs.shiftDays) prefs.shiftDays - day else prefs.shiftDays + day }
            }
        })
        row(listOf(
            Pixel.button(this, "FROM ${clockText(prefs.shiftStart)}", accent) { pickTime(prefs.shiftStart) { prefs.shiftStart = it } },
            Pixel.button(this, "TO ${clockText(prefs.shiftEnd)}", accent) { pickTime(prefs.shiftEnd) { prefs.shiftEnd = it } },
        ))
        body.addView(note("An end earlier than the start is an overnight shift. Switching by hand holds until the schedule next changes."))
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
        body.addView(Pixel.label(this, "SWIPE DOWN ON HOME OPENS", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 14), 0, Pixel.dpi(context, 6)) })
        row(listOf(
            choice("PIXEL LIST", prefs.swipeDown == "pixel") { changed { prefs.swipeDown = "pixel" } },
            choice("PHONE SHADE", prefs.swipeDown == "shade") { changed { prefs.swipeDown = "shade" } },
        ))
        body.addView(note("The pixel list: tap to open, swipe sideways to dismiss. PHONE SHADE at its top still gets you Wi-Fi, the flashlight and other quick settings."))
        body.addView(Pixel.label(this, "PIXEL POP-UPS", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 14), 0, Pixel.dpi(context, 6)) })
        for ((key, name) in NoteCards.POPUPS) {
            body.addView(choice(name.uppercase(), prefs.popups == key) { changed { prefs.popups = key } }.full())
        }
        if (prefs.popups == "everywhere" && !Popups.canDrawEverywhere(this)) {
            body.addView(note("Pop-ups over other apps need Android's \"Display over other apps\" for bitlauncher. Until then they only show on home."))
            body.addView(Pixel.button(this, "ALLOW DISPLAY OVER APPS", accent) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Pixel.say(this, "Open Settings, then Apps, then bitlauncher, and allow Display over other apps.")
                }
            }.full())
        }
        if (prefs.popups != "off") body.addView(note("Pop-ups show for new, important notifications, never while the phone is locked or on Do Not Disturb. Android's own pop-up still shows too, unless you turn off \"Pop on screen\" for an app in its notification settings."))

        section("LOCK SCREEN")
        body.addView(note("A pixel clock and your notifications, shown in front of your phone's lock screen. Swipe up to unlock with your own PIN, pattern or fingerprint. It never replaces them."))
        row(listOf(
            choice("ON", prefs.lockScreen) { changed { prefs.lockScreen = true } },
            choice("OFF", !prefs.lockScreen) { changed { prefs.lockScreen = false } },
        ))
        if (prefs.lockScreen) {
            if (!LockService.isEnabled(this)) {
                body.addView(note("One more step, or it won't show: Android only lets bitlauncher open its lock screen when the screen turns off if bitlauncher is on in Accessibility. It reads nothing on screen; the same switch powers double-tap to lock."))
                body.addView(note("If Android says the setting is restricted: open App info for bitlauncher, tap the ⋮ menu, choose \"Allow restricted settings\", then try again."))
                body.addView(Pixel.button(this, "OPEN ACCESSIBILITY", accent) {
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        Pixel.say(this, "Open your phone's Settings, then Accessibility, and turn on bitlauncher.")
                    }
                }.full())
                body.addView(Pixel.button(this, "OPEN APP INFO", accent) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        Pixel.say(this, "Open Settings, then Apps, then bitlauncher.")
                    }
                }.full())
            } else if (prefs.lockBlocked) {
                body.addView(note("Your phone stopped the pixel lock screen from opening last time. Turning bitlauncher off and on again in Accessibility usually fixes this."))
            }
            body.addView(Pixel.label(this, "LOOK", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            for (look in LockLooks.ALL) {
                body.addView(choice(look.name.uppercase(), prefs.lockStyle == look.key) { changed { prefs.lockStyle = look.key } }.full())
            }
            body.addView(note((LockLooks.ALL.firstOrNull { it.key == prefs.lockStyle } ?: LockLooks.ALL[0]).about + " PREVIEW below shows it."))
            body.addView(Pixel.label(this, "LOCK SCREEN COLOUR", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            row(listOf(
                choice("SAME AS HOME", prefs.lockAccent == 0) { changed { prefs.lockAccent = 0 } },
                choice(if (prefs.lockAccent != 0) "MINE  ${Colors.hex(prefs.lockAccent)}" else "PICK ONE", prefs.lockAccent != 0) {
                    PixelColorPicker(this, "Lock screen colour", prefs.lockAccentOrHome) { c -> changed { prefs.lockAccent = c } }.show()
                },
            ))
            body.addView(Pixel.label(this, "CLOCK FONT", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            for ((key, name) in ClockFonts.ALL) {
                body.addView(choice(name.uppercase(), prefs.clockFont == key) { changed { prefs.clockFont = key } }.full())
            }
            body.addView(note("Used by the home screen clock too. Hold the camera or flashlight in the lock screen's bottom corners to use them. Your next alarm (within 12 hours) and next shift show on their own."))
            body.addView(Pixel.label(this, "SHOW ON LOCK SCREEN", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            row(listOf("APP NAMES", "TITLES", "FULL TEXT").mapIndexed { i, label ->
                choice(label, prefs.lockDetail == i) { changed { prefs.lockDetail = i } }
            })
            if (prefs.lockDetail > 0 && NotificationHub.systemHidesSensitive(this)) {
                body.addView(note("Your phone is set to hide sensitive notification content on the lock screen, so only app names show. Change that in your phone's notification settings."))
            } else {
                body.addView(note("Apps that mark a notification secret never show here, and private ones only show what the app allows."))
            }
            body.addView(Pixel.label(this, "CHARGING ANIMATION", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 6)) })
            body.addView(choice("OFF", prefs.chargeStyle == "off") { changed { prefs.chargeStyle = "off" } }.full())
            for ((key, name) in ChargeView.STYLES) {
                body.addView(choice(name.uppercase(), prefs.chargeStyle == key) { changed { prefs.chargeStyle = key } }.full())
            }
            body.addView(Pixel.button(this, "PREVIEW", accent) {
                startActivity(Intent(this, LockActivity::class.java))
            }.full())
        }
    }

    /** Your own theme: tap a colour to change it, then USE MY THEME. */
    private fun customThemeSection(accent: Int) {
        val mine = Pixel.custom(prefs)
        val using = prefs.theme == "custom"
        body.addView(Pixel.label(this, "MY THEME", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 10), 0, Pixel.dpi(context, 6)) })
        val roles = listOf(Triple("bg", "BACKGROUND", mine.bg), Triple("panel", "PANELS", mine.panel), Triple("ink", "TEXT", mine.ink), Triple("muted", "QUIET TEXT", mine.muted))
        for ((role, label, c) in roles) body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, Pixel.MUTED)
            val p = Pixel.dpi(context, 10)
            setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
            isClickable = true
            setOnClickListener {
                PixelColorPicker(context, label.lowercase().replaceFirstChar { it.uppercase() }, c) { picked ->
                    // Keep the other three as they are now, so a first edit starts from the default.
                    for ((r, _, cur) in roles) if (r != role) prefs.setCustomColor(r, cur)
                    prefs.setCustomColor(role, picked)
                    prefs.touch()
                    if (prefs.theme == "custom") {
                        Pixel.load(context)
                        FamilyTheme.broadcast(context)
                        recreate()
                    } else render()
                }.show()
            }
            addView(View(context).apply { background = PanelDrawable(context, c, Pixel.SHADOW, shadow = false) },
                LinearLayout.LayoutParams(Pixel.dpi(context, 24), Pixel.dpi(context, 24)).apply { rightMargin = Pixel.dpi(context, 10) })
            addView(Pixel.label(context, label, 10f, Pixel.INK), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Pixel.label(context, Colors.hex(c), 9f, Pixel.MUTED))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(this@SettingsActivity, 6) })
        // Say so plainly if text would be hard to read.
        if (Colors.contrast(mine.ink, mine.bg) < 4.5 || Colors.contrast(mine.ink, mine.panel) < 4.5) {
            body.addView(note("Text and background are close in brightness, so words may be hard to read. Make one lighter or darker."))
        }
        body.addView(Pixel.button(this, if (using) "USING MY THEME" else "USE MY THEME", accent) {
            if (prefs.theme == "custom") return@button
            prefs.theme = "custom"
            prefs.touch()
            Pixel.load(this)
            FamilyTheme.broadcast(this)
            recreate()
        }.full())
    }

    /** Five of your own apps in the chosen look. */
    private fun iconPreview(): View {
        IconStyles.current = prefs.iconStyle
        IconStyles.accent = prefs.accent
        IconStyles.tile = prefs.iconTile
        val icons = IconCache(this)
        val users = getSystemService(android.os.UserManager::class.java)
        val me = android.os.Process.myUserHandle()
        val sprites = try {
            getSystemService(android.content.pm.LauncherApps::class.java).getActivityList(null, me)
                .asSequence()
                .mapNotNull { icons.get(AppKey(it.componentName.packageName, it.componentName.className, users.getSerialNumberForUser(me))) }
                .take(5).toList()
        } catch (e: Exception) {
            emptyList()
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            if (sprites.isEmpty()) addView(note("Your app icons show here once home has loaded them."))
            for (s in sprites) addView(AppIconView(context).apply {
                sprite = s
                showLabel = false
            }, LinearLayout.LayoutParams(0, Pixel.dpi(context, 64), 1f))
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
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(LayoutStore.backup(this, prefs).toByteArray()) }
                Pixel.say(this, "Backed up.")
            } catch (e: Exception) {
                Pixel.say(this, "The backup couldn't be written to that file.")
            }
            REQ_RESTORE -> try {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                restored(LayoutStore.restore(this, prefs, text))
            } catch (e: IllegalArgumentException) {
                Pixel.say(this, e.message ?: "That file isn't a bitlauncher backup.")
            } catch (e: Exception) {
                Pixel.say(this, "That backup couldn't be read.")
            }
        }
    }

    /** After a restore: home starts fresh from the restored layout. */
    private fun restored(dropped: Int) {
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        Pixel.say(this, if (dropped > 0) "Restored. $dropped widget${if (dropped == 1) "" else "s"} need adding again." else "Restored.")
        finish()
    }

    // ---------------------------------------------------------------- 128bit account

    private val account by lazy { FamilyAccount(this) }

    private fun accountSection(accent: Int) {
        section("128BIT ACCOUNT")
        if (!account.signedIn) {
            body.addView(note("The same account as 128bitFIT, Play and Tracker. Signed in, bitlauncher backs up your layouts and settings, and Today shows your quests and 128bit activity."))
            val email = field("EMAIL", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, accent)
            val password = field("PASSWORD", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, accent)
            body.addView(email.full())
            body.addView(password.full())
            row(listOf(
                Pixel.button(this, "SIGN IN", accent) {
                    if (email.text.isBlank() || password.text.isEmpty()) {
                        Pixel.say(this, "Type your email and password first.")
                        return@button
                    }
                    account.signIn(email.text.toString(), password.text.toString()) { r -> afterSignIn(r) }
                },
                Pixel.button(this, "GOOGLE", accent) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(account.googleUrl())))
                    } catch (e: Exception) {
                        Pixel.say(this, "No browser found for Google sign-in.")
                    }
                },
            ))
            return
        }
        body.addView(note("Signed in as ${account.email}." + if (account.lastBackup > 0)
            " Last backup ${android.text.format.DateUtils.getRelativeTimeSpanString(account.lastBackup)}." else " Not backed up yet."))
        row(listOf(
            Pixel.button(this, "BACK UP NOW", accent) {
                account.backUp(LayoutStore.backupJson(this, prefs)) { r ->
                    Pixel.say(this, r.exceptionOrNull()?.message ?: "Backed up to your 128bit account.")
                    if (r.isSuccess) account.backupProblem = null
                    render()
                }
            },
            Pixel.button(this, "RESTORE", accent) { restoreFromAccount() },
        ))
        body.addView(Pixel.label(this, "AUTO BACKUP", 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 10), 0, Pixel.dpi(context, 6)) })
        row(listOf(
            choice("ON", prefs.autoBackup) { changed { prefs.autoBackup = true } },
            choice("OFF", !prefs.autoBackup) { changed { prefs.autoBackup = false } },
        ))
        body.addView(note("Backs up after you change something, at most every ten minutes, when you leave home."))
        account.backupProblem?.let { body.addView(note("The last automatic backup didn't work: $it")) }
        body.addView(Pixel.button(this, "SIGN OUT", accent) {
            account.signOut()
            render()
        }.full())
    }

    private fun field(hint: String, type: Int, accent: Int) = NoDropEditText(this).apply {
        this.hint = hint
        typeface = Pixel.font(context)
        textSize = 12f
        setTextColor(Pixel.INK)
        setHintTextColor(Pixel.MUTED)
        inputType = type
        isSingleLine = true
        background = PanelDrawable(context, Pixel.BG, Pixel.MUTED)
        val p = Pixel.dpi(context, 12)
        setPadding(p, p, p, p)
    }

    private fun afterSignIn(r: Result<Unit>) {
        if (r.isFailure) {
            Pixel.say(this, r.exceptionOrNull()?.message ?: "Couldn't sign in.")
            return
        }
        Pixel.say(this, "Signed in.")
        render()
        // This phone has never backed up (a new phone, say): offer what's already in the account.
        // It asks before replacing anything.
        if (account.lastBackup == 0L) account.fetchBackup { b -> if (b.getOrNull() != null) restoreFromAccount() }
    }

    private fun restoreFromAccount() {
        account.fetchBackup { r ->
            val backup = r.getOrElse {
                Pixel.say(this, it.message ?: "Couldn't reach your 128bit account.")
                return@fetchBackup
            }
            if (backup == null) {
                Pixel.say(this, "There's no backup in your account yet.")
                return@fetchBackup
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Restore from your account?")
                .setMessage("Your home screen, Work layout and settings will be replaced with the backup.")
                .setPositiveButton("Restore") { _, _ ->
                    try {
                        restored(LayoutStore.restore(this, prefs, backup.toString()))
                    } catch (e: Exception) {
                        Pixel.say(this, "That backup couldn't be read.")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Google sign-in comes back here (bitlauncher://family-auth?code=...). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSignInReturn(intent)
    }

    private fun handleSignInReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "bitlauncher") return
        val code = data.getQueryParameter("code")
        if (code == null) {
            Pixel.say(this, data.getQueryParameter("error_description") ?: "Google sign-in was cancelled.")
            return
        }
        account.finishGoogle(code) { r -> afterSignIn(r) }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) changed { prefs.weather = true }
            else Pixel.say(this, "Weather stays off: bitlauncher wasn't allowed a rough location.")
            return
        }
        if (requestCode != REQ_CONTACTS) return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) changed { prefs.searchContacts = true }
        else Pixel.say(this, "Contact search stays off: bitlauncher wasn't allowed to read contacts.")
    }

    companion object {
        private const val REQ_CONTACTS = 24
        private const val REQ_LOCATION = 25
        private const val REQ_HOME = 21
        private const val REQ_BACKUP = 22
        private const val REQ_RESTORE = 23
    }
}
