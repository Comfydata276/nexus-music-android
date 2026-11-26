package org.medianexus.music.ui.requests

import org.junit.Assert.assertEquals
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
}
