package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Song
import org.medianexus.music.store.Pending

/** A song's details show the newest the app has seen of its plays and rating. */
class FreshestTest {
    // As the phone's copy of the library has it: a day old.
    private val fromCopy = Song(id = "s1", title = "Lantern Season", playCount = 4, played = "2026-11-20T08:00:00Z", userRating = 2)

    @Test
    fun aLivePageIsNewerThanTheCopy() {
        val freshest = Freshest()
        assertSame(fromCopy, freshest.of(fromCopy))
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(playCount = 6, played = "2026-11-23T09:00:00Z", userRating = 5))))
        val shown = freshest.of(fromCopy)
        assertEquals(6, shown.playCount)
        assertEquals("2026-11-23T09:00:00Z", shown.played)
        assertEquals(5, shown.userRating)
    }

    @Test
    fun aPlayThisAppReportedCountsUntilTheServerIsSeenToHaveIt() {
        val freshest = Freshest()
        val at = java.time.Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        freshest.reported(Pending.Played("s1", at))
        assertEquals(5, freshest.of(fromCopy).playCount)
        assertEquals("2026-11-24T10:00:00Z", freshest.of(fromCopy).played)
        // The server, asked again, has counted it: not twice.
        freshest.saw(listOf(fromCopy.copy(playCount = 5, played = "2026-11-24T10:00:00Z")))
        assertEquals(5, freshest.of(fromCopy).playCount)
        // A rating this app sent is the rating, whatever older pages say.
        freshest.reported(Pending.Rating("s1", 4))
        assertEquals(4, freshest.of(fromCopy).userRating)
    }
}
