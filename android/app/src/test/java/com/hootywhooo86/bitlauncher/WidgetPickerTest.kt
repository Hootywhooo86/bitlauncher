package com.hootywhooo86.bitlauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ImageView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The picker shows a real preview for each widget, not just its name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPickerTest {
    private fun provider(name: String, cls: String, w: Int, h: Int, preview: Int): AppWidgetProviderInfo {
        val ctx = RuntimeEnvironment.getApplication()
        return AppWidgetProviderInfo().apply {
            provider = ComponentName(ctx.packageName, cls)
            label = name
            minWidth = w
            minHeight = h
            previewImage = preview
        }.also { info ->
            // Hidden field; Android fills it in for real providers.
            AppWidgetProviderInfo::class.java.getDeclaredField("providerInfo").apply { isAccessible = true }.set(info, ActivityInfo().apply {
                packageName = ctx.packageName
                this.name = cls
                applicationInfo = ctx.applicationInfo
                nonLocalizedLabel = name
            })
        }
    }

    @Test
    fun cardsShowPreviews() {
        val ctx = RuntimeEnvironment.getApplication()
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        val providers = listOf(
            provider("Big clock", ".Clock", 600, 200, R.drawable.ic_fg),
            provider("Mini", ".Mini", 150, 150, R.drawable.ic_fg),
            provider("Plain", ".Plain", 300, 150, 0),
        )
        val picker = WidgetPicker(home, Pixel.ACCENTS[0], providers, { 4 to 2 }, 200f to 230f, IconCache(ctx)) {}
        picker.show()
        shadowOf(Looper.getMainLooper()).idle()
        val root = picker.window!!.decorView
        val images = mutableListOf<ImageView>()
        fun walk(v: View) {
            if (v is ImageView && v.drawable != null) images.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertTrue("previews loaded: ${images.size}", images.size >= 2)

        System.getenv("SHOT_DIR")?.let { dir ->
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(0xFF3B2F6B.toInt()); root.draw(it) }
            File(dir, "picker.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
