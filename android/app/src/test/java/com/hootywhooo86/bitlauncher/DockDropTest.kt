package com.hootywhooo86.bitlauncher

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.hootywhooo86.bitlauncher.LauncherActivity.DragInfo
import com.hootywhooo86.bitlauncher.LauncherActivity.DropTarget
import com.hootywhooo86.bitlauncher.LauncherActivity.Source

/** Apps (and folders) going into the dock, from everywhere they can come from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockDropTest {
    private fun app(n: Int) = AppKey("demo.app$n", "Main", 0)
    private val dockApp = AppKey("demo.phone", "Main", 0)

    private fun home(): Pair<LauncherActivity, HomeLayout> {
        val ctx = RuntimeEnvironment.getApplication()
        val start = HomeLayout.empty(GridSize.MEDIUM)
        start.pages[0].add(FolderItem("Games", mutableListOf(app(0), app(1), app(2), app(3)), 0, 0))
        start.pages[0].add(AppItem(app(9), 1, 0))
        start.setDock(0, AppItem(dockApp, 0, 0))
        LayoutStore(ctx).save(start)
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val layout = LauncherActivity::class.java.getDeclaredField("layout").apply { isAccessible = true }.get(activity) as HomeLayout
        return activity to layout
    }

    private fun HomeLayout.folder() = pages[0].filterIsInstance<FolderItem>().first()

    @Test
    fun folderAppIntoAnEmptyDockSpot() {
        val (a, layout) = home()
        val folder = layout.folder()
        assertTrue(a.drop(DragInfo(Source.FOLDER, app(1), null, folder), DropTarget.Dock(2)))
        assertEquals(app(1), layout.dockApp(2))
        assertEquals(listOf(app(0), app(2), app(3)), folder.apps)
    }

    @Test
    fun folderAppOntoADockAppTradesPlaces() {
        val (a, layout) = home()
        val folder = layout.folder()
        assertTrue(a.drop(DragInfo(Source.FOLDER, app(2), null, folder), DropTarget.Dock(0)))
        assertEquals(app(2), layout.dockApp(0))
        // The phone app took app 2's old spot (spot three) in the folder.
        assertEquals(listOf(app(0), app(1), dockApp, app(3)), folder.apps)
    }

    @Test
    fun homeAppOntoADockAppMakesADockFolder() {
        val (a, layout) = home()
        val homeApp = layout.pages[0].filterIsInstance<AppItem>().single()
        assertTrue(a.drop(DragInfo(Source.HOME, app(9), homeApp), DropTarget.Dock(0)))
        val made = layout.dock[0] as FolderItem
        assertEquals(listOf(dockApp, app(9)), made.apps)
        assertTrue(layout.pages[0].none { it is AppItem })
    }

    @Test
    fun drawerAppIntoADockFolder() {
        val (a, layout) = home()
        layout.setDock(1, FolderItem("Social", mutableListOf(app(5), app(6)), 1, 0))
        assertTrue(a.drop(DragInfo(Source.DRAWER, app(7)), DropTarget.Dock(1)))
        assertEquals(listOf(app(5), app(6), app(7)), (layout.dock[1] as FolderItem).apps)
    }

    @Test
    fun wholeFolderIntoTheDockAndDockAppsSwap() {
        val (a, layout) = home()
        val folder = layout.folder()
        assertTrue(a.drop(DragInfo(Source.HOME, item = folder), DropTarget.Dock(3)))
        assertTrue(layout.dock[3] === folder)
        assertTrue(layout.pages[0].none { it === folder })
        // Dragging within the dock swaps the two.
        assertTrue(a.drop(DragInfo(Source.DOCK, item = folder, dockIndex = 3), DropTarget.Dock(0)))
        assertTrue(layout.dock[0] === folder)
        assertEquals(dockApp, layout.dockApp(3))
    }
}
