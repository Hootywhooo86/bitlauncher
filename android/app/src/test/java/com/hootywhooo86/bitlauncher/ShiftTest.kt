package com.hootywhooo86.bitlauncher

import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShiftTest {
    private fun at(day: Int, h: Int, m: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 8) // a Sunday
        add(Calendar.DAY_OF_MONTH, day - Calendar.SUNDAY)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private val weekdays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)

    @Test
    fun dayShift() {
        assertTrue(Shift.isWorkTime(at(Calendar.MONDAY, 10), weekdays, 9 * 60, 17 * 60))
        assertFalse(Shift.isWorkTime(at(Calendar.MONDAY, 17), weekdays, 9 * 60, 17 * 60))
        assertFalse(Shift.isWorkTime(at(Calendar.SATURDAY, 10), weekdays, 9 * 60, 17 * 60))
    }

    @Test
    fun overnightShiftCountsFromTheDayItStarts() {
        val fri = setOf(Calendar.FRIDAY)
        assertTrue(Shift.isWorkTime(at(Calendar.FRIDAY, 23), fri, 22 * 60, 6 * 60))
        assertTrue(Shift.isWorkTime(at(Calendar.SATURDAY, 3), fri, 22 * 60, 6 * 60)) // still Friday's shift
        assertFalse(Shift.isWorkTime(at(Calendar.FRIDAY, 3), fri, 22 * 60, 6 * 60)) // Thursday night wasn't a shift
        assertFalse(Shift.isWorkTime(at(Calendar.SATURDAY, 7), fri, 22 * 60, 6 * 60))
    }

    @Test
    fun aHandSwitchHoldsUntilTheScheduleChanges() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.shiftSchedule = true
        prefs.shiftDays = weekdays
        prefs.shiftStart = 9 * 60
        prefs.shiftEnd = 17 * 60
        val monMorning = at(Calendar.MONDAY, 10).timeInMillis
        assertEquals(Shift.WORK, Shift.current(prefs, monMorning))
        Shift.hold(prefs, Shift.HOME, monMorning)
        assertEquals(Shift.HOME, Shift.current(prefs, monMorning + 3600_000))
        assertEquals(at(Calendar.MONDAY, 17).timeInMillis, prefs.modeHeldUntil)
        // After five the schedule says Home anyway; on Tuesday morning it says Work again.
        assertEquals(Shift.WORK, Shift.current(prefs, at(Calendar.TUESDAY, 10).timeInMillis))
    }

    @Test
    fun railtimeCanSwitchTheLayout() {
        val ctx = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        ShiftReceiver().onReceive(ctx, Intent(Shift.ACTION_SET_MODE).putExtra("mode", "work"))
        ShiftReceiver().onReceive(ctx, Intent(Shift.ACTION_SET_MODE).putExtra("mode", "nonsense")) // ignored
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Shift.WORK, Prefs(ctx).mode)
        assertTrue(java.io.File(ctx.filesDir, "layout.work.json").exists())
    }

    @Test
    fun familyAppsCanReadTheTheme() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).accent = Pixel.ACCENTS[1]
        val provider = Robolectric.setupContentProvider(FamilyThemeProvider::class.java, FamilyTheme.AUTHORITY)
        provider.query(Uri.parse("content://${FamilyTheme.AUTHORITY}"), null, null, null, null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(Pixel.ACCENTS[1], c.getInt(c.getColumnIndexOrThrow("accent")))
            assertEquals("default", c.getString(c.getColumnIndexOrThrow("theme")))
        }
    }
}
