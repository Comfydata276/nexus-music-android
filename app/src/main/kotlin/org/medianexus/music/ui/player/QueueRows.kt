package org.medianexus.music.ui.player

import org.medianexus.music.net.Song
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.medianexus.music.playback.UpNext

/** A row of the queue sheet's list of what's to come: a song (with its queue entry's own
 *  number), or the heading between "Next in queue" and the rest of the list. */
sealed interface QueueRow {
    /** The row's key in the list: the entry's number, which moves with it. */
    val key: String

    data class Entry(val uid: Long, val song: Song, val queued: Boolean) : QueueRow {
        override val key: String get() = "u$uid"
    }

    data object Gap : QueueRow {
        override val key: String get() = "gap"
    }
}

/** A song moved in the queue sheet, as the queue is told it: from and to places after the
 *  song playing, and whether it is now in "Next in queue". */
data class QueueMove(val from: Int, val to: Int, val queued: Boolean)

/** A move as the player is told it: the entry, the entry it is to follow (null: straight
 *  after the song playing), and whether it is now in "Next in queue". */
data class EntryMove(val uid: Long, val after: Long?, val queued: Boolean)

/**
 * The queue sheet's list, as the Windows queue panel lays it out (`show_queue` in
 * src/playback.rs): the songs to come, with a heading row between the ones queued by hand
 * and the rest of the list when there are both. The heading takes a row of its own, so a song
 * can be dragged across it.
 */
object QueueRows {
    fun of(upcoming: List<Song>, queuedAhead: Int, uids: List<Long> = emptyList()): List<QueueRow> {
        val ahead = queuedAhead.coerceIn(0, upcoming.size)
        // An entry the player gave no number (none should be) still needs a key of its own.
        val rows = upcoming.mapIndexed { offset, song -> QueueRow.Entry(uids.getOrNull(offset)?.takeIf { it >= 0 } ?: (-2L - offset), song, offset < ahead) }.toMutableList<QueueRow>()
        if (ahead > 0 && ahead < upcoming.size) rows.add(ahead, QueueRow.Gap)
        return rows
    }

    /** Where the heading is among the rows, or -1 if there isn't one. */
    fun gap(upcoming: Int, queuedAhead: Int): Int = if (queuedAhead in 1 until upcoming) queuedAhead else -1

    /**
     * A row was dragged from `row` to `target` (both places among the rows, the heading
     * counted; `target` is where it ends up once dropped). A song moved above the heading
     * joins "Next in queue"; one moved below it goes back among the rest. A port of
     * `queue_row_moved` in src/playback.rs. Null if nothing moves.
     */
    fun move(row: Int, target: Int, gap: Int, queued: Int): QueueMove? {
        if (row < 0 || target < 0 || row == gap || row == target) return null
        return when {
            // No heading: everything shown is of one kind.
            gap < 0 -> QueueMove(row, target, queued > 0)
            row < gap -> if (target < gap) QueueMove(row, target, true) else QueueMove(row, target - 1, false)
            target <= gap -> QueueMove(row - 1, target, true)
            else -> QueueMove(row - 1, target - 1, false)
        }
    }

    /**
     * A row dragged from `row` to `target` among the rows of `upNext` as they were when the
     * drag began, as a move of that entry to after another, both named by number: carried
     * out on the queue as it is when the command arrives, it means what the person saw. Null
     * if nothing moves.
     */
    fun entryMove(upNext: UpNext, row: Int, target: Int): EntryMove? {
        val move = move(row, target, gap(upNext.songs.size, upNext.queuedAhead), upNext.queuedAhead) ?: return null
        val uid = upNext.uids.getOrNull(move.from)?.takeIf { it >= 0 } ?: return null
        // The entry it ends up after, once lifted out: none for the top.
        val rest = upNext.uids.filterIndexed { place, _ -> place != move.from }
        val after = if (move.to <= 0) null else rest.getOrNull(move.to - 1) ?: rest.lastOrNull()
        return EntryMove(uid, after, move.queued)
    }
}

/**
 * The queue sheet's rows as shown, and a drag through them: plain state, so it can be tested
 * without a screen.
 *
 * While nothing is being dragged the rows are the player's queue. A drag rearranges them as
 * the finger goes, against the queue as it was when the drag began; letting go gives the one
 * move to send, and the rows stay as the person left them until the player's next word on
 * the queue (which the move brings) or, if none comes (the move was refused, or changed
 * nothing), until [settle] puts the player's order back. A drag that is cancelled puts the
 * row back and sends nothing.
 */
class QueueDrag {
    val rows = androidx.compose.runtime.mutableStateListOf<QueueRow>()

    /** The key of the row being dragged. */
    var dragging: String? by androidx.compose.runtime.mutableStateOf(null)
        private set

    /** A move sent and not yet answered by a new queue. */
    var awaiting: UpNext? by androidx.compose.runtime.mutableStateOf(null)
        private set

    private var shown: UpNext = UpNext()
    private var from = -1
    private var before: UpNext? = null

    /** The player's queue now. Shown, unless a drag is under way. */
    fun show(upNext: UpNext) {
        shown = upNext
        if (dragging != null) return
        awaiting = null
        set(QueueRows.of(upNext.songs, upNext.queuedAhead, upNext.uids))
    }

    fun start(key: String) {
        val at = rows.indexOfFirst { it.key == key }
        if (at < 0 || rows[at] !is QueueRow.Entry) return
        dragging = key
        from = at
        before = shown
    }

    /** The dragged row has been brought over the row `key`: they change places. */
    fun over(key: String): Boolean {
        val lifted = dragging ?: return false
        val a = rows.indexOfFirst { it.key == lifted }
        val b = rows.indexOfFirst { it.key == key }
        if (a < 0 || b < 0 || a == b) return false
        rows.add(b, rows.removeAt(a))
        return true
    }

    /** The finger has let go: the move to send, if anything moved. */
    fun drop(): EntryMove? {
        val key = dragging ?: return null
        val snapshot = before
        val target = rows.indexOfFirst { it.key == key }
        dragging = null
        before = null
        val move = if (snapshot != null && target >= 0) QueueRows.entryMove(snapshot, from, target) else null
        if (move == null) {
            set(QueueRows.of(shown.songs, shown.queuedAhead, shown.uids))
        } else {
            awaiting = shown
        }
        return move
    }

    /** The drag was cancelled: everything as it was, and nothing sent. */
    fun cancel() {
        dragging = null
        before = null
        set(QueueRows.of(shown.songs, shown.queuedAhead, shown.uids))
    }

    /** The move sent hasn't changed the queue after all: the player's order again. */
    fun settle() {
        if (dragging != null) return
        awaiting = null
        set(QueueRows.of(shown.songs, shown.queuedAhead, shown.uids))
    }

    /** A row taken out here at once (swiped away), ahead of the player's word. */
    fun removeNow(key: String) {
        rows.removeAll { it.key == key }
    }

    private fun set(new: List<QueueRow>) {
        rows.clear()
        rows.addAll(new)
    }
}
