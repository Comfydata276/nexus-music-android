package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.net.Song

/** Where a list that a screen named, rather than sent, starts once the service has read it:
 *  the phone's copy may have changed since the screen drew it. */
class StartPlaceTest {
    private fun songs(vararg ids: String) = ids.map { Song(id = it) }
    private val shown = songs("a", "b", "c", "d", "e", "f", "g", "h")

    /** As the screen sends it: the place, the song, and the songs around it. */
    private fun start(now: List<Song>, place: Int) = StartPlace.startIn(now, place, shown[place].id, StartPlace.neighbours(shown, place))

    @Test
    fun theSongThatWasTappedIsFoundWhereverItIsNow() {
        assertEquals(3, start(shown, 3))
        // Two songs were added above it.
        assertEquals(5, start(songs("x", "y", "a", "b", "c", "d", "e"), 3))
    }

    @Test
    fun aSongThatHasGoneStartsAtItsNearestNeighbourNotAtWhateverTookItsPlace() {
        // "d" was tapped (the fourth). Meanwhile it was taken out, and two songs added at the
        // top: the fourth is now "b". It used to start there, silently.
        val now = songs("x", "y", "a", "b", "c", "e", "f", "g", "h")
        assertEquals("the song after the one that went", 5, start(now, 3))
        // The ones after it have gone too: the one before it.
        assertEquals(4, start(songs("x", "y", "a", "b", "c"), 3))
        // A song the list holds twice: the copy nearest to where the tap was.
        assertEquals(4, StartPlace.startIn(songs("e", "a", "b", "c", "e", "f"), 3, "d", listOf("e", "f", "c")))
    }

    @Test
    fun withNothingAroundItLeftThePlaceIsTheNearestThereIs() {
        assertEquals(2, start(songs("p", "q", "r"), 3))
        assertEquals(1, start(songs("p", "q", "r"), 1))
    }

    @Test
    fun anEmptyListStartsNothing() {
        assertNull(start(emptyList(), 3))
        assertNull(StartPlace.startIn(emptyList(), 0, null, emptyList()))
    }

    @Test
    fun aListSentWithNoSongNamedStartsAtItsPlace() {
        assertEquals(2, StartPlace.startIn(shown, 2, null, emptyList()))
        assertEquals(7, StartPlace.startIn(shown, 99, null, emptyList()))
    }
}
