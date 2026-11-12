package com.hootywhooo86.bitlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinnedTest {
    private val site = PinnedShortcuts.key("com.android.chrome", "https://nhl.com", 0)

    @Test
    fun shortcutsAreKeysThatGoAnywhereAnAppCan() {
        assertTrue(PinnedShortcuts.isShortcut(site))
        assertFalse(PinnedShortcuts.isShortcut(AppKey("com.android.chrome", "Main", 0)))
        assertEquals("https://nhl.com", PinnedShortcuts.idOf(site))

        val layout = HomeLayout.empty(GridSize.MEDIUM)
        layout.pages[0].add(AppItem(site, 0, 0))
        layout.pages[0].add(FolderItem("Hockey", mutableListOf(site, AppKey("com.nhl", "Main", 0)), 1, 0))
        layout.setDock(0, AppItem(site, 0, 0))
        val back = HomeLayout.fromJson(JSONObject(layout.toJson().toString()))
        assertEquals(site, (back.pages[0][0] as AppItem).app)
        assertTrue((back.pages[0][1] as FolderItem).apps.contains(site))
        assertEquals(site, back.dockApp(0))
    }

    @Test
    fun labelsAreRememberedAndRenamesWin() {
        val ctx = RuntimeEnvironment.getApplication()
        PinnedShortcuts.remember(ctx, site, "NHL")
        val apps = AppList(ctx, IconCache(ctx)) {}
        assertEquals("NHL", apps.label(site))
        Prefs(ctx).renamed = mapOf(site.id() to "Scores")
        assertEquals("Scores", apps.label(site))
        Prefs(ctx).renamed = emptyMap()
    }

    @Test
    fun aShortcutIsGoneOnlyWhenItsAppIs() {
        val ctx = RuntimeEnvironment.getApplication()
        val apps = AppList(ctx, IconCache(ctx)) {}
        // No Chrome on this test phone: its pinned shortcut counts as gone, so it gets cleaned up.
        assertFalse(apps.exists(site))
    }
}
