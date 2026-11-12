package com.hootywhooo86.bitlauncher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyTest {
    private val feed = """[
      {"id":"fit-workout-9","app":"fit","type":"workout.logged","at":5000,"data":{"kind":"strength","name":"Push day","minutes":45,"exercises":5,"sets":18}},
      {"id":"play-book-1","app":"play","type":"book.finished","at":4000,"data":{"title":"The Delve","author":"K"}},
      {"id":"fit-health-x","app":"fit","type":"health.day","at":3000,"data":{"date":"2026-11-12","steps":8432,"sleepMinutes":null}},
      {"id":"life-q1","app":"life","type":"quest.issued","at":2000,"data":{"quest":"q1","title":"Log 3 workouts this week","xp":50}},
      {"id":"life-q2","app":"life","type":"quest.issued","at":1000,"data":{"quest":"q2","title":"Finish a book"}},
      {"id":"life-q2-done","app":"life","type":"quest.completed","at":4500,"data":{"quest":"q2"}},
      {"id":"x","app":"custom","type":"thing.happened","at":100,"data":{"text":"Fed the cat"}}
    ]"""

    @Test
    fun feedLinesReadNaturally() {
        val events = FamilyFeed.parse(feed)
        val byId = events.associateBy { it.id }
        assertEquals("Workout  ·  Push day  ·  45 min", FamilyFeed.describe(byId.getValue("fit-workout-9")))
        assertEquals("Finished  ·  The Delve", FamilyFeed.describe(byId.getValue("play-book-1")))
        assertEquals("8,432 steps", FamilyFeed.describe(byId.getValue("fit-health-x")))
        assertEquals("Fed the cat", FamilyFeed.describe(byId.getValue("x"))) // unknown types still show
    }

    @Test
    fun onlyOpenQuestsShow() {
        val events = FamilyFeed.parse(feed)
        assertEquals(listOf("life-q1"), FamilyFeed.openQuests(events).map { it.id })
        // "Lately" leaves the quests out and is newest first.
        assertEquals(listOf("fit-workout-9", "play-book-1", "fit-health-x", "x"), FamilyFeed.lately(events).map { it.id })
    }

    @Test
    fun backupsCarryBothLayoutsAndRestoreThem() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        val home = HomeLayout.empty(GridSize.MEDIUM).apply { pages[0].add(AppItem(AppKey("a.home", "Main", 0), 0, 0)) }
        val work = HomeLayout.empty(GridSize.MEDIUM).apply { pages[0].add(AppItem(AppKey("a.work", "Main", 0), 1, 1)) }
        LayoutStore(ctx, Shift.HOME).save(home)
        LayoutStore(ctx, Shift.WORK).save(work)
        val backup = LayoutStore.backupJson(ctx, prefs)
        assertTrue(backup.has("work"))

        // Wipe, then restore from the backup.
        LayoutStore(ctx, Shift.HOME).save(HomeLayout.empty(GridSize.MEDIUM))
        LayoutStore(ctx, Shift.WORK).save(HomeLayout.empty(GridSize.MEDIUM))
        LayoutStore.restore(ctx, prefs, backup.toString())
        assertEquals("a.home", (LayoutStore(ctx, Shift.HOME).load(GridSize.MEDIUM).pages[0][0] as AppItem).app.pkg)
        assertEquals("a.work", (LayoutStore(ctx, Shift.WORK).load(GridSize.MEDIUM).pages[0][0] as AppItem).app.pkg)
    }

    @Test
    fun todayInvitesYouToSignInRatherThanInventingQuests() {
        val home = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun find(v: View): Workspace? = if (v is Workspace) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        fun has(v: View, t: String): Boolean = (v is android.widget.TextView && v.text.contains(t)) ||
            (v is ViewGroup && (0 until v.childCount).any { has(v.getChildAt(it), t) })
        val ws = find(home.window.decorView)!!
        ws.listener!!.onTodayDrag(400f)
        ws.listener!!.onTodayRelease(400f, 5000f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertTrue(has(home.window.decorView, "Sign in to your 128bit account"))
    }

    @Test
    fun fileBackupsStillRestore() {
        // A backup file made by 0.4 (no "work") restores fine.
        val ctx = RuntimeEnvironment.getApplication()
        val old = JSONObject().put("app", "bitlauncher").put("backup", 1)
            .put("layout", HomeLayout.empty(GridSize.SMALL).toJson()).put("settings", JSONObject())
        LayoutStore.restore(ctx, Prefs(ctx), old.toString())
        assertEquals(GridSize.SMALL, Prefs(ctx).grid)
        Prefs(ctx).grid = GridSize.MEDIUM
    }
}
