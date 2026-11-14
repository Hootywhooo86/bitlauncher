package com.hootywhooo86.bitlauncher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Colour maths for the picker and the custom theme, kept plain so it can be tested. */
object Colors {
    /** "#A78BFA" (no alpha). */
    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** "#a78bfa", "A78BFA" or "#abc" to an opaque colour; null if it isn't one. */
    fun parse(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return (0xFF000000 or t.toLong(16)).toInt()
    }

    /** WCAG contrast ratio, 1 (none) to 21. */
    fun contrast(a: Int, b: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        fun lum(c: Int) = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
        val la = lum(a)
        val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(h, s, v))

    /** The picker's grid for one hue: rows from bright to dark, columns from grey to full colour. */
    fun grid(hue: Float, rows: Int = 6, cols: Int = 6): List<List<Int>> = (0 until rows).map { r ->
        val v = 1f - r * (0.85f / (rows - 1))
        (0 until cols).map { c -> hsv(hue, c / (cols - 1f), v) }
    }

    val HUES = (0 until 12).map { it * 30f }
}

/**
 * Pick any colour, in pixels: a strip of hues, a grid of shades for the chosen hue, and a hex
 * code for exact colours. [onPick] gets the colour when USE is tapped.
 */
class PixelColorPicker(context: Context, private val title: String, initial: Int, private val onPick: (Int) -> Unit) : Dialog(context) {
    private var color = initial
    private var hue = FloatArray(3).also { Color.colorToHSV(initial, it) }[0]
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = View(context)
    private val hexField = EditText(context)
    private var typing = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = Pixel.dpi(context, 16)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, Pixel.INK)
            setPadding(pad, pad, pad + Pixel.dpi(context, 2), pad + Pixel.dpi(context, 2))
        }
        box.addView(Pixel.label(context, title.uppercase(), 11f, Pixel.INK), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
        // Hues, plus greys at the end.
        box.addView(LinearLayout(context).apply {
            for (h in Colors.HUES) addView(swatch(Colors.hsv(h, 0.75f, 0.95f)) { hue = h; renderGrid() }, LinearLayout.LayoutParams(0, Pixel.dpi(context, 28), 1f))
            addView(swatch(Color.GRAY) { hue = -1f; renderGrid() }, LinearLayout.LayoutParams(0, Pixel.dpi(context, 28), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 10) })
        box.addView(grid, LinearLayout.LayoutParams(-1, -2))
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 12))
            addView(preview, LinearLayout.LayoutParams(Pixel.dpi(context, 48), Pixel.dpi(context, 36)))
            addView(hexField.apply {
                typeface = Pixel.font(context)
                textSize = 14f
                setTextColor(Pixel.INK)
                setHintTextColor(Pixel.MUTED)
                hint = "#RRGGBB"
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(7))
                background = PanelDrawable(context, Pixel.BG, Pixel.MUTED, shadow = false)
                val p = Pixel.dpi(context, 8)
                setPadding(p, p, p, p)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (typing) return
                        Colors.parse(s.toString())?.let { set(it, fromHex = true) }
                    }
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Pixel.dpi(context, 10) })
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(LinearLayout(context).apply {
            addView(Pixel.button(context, "CANCEL", Pixel.MUTED) { dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Pixel.button(context, "USE", Pixel.INK) {
                onPick(color)
                dismiss()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Pixel.dpi(context, 8) })
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(box)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(min(context.resources.displayMetrics.widthPixels - Pixel.dpi(context, 32), Pixel.dpi(context, 420)), ViewGroup.LayoutParams.WRAP_CONTENT)
        renderGrid()
        set(initial, fromHex = false)
    }

    private fun swatch(c: Int, onClick: () -> Unit) = View(context).apply {
        background = PanelDrawable(context, c, if (abs(c - color) == 0) Pixel.INK else Pixel.SHADOW, shadow = false)
        setOnClickListener { onClick() }
    }

    private fun renderGrid() {
        grid.removeAllViews()
        val rows = if (hue < 0) listOf((0 until 6).map { c -> Colors.hsv(0f, 0f, 1f - c * 0.19f) }) else Colors.grid(hue)
        for (row in rows) grid.addView(LinearLayout(context).apply {
            for (c in row) addView(swatch(c) { set(c, fromHex = false) }, LinearLayout.LayoutParams(0, Pixel.dpi(context, 30), 1f).apply {
                rightMargin = Pixel.dpi(context, 3)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 3) })
    }

    private fun set(c: Int, fromHex: Boolean) {
        color = c or 0xFF000000.toInt()
        preview.background = PanelDrawable(context, color, Pixel.INK, shadow = false)
        if (!fromHex) {
            typing = true
            hexField.setText(Colors.hex(color))
            typing = false
        }
    }

    /** For tests: what USE would hand back now. */
    val current get() = color
}
