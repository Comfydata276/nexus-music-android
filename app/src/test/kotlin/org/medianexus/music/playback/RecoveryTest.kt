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
}
