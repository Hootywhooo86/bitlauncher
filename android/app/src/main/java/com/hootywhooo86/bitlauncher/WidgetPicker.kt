package com.hootywhooo86.bitlauncher

import android.app.Dialog
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.Collator

/** A plain list of every widget on the phone, grouped by app, with the cells each one takes. */
class WidgetPicker(
    context: Context,
    accent: Int,
    providers: List<AppWidgetProviderInfo>,
    cells: (AppWidgetProviderInfo) -> Pair<Int, Int>,
    onPick: (AppWidgetProviderInfo) -> Unit,
) : Dialog(context) {
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pm = context.packageManager
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Pixel.dpi(context, 16)
            setPadding(p, p, p, p)
        }
        list.addView(Pixel.label(context, "WIDGETS", 14f, accent).apply {
            setPadding(0, 0, 0, Pixel.dpi(context, 12))
        })
        val collator = Collator.getInstance()
        val byApp = providers.groupBy { it.provider.packageName }
            .map { (pkg, infos) ->
                val appLabel = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                appLabel to infos
            }
            .sortedWith { a, b -> collator.compare(a.first, b.first) }
        for ((appLabel, infos) in byApp) {
            list.addView(Pixel.label(context, appLabel.uppercase(), 10f, Pixel.MUTED).apply {
                setPadding(0, Pixel.dpi(context, 12), 0, Pixel.dpi(context, 4))
            })
            for (info in infos) {
                val (w, h) = cells(info)
                val name = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: appLabel
                list.addView(Pixel.button(context, "$name  ${w}x$h", accent) {
                    dismiss()
                    onPick(info)
                }.apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Pixel.dpi(context, 6)
                    }
                })
            }
        }
        val scroll = ScrollView(context).apply {
            addView(list)
            background = PanelDrawable(context, Pixel.BG, accent)
        }
        setContentView(scroll)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.75f).toInt(),
        )
    }
}
