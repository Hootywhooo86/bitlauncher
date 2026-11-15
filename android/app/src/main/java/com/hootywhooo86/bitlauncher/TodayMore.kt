package com.hootywhooo86.bitlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/** The Today page's cards, their names, and the order you've put them in. */
object TodayOrder {
    /** (key, name), in the default order. Widgets you add always come after these. */
    val CARDS = listOf(
        "glance" to "Day at a glance",
        "weather" to "Weather",
        "ticket" to "Next shift or train",
        "events" to "Coming up",
        "media" to "Now playing",
        "notes" to "Notes",
        "onthisday" to "On this day",
        "family" to "128bit",
    )

    fun name(key: String) = CARDS.firstOrNull { it.first == key }?.second ?: key

    /** Your order, with any new cards added at the end. */
    fun order(prefs: Prefs): List<String> {
        val saved = prefs.todayOrder.filter { k -> CARDS.any { it.first == k } }
        return saved + CARDS.map { it.first }.filter { it !in saved }
    }

    fun move(prefs: Prefs, key: String, by: Int) {
        val list = order(prefs).toMutableList()
        val i = list.indexOf(key)
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        prefs.todayOrder = list
    }
}

/** One line for the top of Today: weather, the next event, the next alarm, the next shift. Only what's real. */
object Glance {
    fun line(parts: List<String?>): String? = parts.filterNotNull().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

    fun build(context: Context, prefs: Prefs, w: WeatherNow?, events: List<UpcomingEvent>, now: Long = System.currentTimeMillis()): String? {
        val tf = DateFormat.getTimeFormat(context)
        val weather = w?.let { "${WeatherCodes.describe(it.code)} ${WeatherCodes.temp(it.tempC, prefs.fahrenheit)}" }
        val event = events.firstOrNull()?.let { "${it.title} ${CalendarSource.whenText(context, it, now)}" }
        val alarm = nextAlarm(context)?.takeIf { it > now && it - now < 12 * 3600_000L }?.let { "Alarm ${tf.format(Date(it))}" }
        val ticket = Tickets.next(context, now)?.let { if (it.kind == "SHIFT") it.what.lowercase().replaceFirstChar { c -> c.uppercase() } else "Train ${tf.format(Date(it.at))}" }
        return line(listOf(weather, event, alarm, ticket))
    }
}

/** The next hours as pixel sprites, and a plain warning if rain or snow is on the way. */
object Hourly {
    private val SUN = arrayOf("..#..", "#.#.#", ".###.", "#####", ".###.", "#.#.#", "..#..")
    private val CLOUD = arrayOf(".....", "..##.", ".####", "#####", "#####", ".....", ".....")
    private val RAIN = arrayOf("..##.", ".####", "#####", ".....", "#.#.#", ".#.#.", "#.#.#")
    private val SNOW = arrayOf("..##.", ".####", "#####", ".....", "#...#", "..#..", "#...#")
    private val FOG = arrayOf(".....", "####.", ".....", ".####", ".....", "####.", ".....")
    private val STORM = arrayOf("..##.", ".####", "#####", "..#..", ".##..", "..#..", ".#...")

    fun sprite(code: Int) = when (WeatherCodes.sky(code)) {
        Sky.CLEAR -> SUN
        Sky.RAIN -> RAIN
        Sky.SNOW -> SNOW
        Sky.FOG -> FOG
        Sky.STORM -> STORM
        else -> CLOUD
    }

    private fun wet(h: WeatherHour) = (h.rainPct ?: 0) >= 50 || WeatherCodes.sky(h.code).let { it == Sky.RAIN || it == Sky.SNOW || it == Sky.STORM }

    /** "Rain likely around 3 PM" for the first wet hour in the next six; null if it looks dry. */
    fun warning(hours: List<WeatherHour>, now: Long, timeText: (Long) -> String): String? {
        val soon = hours.filter { it.time + 3600_000L > now }.take(6)
        val first = soon.firstOrNull(::wet) ?: return null
        val what = when (WeatherCodes.sky(first.code)) {
            Sky.SNOW -> "Snow"
            Sky.STORM -> "Storms"
            else -> "Rain"
        }
        return if (first.time <= now) "$what now. Take a jacket." else "$what likely around ${timeText(first.time)}. Take a jacket."
    }

    /** Six hours across: time, sprite, temperature. */
    fun strip(context: Context, hours: List<WeatherHour>, fahrenheit: Boolean, accent: Int, now: Long = System.currentTimeMillis()): View? {
        val soon = hours.filter { it.time + 3600_000L > now }.take(6)
        if (soon.size < 2) return null
        val tf = java.text.SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH" else "ha", java.util.Locale.getDefault())
        return LinearLayout(context).apply {
            setPadding(0, Pixel.dpi(context, 12), 0, 0)
            soon.forEachIndexed { i, h ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(Pixel.label(context, if (i == 0) "NOW" else tf.format(Date(h.time)).uppercase(), 8f, if (i == 0) accent else Pixel.MUTED))
                    addView(GlyphView(context, sprite(h.code), if (WeatherCodes.sky(h.code) == Sky.CLEAR) 0xFFFFD34D.toInt() else Pixel.INK),
                        LinearLayout.LayoutParams(Pixel.dpi(context, 22), Pixel.dpi(context, 22)).apply { topMargin = Pixel.dpi(context, 6); bottomMargin = Pixel.dpi(context, 6) })
                    addView(Pixel.label(context, WeatherCodes.temp(h.tempC, fahrenheit), 10f, Pixel.INK))
                    h.rainPct?.takeIf { it >= 20 }?.let { addView(Pixel.label(context, "$it%", 7f, 0xFF60A5FA.toInt()).apply { setPadding(0, Pixel.dpi(context, 3), 0, 0) }) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }
}

/** Your quick notes: one page of text, saved on this phone as you type, and in your 128bit backup. */
object QuickNotes {
    private fun sp(c: Context) = c.getSharedPreferences("notes", Context.MODE_PRIVATE)
    fun text(c: Context): String = sp(c).getString("text", "")!!
    fun save(c: Context, text: String) = sp(c).edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply()

    fun card(context: Context, accent: Int): View = TodayCards.panel(context).apply {
        addView(Pixel.label(context, "NOTES", 10f, accent))
        val main = Handler(Looper.getMainLooper())
        val field = EditText(context).apply {
            setText(text(context))
            hint = "Groceries, a number, a thought. Saves as you type."
            setHintTextColor(Pixel.MUTED)
            setTextColor(Pixel.INK)
            textSize = 15f
            background = null
            gravity = Gravity.TOP
            minLines = 3
            setPadding(0, Pixel.dpi(context, 10), 0, 0)
        }
        val saved = Pixel.label(context, "", 8f, Pixel.MUTED)
        val save = Runnable {
            save(context, field.text.toString())
            saved.text = "SAVED ON THIS PHONE"
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saved.text = ""
                main.removeCallbacks(save)
                main.postDelayed(save, 400)
            }
        })
        addView(field, LinearLayout.LayoutParams(-1, -2))
        addView(saved)
    }
}

/**
 * Photos taken on this day in earlier years. Off until you allow photos; read on the phone and
 * shown pixelated (tap one to see it properly). Nothing is uploaded.
 */
object OnThisDay {
    private val worker = Executors.newSingleThreadExecutor()

    data class Photo(val uri: Uri, val yearsAgo: Int, val taken: Long)

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(c: Context): Boolean = c.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

    /** Up to [max] photos from this calendar day in the last ten years, most recent year first. */
    fun find(c: Context, now: Long = System.currentTimeMillis(), max: Int = 3): List<Photo> {
        val out = mutableListOf<Photo>()
        for (years in 1..10) {
            if (out.size >= max) break
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.YEAR, -years)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = start + 24 * 3600_000L
            try {
                c.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                    "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
                    arrayOf(start.toString(), end.toString()),
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC",
                )?.use { cur ->
                    while (cur.moveToNext() && out.size < max) {
                        out += Photo(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)), years, cur.getLong(1))
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** Pixelated: shrink to [cells] across, then scale back up without smoothing. */
    fun pixelate(b: Bitmap, cells: Int = 48): Bitmap {
        val side = minOf(b.width, b.height)
        val square = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, cells, cells, true)
        return Bitmap.createScaledBitmap(small, cells * 6, cells * 6, false)
    }

    fun card(context: Context, accent: Int, onAllow: () -> Unit): View = TodayCards.panel(context).apply {
        addView(Pixel.label(context, "ON THIS DAY", 10f, accent))
        if (!allowed(context)) {
            addView(TextView(context).apply {
                text = "Photos you took on this day in other years, pixelated. Read on this phone only; nothing is uploaded."
                setTextColor(Pixel.MUTED)
                textSize = 14f
                setPadding(0, Pixel.dpi(context, 8), 0, Pixel.dpi(context, 10))
            })
            addView(Pixel.button(context, "SHOW MY PHOTOS", accent) { onAllow() }, LinearLayout.LayoutParams(-2, -2))
            return@apply
        }
        val row = LinearLayout(context)
        val note = TextView(context).apply {
            setTextColor(Pixel.MUTED)
            textSize = 14f
            setPadding(0, Pixel.dpi(context, 8), 0, 0)
        }
        addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Pixel.dpi(context, 10) })
        addView(note)
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val photos = find(context)
            val thumbs = photos.mapNotNull { p ->
                try {
                    val t = if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(p.uri, android.util.Size(256, 256), null) else null
                    t?.let { p to pixelate(it) }
                } catch (e: Exception) {
                    null
                }
            }
            main.post {
                if (thumbs.isEmpty()) {
                    note.text = "No photos from this day in other years."
                    return@post
                }
                for ((p, bmp) in thumbs) row.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        isClickable = true
                        contentDescription = "Photo from ${p.yearsAgo} years ago"
                        setOnClickListener {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, p.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                Pixel.say(context, "No app on this phone can open photos.")
                            }
                        }
                    }, LinearLayout.LayoutParams(-1, Pixel.dpi(context, 96)))
                    addView(Pixel.label(context, if (p.yearsAgo == 1) "1 YEAR AGO" else "${p.yearsAgo} YEARS AGO", 8f, Pixel.MUTED).apply {
                        setPadding(0, Pixel.dpi(context, 4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Pixel.dpi(context, 8) })
            }
        }
    }
}

/** Directions to work from the shift card: opens your maps app; no travel time is made up. */
object Commute {
    fun directions(context: Context, address: String) {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(address))),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))),
        )
        val intent = tries.firstOrNull { it.resolveActivity(context.packageManager) != null }
        if (intent == null) Pixel.say(context, "No maps app on this phone can give directions.")
        else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
