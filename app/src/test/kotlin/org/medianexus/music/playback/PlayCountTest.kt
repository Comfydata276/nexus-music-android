package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

class PlayCountTest {
    @Test
    fun aPlayCountsAfterHalfTheSongOrFourMinutes() {
        val count = PlayCount()
        count.start("s1", 100.0)
        var counted = 0
        for (second in 1..60) if (count.at(second.toDouble(), second * 1000L)) counted++
        // Once, at 50 seconds.
        assertEquals(1, counted)
        // A long song counts at four minutes.
        assertEquals(240.0, PlayCount.threshold(3600.0), 0.0)
        assertEquals(240.0, PlayCount.threshold(0.0), 0.0)
    }

    @Test
    fun skippingAheadIsNotListening() {
        val count = PlayCount()
        count.start("s1", 100.0)
        for (second in 1..10) count.at(second.toDouble(), second * 1000L)
        count.jumped(10.0, 80.0, 10_000)
        var counted = false
        for (second in 81..99) counted = count.at(second.toDouble(), (second - 70) * 1000L) || counted
        // 10 seconds before the jump and 19 after it: short of 50.
        assertFalse(counted)
        assertEquals(29.0, count.heard, 0.01)
        // A big jump that wasn't announced as a seek counts for no more than the time it took.
        count.start("s2", 100.0, 0.0, 0)
        assertFalse(count.at(90.0, 1_000))
        assertTrue(count.heard <= 1.5)
    }

    @Test
    fun aSampleThatArrivesLateStillCounts() {
        // The once-a-second sample held up for five seconds (the main thread was busy): the
        // five seconds were heard, and used to be thrown away as "a jump".
        val count = PlayCount()
        count.start("s1", 20.0)
        count.at(1.0, 1_000)
        count.at(6.0, 6_000)
        assertEquals(6.0, count.heard, 0.01)
        assertTrue(count.at(10.0, 10_000))
    }

    @Test
    fun timePausedIsNotListening() {
        val count = PlayCount()
        count.start("s1", 100.0)
        count.at(5.0, 5_000)
        // Paused at 5.4, a sample taken there, then a minute of silence.
        count.pause(5.4, 5_400)
        count.at(5.4, 65_000)
        count.resume(5.4, 65_400)
        count.at(6.4, 66_400)
        assertEquals(6.4, count.heard, 0.01)
    }

    private class Told {
        val nowPlaying = mutableListOf<String>()
        val played = mutableListOf<String>()
        val reporter = PlayReporter({ nowPlaying += it.id }, { id, _ -> played += id })
    }

    private fun song(id: String, seconds: Int) = Song(id = id, duration = seconds)

    @Test
    fun thePauseTakesALastSample() {
        // Ten seconds long: counts at five. Paused at 5.2 between two one-second samples,
        // then skipped: the play was heard, and used to be lost.
        val t = Told()
        t.reporter.playing(1, song("a", 10), 0.0, 0)
        for (s in 1..4) t.reporter.tick(s.toDouble(), s * 1000L)
        t.reporter.paused(5.2, 5_200)
        assertEquals(listOf("a"), t.played)
        assertEquals(listOf("a"), t.nowPlaying)
    }

    @Test
    fun movingOnTakesALastSampleOfTheSongBefore() {
        val t = Told()
        t.reporter.playing(1, song("a", 8), 0.0, 0)
        for (s in 1..3) t.reporter.tick(s.toDouble(), s * 1000L)
        // Next pressed at 4.1 seconds, past half of an eight-second song.
        t.reporter.left(4.1, 4_100)
        t.reporter.playing(2, song("b", 100), 0.0, 4_600)
        assertEquals(listOf("a"), t.played)
        assertEquals(listOf("a", "b"), t.nowPlaying)
    }

    @Test
    fun aSongRunningIntoTheNextIsCountedToItsEnd() {
        val t = Told()
        t.reporter.playing(1, song("a", 4), 0.0, 0)
        t.reporter.tick(1.0, 1_000)
        // Gapless: the next starts with no pause; the first is counted to where it ended.
        t.reporter.left(4.0, 4_000)
        t.reporter.playing(2, song("b", 4), 0.0, 4_000)
        assertEquals(listOf("a"), t.played)
    }

    @Test
    fun aQueueOfOneSongPlayedAgainFromTheTopCountsAgain() {
        val t = Told()
        val only = song("a", 10)
        t.reporter.playing(7, only, 0.0, 0)
        for (s in 1..10) t.reporter.tick(s.toDouble(), s * 1000L)
        // It ends; the player parks on it again, paused at the top.
        t.reporter.ended(10_000)
        t.reporter.paused(0.0, 10_000)
        assertEquals(listOf("a"), t.played)
        // Play pressed again: the same entry, a new play.
        t.reporter.playing(7, only, 0.0, 60_000)
        for (s in 1..10) t.reporter.tick(s.toDouble(), 60_000 + s * 1000L)
        assertEquals(listOf("a", "a"), t.played)
        assertEquals(listOf("a", "a"), t.nowPlaying)
    }

    @Test
    fun aPlaySaysWhichPlaylistItWasFromAndAStationIsNeverAPlay() {
        val told = mutableListOf<Pair<String, String>>()
        val reporter = PlayReporter({}, { id, from -> told += id to from })
        reporter.playing(1, song("a", 10), 0.0, 0, from = "playlist:p")
        for (s in 1..5) reporter.tick(s.toDouble(), s * 1000L)
        // The next, put in the queue by hand: from nowhere.
        reporter.left(5.0, 5_000)
        reporter.playing(2, song("b", 10), 0.0, 5_000)
        for (s in 1..5) reporter.tick(s.toDouble(), 5_000 + s * 1000L)
        reporter.left(5.0, 10_000)
        assertEquals(listOf("a" to "playlist:p", "b" to ""), told)
        // An internet radio station, however long it plays (Windows: `radio_url.is_empty()`).
        reporter.playing(3, Song(id = "station", radioUrl = "http://radio.invalid/live"), 0.0, 10_000)
        for (s in 1..600) reporter.tick(s.toDouble(), 10_000 + s * 1000L)
        reporter.left(600.0, 610_000)
        assertEquals(2, told.size)
    }

    @Test
    fun repeatingOneSongCountsEachTimeRound() {
        val t = Told()
        val only = song("a", 6)
        t.reporter.playing(3, only, 0.0, 0)
        for (round in 0..2) {
            val base = round * 6_000L
            for (s in 1..5) t.reporter.tick(s.toDouble(), base + s * 1000L)
            t.reporter.left(6.0, base + 6_000, again = true)
            t.reporter.playing(3, only, 0.0, base + 6_000)
        }
        assertEquals(listOf("a", "a", "a"), t.played)
        assertEquals(4, t.nowPlaying.size)
    }

    @Test
    fun pausingAndCarryingOnIsTheSamePlay() {
        val t = Told()
        val one = song("a", 100)
        t.reporter.playing(1, one, 0.0, 0)
        for (s in 1..30) t.reporter.tick(s.toDouble(), s * 1000L)
        t.reporter.paused(30.0, 30_000)
        t.reporter.playing(1, one, 30.0, 500_000)
        for (s in 31..60) t.reporter.tick(s.toDouble(), 500_000 + (s - 30) * 1000L)
        assertEquals(listOf("a"), t.played)
        assertEquals(listOf("a"), t.nowPlaying)
    }

    @Test
    fun aSeekWithinTheSongIsNotListening() {
        val t = Told()
        t.reporter.playing(1, song("a", 100), 0.0, 0)
        for (s in 1..10) t.reporter.tick(s.toDouble(), s * 1000L)
        t.reporter.sought(10.0, 90.0, 10_000)
        for (s in 91..99) t.reporter.tick(s.toDouble(), 10_000 + (s - 90) * 1000L)
        assertTrue(t.played.isEmpty())
    }
}
