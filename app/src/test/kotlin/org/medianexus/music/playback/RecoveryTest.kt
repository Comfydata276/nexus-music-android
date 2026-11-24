package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val every = 15_000L

    @Test
    fun anOutageIsCountedFromWhenTheSongLastWentWellNotFromTheFirstError() {
        val outage = Recovery.Outage()
        // The song was playing until 1:00, when the player ran out of what it had fetched.
        outage.well(generation = 1, uid = 7, now = 60_000)
        // It rides the bad connection out by itself for most of a minute before it says
        // anything has failed. That minute was spent out of reach of the server too, and
        // used not to be counted.
        assertEquals(55_000, outage.waited(1, 7, now = 115_000))
        // Tried every fifteen seconds since: given up on ten minutes after the music stopped.
        assertFalse(Recovery.givesUp(outage.waited(1, 7, now = 60_000 + 9 * 60_000L), every))
        assertTrue(Recovery.givesUp(outage.waited(1, 7, now = 60_000 + 10 * 60_000L - every + 1), every))
    }

    @Test
    fun anotherSongOrAnotherAccountStartsFromNothing() {
        val outage = Recovery.Outage()
        outage.well(1, 7, now = 0)
        assertEquals(9 * 60_000L, outage.waited(1, 7, now = 9 * 60_000L))
        // Passed over, or skipped: the next song's trouble is its own. It used to inherit the
        // nine minutes and be given up on at its first failure.
        assertEquals(0, outage.waited(1, 8, now = 9 * 60_000L + 20_000))
        assertEquals(15_000, outage.waited(1, 8, now = 9 * 60_000L + 35_000))
        assertFalse(Recovery.givesUp(outage.waited(1, 8, now = 9 * 60_000L + 35_000), every))
        // Someone else signs in, and their server is out of reach as well.
        assertEquals(0, outage.waited(2, 8, now = 30 * 60_000L))
        // An error with nothing counted yet (the account has just been taken on) starts there.
        outage.clear()
        assertEquals(0, outage.waited(2, 8, now = 45 * 60_000L))
        assertEquals(1_000, outage.waited(2, 8, now = 45 * 60_000L + 1_000))
    }

    @Test
    fun aMomentOfSoundBetweenStallsDoesNotStartTheCountAgain() {
        val outage = Recovery.Outage()
        // Three minutes of music, and then the link goes bad.
        outage.played(1, 7, since = 0, now = 180_000)
        assertEquals(40_000, outage.waited(1, 7, now = 220_000))
        // Every so often two seconds of it get through and play. Each of those used to be
        // "going well", so the ten minutes never ran out and the song was tried for ever.
        outage.played(1, 7, since = 250_000, now = 252_000)
        outage.played(1, 7, since = 300_000, now = 301_500)
        assertEquals(125_000, outage.waited(1, 7, now = 305_000))
        assertTrue(Recovery.givesUp(outage.waited(1, 7, now = 180_000 + 10 * 60_000L), every))
        // Half a minute without a break is the music back.
        outage.played(1, 7, since = 400_000, now = 400_000 + Recovery.STEADY_MILLIS)
        assertEquals(5_000, outage.waited(1, 7, now = 435_000))
    }

    @Test
    fun pressingPlayIsANewTry() {
        val outage = Recovery.Outage()
        outage.well(1, 7, now = 0)
        assertTrue(Recovery.givesUp(outage.waited(1, 7, now = 11 * 60_000L), every))
        outage.well(1, 7, now = 12 * 60_000L)
        assertFalse(Recovery.givesUp(outage.waited(1, 7, now = 12 * 60_000L + 40_000), every))
    }

    @Test
    fun whenTheTimeForAStartRunsOutABusyPlayerIsNotTakenToMeanMediaThreeHasTheForeground() {
        // Still looking something up (a search can take seven seconds): not yet.
        assertEquals(Recovery.Held.WAIT, Recovery.whenHoldRunsOut(looking = 1, busy = false))
        assertEquals(Recovery.Held.WAIT, Recovery.whenHoldRunsOut(looking = 2, busy = true))
        // Playing, and the foreground is still held by the service's own notification: it
        // is handed to Media3, not forgotten.
        assertEquals(Recovery.Held.HAND_OVER, Recovery.whenHoldRunsOut(looking = 0, busy = true))
        // Nothing came of it.
        assertEquals(Recovery.Held.STAND_DOWN, Recovery.whenHoldRunsOut(looking = 0, busy = false))
    }

    @Test
    fun aChangeOfQualityReopensOnlyASongThatIsStreamedNow() {
        val ready = androidx.media3.common.Player.STATE_READY
        // Playing or paused part-way, from the server: opened again at the new quality.
        assertTrue(Recovery.reopensForQuality(ready, downloaded = false, radio = false, waitingForServer = false))
        assertTrue(Recovery.reopensForQuality(androidx.media3.common.Player.STATE_BUFFERING, downloaded = false, radio = false, waitingForServer = false))
        // Its download is what plays, whatever the quality.
        assertFalse(Recovery.reopensForQuality(ready, downloaded = true, radio = false, waitingForServer = false))
        // A station is what it sends.
        assertFalse(Recovery.reopensForQuality(ready, downloaded = false, radio = true, waitingForServer = false))
        // Nothing open, or ended: the next song opened is at the new quality anyway.
        assertFalse(Recovery.reopensForQuality(androidx.media3.common.Player.STATE_IDLE, downloaded = false, radio = false, waitingForServer = false))
        assertFalse(Recovery.reopensForQuality(androidx.media3.common.Player.STATE_ENDED, downloaded = false, radio = false, waitingForServer = false))
        // Waiting for the server: the next try is.
        assertFalse(Recovery.reopensForQuality(ready, downloaded = false, radio = false, waitingForServer = true))
    }

    // ---- A song chosen with the server out of reach (offline hardening 2, item 6) -------------

    @Test
    fun aSongChosenWithTheServerKnownToBeOutOfReachIsPassedOverAtOnce() {
        // Known out of reach, not on the phone, meant to play, not yet heard: passed over.
        assertTrue(Recovery.passesOverOutOfReach(known = true, onPhone = false, heard = false, meansToPlay = true))
        // On the phone: it plays.
        assertFalse(Recovery.passesOverOutOfReach(known = true, onPhone = true, heard = false, meansToPlay = true))
        // It was playing when the server went: it stays where it is, and carries on when the
        // server is back.
        assertFalse(Recovery.passesOverOutOfReach(known = true, onPhone = false, heard = true, meansToPlay = true))
        // Not known (a request failed; the server has not been asked, or has not answered):
        // that is not yet "out of reach".
        assertFalse(Recovery.passesOverOutOfReach(known = false, onPhone = false, heard = false, meansToPlay = true))
        // Paused: nothing is being waited for.
        assertFalse(Recovery.passesOverOutOfReach(known = true, onPhone = false, heard = false, meansToPlay = false))
    }

    @Test
    fun whatItIsPassedOverForIsTheNextThatPlays() {
        val onPhone = setOf(0, 4, 7)
        assertEquals(4, Recovery.nextThatPlays(current = 1, count = 9) { it in onPhone })
        assertEquals(7, Recovery.nextThatPlays(current = 4, count = 9) { it in onPhone })
        // Never back to an earlier one, never the one it is on.
        assertEquals(null, Recovery.nextThatPlays(current = 7, count = 9) { it in onPhone })
        assertEquals(null, Recovery.nextThatPlays(current = 0, count = 1) { true })
    }

    @Test
    fun aSongThatStopsWellBeforeItsLengthEndedEarly() {
        // Half way through a three-minute song.
        assertTrue(Recovery.endedEarly(positionMs = 90_000, seconds = 180))
        // A second or two out (a library's lengths are whole seconds): no.
        assertFalse(Recovery.endedEarly(positionMs = 178_200, seconds = 180))
        assertFalse(Recovery.endedEarly(positionMs = 180_400, seconds = 180))
        // A long one is allowed a fiftieth: a two-hour mix a minute and a half short is not it.
        assertFalse(Recovery.endedEarly(positionMs = 7_200_000 - 90_000, seconds = 7_200))
        assertTrue(Recovery.endedEarly(positionMs = 7_200_000 - 200_000, seconds = 7_200))
        // No length known: nothing to hold it against.
        assertFalse(Recovery.endedEarly(positionMs = 5_000, seconds = 0))
    }
}
