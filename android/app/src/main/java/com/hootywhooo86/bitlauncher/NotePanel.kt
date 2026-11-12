package com.hootywhooo86.bitlauncher

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Shared pieces for drawing notifications as pixel cards. */
object NoteCards {
    /** Pop-up choices: (key, name). */
    val POPUPS = listOf("off" to "Off", "home" to "On home only", "everywhere" to "Everywhere")

    fun appName(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** "now", "5m", "2h", "3d". */
    fun ago(posted: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - posted) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h"
            else -> "${m / (24 * 60)}d"
        }
    }

    /** Groups for the panel: one per app, newest app first, newest note first; no group headers. */
    fun groups(notes: List<Note>): List<List<Note>> = notes.filter { !it.groupSummary }
        .groupBy { it.pkg to it.user }
        .values
        .map { list -> list.sortedByDescending { it.posted } }
        .sortedByDescending { it.first().posted }

    fun sprite(context: Context, icons: IconCache, note: Note): View = ImageView(context).apply {
        icons.getForPackage(note.pkg, note.user)?.let {
            setImageDrawable(BitmapDrawable(context.resources, it).apply { paint.isFilterBitmap = false })
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Title and text, one under the other. */
    fun words(context: Context, note: Note, titleLines: Int = 1, textLines: Int = 3): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (note.title.isNotBlank()) addView(TextView(context).apply {
                text = note.title
                setTextColor(Pixel.INK)
                textSize = 14f
                maxLines = titleLines
                ellipsize = TextUtils.TruncateAt.END
            })
            if (note.text.isNotBlank()) addView(TextView(context).apply {
                text = note.text
                setTextColor(Pixel.MUTED)
                textSize = 13f
                maxLines = textLines
                ellipsize = TextUtils.TruncateAt.END
            })
        }

    /** Open what the notification points at; one honest sentence if it can't be. */
    fun open(context: Context, note: Note) {
        val intent = note.intent
        if (intent == null) {
            Pixel.say(context, "This notification doesn't open anything.")
            return
        }
        try {
            intent.send()
            if (note.autoCancel && note.clearable) NotificationHub.dismiss(note.key)
        } catch (e: Exception) {
            Pixel.say(context, "That notification can't be opened any more.")
        }
    }
}

/**
 * A card that can be swiped sideways. Past a third of its width (or flung) it slides off and
 * [onSwiped] runs; otherwise it springs back. Vertical drags are left to the scroll view.
 */
class SwipeCard(context: Context, private val swipeable: Boolean, private val onSwiped: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastT = 0L
    private var velocity = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!swipeable) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                lastX = e.rawX; lastT = e.eventTime; velocity = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                if (abs(dx) > slop && abs(dx) > abs(e.rawY - downY) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!swipeable) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                if (!dragging && abs(dx) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val dt = (e.eventTime - lastT).coerceAtLeast(1)
                    velocity = (e.rawX - lastX) * 1000f / dt
                    lastX = e.rawX; lastT = e.eventTime
                    translationX = dx
                    alpha = 1f - (abs(dx) / width.coerceAtLeast(1)).coerceIn(0f, 0.8f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return super.onTouchEvent(e)
                dragging = false
                val gone = abs(translationX) > width / 3f || (abs(velocity) > 1500 && velocity * translationX > 0)
                if (gone && e.actionMasked == MotionEvent.ACTION_UP) dismiss(if (translationX >= 0) 1 else -1) else springBack()
            }
        }
        return true
    }

    fun dismiss(direction: Int) {
        if (!Pixel.motion(context)) {
            onSwiped()
            return
        }
        animate().translationX(direction * width.toFloat()).alpha(0f).setDuration(160).setInterpolator(Ease.exit)
            .withEndAction(onSwiped).start()
    }

    private fun springBack() {
        animate().translationX(0f).alpha(1f).setDuration(if (Pixel.motion(context)) 180 else 0).setInterpolator(Ease.move).start()
    }
}

/**
 * bitlauncher's own notification shade: swipe down on home. Grouped by app, tap to open,
 * swipe sideways to dismiss, CLEAR ALL, and a way through to the phone's shade for quick settings.
 */
class NotificationPanel(context: Context, private val icons: IconCache, private val onPhoneShade: () -> Unit) : FrameLayout(context) {
    var accent = Pixel.ACCENTS[0]
    private val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val listener: () -> Unit = { if (visibility == VISIBLE) fill() }

    /** Keys being animated away, so a refresh doesn't bring them back for a frame. */
    private val leaving = mutableSetOf<String>()

    init {
        visibility = GONE
        setBackgroundColor(withAlpha(Pixel.BG, 200))
        isClickable = true
        setOnClickListener { close() }
        val pad = Pixel.dpi(context, 14)
        sheet.setPadding(pad, pad, pad, pad)
        sheet.background = PanelDrawable(context, Pixel.BG, withAlpha(Pixel.MUTED, 160))
        sheet.isClickable = true
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 10) })
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.addView(Pixel.label(context, "▲  CLOSE", 10f, Pixel.MUTED).apply {
            gravity = Gravity.CENTER
            val p = Pixel.dpi(context, 10)
            setPadding(p, p, p, 0)
            isClickable = true
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(-1, -2))
        addView(sheet, LayoutParams(-1, -1))
    }

    /** Leave room for the status bar and navigation; the sheet stops short of the bottom. */
    fun setInsets(top: Int, bottom: Int) {
        (sheet.layoutParams as LayoutParams).apply {
            topMargin = top + Pixel.dpi(context, 6)
            bottomMargin = bottom + Pixel.dpi(context, 48)
            leftMargin = Pixel.dpi(context, 8)
            rightMargin = Pixel.dpi(context, 8)
        }
        sheet.requestLayout()
    }

    val isOpen get() = visibility == VISIBLE

    fun open() {
        fill()
        visibility = VISIBLE
        NotificationHub.listen(listener)
        if (Pixel.motion(context)) {
            alpha = 0f
            sheet.translationY = -Pixel.dp(context, 120f)
            animate().alpha(1f).setDuration(160).start()
            sheet.animate().translationY(0f).setDuration(260).setInterpolator(Ease.enter).start()
        }
    }

    fun close() {
        if (!isOpen) return
        NotificationHub.unlisten(listener)
        if (!Pixel.motion(context)) {
            visibility = GONE
            return
        }
        animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE; alpha = 1f }.start()
        sheet.animate().translationY(-Pixel.dp(context, 120f)).setDuration(180).setInterpolator(Ease.exit).start()
    }

    private fun fill() {
        val notes = NotificationHub.snapshot().filter { it.key !in leaving }
        header.removeAllViews()
        header.addView(Pixel.label(context, "NOTIFICATIONS", 12f, accent), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(small("PHONE SHADE") { close(); onPhoneShade() })
        if (notes.any { it.clearable && !it.groupSummary }) header.addView(small("CLEAR ALL") { clearAll() }, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = Pixel.dpi(context, 6)
        })
        list.removeAllViews()
        when {
            !NotificationHub.hasAccess(context) -> {
                list.addView(message("bitlauncher can't see your notifications yet. Allow notification access and they'll show here."))
                list.addView(Pixel.button(context, "ALLOW NOTIFICATION ACCESS", accent) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: Exception) {
                        Pixel.say(context, "Open Settings, then Notifications, then Device & app notifications, and allow bitlauncher.")
                    }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            notes.none { !it.groupSummary } -> list.addView(message("Nothing new. You're all caught up."))
            else -> NoteCards.groups(notes).forEach { group -> list.addView(groupView(group)) }
        }
    }

    private fun groupView(group: List<Note>): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 12) }
        }
        val first = group.first()
        val name = NoteCards.appName(context, first.pkg).uppercase()
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(NoteCards.sprite(context, icons, first), LinearLayout.LayoutParams(Pixel.dpi(context, 20), Pixel.dpi(context, 20)).apply {
                rightMargin = Pixel.dpi(context, 8)
            })
            addView(Pixel.label(context, if (group.size > 1) "$name  x${group.size}" else name, 9f, Pixel.MUTED))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 6) })
        group.forEach { box.addView(card(it)) }
        return box
    }

    private fun card(note: Note): View {
        lateinit var holder: SwipeCard
        holder = SwipeCard(context, note.clearable) {
            leaving.add(note.key)
            NotificationHub.dismiss(note.key)
            (holder.parent as? LinearLayout)?.removeView(holder)
            if (NotificationHub.snapshot().none { it.key !in leaving && !it.groupSummary }) fill()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = PanelDrawable(context, Pixel.PANEL, withAlpha(Pixel.MUTED, 120))
            val p = Pixel.dpi(context, 10)
            setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
            isClickable = true
            setOnClickListener {
                close()
                NoteCards.open(context, note)
            }
        }
        row.addView(NoteCards.words(context, note, 2, 4), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Pixel.label(context, NoteCards.ago(note.posted).uppercase(), 8f, Pixel.MUTED), LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = Pixel.dpi(context, 8)
        })
        holder.addView(row, LayoutParams(-1, -2))
        holder.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 6) }
        holder.tag = note.key
        return holder
    }

    private fun clearAll() {
        val clearable = NotificationHub.snapshot().filter { it.clearable }.map { it.key }
        leaving.addAll(clearable)
        NotificationHub.dismissAll()
        // Cards fall away one after another, top first.
        val cards = mutableListOf<SwipeCard>()
        fun collect(v: View) {
            if (v is SwipeCard && v.tag in clearable) cards.add(v) else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(list)
        if (!Pixel.motion(context) || cards.isEmpty()) {
            fill()
            return
        }
        cards.forEachIndexed { i, c ->
            c.animate().translationX(c.width.toFloat()).alpha(0f).setStartDelay(i * 40L).setDuration(160).setInterpolator(Ease.exit).start()
        }
        postDelayed({ fill() }, cards.size * 40L + 180)
    }

    private fun small(text: String, onClick: () -> Unit): TextView = Pixel.label(context, text, 9f, Pixel.INK).apply {
        val p = Pixel.dpi(context, 8)
        setPadding(p, p, p, p)
        background = PanelDrawable(context, Pixel.PANEL, accent, shadow = false)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun message(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Pixel.MUTED)
        textSize = 14f
        val p = Pixel.dpi(context, 6)
        setPadding(p, p * 2, p, p * 3)
    }
}

/** A pixel pop-up card for one new notification. Tap opens it; swipe it up or sideways to put it away. */
class PopupCard(context: Context, icons: IconCache, val note: Note, accent: Int, private val onGone: () -> Unit) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { leave() }
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var gone = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = PanelDrawable(context, Pixel.PANEL, accent)
        val p = Pixel.dpi(context, 10)
        setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
        addView(NoteCards.sprite(context, icons, note), LayoutParams(Pixel.dpi(context, 32), Pixel.dpi(context, 32)).apply {
            rightMargin = Pixel.dpi(context, 10)
        })
        val words = NoteCards.words(context, note, 1, 2)
        words.addView(Pixel.label(context, NoteCards.appName(context, note.pkg).uppercase(), 8f, accent).apply {
            setPadding(0, 0, 0, Pixel.dpi(context, 4))
        }, 0)
        addView(words, LayoutParams(0, -2, 1f))
    }

    /** Slide in from the top and leave by itself after a few seconds. */
    fun show() {
        if (Pixel.motion(context)) {
            translationY = -Pixel.dp(context, 90f)
            alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(Ease.enter).start()
        }
        main.postDelayed(hide, 4500)
    }

    fun leave(dx: Float = 0f) {
        if (gone) return
        gone = true
        main.removeCallbacks(hide)
        if (!Pixel.motion(context)) {
            onGone()
            return
        }
        val a = animate().alpha(0f).setDuration(200).setInterpolator(Ease.exit)
        if (dx != 0f) a.translationX(if (dx > 0) width.toFloat() else -width.toFloat()) else a.translationY(-height.toFloat() - Pixel.dp(context, 20f))
        a.withEndAction(onGone).start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false
                main.removeCallbacks(hide)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = (e.rawY - downY).coerceAtMost(0f)
                if (abs(dx) > 12 || abs(dy) > 12) moved = true
                if (abs(dx) > abs(dy)) translationX = dx else translationY = dy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                when {
                    !moved && e.actionMasked == MotionEvent.ACTION_UP -> {
                        leave()
                        NoteCards.open(context, note)
                    }
                    abs(dx) > width / 4f -> leave(dx)
                    dy < -height / 3f -> leave()
                    else -> {
                        animate().translationX(0f).translationY(0f).setDuration(160).setInterpolator(Ease.move).start()
                        main.postDelayed(hide, 3000)
                    }
                }
            }
        }
        return true
    }
}

/** Who shows a pop-up for a new notification: the home screen while it's in front, otherwise the service. */
object Popups {
    /** True while the home screen is resumed; it shows its own pop-ups then. */
    @Volatile
    var homeShowing = false

    /** Is now a good moment? Not with the screen off or the phone locked: the lock screen has its own list. */
    fun phoneInUse(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    } catch (e: Exception) {
        false
    }

    fun canDrawEverywhere(context: Context) = Settings.canDrawOverlays(context)
}

/** Pop-ups over other apps ("Everywhere"), drawn from the notification service as an overlay window. */
class OverlayPopups(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val icons by lazy { IconCache(context) }
    private var current: View? = null

    fun maybeShow(note: Note) {
        if (Popups.homeShowing) return // home shows its own
        if (Prefs(context).popups != "everywhere" || !Popups.canDrawEverywhere(context) || !Popups.phoneInUse(context)) return
        Pixel.load(context)
        removeCurrent()
        val prefs = Prefs(context)
        val frame = FrameLayout(context)
        lateinit var card: PopupCard
        card = PopupCard(context, icons, note, prefs.accent) { if (current === frame) removeCurrent() }
        frame.addView(card, FrameLayout.LayoutParams(-1, -2))
        val m = Pixel.dpi(context, 10)
        frame.setPadding(m, m, m, m)
        val lp = WindowManager.LayoutParams(
            minOf(context.resources.displayMetrics.widthPixels, Pixel.dpi(context, 480)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Pixel.dpi(context, 28)
        }
        try {
            wm.addView(frame, lp)
            current = frame
            card.show()
        } catch (e: Exception) {
            // Permission withdrawn in the meantime: nothing to show.
            current = null
        }
    }

    private fun removeCurrent() {
        val v = current ?: return
        current = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }
}
