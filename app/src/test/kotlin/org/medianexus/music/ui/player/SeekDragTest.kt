package org.medianexus.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A seek dragged on Now Playing belongs to the queue entry it began on. */
class SeekDragTest {
    @Test
    fun theSameSongPlayingAgainIsAnotherEntryAndTakesNoSeek() {
        // The same song is in the queue twice in a row: entries 7 and 8.
        val drag = SeekDrag(entry = 7)
        drag.move(0.5f)
        assertEquals(0.5f, drag.at)
        // Entry 7 ends while the finger is down and 8 begins: let go, nothing is sent, and
        // the time shown is the song's own again.
        assertNull(drag.end(0.5f, entryNow = 8))
        assertNull(drag.at)
        // On the entry it began on, it seeks.
        val same = SeekDrag(entry = 8)
        same.move(0.25f)
        assertEquals(0.25f, same.end(0.25f, entryNow = 8))
    }

    @Test
    fun aDragThatEndsAnyOtherWayLeavesNoTimeShown() {
        val drag = SeekDrag(entry = 3)
        drag.move(0.9f)
        drag.cancel()
        assertNull(drag.at)
    }
}
