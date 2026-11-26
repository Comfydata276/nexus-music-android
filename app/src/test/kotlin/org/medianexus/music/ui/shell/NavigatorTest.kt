package org.medianexus.music.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private fun Navigator.routes() = visible.map { it.route }

    @Test
    fun backGoesThroughTheTabThenToHomeThenOut() {
        val nav = Navigator.fresh()
        assertEquals(listOf(Route.Home), nav.routes())
        assertFalse(nav.canGoBack)

        nav.select(Tab.LIBRARY)
        nav.open(Route.Settings)
        // Home's history lies under the tab's, so Back can show where it leads.
        assertEquals(listOf(Route.Home, Route.Library, Route.Settings), nav.routes())
        nav.back()
        assertEquals(listOf(Route.Home, Route.Library), nav.routes())
        nav.back()
        assertEquals(Tab.HOME, nav.tab)
        assertEquals(listOf(Route.Home), nav.routes())
        // The tab's own history was kept for coming back to.
        assertEquals(listOf(Route.Library), nav.stack(Tab.LIBRARY).map { it.route })
    }

    @Test
    fun eachTabKeepsItsOwnHistoryAndChoosingItAgainGoesToItsStart() {
        val nav = Navigator.fresh()
        nav.open(Route.Settings)
        nav.select(Tab.SEARCH)
        nav.select(Tab.HOME)
        assertEquals(listOf(Route.Home, Route.Settings), nav.routes())
        nav.select(Tab.HOME)
        assertEquals(listOf(Route.Home), nav.routes())
        assertEquals(Motion.POP, nav.motion)
    }

    @Test
    fun twoVisitsToOnePageAreToldApart() {
        val nav = Navigator.fresh()
        nav.open(Route.Settings)
        nav.select(Tab.SEARCH)
        nav.open(Route.Settings)
        val ids = (nav.stack(Tab.HOME) + nav.stack(Tab.SEARCH) + nav.stack(Tab.LIBRARY)).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun survivesBeingSavedAndRestored() {
        val nav = Navigator.fresh()
        nav.select(Tab.SEARCH)
        nav.open(Route.Settings)
        val saved = with(Navigator.saver) { SaverScope { true }.save(nav) }!!
        val back = Navigator.saver.restore(saved)!!
        assertEquals(Tab.SEARCH, back.tab)
        assertEquals(nav.visible, back.visible)
        back.open(Route.Settings)
        assertTrue(back.visible.map { it.id }.toSet().size == back.visible.size)
        // Something that no longer reads gives a fresh start, not a crash.
        assertEquals(listOf(Route.Home), Navigator.saver.restore("{not json")!!.routes())
    }

    @Test
    fun aSavedRequestsPageIsDroppedWhenTheBuildHasNoRequests() {
        try {
            org.medianexus.music.requests.RequestsBuild.forTests = true
            val nav = Navigator.fresh()
            nav.select(Tab.LIBRARY)
            nav.open(Route.Requests)
            nav.open(Route.OutsideAlbum("rg-1", "Paper Tides"))
            nav.select(Tab.SEARCH)
            nav.open(Route.OutsideArtist("qv", "Quillon Vane"))
            val saved = with(Navigator.saver) { SaverScope { true }.save(nav) }!!
            // Restored by a build without requests: none of their pages, and no blank ones.
            org.medianexus.music.requests.RequestsBuild.forTests = false
            val back = Navigator.saver.restore(saved)!!
            assertEquals(listOf(Route.Library), back.stack(Tab.LIBRARY).map { it.route })
            assertEquals(listOf(Route.Search), back.stack(Tab.SEARCH).map { it.route })
            assertEquals(listOf(Route.Home, Route.Search), back.routes())
        } finally {
            org.medianexus.music.requests.RequestsBuild.forTests = null
        }
    }
}
