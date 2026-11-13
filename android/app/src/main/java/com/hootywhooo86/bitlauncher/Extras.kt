package com.hootywhooo86.bitlauncher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.View
import org.json.JSONObject
import java.util.Calendar

/** Digit styles for the block clock. Every font is 5x7 so the clock's size never changes. */
object ClockFonts {
    /** (key, name). */
    val ALL = listOf("blocks" to "Blocks", "round" to "Rounded", "arcade" to "Arcade", "lcd" to "LCD segments")

    private val ROUND = arrayOf(
        arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        arrayOf(".##..", "#.#..", "..#..", "..#..", "..#..", "..#..", "#####"),
        arrayOf(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
        arrayOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        arrayOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
        arrayOf("#####", "....#", "...#.", "..#..", "..#..", "..#..", "..#.."),
        arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        arrayOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
    )

    private val ARCADE = arrayOf(
        arrayOf("#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####"),
        arrayOf("###..", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
        arrayOf("#####", "....#", "....#", "#####", "#....", "#....", "#####"),
        arrayOf("#####", "....#", "....#", ".####", "....#", "....#", "#####"),
        arrayOf("#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"),
        arrayOf("#####", "#....", "#....", "#####", "....#", "....#", "#####"),
        arrayOf("#####", "#....", "#....", "#####", "#...#", "#...#", "#####"),
        arrayOf("#####", "....#", "....#", "...#.", "..#..", "..#..", "..#.."),
        arrayOf("#####", "#...#", "#...#", "#####", "#...#", "#...#", "#####"),
        arrayOf("#####", "#...#", "#...#", "#####", "....#", "....#", "#####"),
    )

    /** Seven-segment digits: a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle. */
    private val SEGMENTS = arrayOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")

    private fun segment(on: String): Array<String> {
        val g = Array(7) { CharArray(5) { '.' } }
        fun row(r: Int) { for (c in 1..3) g[r][c] = '#' }
        fun col(c: Int, r0: Int, r1: Int) { for (r in r0..r1) g[r][c] = '#' }
        if ('a' in on) row(0)
        if ('g' in on) row(3)
        if ('d' in on) row(6)
        if ('f' in on) col(0, 1, 2)
        if ('b' in on) col(4, 1, 2)
        if ('e' in on) col(0, 4, 5)
        if ('c' in on) col(4, 4, 5)
        return Array(7) { String(g[it]) }
    }

    private val LCD = Array(10) { segment(SEGMENTS[it]) }

    /** All segments: drawn faintly behind LCD digits, like an unlit display. */
    val LCD_GHOST = segment("abcdefg")

    fun digits(font: String): Array<Array<String>> = when (font) {
        "round" -> ROUND
        "arcade" -> ARCADE
        "lcd" -> LCD
        else -> PixelGlyphs.DIGITS
    }
}

/** Short countdowns: "IN 7H 12M", "IN 40M", "NOW". */
fun countdown(millis: Long): String {
    val m = ((millis + 59_999) / 60_000).toInt()
    return when {
        m <= 0 -> "NOW"
        m < 60 -> "IN ${m}M"
        else -> "IN ${m / 60}H ${m % 60}M"
    }
}

/** "WAKE 6:30 · IN 7H 12M" when an alarm is set within [window]; null otherwise. */
fun alarmLine(now: Long, trigger: Long?, timeText: String, window: Long = 12 * 3_600_000L): String? {
    if (trigger == null || trigger <= now || trigger - now > window) return null
    return "WAKE ${timeText.uppercase()}  ·  ${countdown(trigger - now)}"
}

fun nextAlarm(context: Context): Long? = try {
    context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
} catch (e: Exception) {
    null
}

/**
 * The ticket stub: your next shift from the shift schedule, or a train another 128bit app
 * (Railway Time) sent with SET_TICKET. Shown only when there's something real in the next 12 hours.
 */
object Tickets {
    const val ACTION_SET_TICKET = "com.hootywhooo86.bitlauncher.SET_TICKET"
    private const val WINDOW = 12 * 3_600_000L

    /** (kind, what, at): kind is "SHIFT" or "TRAIN". */
    data class Ticket(val kind: String, val what: String, val at: Long)

    private fun sp(context: Context) = context.getSharedPreferences("ticket", Context.MODE_PRIVATE)

    fun save(context: Context, what: String, at: Long) =
        sp(context).edit().putString("t", JSONObject().put("what", what.take(40)).put("at", at).toString()).apply()

    fun clear(context: Context) = sp(context).edit().remove("t").apply()

    private fun sent(context: Context): Ticket? = try {
        sp(context).getString("t", null)?.let { JSONObject(it) }?.let { Ticket("TRAIN", it.getString("what"), it.getLong("at")) }
    } catch (e: Exception) {
        null
    }

    /** From the shift schedule: on shift now (ending at…), or the next start within the window. */
    fun shift(prefs: Prefs, now: Long, timeText: (Long) -> String): Ticket? {
        if (!prefs.shiftSchedule) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val working = Shift.isWorkTime(cal, prefs.shiftDays, prefs.shiftStart, prefs.shiftEnd)
        val change = Shift.nextChange(prefs, now)
        if (change <= now || change - now > WINDOW) return null
        return if (working) Ticket("SHIFT", "ON SHIFT  ·  ENDS ${timeText(change).uppercase()}", change)
        else Ticket("SHIFT", "SHIFT STARTS ${timeText(change).uppercase()}", change)
    }

    /** The soonest real ticket, or null. */
    fun next(context: Context, now: Long = System.currentTimeMillis()): Ticket? {
        val fmt = android.text.format.DateFormat.getTimeFormat(context)
        val train = sent(context)?.takeIf { it.at > now && it.at - now <= WINDOW }
        val shift = shift(Prefs(context), now) { fmt.format(java.util.Date(it)) }
        return listOfNotNull(train, shift).minByOrNull { it.at }
    }
}

/** Railway Time (or any 128bit app) sets the next train: extras "what" (text) and "at" (millis). */
class TicketReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Tickets.ACTION_SET_TICKET) return
        val what = intent.getStringExtra("what")
        val at = intent.getLongExtra("at", 0L)
        if (what.isNullOrBlank() || at <= 0L) Tickets.clear(context) else Tickets.save(context, what, at)
    }
}

/**
 * A paper ticket in pixels: a stub on the left behind a perforated line, notches bitten out
 * of both edges at the perforation.
 */
class TicketDrawable(context: Context, private val fill: Int, private val edge: Int, private val stubW: Float) : Drawable() {
    private val b = Pixel.dp(context, 2f)
    private val p = Paint()

    override fun draw(c: Canvas) {
        val r = bounds
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val R = r.right.toFloat() - b * 2
        val B = r.bottom.toFloat() - b * 2
        p.color = Pixel.SHADOW
        c.drawRect(l + b * 2, t + b * 2, R + b * 2, B + b * 2, p)
        p.color = edge
        c.drawRect(l, t, R, B, p)
        p.color = fill
        c.drawRect(l + b, t + b, R - b, B - b, p)
        // Notches at the perforation, top and bottom.
        val px = l + stubW
        p.color = Pixel.BG
        c.drawRect(px - b * 2, t, px + b * 2, t + b * 2, p)
        c.drawRect(px - b * 2, B - b * 2, px + b * 2, B, p)
        // The perforation: dashes down the line.
        p.color = edge
        var y = t + b * 3
        while (y < B - b * 3) {
            c.drawRect(px - b / 2, y, px + b / 2, y + b, p)
            y += b * 2
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A small pixel icon button for the lock screen corners: hold to use it. */
class CornerButton(context: Context, private val icon: Array<String>, private val accent: Int) : View(context) {
    var on = false
        set(v) { field = v; invalidate() }
    private val p = Paint()

    override fun onDraw(c: Canvas) {
        val u = width / 12f
        p.color = Pixel.SHADOW
        c.drawRect(u * 0.6f, u * 0.6f, width.toFloat(), height.toFloat(), p)
        p.color = if (on) accent else withAlpha(Pixel.PANEL, 235)
        c.drawRect(0f, 0f, width - u * 0.6f, height - u * 0.6f, p)
        p.color = if (on) Pixel.BG else Pixel.INK
        val ox = (12 - icon[0].length) / 2f * u - u * 0.3f
        val oy = (12 - icon.size) / 2f * u - u * 0.3f
        for (r in icon.indices) for (col in icon[r].indices) if (icon[r][col] == '#') {
            c.drawRect(ox + col * u, oy + r * u, ox + (col + 1) * u, oy + (r + 1) * u, p)
        }
    }

    companion object {
        val CAMERA = arrayOf("..##...", "#######", "#..#..#", "#.#.#.#", "#..#..#", "#######")
        val TORCH = arrayOf("#####", "#####", ".###.", ".#.#.", ".###.", ".###.", ".###.")
    }
}

/** Turns the flashlight on and off, and says if there isn't one. */
class Torch(private val context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val id: String? = try {
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        null
    }
    var on = false
        private set
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == id) {
                on = enabled
                onChange(enabled)
            }
        }
    }

    fun start() = try {
        cm.registerTorchCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (e: Exception) {
    }

    fun stop() = try {
        cm.unregisterTorchCallback(callback)
    } catch (e: Exception) {
    }

    fun toggle() {
        val cam = id
        if (cam == null) {
            Pixel.say(context, "This phone has no flashlight bitlauncher can use.")
            return
        }
        try {
            cm.setTorchMode(cam, !on)
            on = !on
            onChange(on)
        } catch (e: Exception) {
            Pixel.say(context, "The flashlight is busy (the camera may be using it).")
        }
    }
}

/** The ticket stub view: SHIFT or TRAIN on the stub, what and when on the ticket. */
object TicketView {
    fun make(context: Context, t: Tickets.Ticket, accent: Int, now: Long = System.currentTimeMillis()): View {
        val stubW = Pixel.dp(context, 64f)
        return android.widget.LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = TicketDrawable(context, Pixel.PANEL, withAlpha(accent, 200), stubW)
            val p = Pixel.dpi(context, 12)
            setPadding(0, p, p + Pixel.dpi(context, 4), p + Pixel.dpi(context, 4))
            addView(Pixel.label(context, t.kind, 9f, accent).apply { gravity = android.view.Gravity.CENTER }, android.widget.LinearLayout.LayoutParams(stubW.toInt(), -2))
            val words = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            words.addView(Pixel.label(context, t.what.uppercase(), 10f, Pixel.INK).apply { maxLines = 2 })
            words.addView(Pixel.label(context, countdown(t.at - now), 9f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 5), 0, 0) })
            addView(words, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Pixel.dpi(context, 12) })
        }
    }
}
