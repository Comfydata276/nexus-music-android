package org.medianexus.music.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.R
import org.medianexus.music.net.Album

/** Home's shelves, as Windows' `home` puts them together. */
class HomeModelTest {
    private fun albums(vararg ids: String, played: String = "") = ids.map { Album(id = it, name = it, played = played) }

    private val now = 1_800_000_000L

    @Test
    fun theTilesAreWhatWasPlayedToppedUpWithWhatsNew() {
        val tiles = HomeModel.tiles(albums("a", "b"), albums("b", "c", "d", "e", "f", "g", "h", "i", "j"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), tiles.map { it.id })
    }

    @Test
    fun theShelvesComeInTheWindowsOrderLessTheEmptyOnes() {
        val shelves = HomeModel.shelves(albums("r"), albums("n"), albums("f"), emptyList(), now)
        assertEquals(listOf(R.string.recently_added, R.string.recently_played, R.string.most_played), shelves.map { it.title })
        assertEquals(listOf(HomeModel.SORT_NEWEST, HomeModel.SORT_RECENT, HomeModel.SORT_FREQUENT), shelves.map { it.showAll })
    }

    @Test
    fun oldFavouritesNeedFourAndFortyFiveDays() {
        val long = java.time.Instant.ofEpochSecond(now - 50L * 24 * 3600).toString()
        val lately = java.time.Instant.ofEpochSecond(now - 5L * 24 * 3600).toString()
        val three = HomeModel.shelves(emptyList(), emptyList(), albums("a", "b", "c", played = long) + albums("d", played = lately), emptyList(), now)
        assertEquals(listOf(R.string.most_played), three.map { it.title })
        val four = HomeModel.shelves(emptyList(), emptyList(), albums("a", "b", "c", "e", played = long), emptyList(), now)
        assertEquals(listOf(R.string.most_played, R.string.been_a_while), four.map { it.title })
    }

    @Test
    fun theGreetingFollowsTheHour() {
        assertEquals(R.string.good_morning, HomeModel.greeting(5))
        assertEquals(R.string.good_afternoon, HomeModel.greeting(12))
        assertEquals(R.string.good_evening, HomeModel.greeting(18))
        assertEquals(R.string.good_evening, HomeModel.greeting(2))
        assertEquals(0L, HomeModel.playedAt("not a time"))
    }
}
