package com.hootywhooo86.bitlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Your recent searches, on this phone only. Off in settings means none are kept. */
object SearchHistory {
    private const val MAX = 20
    private fun sp(c: Context) = c.getSharedPreferences("searchHistory", Context.MODE_PRIVATE)

    fun all(c: Context): List<String> = try {
        val a = JSONArray(sp(c).getString("q", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun add(c: Context, q: String) {
        val t = q.trim()
        if (t.isEmpty() || !Prefs(c).searchHistory) return
        val list = (listOf(t) + all(c).filter { !it.equals(t, ignoreCase = true) }).take(MAX)
        sp(c).edit().putString("q", JSONArray(list).toString()).apply()
    }

    fun remove(c: Context, q: String) = sp(c).edit().putString("q", JSONArray(all(c).filter { it != q }).toString()).apply()

    fun clear(c: Context) = sp(c).edit().remove("q").apply()
}

/** Bitter Searcher's look: bar style, where the bar sits, the app row, history, the web. */
object SearchLooks {
    val BARS = listOf("panel" to "Panel", "underline" to "Underline", "plain" to "Plain")
    val POSITIONS = listOf("top" to "Top", "bottom" to "Bottom (by the keyboard)")
    val APP_ROWS = listOf(0 to "Off", 4 to "4", 5 to "5", 6 to "6")
    val ENGINES = listOf("bit" to "Bit Browser", "google" to "Google", "duck" to "DuckDuckGo")
}

/** A small pixel glyph beside a result: clock for history, magnifier for the web, a block otherwise. */
class GlyphView(context: Context, private val rows: Array<String>, private val ink: Int) : View(context) {
    private val p = Paint().apply { color = ink }

    override fun onDraw(c: Canvas) {
        val u = minOf(width / rows[0].length, height / rows.size).toFloat()
        val ox = (width - u * rows[0].length) / 2
        val oy = (height - u * rows.size) / 2
        for (r in rows.indices) for (col in rows[r].indices) if (rows[r][col] == '#') {
            c.drawRect(ox + col * u, oy + r * u, ox + (col + 1) * u, oy + (r + 1) * u, p)
        }
    }

    companion object {
        val CLOCK = arrayOf(".###.", "#.#.#", "#.##.", "#...#", ".###.")
        val GLASS = arrayOf(".##..", "#..#.", "#..#.", ".##..", "....#")
        val DOT = arrayOf(".....", ".###.", ".###.", ".###.", ".....")
    }
}

/**
 * The search screen: the box, a row of apps (your usual ones, then matches as you type), and
 * below that your recent searches or the answers. Laid out top-down, or bottom-up by the keyboard.
 */
class BitterSearchView(
    context: Context,
    private val icons: IconCache,
    private val apps: () -> List<AppEntry>,
    private val onOpenApp: (AppEntry) -> Unit,
    private val engine: BitterSearch,
) : LinearLayout(context) {
    private val prefs = Prefs(context)
    private val accent = prefs.accent
    val field = EditText(context)
    private val appRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val results = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(results) }
    private var shownApps: List<AppEntry> = emptyList()
    private var answers: List<DrawerView.Answer> = emptyList()

    init {
        orientation = VERTICAL
        val pad = Pixel.dpi(context, 14)
        setPadding(pad, 0, pad, 0)
        val bar = FrameLayout(context).apply {
            val p = Pixel.dpi(context, 12)
            when (prefs.searchBar) {
                "underline" -> {
                    background = UnderlineDrawable(context, accent)
                    setPadding(Pixel.dpi(context, 4), p, Pixel.dpi(context, 4), p)
                }
                "plain" -> setPadding(Pixel.dpi(context, 4), p, Pixel.dpi(context, 4), p)
                else -> {
                    background = PanelDrawable(context, Pixel.PANEL, accent)
                    setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
                }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(PixelIcons.view(context, "search"), LayoutParams(Pixel.dpi(context, 26), Pixel.dpi(context, 26)).apply {
                    rightMargin = Pixel.dpi(context, 10)
                })
                addView(field.apply {
                    hint = "Search apps, people, settings, the web"
                    setHintTextColor(Pixel.MUTED)
                    setTextColor(Pixel.INK)
                    textSize = 17f
                    background = null
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_GO
                    setPadding(0, 0, 0, 0)
                }, LayoutParams(0, -2, 1f))
            })
        }
        val rowBox = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(appRow, LayoutParams(-1, Pixel.dpi(context, prefs.iconDp() + 40)))
            addView(View(context).apply { setBackgroundColor(withAlpha(Pixel.MUTED, 70)) }, LayoutParams(-1, Pixel.dpi(context, 2)))
        }
        val top = prefs.searchPosition != "bottom"
        val gap = Pixel.dpi(context, 10)
        if (top) {
            addView(bar, LayoutParams(-1, -2))
            if (prefs.searchApps > 0) addView(rowBox, LayoutParams(-1, -2).apply { topMargin = gap })
            addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        } else {
            // Bottom-up: results grow upwards from just above the apps and the box.
            scroll.isFillViewport = true
            results.gravity = Gravity.BOTTOM
            addView(scroll, LayoutParams(-1, 0, 1f))
            if (prefs.searchApps > 0) addView(rowBox, LayoutParams(-1, -2).apply { topMargin = gap })
            addView(bar, LayoutParams(-1, -2).apply { topMargin = gap; bottomMargin = gap })
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        field.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val q = field.text.toString()
                SearchHistory.add(context, q)
                val first = shownApps.firstOrNull()
                if (q.isNotBlank() && first != null && first.label.startsWith(q.trim(), true)) onOpenApp(first)
                else answers.firstOrNull()?.action?.invoke()
                true
            } else false
        }
        render()
    }

    fun showKeyboard() {
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** The apps for the row: matches when typing, your usual apps when not. */
    fun appsFor(q: String): List<AppEntry> {
        val all = apps()
        val n = prefs.searchApps
        if (n == 0) return emptyList()
        if (q.isBlank()) {
            val usage = Usage(context)
            val byId = all.associateBy { it.key.id() }
            val recent = usage.recent().mapNotNull { byId[it.id()] }
            val most = all.sortedByDescending { usage.count(it.key) }
            return (recent + most).distinctBy { it.key.id() }.take(n)
        }
        val t = q.trim()
        val starts = all.filter { it.label.startsWith(t, true) }
        val words = all.filter { a -> a !in starts && a.label.split(' ').any { it.startsWith(t, true) } }
        val within = all.filter { it !in starts && it !in words && it.label.contains(t, true) }
        return (starts + words + within).take(n)
    }

    fun render() {
        val q = field.text.toString()
        shownApps = appsFor(q)
        appRow.removeAllViews()
        for (a in shownApps) appRow.addView(AppIconView(context).apply {
            tagItem = a.key
            sprite = icons.get(a.key)
            label = a.label
            this.accent = this@BitterSearchView.accent
            iconSizeDp = prefs.iconDp()
            showLabel = true
            setOnClickListener {
                if (q.isNotBlank()) SearchHistory.add(context, q)
                onOpenApp(a)
            }
        }, LayoutParams(0, -1, 1f))
        // Keep empty slots, so the row's icons stay the same size.
        repeat((prefs.searchApps - shownApps.size).coerceAtLeast(0)) { appRow.addView(View(context), LayoutParams(0, -1, 1f)) }

        results.removeAllViews()
        if (q.isBlank()) {
            answers = emptyList()
            val history = if (prefs.searchHistory) SearchHistory.all(context) else emptyList()
            for (h in history) results.addView(row(GlyphView.CLOCK, h, null, onRemove = {
                SearchHistory.remove(context, h)
                render()
            }) { field.setText(h); field.setSelection(h.length) })
            if (history.isNotEmpty()) results.addView(Pixel.label(context, "CLEAR RECENT SEARCHES", 9f, Pixel.MUTED).apply {
                val p = Pixel.dpi(context, 12)
                setPadding(p, p, p, p)
                isClickable = true
                setOnClickListener {
                    SearchHistory.clear(context)
                    render()
                }
            })
            if (history.isEmpty()) results.addView(TextView(context).apply {
                text = if (prefs.searchHistory) "Your recent searches will show here. They stay on this phone." else "Recent searches are off."
                setTextColor(Pixel.MUTED)
                textSize = 14f
                setPadding(Pixel.dpi(context, 6), Pixel.dpi(context, 12), 0, 0)
            })
            return
        }
        answers = engine.answers(q, shownApps.size)
        for (a in answers) {
            val glyph = if (a.detail.startsWith("Web") || a.detail.startsWith("Bit Browser") || a.detail == "Google" || a.detail == "DuckDuckGo") GlyphView.GLASS else GlyphView.DOT
            results.addView(row(glyph, a.title, a.detail, onRemove = null) {
                SearchHistory.add(context, q)
                a.action()
            })
        }
    }

    private fun row(glyph: Array<String>, title: String, detail: String?, onRemove: (() -> Unit)?, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val p = Pixel.dpi(context, 8)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { onClick() }
            addView(FrameLayout(context).apply {
                background = PanelDrawable(context, Pixel.PANEL, withAlpha(Pixel.MUTED, 90), shadow = false)
                addView(GlyphView(context, glyph, Pixel.MUTED), FrameLayout.LayoutParams(Pixel.dpi(context, 16), Pixel.dpi(context, 16), Gravity.CENTER))
            }, LayoutParams(Pixel.dpi(context, 34), Pixel.dpi(context, 34)).apply { rightMargin = Pixel.dpi(context, 14) })
            val words = LinearLayout(context).apply { orientation = VERTICAL }
            words.addView(TextView(context).apply {
                text = title
                setTextColor(Pixel.INK)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (detail != null) words.addView(Pixel.label(context, detail.uppercase(), 8f, Pixel.MUTED).apply { setPadding(0, Pixel.dpi(context, 3), 0, 0) })
            addView(words, LayoutParams(0, -2, 1f))
            if (onRemove != null) addView(Pixel.label(context, "X", 10f, Pixel.MUTED).apply {
                val q = Pixel.dpi(context, 10)
                setPadding(q, q, q, q)
                isClickable = true
                setOnClickListener { onRemove() }
                contentDescription = "Remove from recent searches"
            })
        }
}

/** Just a line under the box, in the accent. */
class UnderlineDrawable(context: Context, private val ink: Int) : android.graphics.drawable.Drawable() {
    private val h = Pixel.dp(context, 2f)
    private val p = Paint().apply { color = ink }
    override fun draw(c: Canvas) {
        val r = bounds
        c.drawRect(r.left.toFloat(), r.bottom - h, r.right.toFloat(), r.bottom.toFloat(), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** The Bitter Searcher bar at the bottom of home (under the dock), drawn like its widget. */
class HomeSearchBar(context: Context) : View(context) {
    var accent = Pixel.ACCENTS[0]
        set(v) { field = v; bmp = null; invalidate() }
    private var bmp: android.graphics.Bitmap? = null

    init {
        isClickable = true
        contentDescription = "Bitter Searcher"
        setOnClickListener {
            context.startActivity(android.content.Intent(context, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_MODE, "search"))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bmp = null
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        val b = bmp ?: SearchWidgets.render(context, width, height, "BITTER SEARCHER",
            arrayOf(".###..", "#...#.", "#...#.", "#...#.", ".###..", "....##", ".....#"), accent, PixelIcons.get(context, "search")).also { bmp = it }
        c.drawBitmap(b, 0f, 0f, null)
    }
}
