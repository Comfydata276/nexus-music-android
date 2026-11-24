package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.net.Song

/** Where a list starts once the service has worked out the arrangement the screen showed:
 *  at the song that was tapped, wherever it now stands in it, or nowhere at all. */
class StartPlaceTest {
    private fun songs(vararg ids: String) = ids.map { Song(id = it) }
    private val shown = songs("a", "b", "c", "d", "e", "f", "g", "h")

    private fun start(now: List<Song>, place: Int, tapped: String? = shown[place].id, arranged: IntArray = IntArray(now.size) { it }) =
        StartPlace.rowOf({ now[it].id }, arranged, place, tapped)

    @Test
    fun theSongThatWasTappedIsFoundWhereverItIsNow() {
        assertEquals(3, start(shown, 3))
        // Two songs were added above it.
        assertEquals(5, start(songs("x", "y", "a", "b", "c", "d", "e"), 3))
        // In an arrangement (sorted, narrowed), by the row it is on.
        assertEquals(1, start(shown, 3, arranged = intArrayOf(5, 3, 1)))
    }

    @Test
    fun aSongThatHasGoneStartsNothing() {
        // "d" was tapped (the fourth). Meanwhile it was taken out, and two songs added at the
        // top: the fourth is now "b". Neither it, nor a neighbour, nor the top of the list:
        // something the person didn't choose is not played.
        assertNull(start(songs("x", "y", "a", "b", "c", "e", "f", "g", "h"), 3))
        // In the list still, but not in the arrangement the screen showed (narrowed to
        // what was typed): the same.
        assertNull(start(shown, 3, arranged = intArrayOf(0, 1, 2)))
    }

    @Test
    fun aSongTheListHoldsTwiceIsTheCopyThatWasTapped() {
        val twice = songs("d", "a", "b", "d", "c")
        assertEquals(3, start(twice, 3, tapped = "d"))
        assertEquals(0, start(twice, 0, tapped = "d"))
        // The list has moved on: the first copy of it there is.
        assertEquals(1, start(songs("x", "d", "y", "d"), 2, tapped = "d"))
    }

    @Test
    fun anEmptyListStartsNothing() {
        assertNull(start(emptyList(), 3))
        assertNull(start(emptyList(), 0, tapped = null))
    }

    @Test
    fun aListStartedWithNoSongNamedStartsAtItsFirstRow() {
        // The play button: the arrangement from the top.
        assertEquals(0, start(shown, 5, tapped = null))
        assertEquals(0, start(shown, 0, tapped = null, arranged = intArrayOf(7, 2)))
    }
}
