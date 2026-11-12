package com.hootywhooo86.bitlauncher

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * Starts the pixel lock screen when the screen goes off, so it's waiting when the screen comes
 * back on. Lives with the launcher's process; registered only while the setting is on.
 */
class BitApp : Application() {
    private var registered = false
    private var requestedAt = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> showLock()
                Intent.ACTION_SCREEN_ON -> checkItOpened()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Pixel.load(this)
        syncLockScreen()
    }

    /** Call after the setting changes. */
    fun syncLockScreen() {
        val want = Prefs(this).lockScreen
        if (want && !registered) {
            registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            registered = true
        } else if (!want && registered) {
            unregisterReceiver(screen)
            registered = false
        }
    }

    private fun showLock() {
        // The screen also goes off against your ear during a call. Never cover the call.
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return
        LockActivity.shownAt = 0L
        requestedAt = SystemClock.elapsedRealtime()
        val open = Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The accessibility service is allowed to open it from the background; the app alone
        // usually isn't (Android quietly drops the request), so try that only as a fallback.
        if (LockService.showLock(open)) return
        try {
            startActivity(open)
        } catch (e: Exception) {
            Prefs(this).lockBlocked = true
        }
    }

    /** Android can quietly refuse to open it. If that happened, remember, so Settings can say so. */
    private fun checkItOpened() {
        if (requestedAt == 0L) return
        val prefs = Prefs(this)
        val opened = LockActivity.shownAt >= requestedAt
        if (prefs.lockBlocked == opened) prefs.lockBlocked = !opened
        requestedAt = 0L
    }
}

/**
 * The pixel lock screen: block clock, date, and notifications as the privacy setting allows.
 * Swipe up to unlock; that hands over to the phone's own PIN, pattern or fingerprint.
 * It sits in front of the system lock screen and never replaces it.
 */
class LockActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var icons: IconCache
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private lateinit var chevron: PixelChevron
    private lateinit var clock: PixelClockView
    private lateinit var date: TextView
    private lateinit var info: TextView
    private lateinit var mediaBox: LinearLayout
    private lateinit var chargeBox: LinearLayout
    private lateinit var charge: ChargeView
    private lateinit var chargeText: TextView

    /** Plugged in or not, and how full: the charging animation follows it live. */
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showCharge(intent)
    }
    private lateinit var media: MediaSource
    private var listW = 0
    private var leaving = false
    private var block = 1
    private val onNotes = { renderNotes(animate = true) }

    /** Unlocked some other way (fingerprint sensor, face, smart lock): get out of the way. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = leave()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        icons = IconCache(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        block = Pixel.dpi(this, 4)
        media = MediaSource(this) { renderMedia() }
        setContentView(build())
        shownAt = SystemClock.elapsedRealtime()
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onDestroy() {
        super.onDestroy()
        float?.cancel()
        unregisterReceiver(unlocked)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shownAt = SystemClock.elapsedRealtime()
        content.translationY = 0f
        content.alpha = 1f
        renderNotes(animate = false)
    }

    override fun onStart() {
        super.onStart()
        NotificationHub.listen(onNotes)
        date.text = DateFormat.getDateInstance(DateFormat.FULL).format(Date()).uppercase()
        clock.update(animate = false)
        renderInfo()
        media.start()
        // The battery broadcast is sticky: registering hands back the current state at once.
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::showCharge)
        renderNotes(animate = true)
    }

    override fun onStop() {
        super.onStop()
        NotificationHub.unlisten(onNotes)
        media.stop()
        unregisterReceiver(battery)
        charge.charging = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back does nothing here: the way out is the unlock swipe.
    }

    private val look get() = prefs.lockStyle

    private fun build(): View {
        val accent = prefs.accent
        val look = look
        val root = SwipeUpFrame(this) { unlock(null) }
        root.setBackgroundColor(Pixel.BG)
        // The home scene behind the lock screen too, dimmed so text stays readable.
        if (prefs.scene != "none") {
            val cached = if (prefs.weather) WeatherClient(this).cached() else null
            root.addView(SceneView(this).apply {
                scene = prefs.scene
                this.accent = prefs.accent
                timeOfDay = prefs.sceneTime
                sunrise = cached?.sunrise
                sunset = cached?.sunset
                sky = cached?.sky
            }, FrameLayout.LayoutParams(-1, -1))
            root.addView(View(this).apply { setBackgroundColor(withAlpha(Pixel.BG, 130)) }, FrameLayout.LayoutParams(-1, -1))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.content = content

        clock = PixelClockView(this, blink = true).apply { this.accent = accent }
        // On a big screen (an unfolded Fold) the clock and cards keep a readable size.
        val screenW = resources.displayMetrics.widthPixels
        listW = minOf(screenW - Pixel.dpi(this, 40), Pixel.dpi(this, 520))
        var clockW = minOf(screenW - Pixel.dpi(this, 48), Pixel.dpi(this, 400))
        var pocket: PocketDrawable? = null
        when (look) {
            "minimal" -> clockW = minOf(screenW - Pixel.dpi(this, 24), Pixel.dpi(this, 560))
            "pocket" -> {
                // The whole lock screen on a little console's LCD.
                pocket = PocketDrawable(this, accent)
                val bz = pocket.bezel
                content.background = pocket
                content.foreground = android.graphics.drawable.InsetDrawable(TextureDrawable(this, grid = true), bz - Pixel.dpi(this, 4), bz, bz, bz)
                content.setPadding(bz + Pixel.dpi(this, 8), bz + Pixel.dpi(this, 14), bz + Pixel.dpi(this, 12), bz + Pixel.dpi(this, 14))
                listW -= 2 * bz + Pixel.dpi(this, 20)
                clockW = minOf(clockW, listW - Pixel.dpi(this, 16))
            }
            "arcade" -> content.addView(Pixel.label(this, "PLAYER 1", 12f, accent).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, Pixel.dpi(context, 16))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(clock, LinearLayout.LayoutParams(clockW, -2))
        date = Pixel.label(this, "", 11f, Pixel.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Pixel.dpi(context, 14), 0, Pixel.dpi(context, 6))
        }
        info = Pixel.label(this, "", 10f, Pixel.INK).apply {
            gravity = Gravity.CENTER
            setLineSpacing(Pixel.dp(context, 6f), 1f)
            setPadding(0, Pixel.dpi(context, 6), 0, Pixel.dpi(context, 22))
        }
        if (look == "rpg") {
            // Date and weather in a status window.
            val status = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = RpgBoxDrawable(context, withAlpha(Pixel.PANEL, 240), Pixel.INK, withAlpha(Pixel.MUTED, 160))
                val p = Pixel.dpi(context, 16)
                setPadding(p, Pixel.dpi(context, 6), p + Pixel.dpi(context, 4), Pixel.dpi(context, 10))
            }
            date.setTextColor(Pixel.INK)
            info.setPadding(0, Pixel.dpi(this, 2), 0, Pixel.dpi(this, 8))
            status.addView(date, LinearLayout.LayoutParams(-1, -2))
            status.addView(info, LinearLayout.LayoutParams(-1, -2))
            content.addView(status, LinearLayout.LayoutParams(listW, -2).apply {
                topMargin = Pixel.dpi(this@LockActivity, 18)
                bottomMargin = Pixel.dpi(this@LockActivity, 18)
            })
        } else {
            content.addView(date, LinearLayout.LayoutParams(-1, -2))
            content.addView(info, LinearLayout.LayoutParams(-1, -2))
        }
        charge = ChargeView(this).also { it.accent = accent; it.style = prefs.chargeStyle }
        chargeText = Pixel.label(this, "", 10f, Pixel.INK).apply { gravity = Gravity.CENTER }
        chargeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(charge, LinearLayout.LayoutParams(-1, -2))
            addView(chargeText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Pixel.dpi(context, 8) })
        }
        content.addView(chargeBox, LinearLayout.LayoutParams(minOf(listW, Pixel.dpi(this, 360)), -2).apply { bottomMargin = Pixel.dpi(this@LockActivity, 22) })
        mediaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(mediaBox, LinearLayout.LayoutParams(listW, -2))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(listW, -2))
        root.addView(content, if (pocket != null) FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            else FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        hint = Pixel.label(this, LockLooks.hint(look), 10f, Pixel.INK).apply { gravity = Gravity.CENTER }
        chevron = PixelChevron(this)
        val hintBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false // the chevron hops above its own box
            clipToPadding = false
            setPadding(0, block * 2, 0, 0)
            addView(chevron, LinearLayout.LayoutParams(-1, block * 4).apply { bottomMargin = block * 2 })
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(hintBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        bob()
        if (look == "arcade") {
            // CRT scanlines over everything, and the prompt fades like an attract screen.
            root.addView(ScreenTexture(this, grid = false), FrameLayout.LayoutParams(-1, -1))
            if (Pixel.motion(this)) android.animation.ObjectAnimator.ofFloat(hint, View.ALPHA, 1f, 0.15f).apply {
                duration = 700
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                b.top to b.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            (content.layoutParams as FrameLayout.LayoutParams).topMargin = top + Pixel.dpi(this, 48)
            ((hint.parent as View).layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + Pixel.dpi(this, 24)
            content.requestLayout()
            insets
        }
        return root
    }

    private var float: android.animation.ValueAnimator? = null

    /** The chevron floats gently up and down, like it's breathing. */
    private fun bob() {
        if (!Pixel.motion(this)) return
        float = android.animation.ValueAnimator.ofFloat(0f, -block * 1.5f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { chevron.translationY = it.animatedValue as Float }
            start()
        }
    }

    private fun renderNotes(animate: Boolean) {
        list.removeAllViews()
        if (!NotificationHub.hasAccess(this)) return // the lock screen still shows the clock
        val lines = NotePrivacy.lockLines(NotificationHub.snapshot(), prefs.lockDetail, NotificationHub.systemHidesSensitive(this))
        val accent = prefs.accent
        if (look == "minimal") {
            LockLooks.summary(lines) { NoteCards.appName(this, it) }?.let {
                list.addView(Pixel.label(this, it, 10f, Pixel.INK).apply { gravity = Gravity.CENTER })
            }
            return
        }
        if (look == "arcade" && lines.isNotEmpty()) list.addView(Pixel.label(this, "HIGH SCORES", 11f, accent).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Pixel.dpi(context, 12))
        })
        val max = when (look) {
            "rpg" -> 3
            "pocket" -> 4
            else -> 5
        }
        lines.take(max).forEachIndexed { i, line -> list.addView(row(line, accent, animate, i)) }
        if (lines.size > max) {
            list.addView(Pixel.label(this, "+${lines.size - max} MORE APPS", 9f, Pixel.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, Pixel.dpi(context, 8), 0, 0)
            })
        }
    }

    private fun row(line: LockLine, accent: Int, animate: Boolean, index: Int): View {
        val appName = NoteCards.appName(this, line.pkg)
        val row = when (look) {
            "rpg" -> rpgRow(line, appName, accent)
            "arcade" -> scoreRow(line, appName, accent, index)
            "pocket" -> lcdRow(line, appName, accent)
            else -> cardRow(line, appName, accent)
        }
        if (animate && Pixel.motion(this)) {
            // Rows slide up into place one after another.
            row.translationY = (block * 5).toFloat()
            row.alpha = 0f
            row.animate().translationY(0f).alpha(1f).setStartDelay(index * 45L).setDuration(340)
                .setInterpolator(Ease.enter).start()
        }
        return row
    }

    private fun spriteView(line: LockLine, sizeDp: Int): ImageView = ImageView(this).apply {
        icons.getForPackage(line.pkg, line.user)?.let {
            setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, it).apply { paint.isFilterBitmap = false })
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Pixel.dpi(context, sizeDp), Pixel.dpi(context, sizeDp))
    }

    private fun plain(text: String, color: Int, size: Float, lines: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** A dialog box: the app's name on a tab over the top edge, its words inside, a breathing ▼. */
    private fun rpgRow(line: LockLine, appName: String, accent: Int): View {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 12) }
            isClickable = true
            setOnClickListener { unlock(line) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RpgBoxDrawable(context, withAlpha(Pixel.PANEL, 240), Pixel.INK, withAlpha(Pixel.MUTED, 160))
            val p = Pixel.dpi(context, 18)
            setPadding(p, Pixel.dpi(context, 20), p + Pixel.dpi(context, 4), Pixel.dpi(context, 16))
            minimumHeight = Pixel.dpi(context, 72)
        }
        val said = listOfNotNull(line.title, line.text).joinToString(": ").ifBlank { if (line.count > 1) "${line.count} new notifications." else "A new notification." }
        box.addView(plain(said, Pixel.INK, 14f, 3), LinearLayout.LayoutParams(-1, -2).apply { rightMargin = Pixel.dpi(this@LockActivity, 8) })
        frame.addView(box, FrameLayout.LayoutParams(-1, -2).apply { topMargin = Pixel.dpi(this@LockActivity, 10) })
        val tab = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, accent, shadow = false)
            val p = Pixel.dpi(context, 6)
            setPadding(p, p, p + Pixel.dpi(context, 2), p)
            addView(spriteView(line, 16).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = Pixel.dpi(context, 6) })
            addView(Pixel.label(context, if (line.count > 1) "${appName.uppercase()} x${line.count}" else appName.uppercase(), 9f, accent))
        }
        frame.addView(tab, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Pixel.dpi(this@LockActivity, 14) })
        frame.addView(MoreArrow(this, Pixel.INK), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Pixel.dpi(this@LockActivity, 18)
            bottomMargin = Pixel.dpi(this@LockActivity, 12)
        })
        return frame
    }

    /** One line of the high-score table: place, app, count, and a name if privacy allows. */
    private fun scoreRow(line: LockLine, appName: String, accent: Int, index: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        val p = Pixel.dpi(context, 6)
        setPadding(p, p, p, p)
        isClickable = true
        setOnClickListener { unlock(line) }
        addView(Pixel.label(context, LockLooks.place(index), 10f, if (index == 0) accent else Pixel.MUTED), LinearLayout.LayoutParams(Pixel.dpi(context, 44), -2))
        addView(spriteView(line, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = Pixel.dpi(context, 8) })
        addView(Pixel.label(context, appName.uppercase().take(12), 10f, Pixel.INK), LinearLayout.LayoutParams(0, -2, 1f))
        line.title?.let { addView(Pixel.label(context, it.uppercase().take(10), 9f, Pixel.MUTED).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Pixel.dpi(context, 10) }) }
        addView(Pixel.label(context, "x${line.count.toString().padStart(2, '0')}", 10f, accent))
    }

    /** A line on the little LCD: sprite, app, and words, no cards. */
    private fun lcdRow(line: LockLine, appName: String, accent: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 10) }
        isClickable = true
        setOnClickListener { unlock(line) }
        addView(spriteView(line, 24).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = Pixel.dpi(context, 10) })
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Pixel.label(context, if (line.count > 1) "${appName.uppercase()} x${line.count}" else appName.uppercase(), 9f, accent))
        listOfNotNull(line.title, line.text).joinToString(": ").takeIf { it.isNotBlank() }?.let {
            words.addView(plain(it, Pixel.INK, 13f, 2).apply { setPadding(0, Pixel.dpi(context, 3), 0, 0) })
        }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun cardRow(line: LockLine, appName: String, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = PanelDrawable(context, withAlpha(Pixel.PANEL, 235), withAlpha(Pixel.MUTED, 140))
            val p = Pixel.dpi(context, 10)
            setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 8) }
            isClickable = true
            setOnClickListener { unlock(line) }
        }
        val sprite = icons.getForPackage(line.pkg, line.user)
        row.addView(ImageView(this).apply {
            if (sprite != null) setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, sprite).apply { paint.isFilterBitmap = false })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(Pixel.dpi(this, 32), Pixel.dpi(this, 32)).apply { rightMargin = Pixel.dpi(this@LockActivity, 12) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = if (line.count > 1) "${appName.uppercase()}  x${line.count}" else appName.uppercase()
        words.addView(Pixel.label(this, head, 10f, accent))
        line.title?.let {
            words.addView(TextView(this).apply {
                text = it
                setTextColor(Pixel.INK)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, Pixel.dpi(context, 4), 0, 0)
            })
        }
        line.text?.let {
            words.addView(TextView(this).apply {
                text = it
                setTextColor(Pixel.MUTED)
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** Hand over to the phone's own unlock. Only after it succeeds do we open anything. */
    /** Weather and the next event, under the date. The event's name follows the privacy setting. */
    private fun renderInfo() {
        val lines = mutableListOf<String>()
        if (prefs.weather) WeatherClient(this).cached()?.let { w ->
            val f = prefs.fahrenheit
            lines += "${WeatherCodes.temp(w.tempC, f)} ${WeatherCodes.describe(w.code).uppercase()}  ·  H ${WeatherCodes.temp(w.highC, f)} L ${WeatherCodes.temp(w.lowC, f)}"
        }
        CalendarSource.upcoming(this, 1).firstOrNull()?.let { e ->
            val showTitle = prefs.lockDetail > 0 && !NotificationHub.systemHidesSensitive(this)
            val time = CalendarSource.whenText(this, e).uppercase()
            lines += if (showTitle) "NEXT  $time  ${e.title.uppercase()}" else "NEXT EVENT  $time"
        }
        info.text = lines.joinToString("\n")
        info.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showCharge(intent: Intent) {
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val style = prefs.chargeStyle
        if (!plugged || level < 0 || style == "off") {
            chargeBox.visibility = View.GONE
            charge.charging = false
            return
        }
        val pct = level * 100 / scale
        val full = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) == android.os.BatteryManager.BATTERY_STATUS_FULL || pct >= 100
        charge.style = style
        charge.level = pct
        charge.charging = !full
        val estimate = if (!full && android.os.Build.VERSION.SDK_INT >= 28)
            fullIn(getSystemService(android.os.BatteryManager::class.java).computeChargeTimeRemaining()) else null
        chargeText.text = (if (full) "CHARGED  $pct%" else "CHARGING  $pct%") + (estimate?.let { "  ·  ${it.uppercase()}" } ?: "")
        chargeBox.visibility = View.VISIBLE
    }

    private fun renderMedia() {
        mediaBox.removeAllViews()
        media.current()?.let { mediaBox.addView(TodayCards.media(this, prefs.accent, it)) }
    }

    /** Unlocked: break apart block by block to reveal home, then go. */
    private fun leave() {
        if (leaving) return
        leaving = true
        val root = window.decorView
        if (!Pixel.motion(this) || root.width == 0) {
            finish()
            overridePendingTransitionCompat()
            return
        }
        val shot = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(shot))
        val dissolve = DissolveView(this, shot, Pixel.dpi(this, 18)) {
            finish()
            overridePendingTransitionCompat()
        }
        setContentView(dissolve)
        dissolve.start()
    }

    private fun unlock(line: LockLine?) {
        val then = {
            line?.intent?.let {
                try {
                    it.send()
                } catch (e: Exception) {
                    Pixel.say(this, "That notification can't be opened any more.")
                }
            }
            leave()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            then()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
            override fun onDismissCancelled() = resetSwipe()
            override fun onDismissError() = resetSwipe()
        })
    }

    private fun resetSwipe() {
        content.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(Ease.move).start()
    }

    private fun overridePendingTransitionCompat() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** When the lock screen last appeared; the app checks this to know if Android blocked it. */
        @Volatile
        var shownAt = 0L
    }
}

/**
 * Follows your finger up exactly, fading as it goes. Let go past a quarter of the screen, or
 * flick up, and it carries on up and unlocks; otherwise it springs back down.
 * Taps still reach the notification rows.
 */
private class SwipeUpFrame(context: Context, private val onUnlock: () -> Unit) : FrameLayout(context) {
    var content: View? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fling = Pixel.dp(context, 500f)
    private var downY = 0f
    private var velocity: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.y
            MotionEvent.ACTION_MOVE -> if (downY - e.y > slop) {
                downY = e.y // no jump by the slop
                content?.animate()?.cancel()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = android.view.VelocityTracker.obtain()
        velocity?.addMovement(e)
        val c = content ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.y
            MotionEvent.ACTION_MOVE -> {
                val up = (downY - e.y).coerceAtLeast(0f)
                c.translationY = -up
                c.alpha = 1f - (up / (height / 2f)).coerceIn(0f, 0.8f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                val up = downY - e.y
                val unlock = e.actionMasked == MotionEvent.ACTION_UP && (vy < -fling || (vy < fling && up > height / 4f))
                if (unlock) {
                    // Carry on in the direction the finger was going, then hand over.
                    val rest = height / 2f + c.translationY
                    c.animate().translationY(-height / 2f).alpha(0f)
                        .setDuration(Ease.finishDuration(rest.coerceAtLeast(0f), height / 2f, -vy))
                        .setInterpolator(Ease.move).withEndAction(onUnlock).start()
                } else {
                    c.animate().translationY(0f).alpha(1f).setDuration(280)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
                }
            }
        }
        return true
    }
}

/**
 * The unlock: a snapshot of the lock screen that breaks into blocks, which drop out in a
 * random order over most of a second. Behind it (the window is see-through) is home.
 */
internal class DissolveView(context: Context, private val shot: android.graphics.Bitmap, private val size: Int, private val done: () -> Unit) : View(context) {
    private val cols = (shot.width + size - 1) / size
    private val rows = (shot.height + size - 1) / size
    private val order = (0 until cols * rows).shuffled()
    private var gone = 0
    private val src = android.graphics.Rect()
    private val dst = android.graphics.Rect()
    private val paint = android.graphics.Paint().apply { isFilterBitmap = false }
    private val removed = BooleanArray(cols * rows)

    companion object {
        /** Slow enough to see it happen: it starts gently, breaks up through the middle, settles. */
        const val UNLOCK_MS = 800L
    }

    fun start() {
        android.animation.ValueAnimator.ofInt(0, order.size).apply {
            duration = UNLOCK_MS
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val target = it.animatedValue as Int
                while (gone < target) removed[order[gone++]] = true
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shot.recycle()
                    done()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (shot.isRecycled) return
        for (i in 0 until cols * rows) {
            if (removed[i]) continue
            val x = (i % cols) * size
            val y = (i / cols) * size
            src.set(x, y, minOf(x + size, shot.width), minOf(y + size, shot.height))
            dst.set(src)
            canvas.drawBitmap(shot, src, dst, paint)
        }
    }
}
