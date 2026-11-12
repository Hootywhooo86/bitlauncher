package com.hootywhooo86.bitlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * A standard Android icon pack (the kind made for Nova and other launchers): a map from app
 * components to drawables, read from the pack's appfilter.xml. Icons it has are used instead of
 * the app's own, then pixelated like everything else. Apps the pack doesn't cover keep theirs.
 */
class IconPack private constructor(val pkg: String, private val res: Resources, private val map: Map<String, String>) {

    /** The pack's icon for an app, or null if it doesn't have one. */
    fun iconFor(component: ComponentName): Drawable? {
        val name = map[component.flattenToString()] ?: map[component.packageName] ?: return null
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return try {
            res.getDrawable(id, null)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** The intents icon packs declare so launchers can find them. */
        private val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME")

        /** Installed icon packs as (package, name), A–Z. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            return PACK_ACTIONS.flatMap { action ->
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA).map { it.activityInfo.packageName }
            }.distinct().map { pkg ->
                pkg to try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            }.sortedBy { it.second.lowercase() }
        }

        /** Load a pack. Null if it's gone or its icon list can't be read. */
        fun load(context: Context, pkg: String): IconPack? {
            if (pkg.isEmpty()) return null
            return try {
                val res = context.packageManager.getResourcesForApplication(pkg)
                val parser = openAppFilter(res, pkg) ?: return null
                IconPack(pkg, res, parse(parser))
            } catch (e: Exception) {
                null
            }
        }

        private fun openAppFilter(res: Resources, pkg: String): XmlPullParser? {
            @Suppress("DiscouragedApi")
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id != 0) return res.getXml(id)
            return try {
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `<item component="ComponentInfo{pkg/cls}" drawable="name"/>` entries, keyed by the
         * flattened component. A bare package also maps, so an app whose activity moved still matches.
         */
        fun parse(parser: XmlPullParser): Map<String, String> {
            val map = HashMap<String, String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val flat = component?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                    if (flat != null && drawable != null && '/' in flat) {
                        val cn = ComponentName.unflattenFromString(flat)
                        if (cn != null) {
                            map[cn.flattenToString()] = drawable
                            map.putIfAbsent(cn.packageName, drawable)
                        }
                    }
                }
                event = parser.next()
            }
            return map
        }
    }
}
