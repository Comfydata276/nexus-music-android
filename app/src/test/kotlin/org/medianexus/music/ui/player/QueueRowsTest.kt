package org.medianexus.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.net.Song

/** The queue sheet's rows and the moves dragging them makes, as the Windows queue panel's
 *  (`show_queue` and `queue_row_moved` in src/playback.rs). */
class QueueRowsTest {
    private fun songs(vararg ids: String) = ids.map { Song(id = it) }

    private fun names(rows: List<QueueRow>) = rows.joinToString(" ") {
        when (it) {
            QueueRow.Gap -> "|"
            is QueueRow.Entry -> it.song.id + if (it.queued) "*" else ""
        }
    }

    @Test
    fun theHeadingSitsBetweenWhatWasQueuedAndTheRest() {
        assertEquals("q1* q2* | r1 r2", names(QueueRows.of(songs("q1", "q2", "r1", "r2"), 2)))
        // Only one kind: no heading in the list (the one above it says which).
        assertEquals("r1 r2", names(QueueRows.of(songs("r1", "r2"), 0)))
        assertEquals("q1* q2*", names(QueueRows.of(songs("q1", "q2"), 2)))
        assertEquals(2, QueueRows.gap(4, 2))
        assertEquals(-1, QueueRows.gap(2, 2))
        assertEquals(-1, QueueRows.gap(2, 0))
    }

    @Test
    fun movingWithinOnePartKeepsItThere() {
        // [q1 q2 | r1 r2]: q2 to the top stays queued; r2 above r1 stays among the rest.
        assertEquals(QueueMove(1, 0, true), QueueRows.move(1, 0, gap = 2, queued = 2))
        assertEquals(QueueMove(3, 2, false), QueueRows.move(4, 3, gap = 2, queued = 2))
    }

    @Test
    fun draggingAcrossTheHeadingJoinsOrLeavesNextInQueue() {
        // r2 dragged up to the top of the list: it joins "Next in queue", first.
        assertEquals(QueueMove(3, 0, true), QueueRows.move(4, 0, gap = 2, queued = 2))
        // r1 swapped with the heading: the last of "Next in queue", in the same place.
        assertEquals(QueueMove(2, 2, true), QueueRows.move(3, 2, gap = 2, queued = 2))
        // q1 dragged past the heading, to below r1: it leaves "Next in queue", after r1.
        assertEquals(QueueMove(0, 2, false), QueueRows.move(0, 3, gap = 2, queued = 2))
        // q2 swapped with the heading: the first of the rest, in the same place.
        assertEquals(QueueMove(1, 1, false), QueueRows.move(1, 2, gap = 2, queued = 2))
    }

    @Test
    fun withNoHeadingEverythingIsOfOneKind() {
        assertEquals(QueueMove(0, 2, false), QueueRows.move(0, 2, gap = -1, queued = 0))
        assertEquals(QueueMove(2, 0, true), QueueRows.move(2, 0, gap = -1, queued = 3))
    }

    @Test
    fun theHeadingItselfAndStayingPutMoveNothing() {
        assertNull(QueueRows.move(2, 0, gap = 2, queued = 2))
        assertNull(QueueRows.move(1, 1, gap = 2, queued = 2))
        assertNull(QueueRows.move(-1, 0, gap = 2, queued = 2))
    }
}
