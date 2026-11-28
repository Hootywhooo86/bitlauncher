package com.hootywhooo86.bitlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release20Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun at(m: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { set(2026, m - 1, d, h, 0, 0) }.timeInMillis

    private fun shoot(b: Bitmap, name: String) = System.getenv("SHOT_DIR")?.let { dir ->
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun seasonsFollowTheDate() {
        assertEquals(Seasons.AUTUMN, Seasons.now(at(10, 15)))
        assertEquals(Seasons.AUTUMN, Seasons.now(at(9, 22)))
        assertEquals(Seasons.SUMMER, Seasons.now(at(9, 21)))
        assertEquals(Seasons.WINTER, Seasons.now(at(12, 24)))
        assertEquals(Seasons.WINTER, Seasons.now(at(3, 19)))
        assertEquals(Seasons.SPRING, Seasons.now(at(3, 20)))
        assertEquals(Seasons.SUMMER, Seasons.now(at(7, 4)))
        assertEquals(Seasons.NEW_YEAR, Seasons.now(at(12, 31, 22)))
        assertEquals(Seasons.NEW_YEAR, Seasons.now(at(1, 1, 1)))
        assertEquals(Seasons.WINTER, Seasons.now(at(1, 1, 9)))
    }

    @Test
    fun visitorsOnlyComeAtTheRightTime() {
        val fx = SceneFx().apply { size(160, 320); seasonsOn = false }
        assertFalse("nothing by day", fx.maybeVisit(night = false, dawn = false, scene = "city"))
        assertFalse("no deer in the city", fx.maybeVisit(night = false, dawn = true, scene = "city"))
        assertTrue(fx.maybeVisit(night = false, dawn = true, scene = "railway"))
        assertTrue(fx.busy(false))
        assertFalse("one at a time", fx.maybeVisit(night = true, dawn = false, scene = "railway"))
        repeat(1000) { fx.step(false) }
        assertFalse("and it leaves", fx.busy(false))
        fx.visitorsOn = false
        assertFalse(fx.maybeVisit(night = true, dawn = false, scene = "railway"))
        // Summer is still unless it's night (fireflies).
        fx.seasonsOn = true
        fx.season = Seasons.SUMMER
        assertFalse(fx.busy(false))
        assertTrue(fx.busy(true))
    }

    @Test
    fun seasonsAndVisitorsSettingsSurviveABackup() {
        val prefs = Prefs(ctx)
        prefs.sceneSeasons = false
        prefs.sceneVisitors = false
        val json = prefs.toJson()
        prefs.sceneSeasons = true
        prefs.sceneVisitors = true
        prefs.restore(json)
        assertFalse(prefs.sceneSeasons)
        assertFalse(prefs.sceneVisitors)
        prefs.sceneSeasons = true
        prefs.sceneVisitors = true
    }

    @Test
    fun seasonSheet() {
        // Each season over the railway at a fitting time, after the effects have run a while.
        val seasons = listOf(Seasons.AUTUMN to "dusk", Seasons.WINTER to "day", Seasons.SPRING to "dawn", Seasons.SUMMER to "night", Seasons.NEW_YEAR to "night")
        val w = 540
        val h = 1100
        val sheet = Bitmap.createBitmap(w * seasons.size, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        seasons.forEachIndexed { i, (season, time) ->
            val v = SceneView(ctx).apply { scene = "railway"; timeOfDay = time }
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.fx.season = season
            if (season == Seasons.SPRING) v.fx.maybeVisit(night = false, dawn = true, scene = "railway")
            if (season == Seasons.SUMMER) v.fx.maybeVisit(night = true, dawn = false, scene = "railway")
            repeat(if (season == Seasons.SPRING) 60 else 120) { v.fx.step(time == "night") }
            if (season == Seasons.NEW_YEAR) {
                var n = 0
                while (v.fx.bursts.none { it[2] in 9f..13f } && n++ < 2000) v.fx.step(true)
                assertTrue("a firework goes off", v.fx.bursts.any { it[2] in 9f..13f })
            }
            Thread.sleep(800)
            shadowOf(Looper.getMainLooper()).idle()
            c.save()
            c.translate((i * w).toFloat(), 0f)
            v.draw(c)
            c.restore()
        }
        shoot(sheet, "seasons.png")
    }

    @Test
    fun snowSettlesOnTheDockInWinter() {
        val dock = DockDrawable(ctx, "panel")
        val b = Bitmap.createBitmap(600, 140, Bitmap.Config.ARGB_8888)
        dock.setBounds(0, 20, 600, 140)
        dock.snow = true
        dock.draw(Canvas(b))
        assertEquals(0xFFF4F6FF.toInt(), b.getPixel(300, 22))
        shoot(b, "dock-snow.png")
    }

    @Test
    fun chargingIsFinerWithFineDetail() {
        val v = ChargeView(ctx).apply { fine = true; style = "battery"; level = 60; charging = true }
        v.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        assertEquals(26 * 12, v.measuredHeight) // 960/160 = 6 per fine pixel, 12 per grid pixel
        val chunky = ChargeView(ctx).apply { fine = false }
        chunky.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        assertEquals(26 * 12, chunky.measuredHeight) // same size, coarser pixels

        val sheet = Bitmap.createBitmap(960, 340 * ChargeView.STYLES.size, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Pixel.BG)
        val c = Canvas(sheet)
        ChargeView.STYLES.forEachIndexed { row, (key, _) ->
            val cv = ChargeView(ctx).apply { fine = true; style = key; level = 65; charging = true }
            cv.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            cv.layout(0, 0, cv.measuredWidth, cv.measuredHeight)
            c.save()
            c.translate(0f, row * 340f + 14f)
            cv.draw(c)
            c.restore()
        }
        shoot(sheet, "charge-fine.png")
    }
}
