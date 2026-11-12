package com.hootywhooo86.bitlauncher

import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RemoteViews
import java.text.Collator

/**
 * Every widget on the phone, grouped by app, each shown as a preview of what it really looks
 * like at the size it will take. Previews load one per frame, so the list opens instantly.
 */
class WidgetPicker(
    context: Context,
    private val accent: Int,
    providers: List<AppWidgetProviderInfo>,
    private val cells: (AppWidgetProviderInfo) -> Pair<Int, Int>,
    /** One home-screen cell, in pixels, so previews keep the widget's real proportions. */
    private val cellPx: Pair<Float, Float>,
    private val icons: IconCache,
    private val onPick: (AppWidgetProviderInfo) -> Unit,
) : Dialog(context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<PreviewBox, AppWidgetProviderInfo>>()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pm = context.packageManager
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Pixel.dpi(context, 16)
            setPadding(p, p, p, p)
        }
        list.addView(Pixel.label(context, "WIDGETS", 14f, accent).apply {
            setPadding(0, 0, 0, Pixel.dpi(context, 4))
        })
        list.addView(Pixel.label(context, "TAP ONE TO ADD IT", 9f, Pixel.MUTED).apply {
            setPadding(0, 0, 0, Pixel.dpi(context, 8))
        })
        val collator = Collator.getInstance()
        val byApp = providers.groupBy { it.provider.packageName to it.profile }
            .map { (key, infos) ->
                val appLabel = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(key.first, 0)).toString()
                } catch (e: Exception) {
                    key.first
                }
                Triple(key, appLabel, infos)
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
        val users = context.getSystemService(android.os.UserManager::class.java)
        for ((key, appLabel, infos) in byApp) {
            list.addView(appHeader(appLabel, icons.getForPackage(key.first, users.getSerialNumberForUser(key.second))))
            for (info in infos) list.addView(card(info, appLabel))
        }
        val scroll = ScrollView(context).apply {
            addView(list)
            background = PanelDrawable(context, Pixel.BG, accent)
        }
        setContentView(scroll)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.82f).toInt(),
        )
        setOnDismissListener { main.removeCallbacksAndMessages(null) }
        main.post(::loadNext)
    }

    private fun appHeader(label: String, sprite: android.graphics.Bitmap?): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Pixel.dpi(context, 16), 0, Pixel.dpi(context, 8))
            if (sprite != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(BitmapDrawable(resources, sprite).apply { paint.isFilterBitmap = false })
                }, LinearLayout.LayoutParams(Pixel.dpi(context, 24), Pixel.dpi(context, 24)).apply {
                    rightMargin = Pixel.dpi(context, 10)
                })
            }
            addView(Pixel.label(context, label.uppercase(), 10f, Pixel.MUTED))
        }

    private fun card(info: AppWidgetProviderInfo, appLabel: String): View {
        val (w, h) = cells(info)
        val name = info.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: appLabel
        val box = PreviewBox(context, (w * cellPx.first).toInt().coerceAtLeast(1), (h * cellPx.second).toInt().coerceAtLeast(1))
        pending.add(box to info)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = PanelDrawable(context, Pixel.PANEL, withAlpha(Pixel.MUTED, 140))
            val p = Pixel.dpi(context, 12)
            setPadding(p, p, p + Pixel.dpi(context, 2), p + Pixel.dpi(context, 2))
            isClickable = true
            foreground = context.getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener {
                dismiss()
                onPick(info)
            }
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Pixel.dpi(context, 10), 0, 0)
                addView(Pixel.label(context, name, 11f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(Pixel.label(context, "${w}x$h", 10f, accent))
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Pixel.dpi(context, 10) }
        }
    }

    /** One preview per frame: the dialog stays smooth while the rest fill in. */
    private fun loadNext() {
        val (box, info) = pending.removeFirstOrNull() ?: return
        box.show(previewFor(info, box))
        main.post(::loadNext)
    }

    /**
     * The best picture of the widget the phone can give us:
     * 1. Android 15's generated preview (the widget's real look, rendered by its app),
     * 2. the app's preview layout (Android 12+),
     * 3. the app's preview image,
     * 4. the widget's own icon, if nothing else exists.
     */
    private fun previewFor(info: AppWidgetProviderInfo, box: PreviewBox): View? {
        val density = context.resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                AppWidgetManager.getInstance(context)
                    .getWidgetPreview(info.provider, info.profile, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                    ?.let { return it.apply(context, box) }
            } catch (e: Exception) {
                // Fall through to the next kind of preview.
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
            try {
                return RemoteViews(info.provider.packageName, info.previewLayout).apply(context, box)
            } catch (e: Exception) {
            }
        }
        try {
            info.loadPreviewImage(context, density)?.let { d ->
                return ImageView(context).apply {
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            }
        } catch (e: Exception) {
        }
        return try {
            info.loadIcon(context, density)?.let { d ->
                ImageView(context).apply {
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Lays a preview out at the widget's real size on the home screen, then scales it down to fit,
 * so text and spacing look the way they will once placed.
 */
private class PreviewBox(context: Context, private val realW: Int, private val realH: Int) : FrameLayout(context) {
    private val maxH = Pixel.dpi(context, 170)

    init {
        clipChildren = true
        // While the preview loads: the widget's footprint, so the list doesn't jump.
        background = PanelDrawable(context, withAlpha(Pixel.BG, 160), withAlpha(Pixel.MUTED, 60), shadow = false)
    }

    fun show(preview: View?) {
        removeAllViews()
        if (preview == null) {
            addView(Pixel.label(context, "NO PREVIEW", 9f, Pixel.MUTED).apply { gravity = Gravity.CENTER })
            return
        }
        background = null
        addView(preview)
        if (Pixel.motion(context)) {
            preview.alpha = 0f
            preview.animate().alpha(1f).setDuration(200).setInterpolator(Ease.enter).start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = minOf((w.toLong() * realH / realW).toInt(), maxH)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is android.widget.TextView) {
                child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            } else {
                child.measure(MeasureSpec.makeMeasureSpec(realW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(realH, MeasureSpec.EXACTLY))
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is android.widget.TextView) {
                child.layout(0, 0, w, h)
                continue
            }
            child.layout(0, 0, realW, realH)
            val scale = minOf(w / realW.toFloat(), h / realH.toFloat())
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
            child.translationX = (w - realW * scale) / 2f
            child.translationY = (h - realH * scale) / 2f
        }
    }
}
