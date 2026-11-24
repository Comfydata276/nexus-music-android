package org.medianexus.music.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTest {
    /** The room and the word as Windows' `account_words` works them out, for the made-up test
     *  account (worked out beside it with sha256 by hand: `nexus-music room\nuser\npassword`). */
    @Test
    fun theRoomAndTheWordAreWindowsOwn() {
        val (room, word) = RoomSide.words("tester", "tester-pass-1")
        assertEquals("db49ef51dc4001a851ce7a302dd0fe9b4d452a169d757c24148f947072024d45", room)
        assertEquals("5cd31b1e1c451e84120b9fba915c24aa", word)
        // The relay takes 32 to 64 letters and digits for a room.
        assertTrue(room.length in 32..64 && room.all(Char::isLetterOrDigit))
    }

    /** Rule 1: the phone holds a connection to the relay only while it has a reason to. */
    @Test
    fun theConnectionIsHeldOnlyWithAReason() {
        val now = 100_000_000L
        assertFalse(Reasons.open(inFront = false, playing = false, stoppedAt = null, now = now, inJam = false))
        assertTrue(Reasons.open(inFront = true, playing = false, stoppedAt = null, now = now, inJam = false))
        assertTrue(Reasons.open(inFront = false, playing = true, stoppedAt = null, now = now, inJam = false))
        assertTrue(Reasons.open(inFront = false, playing = false, stoppedAt = null, now = now, inJam = true))
        // Paused: held for the ten minutes the playback service stays in the foreground, then let go.
        assertTrue(Reasons.open(false, false, stoppedAt = now - Reasons.PAUSED_GRACE_MILLIS + 1, now = now, inJam = false))
        assertFalse(Reasons.open(false, false, stoppedAt = now - Reasons.PAUSED_GRACE_MILLIS, now = now, inJam = false))
    }

    @Test
    fun aDeviceSaysWhatItIsDoing() {
        val status = RoomSide.status(session = "abc", title = "Blue", artist = "A", playing = true, steering = null)
        assertEquals("""{"session":"abc","title":"Blue","artist":"A","playing":true,"steering":null}""", status.toString())
    }
}
