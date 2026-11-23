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
    fun theNearestNeighbourIsNearestByDistanceWhicheverSideOfTheTapItWasOn() {
        // "d" was tapped. It has gone, and so have the three songs after it. "c", straight
        // before it, is one place away; "h" is four after. Every song after it used to be
        // tried before any song before it, so it started at "h".
        val now = songs("a", "b", "c", "h")
        assertEquals("c", now[start(now, 3)!!].id)
        // After and before by turns, nearest first: with "c" gone as well, "b" (two before)
        // comes ahead of "h" (four after), and "f" (two after) ahead of "b".
        assertEquals("b", songs("a", "b", "h")[start(songs("a", "b", "h"), 3)!!].id)
        assertEquals("f", songs("a", "b", "f", "h")[start(songs("a", "b", "f", "h"), 3)!!].id)
        assertEquals(listOf("e", "c", "f", "b", "g", "a", "h"), StartPlace.neighbours(shown, 3))
    }

    @Test
    fun withNoneOfTheNearbyOnesLeftTheListStartsAtItsFirstSong() {
        // Nothing that was near the tap is in the list any more: there is no telling what
        // was meant, and whatever now sits at the old place is not it. The page from its top.
        assertEquals(0, start(songs("p", "q", "r", "s", "t"), 3))
        assertEquals(0, start(songs("p", "q", "r"), 1))
        assertEquals(0, start(songs("p"), 6))
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
