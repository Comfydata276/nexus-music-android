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

    @Test
    fun ofTwoThingsTheServerSaidTheOneReadLaterWins() {
        val freshest = Freshest()
        val page = Album(id = "al1", songs = listOf(fromCopy.copy(playCount = 9, played = "2026-11-23T09:00:00Z", userRating = 3)))
        freshest.sawPage(page, at = 2_000)
        // A reading asked for earlier that arrives after: not over the later one.
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(playCount = 7, userRating = 1))), at = 1_000)
        assertEquals(3, freshest.of(fromCopy).userRating)
        assertEquals(9, freshest.of(fromCopy).playCount)
        // Read later: what it says, whatever was seen before (the rating was changed elsewhere).
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(playCount = 9, played = "2026-11-23T09:00:00Z", userRating = 5))), at = 3_000)
        assertEquals(5, freshest.of(fromCopy).userRating)
    }

    @Test
    fun aNewerCopyOfTheLibraryIsNewerThanAPageReadBeforeIt() {
        val freshest = Freshest()
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(playCount = 6, userRating = 3))), at = 1_000)
        assertEquals(3, freshest.of(fromCopy).userRating)
        // The copy is fetched again, after that page: its row is what the server says now.
        // (The page used to be shown over it for the rest of the account's time.)
        freshest.copyArrived(fetched = 2_000)
        val newer = fromCopy.copy(playCount = 8, userRating = 5)
        assertSame(newer, freshest.of(newer))
    }

    @Test
    fun aRatingMadeHereIsShownUntilItSettlesThenTheServersWordAgain() {
        var now = 1_000L
        val freshest = Freshest(clock = { now })
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(userRating = 2))), at = 500)
        // Three stars, here.
        freshest.reported(Pending.Rating("s1", 3))
        assertEquals(3, freshest.of(fromCopy).userRating)
        // The server takes it: what the server says is now three.
        now = 2_000
        freshest.settled(Pending.Rating("s1", 3), taken = true)
        assertEquals(3, freshest.of(fromCopy).userRating)
        // Changed to five on Windows, and read here later: five (it went on showing three).
        freshest.sawPage(Album(id = "al1", songs = listOf(fromCopy.copy(userRating = 5))), at = 3_000)
        assertEquals(5, freshest.of(fromCopy).userRating)
        // One the server refuses: forgotten, the server's word stands.
        freshest.reported(Pending.Rating("s1", 1))
        assertEquals(1, freshest.of(fromCopy).userRating)
        freshest.settled(Pending.Rating("s1", 1), taken = false)
        assertEquals(5, freshest.of(fromCopy).userRating)
    }
}
