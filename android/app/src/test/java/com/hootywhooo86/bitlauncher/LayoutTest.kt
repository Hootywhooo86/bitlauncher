package com.hootywhooo86.bitlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun app(n: Int) = AppKey("pkg.$n", "Main", 0)

    private fun appsOn(layout: HomeLayout) = layout.pages.flatten().filterIsInstance<AppItem>().map { it.app }.toSet()

    @Test
    fun gridSizesAreTheThreeWeShip() {
        assertEquals(listOf("4x5", "5x6", "6x7"), GridSize.entries.map { it.key })
        assertEquals(GridSize.MEDIUM, GridSize.fromKey("nonsense"))
        assertEquals(4, GridSize.SMALL.dockSlots)
        assertEquals(6, GridSize.LARGE.dockSlots)
    }

    @Test
    fun placeAnywhereFillsThenAddsAPage() {
        val layout = HomeLayout.empty(GridSize.SMALL)
        repeat(21) { layout.placeAnywhere(AppItem(app(it), 0, 0)) }
        assertEquals(2, layout.pages.size)
        assertEquals(20, layout.pages[0].size)
        assertEquals(1, layout.pages[1].size)
    }

    @Test
    fun isFreeRespectsWidgetsAndBounds() {
        val layout = HomeLayout.empty(GridSize.MEDIUM)
        layout.pages[0].add(WidgetItem(7, "w/W", 1, 1, 3, 2))
        assertFalse(layout.isFree(0, 2, 2, 1, 1))
        assertTrue(layout.isFree(0, 0, 0, 1, 1))
        assertFalse(layout.isFree(0, 4, 0, 2, 1)) // off the right edge
        assertNotNull(layout.itemAt(0, 3, 2))
        assertNull(layout.itemAt(0, 4, 1))
    }

    @Test
    fun shrinkingTheGridLosesNothing() {
        val layout = HomeLayout.empty(GridSize.LARGE)
        layout.pages[0].add(WidgetItem(1, "w/W", 0, 0, 6, 2))
        repeat(30) { layout.placeAnywhere(AppItem(app(it), 0, 0)) }
        assertEquals(1, layout.pages.size) // 6x7 page: widget rows plus 30 icons, full
        val before = layout.allApps().toSet()

        layout.resize(GridSize.SMALL)

        assertEquals(before, appsOn(layout))
        for (page in layout.pages) {
            val taken = mutableSetOf<Pair<Int, Int>>()
            for (item in page) {
                assertTrue("in bounds", item.x >= 0 && item.y >= 0 && item.x + item.spanX <= 4 && item.y + item.spanY <= 5)
                for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) {
                    assertTrue("no overlap", taken.add(item.x + dx to item.y + dy))
                }
            }
        }
        val widget = layout.pages.flatten().filterIsInstance<WidgetItem>().single()
        assertEquals(4, widget.w)
    }

    @Test
    fun itemsThatStillFitStayPut() {
        val layout = HomeLayout.empty(GridSize.SMALL)
        layout.pages[0].add(AppItem(app(1), 2, 3))
        layout.resize(GridSize.LARGE)
        val item = layout.pages[0].single()
        assertEquals(2, item.x)
        assertEquals(3, item.y)
    }

    @Test
    fun dockOverflowMovesToHome() {
        val layout = HomeLayout(GridSize.LARGE, mutableListOf(), MutableList(6) { app(it) })
        layout.resize(GridSize.SMALL)
        assertEquals(4, layout.dock.size)
        assertEquals(6, layout.allApps().size)
        assertEquals(setOf(app(4), app(5)), appsOn(layout))
    }

    @Test
    fun uninstalledAppsDisappearAndFoldersDissolve() {
        val layout = HomeLayout.empty(GridSize.MEDIUM)
        layout.pages[0].add(FolderItem("Games", mutableListOf(app(1), app(2)), 0, 0))
        layout.pages[0].add(AppItem(app(2), 1, 0))
        layout.dock[0] = app(2)

        layout.removeApps { it == app(2) }

        assertEquals(listOf(app(1)), layout.allApps())
        val left = layout.pages[0].single()
        assertTrue(left is AppItem)
        assertEquals(0, left.x)
    }

    @Test
    fun jsonRoundTrip() {
        val layout = HomeLayout.empty(GridSize.LARGE)
        layout.pages[0].add(AppItem(app(1), 1, 2))
        layout.pages[0].add(FolderItem("Work", mutableListOf(app(2), app(3)), 0, 0))
        layout.pages.add(mutableListOf(WidgetItem(9, "a/B", 0, 0, 4, 2)))
        layout.dock[1] = app(4)

        val back = HomeLayout.fromJson(JSONObject(layout.toJson().toString()))

        assertEquals(GridSize.LARGE, back.grid)
        assertEquals(layout.toJson().toString(), back.toJson().toString())
    }

    @Test
    fun unknownItemTypesAreSkippedNotFatal() {
        val json = JSONObject("""{"v":1,"grid":"4x5","pages":[[{"t":"future","x":0,"y":0}]],"dock":[]}""")
        val layout = HomeLayout.fromJson(json)
        assertTrue(layout.pages[0].isEmpty())
        assertEquals(4, layout.dock.size)
    }
}
