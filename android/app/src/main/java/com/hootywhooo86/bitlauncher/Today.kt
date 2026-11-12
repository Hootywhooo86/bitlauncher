package com.hootywhooo86.bitlauncher

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

/** One upcoming event from the phone's calendar. */
data class UpcomingEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/** The phone's calendar (which includes Google Calendar and anything synced to it). Read only. */
object CalendarSource {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events happening now or in the next two days, soonest first. */
    fun upcoming(context: Context, max: Int = 4): List<UpcomingEvent> {
        if (!hasPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - DateUtils.DAY_IN_MILLIS)
            ContentUris.appendId(it, now + 2 * DateUtils.DAY_IN_MILLIS)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<UpcomingEvent>()
        try {
            context.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val end = c.getLong(3)
                    if (end < now) continue // already over
                    out += UpcomingEvent(c.getLong(0), c.getString(1) ?: "(No title)", c.getLong(2), end, c.getInt(4) == 1)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** "Now", "Today 18:00", "Tomorrow 09:30", "Sat all day". */
    fun whenText(context: Context, e: UpcomingEvent, now: Long = System.currentTimeMillis()): String {
        val day = Calendar.getInstance().apply { timeInMillis = e.begin }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val dayDiff = (day.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 366 + day.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        val dayName = when {
            dayDiff <= 0 -> "Today"
            dayDiff == 1 -> "Tomorrow"
            else -> android.text.format.DateFormat.format("EEE", day).toString()
        }
        if (!e.allDay && e.begin <= now && e.end > now) return "Now"
        if (e.allDay) return "$dayName all day"
        return "$dayName ${DateFormat.getTimeFormat(context).format(Date(e.begin))}"
    }
}

/**
 * What's playing, from any app with a media session (128bitPlay, Spotify, podcasts). Android
 * only shares this with apps that have notification access, which bitlauncher asks for already.
 */
class MediaSource(private val context: Context, private val onChanged: () -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, BitNotificationService::class.java)
    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
        override fun onSessionDestroyed() = pick()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessions, listener)
        } catch (e: SecurityException) {
            // No notification access: no now-playing card, nothing else changes.
        }
        pick()
    }

    fun stop() {
        try {
            manager.removeOnActiveSessionsChangedListener(sessions)
        } catch (e: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The session that's playing, or else the most recent one with something loaded. */
    private fun pick() {
        val list = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            emptyList()
        }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        onChanged()
    }

    fun current(): MediaController? = controller?.takeIf { it.metadata != null }
}

/** Cards for the Today page and the lock screen. Each is a pixel panel. */
object TodayCards {
    fun panel(context: Context, accent: Int? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = PanelDrawable(context, withAlpha(Pixel.PANEL, 235), accent ?: withAlpha(Pixel.MUTED, 140))
        val p = Pixel.dpi(context, 14)
        setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 10) }
    }

    private fun heading(context: Context, text: String, accent: Int) =
        Pixel.label(context, text.uppercase(), 10f, accent).apply { setPadding(0, 0, 0, Pixel.dpi(context, 8)) }

    private fun body(context: Context, text: String, size: Float = 15f, color: Int = Pixel.INK) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    fun weather(context: Context, accent: Int, w: WeatherNow?, problem: String?, fahrenheit: Boolean, onFix: (() -> Unit)?): View =
        panel(context).apply {
            addView(heading(context, w?.place ?: "Weather", accent))
            if (w == null) {
                addView(body(context, problem ?: "Weather is off. Turn it on in Settings to see it here.", 14f, Pixel.MUTED))
                onFix?.let { fix -> addView(Pixel.button(context, "SETTINGS", accent) { fix() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Pixel.dpi(context, 10) }) }
                return@apply
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(Pixel.label(context, WeatherCodes.temp(w.tempC, fahrenheit), 34f, Pixel.INK))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Pixel.dpi(context, 14), 0, 0, Pixel.dpi(context, 4))
                    addView(body(context, WeatherCodes.describe(w.code), 15f))
                    addView(body(context, "Feels ${WeatherCodes.temp(w.feelsC, fahrenheit)}  ·  H ${WeatherCodes.temp(w.highC, fahrenheit)}  L ${WeatherCodes.temp(w.lowC, fahrenheit)}", 13f, Pixel.MUTED))
                })
            })
            val tf = DateFormat.getTimeFormat(context)
            addView(Pixel.label(context, "SUNRISE ${tf.format(Date(w.sunrise))}   SUNSET ${tf.format(Date(w.sunset))}", 9f, Pixel.MUTED).apply {
                setPadding(0, Pixel.dpi(context, 10), 0, 0)
            })
            // Old readings say how old they are: no pretending.
            if (System.currentTimeMillis() - w.fetchedAt > 2 * 3600_000L) {
                addView(body(context, "Updated ${DateUtils.getRelativeTimeSpanString(w.fetchedAt)}", 12f, Pixel.MUTED))
            }
        }

    fun events(context: Context, accent: Int, events: List<UpcomingEvent>?, onAsk: () -> Unit, onOpen: (UpcomingEvent) -> Unit): View =
        panel(context).apply {
            addView(heading(context, "Coming up", accent))
            when {
                events == null -> {
                    addView(body(context, "Show your next events here. bitlauncher only reads your calendar; it never changes it.", 14f, Pixel.MUTED))
                    addView(Pixel.button(context, "SHOW MY CALENDAR", accent) { onAsk() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Pixel.dpi(context, 10) })
                }
                events.isEmpty() -> addView(body(context, "Nothing in the next two days.", 14f, Pixel.MUTED))
                else -> for (e in events) addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Pixel.dpi(context, 4), 0, Pixel.dpi(context, 4))
                    isClickable = true
                    foreground = context.getDrawable(android.R.drawable.list_selector_background)
                    setOnClickListener { onOpen(e) }
                    addView(Pixel.label(context, CalendarSource.whenText(context, e).uppercase(), 9f, accent), LinearLayout.LayoutParams(Pixel.dpi(context, 110), -2))
                    addView(body(context, e.title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
        }

    /** Now playing, with the cover art pixelated like everything else. */
    fun media(context: Context, accent: Int, c: MediaController): View = panel(context).apply {
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.description?.title?.toString() ?: ""
        val sub = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: meta?.description?.subtitle?.toString() ?: ""
        val app = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) {
            c.packageName
        }
        addView(heading(context, "Playing on $app", accent))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val art: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.description?.iconBitmap
            if (art != null) {
                val sprite = IconCache.pixelate(BitmapDrawable(context.resources, art))
                addView(ImageView(context).apply {
                    setImageDrawable(BitmapDrawable(context.resources, sprite).apply { paint.isFilterBitmap = false })
                }, LinearLayout.LayoutParams(Pixel.dpi(context, 56), Pixel.dpi(context, 56)).apply { rightMargin = Pixel.dpi(context, 12) })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(body(context, title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (sub.isNotBlank()) addView(body(context, sub, 13f, Pixel.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val controls = c.transportControls
        addView(LinearLayout(context).apply {
            setPadding(0, Pixel.dpi(context, 10), 0, 0)
            fun add(label: String, run: () -> Unit) = addView(Pixel.button(context, label, accent) { run() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (childCount > 0) leftMargin = Pixel.dpi(context, 6)
            })
            add("|<") { controls.skipToPrevious() }
            add(if (playing) "PAUSE" else "PLAY") { if (playing) controls.pause() else controls.play() }
            add(">|") { controls.skipToNext() }
        })
    }
}

/**
 * The Today page: slides in from the left of the first home page. The activity fills it with
 * cards and the widgets you add; this view handles its scrolling and the swipe to close.
 */
class TodayView(context: Context) : FrameLayout(context) {
    val cards: LinearLayout
    private val scroll: ScrollView
    /** Swiping it back to the left: [px] so far, and the release. */
    var onPull: ((Float) -> Unit)? = null
    var onRelease: ((px: Float, velocityX: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null

    init {
        setBackgroundColor(withAlpha(Pixel.BG, 252))
        isClickable = true
        cards = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Pixel.dpi(context, 16)
            setPadding(p, p, p, p)
        }
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(cards)
        }
        addView(scroll, LayoutParams(-1, -1))
    }

    fun setInsets(top: Int, bottom: Int) {
        scroll.setPadding(0, top, 0, bottom)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - e.x
                if (dx > slop && dx > abs(e.y - downY) * 1.5f) {
                    downX = e.x
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> onPull?.invoke((downX - e.x).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = if (e.actionMasked == MotionEvent.ACTION_UP) velocity?.xVelocity ?: 0f else 0f
                onRelease?.invoke((downX - e.x).coerceAtLeast(0f), vx)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }
}
