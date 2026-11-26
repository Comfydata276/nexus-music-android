package org.medianexus.music.ui.requests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.core.Asked

/** The Requests page's Arrived rows each have a key of their own (Compose crashes on two alike). */
class ArrivedKeysTest {
    @get:Rule val limit: Timeout = Timeout.seconds(10)

    @Test
    fun twoNotesOfTheSameArtistAndTitleWithDifferentIdsMetByOneAlbumHaveTwoKeys() {
        // Asked for twice under Lidarr's two ids for it; one album in the library satisfies both.
        val first = Asked(album = "201", title = "Tidewater", artist = "The Quiet Harbour", at = 1_700_000_000, arrived = 1_700_100_000, libraryAlbum = "al-9")
        val second = first.copy(album = "202", at = 1_700_000_500)
        val keys = arrivedKeys(listOf(first, second))
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun theSameAlbumAskedForAgainAfterItArrivedHasTwoKeys() {
        // The list merges notes only while they are awaited: once one has arrived, asking for the
        // same album again makes a second note with the same id.
        val first = Asked(album = "201", title = "Tidewater", artist = "The Quiet Harbour", at = 1_700_000_000, arrived = 1_700_100_000, libraryAlbum = "al-9")
        val again = first.copy(at = 1_700_200_000, arrived = 1_700_300_000)
        assertEquals(2, arrivedKeys(listOf(first, again)).toSet().size)
    }

    @Test
    fun twoNotesAlikeInEverythingStillHaveTwoKeys() {
        val one = Asked(title = "Tidewater", artist = "The Quiet Harbour", arrived = 1_700_100_000)
        assertEquals(2, arrivedKeys(listOf(one, one.copy())).toSet().size)
    }

    @Test
    fun aRowKeepsItsKeyWhenAnotherArrivesAboveIt() {
        val first = Asked(album = "201", title = "Tidewater", artist = "The Quiet Harbour", at = 1_700_000_000, arrived = 1_700_100_000, libraryAlbum = "al-9")
        val newer = Asked(album = "305", title = "Paper Tides", artist = "Quillon Vane", at = 1_700_400_000, arrived = 1_700_500_000, libraryAlbum = "al-12")
        assertEquals(arrivedKeys(listOf(first))[0], arrivedKeys(listOf(newer, first))[1])
    }

    @Test
    fun aNumberedKeyCanNotBeAnotherNotesOwn() {
        // A note twice gets a count; a third note whose artist ends in what the count would
        // look like must still have a key of its own.
        val one = Asked(album = "201", title = "Tidewater", artist = "The Quiet Harbour", at = 1_700_000_000, arrived = 1_700_100_000)
        val lookalike = one.copy(artist = "The Quiet Harbour#1")
        assertEquals(3, arrivedKeys(listOf(one, one.copy(), lookalike)).toSet().size)
        // Nor can one field's words run into the next's.
        val split = one.copy(title = "Tidewater/The", artist = "Quiet Harbour")
        val joined = one.copy(title = "Tidewater", artist = "The/Quiet Harbour")
        assertEquals(2, arrivedKeys(listOf(split, joined)).toSet().size)
    }

    // ---- the swipe on a row on its way (AA4) ----------------------------------------------------

    private val shown = org.medianexus.music.core.Pending(id = 7, title = "Paper Tides", artist = "Quillon Vane", album = "rg-1")

    @Test
    fun aSwipeIsBoundToTheConnectionItWasShownUnder() {
        // After a refresh under a new connection, the same number is a new row: its swipe is new.
        assertNotEquals(swipeKey(shown, under = 1), swipeKey(shown, under = 2))
    }

    @Test
    fun aSwipeIsBoundToTheRowItself() {
        // The same number standing for another album (Lidarr reused it) is another row.
        assertNotEquals(swipeKey(shown, under = 1), swipeKey(shown.copy(title = "Saltwater Hours", album = "rg-2"), under = 1))
        assertEquals(swipeKey(shown, under = 1), swipeKey(shown.copy(), under = 1))
    }
}
