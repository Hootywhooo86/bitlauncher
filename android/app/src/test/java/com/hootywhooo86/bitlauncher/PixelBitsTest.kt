package com.hootywhooo86.bitlauncher

import android.app.Notification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelBitsTest {
    private fun note(
        pkg: String,
        title: String = "Sam",
        text: String = "see you at 6",
        visibility: Int = Notification.VISIBILITY_PUBLIC,
        publicTitle: String? = null,
        ongoing: Boolean = false,
        summary: Boolean = false,
        posted: Long = 1,
    ) = Note("$pkg:$posted", pkg, 0, title, text, visibility, publicTitle, null, ongoing, summary, posted)

    @Test
    fun appNamesOnlyByDefaultShowsNoText() {
        val lines = NotePrivacy.lockLines(listOf(note("chat")), NotePrivacy.APPS_ONLY, systemHidesSensitive = false)
        assertEquals(1, lines.size)
        assertNull(lines[0].title)
        assertNull(lines[0].text)
    }

    @Test
    fun secretNotificationsNeverShowEvenTheApp() {
        val lines = NotePrivacy.lockLines(listOf(note("bank", visibility = Notification.VISIBILITY_SECRET)), NotePrivacy.FULL, false)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun privateNotificationsShowInFullWhenThePhoneAllowsSensitiveContent() {
        // Like Android's own lock screen: Messenger and most chat apps mark messages private.
        val chat = note("chat", visibility = Notification.VISIBILITY_PRIVATE)
        val shown = NotePrivacy.lockLines(listOf(chat), NotePrivacy.FULL, false).single()
        assertEquals("Sam", shown.title)
        assertNotNull(shown.text)
        // The phone's "hide sensitive content" still hides it.
        assertNull(NotePrivacy.lockLines(listOf(chat), NotePrivacy.FULL, true).single().title)
    }

    @Test
    fun phonesHideSensitiveSettingWins() {
        val lines = NotePrivacy.lockLines(listOf(note("chat")), NotePrivacy.FULL, systemHidesSensitive = true)
        assertNull(lines.single().title)
    }

    @Test
    fun titlesLevelShowsTitleButNotText() {
        val line = NotePrivacy.lockLines(listOf(note("chat")), NotePrivacy.TITLES, false).single()
        assertEquals("Sam", line.title)
        assertNull(line.text)
    }

    @Test
    fun oneRowPerAppNewestFirstSkippingOngoingAndSummaries() {
        val notes = listOf(
            note("chat", posted = 1), note("chat", title = "Alex", posted = 5),
            note("mail", posted = 3),
            note("music", ongoing = true, posted = 9),
            note("chat", summary = true, posted = 10),
        )
        val lines = NotePrivacy.lockLines(notes, NotePrivacy.FULL, false)
        assertEquals(listOf("chat", "mail"), lines.map { it.pkg })
        assertEquals(2, lines[0].count)
        assertEquals("Alex", lines[0].title)
    }

    @Test
    fun clockGlyphsAreAllFiveBySeven() {
        assertEquals(10, PixelGlyphs.DIGITS.size)
        for (d in PixelGlyphs.DIGITS) {
            assertEquals(PixelGlyphs.H, d.size)
            d.forEach { row -> assertEquals(PixelGlyphs.W, row.length); assertTrue(row.all { it == '#' || it == '.' }) }
        }
    }

    @Test
    fun clockDigitsFollowTwelveAndTwentyFourHour() {
        assertArrayEquals(intArrayOf(0, 7, 0, 5), PixelGlyphs.digitsFor(7, 5, use24 = true))
        assertArrayEquals(intArrayOf(1, 9, 3, 0), PixelGlyphs.digitsFor(19, 30, use24 = true))
        assertArrayEquals(intArrayOf(0, 7, 3, 0), PixelGlyphs.digitsFor(19, 30, use24 = false))
        assertArrayEquals(intArrayOf(1, 2, 0, 0), PixelGlyphs.digitsFor(0, 0, use24 = false))
        assertArrayEquals(intArrayOf(1, 2, 1, 5), PixelGlyphs.digitsFor(12, 15, use24 = false))
    }

    @Test
    fun gesturesFinishAtTheFingersPace() {
        // Slow release, most of the way left: the longest, capped so it never drags.
        assertEquals(300L, Ease.finishDuration(1000f, 1000f, 0f))
        // A hard flick finishes quickly, but never faster than the eye can follow.
        assertEquals(120L, Ease.finishDuration(1000f, 1000f, 20000f))
        // Almost there: short.
        assertTrue(Ease.finishDuration(100f, 1000f, 0f) <= 130L)
    }
}
