package com.hootywhooo86.bitlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun sums() {
        assertEquals("96", Search.calculate("12*8"))
        assertEquals("96", Search.calculate("12 x 8"))
        assertEquals("3.5", Search.calculate("(3+4)/2"))
        assertEquals("1024", Search.calculate("2^10"))
        assertEquals("512", Search.calculate("2^3^2"))
        assertEquals("-2", Search.calculate("-5+3"))
        assertEquals("12", Search.calculate("15% of 80"))
        assertEquals("40", Search.calculate("80*50%"))
        assertEquals("0.3333333333", Search.calculate("1/3"))
        assertEquals("1500", Search.calculate("1,000+500"))
    }

    @Test
    fun notSums() {
        assertNull(Search.calculate("42"))
        assertNull(Search.calculate("-7"))
        assertNull(Search.calculate("spotify"))
        assertNull(Search.calculate("1/0"))
        assertNull(Search.calculate("(1+2"))
        assertNull(Search.calculate("128bit"))
    }

    @Test
    fun conversions() {
        assertEquals("3.11 mi", Search.convert("5 km to mi"))
        assertEquals("-4 °F", Search.convert("-20 c in f"))
        assertEquals("68.04 kg", Search.convert("150 lb to kg"))
        assertEquals("6.56 ft", Search.convert("2 metres in feet"))
        assertEquals("62.14 mph", Search.convert("100 kmh to mph"))
        assertNull(Search.convert("5 km to kg")) // different kinds
        assertNull(Search.convert("5 bananas to mi"))
        assertNull(Search.convert("km to mi"))
    }

    @Test
    fun settingsPages() {
        assertEquals("Wi-Fi", Search.settings("wifi").first().label)
        assertEquals("Display & brightness", Search.settings("dark").first().label)
        assertTrue(Search.settings("blue").any { it.label == "Bluetooth" })
        assertTrue(Search.settings("b").isEmpty())
        assertTrue(Search.settings("zzzz").isEmpty())
    }
}
