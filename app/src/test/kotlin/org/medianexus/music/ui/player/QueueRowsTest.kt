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

    // ---- Named by entry, not by place ------------------------------------------------------

    /** A queue as the player publishes it: songs with their entries' numbers. */
    private fun upNext(ids: List<String>, uids: List<Long>, queued: Int = 0) =
        org.medianexus.music.playback.UpNext(ids.map { Song(id = it) }, queued, uids = uids)

    @Test
    fun theSameSongTwiceIsTwoRowsWithKeysOfTheirOwn() {
        val queue = upNext(listOf("x", "y", "x"), listOf(11, 12, 13))
        val rows = QueueRows.of(queue.songs, 0, queue.uids)
        assertEquals(listOf("u11", "u12", "u13"), rows.map { it.key })
        // The second "x" dragged to the top: that entry moves, not the first "x".
        assertEquals(EntryMove(13, null, false), QueueRows.entryMove(queue, 2, 0))
        // The first "x" dragged below "y": it follows "y".
        assertEquals(EntryMove(11, 12, false), QueueRows.entryMove(queue, 0, 1))
    }

    @Test
    fun aDragMeansWhatWasOnScreenEvenIfTheSongChangedMeanwhile() {
        val drag = QueueDrag()
        val before = upNext(listOf("a", "b", "c", "d"), listOf(1, 2, 3, 4))
        drag.show(before)
        drag.start("u4")
        // The song playing ends while the finger is down: the player's queue moves on.
        drag.show(upNext(listOf("b", "c", "d"), listOf(2, 3, 4)))
        // The rows on screen are untouched by it, and the drag goes on over them.
        assertEquals(listOf("u1", "u2", "u3", "u4"), drag.rows.map { it.key })
        drag.over("u2")
        drag.over("u1")
        // "d" was dropped at the top of what the person saw: it goes straight after the song
        // playing, named by its own number.
        assertEquals(EntryMove(4, null, false), drag.drop())
        // Shown as left until the player answers; then the player's order.
        assertEquals(listOf("u4", "u1", "u2", "u3"), drag.rows.map { it.key })
        drag.show(upNext(listOf("d", "b", "c"), listOf(4, 2, 3)))
        assertEquals(listOf("u4", "u2", "u3"), drag.rows.map { it.key })
    }

    @Test
    fun aCancelledDragPutsTheRowBackAndSendsNothing() {
        val drag = QueueDrag()
        drag.show(upNext(listOf("a", "b", "c"), listOf(1, 2, 3)))
        drag.start("u3")
        drag.over("u2")
        assertEquals(listOf("u1", "u3", "u2"), drag.rows.map { it.key })
        drag.cancel()
        assertEquals(listOf("u1", "u2", "u3"), drag.rows.map { it.key })
        assertNull(drag.awaiting)
        // Dropped where it started: nothing to send either.
        drag.start("u2")
        assertNull(drag.drop())
        assertEquals(listOf("u1", "u2", "u3"), drag.rows.map { it.key })
    }

    @Test
    fun aMoveThatChangesNothingShowsThePlayersOrderAgain() {
        val drag = QueueDrag()
        val queue = upNext(listOf("a", "b", "c"), listOf(1, 2, 3))
        drag.show(queue)
        drag.start("u1")
        drag.over("u2")
        assertEquals(EntryMove(1, 2, false), drag.drop())
        assertEquals(listOf("u2", "u1", "u3"), drag.rows.map { it.key })
        // The player refused it: no new queue comes, and the sheet settles back.
        drag.settle()
        assertEquals(listOf("u1", "u2", "u3"), drag.rows.map { it.key })
    }

    @Test
    fun aMoveAcrossTheHeadingNamesTheEntryItFollows() {
        // [q1 q2 | r1 r2]: r2 to just under q1 joins "Next in queue" after q1.
        val queue = upNext(listOf("q1", "q2", "r1", "r2"), listOf(5, 6, 7, 8), queued = 2)
        assertEquals(EntryMove(8, 5, true), QueueRows.entryMove(queue, 4, 1))
        // q1 below r1: leaves it, after r1.
        assertEquals(EntryMove(5, 7, false), QueueRows.entryMove(queue, 0, 3))
    }
}
