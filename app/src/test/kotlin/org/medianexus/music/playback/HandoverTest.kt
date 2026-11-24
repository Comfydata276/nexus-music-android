package org.medianexus.music.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.library.Arrange
import org.medianexus.music.library.ListView
import org.medianexus.music.library.Shown
import org.medianexus.music.library.columns
import org.medianexus.music.net.Song

/**
 * A tap plays exactly the list it was made on: what the screen hands over is the arrangement
 * it showed (the list, its find text, its order) and the song tapped; the player works that
 * arrangement out again from the list as the phone has it, and starts at that song, or, if
 * it isn't there any more, plays nothing rather than something else.
 */
class HandoverTest {
    private val list = listOf(
        Song(id = "1", title = "Amber road"), Song(id = "2", title = "Blue harbour"), Song(id = "3", title = "Amber light"),
        Song(id = "4", title = "Cold harbour"), Song(id = "5", title = "Amber sky"),
    )

    private fun pick(songs: List<Song>, view: ListView?, start: Int, startId: String?) =
        StartPlace.pick(songs.columns(), { songs[it].id }, view, start, startId)

    @Test
    fun theArrangementOnScreenIsPlayedFromTheSongTapped() {
        // "amber", by title: Amber light, Amber road, Amber sky. "Amber road" tapped.
        val view = ListView(ListView.TITLE, filter = "amber")
        val picked = pick(list, view, 0, "1") as StartPlace.Picked.Rows
        assertEquals(listOf("3", "1", "5"), picked.shown!!.map { list[it].id })
        assertEquals(1, picked.first)
    }

    @Test
    fun aSongNotInTheArrangementPlaysNothing() {
        // The find box says "harbour" now, but the row tapped was "Amber road", still on screen
        // from "amber": the arrangement handed over is the one on screen. Handed the newer
        // find text instead, the song isn't in it, and nothing plays (it used to play the
        // first harbour song).
        assertEquals(StartPlace.Picked.Gone, pick(list, ListView(filter = "harbour"), 0, "1"))
        // Gone from the list since the screen drew it: nothing either.
        assertEquals(StartPlace.Picked.Gone, pick(list.filter { it.id != "1" }, ListView(filter = "amber"), 0, "1"))
        assertEquals(StartPlace.Picked.Empty, pick(emptyList(), null, 0, null))
    }

    @Test
    fun thePlayButtonStartsTheArrangementAtItsTop() {
        val picked = pick(list, ListView(ListView.TITLE, descending = true, filter = "harbour"), 0, null) as StartPlace.Picked.Rows
        assertEquals(listOf("4", "2"), picked.shown!!.map { list[it].id })
        assertEquals(0, picked.first)
    }

    @Test
    fun rowsOfASupersededArrangementCannotBeTapped() {
        val view = ListView(filter = "amber")
        val arranged = Shown(list, view, Arrange.arrange(list.columns(), view))
        assertTrue(arranged.tappable(list, view))
        // Another letter typed: until the new arrangement is ready, the old rows are shown but
        // not tappable.
        assertFalse(arranged.tappable(list, view.copy(filter = "amber r")))
        assertFalse(arranged.tappable(list, view.copy(sort = ListView.TITLE)))
        // A newer copy of the list arrived: the same.
        assertFalse(arranged.tappable(list.toList(), view))
    }

    @Test
    fun aQueueFromAFilteredListFollowsItWithTheSameFilter() {
        val view = ListView(filter = "amber")
        val shown = Arrange.arrange(list.columns(), view)
        val queue = Queue()
        queue.replace(shown.map { list[it] }, 0, "Mix", source = "playlist:p", origins = shown.toList(), sourceList = list.map { it.id }, filter = view.filter)
        assertEquals("amber", queue.filter)
        // The playlist gains two songs, one that matches what was typed and one that doesn't.
        val now = list + Song(id = "6", title = "Amber dusk") + Song(id = "7", title = "Grey harbour")
        val follow = ListView.following("playlist:p", org.medianexus.music.store.ListPrefs(sortChosen = true), queue.filter)
        queue.follow(now.map { it.id }, now::getOrNull, Random(1), Arrange.arrange(now.columns(), follow).toList())
        assertEquals(listOf("1", "3", "5", "6"), queue.playingOrder().map { it.id })
    }
}
