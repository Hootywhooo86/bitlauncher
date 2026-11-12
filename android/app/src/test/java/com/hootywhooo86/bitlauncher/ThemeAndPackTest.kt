package com.hootywhooo86.bitlauncher

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeAndPackTest {
    @Test
    fun readsAnIconPacksAppFilter() {
        val xml = """
            <resources>
              <item component="ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}" drawable="spotify" />
              <item component="ComponentInfo{com.google.android.gm/.ConversationListActivityGmail}" drawable="gmail" />
              <item component=":BROKEN" drawable="nope" />
              <calendar prefix="cal_" />
            </resources>
        """.trimIndent()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val map = IconPack.parse(parser)
        assertEquals("spotify", map["com.spotify.music/com.spotify.music.MainActivity"])
        assertEquals("spotify", map["com.spotify.music"]) // package fallback
        assertEquals("gmail", map["com.google.android.gm/com.google.android.gm.ConversationListActivityGmail"])
        assertNull(map[":BROKEN"])
    }

    @Test
    fun switchingThemeRecolours() {
        val ctx = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0xFF0B0B16.toInt(), Pixel.BG)
        Prefs(ctx).apply { theme = "gameboy"; touch() }
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0xFF0F380F.toInt(), Pixel.BG)
        Prefs(ctx).apply { theme = "default"; touch() }
        Pixel.load(ctx)
    }
}
