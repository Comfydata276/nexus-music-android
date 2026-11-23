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
     * A row dragged from `row` to `target` among `rows` (the rows as shown when the drag
     * began), as a move of that entry to after another, both named by number: carried out
     * on the queue as it is when the command arrives, it means what the person saw. Null if
     * nothing moves.
     */
    fun entryMove(rows: List<QueueRow>, row: Int, target: Int): EntryMove? {
        val entries = rows.filterIsInstance<QueueRow.Entry>()
        val move = move(row, target, rows.indexOf(QueueRow.Gap), entries.count { it.queued }) ?: return null
        val uid = entries.getOrNull(move.from)?.uid?.takeIf { it >= 0 } ?: return null
        // The entry it ends up after, once lifted out: none for the top.
        val rest = entries.filterIndexed { place, _ -> place != move.from }
        val after = if (move.to <= 0) null else (rest.getOrNull(move.to - 1) ?: rest.lastOrNull())?.uid
        return EntryMove(uid, after, move.queued)
    }

    /** As [entryMove], against the player's queue as it published it. */
    fun entryMove(upNext: UpNext, row: Int, target: Int): EntryMove? = entryMove(of(upNext.songs, upNext.queuedAhead, upNext.uids), row, target)

    /**
     * The rows the player will report once it has made `move` (as `Queue.moveUid` makes it):
     * the entry lifted out and put straight after `after` (at the top if that has gone),
     * in or out of "Next in queue". Unchanged if the entry isn't there.
     */
    fun moved(rows: List<QueueRow>, move: EntryMove): List<QueueRow> {
        val entries = rows.filterIsInstance<QueueRow.Entry>().toMutableList()
        val from = entries.indexOfFirst { it.uid == move.uid }
        if (from < 0) return rows
        val entry = entries.removeAt(from)
        val anchor = if (move.after == null) -1 else entries.indexOfFirst { it.uid == move.after }
        entries.add(anchor + 1, entry.copy(queued = move.queued))
        return rowsOf(entries)
    }

    /** The rows the player will report once it has taken out the entry `uid`. */
    fun removed(rows: List<QueueRow>, uid: Long): List<QueueRow> = rowsOf(rows.filterIsInstance<QueueRow.Entry>().filter { it.uid != uid })

    /** Rows for these entries: "Next in queue" is the run of queued ones at the top, as the
     *  queue counts it. */
    private fun rowsOf(entries: List<QueueRow.Entry>): List<QueueRow> {
        val ahead = entries.indexOfFirst { !it.queued }.let { if (it < 0) entries.size else it }
        return of(entries.map { it.song }, ahead, entries.map { it.uid })
    }

    /** The same entries, in the same order, in and out of "Next in queue" alike. */
    fun sameOrder(a: List<QueueRow>, b: List<QueueRow>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.key == y.key && (x !is QueueRow.Entry || y !is QueueRow.Entry || x.queued == y.queued)
        }
}

/**
 * The queue sheet's rows as shown, and every edit made on them: plain state, so it can be
 * tested without a screen.
 *
 * There is one list, the rows as shown, and every edit (a drag, a swipe, "Move up" or "Move
 * down", "Remove from queue") is worked out against it and sent naming entries by their own
 * numbers: the one moved, and the one it is to follow. The edit shows at once, and the rows
 * it should leave are remembered, in the order sent.
 *
 * The player's reports then decide. One that is what the edits sent so far should leave on
 * the way (the first edit made, the second not yet) changes nothing on screen: the rest are
 * still coming. One that is anything else (an edit refused, the song changed meanwhile) is
 * what is shown, and the edits still awaited are forgotten: whatever the player does with
 * them comes in its next report. There is no waiting for a time to pass: a move answered
 * slowly is not put back, nor one refused kept on show past the player's next word.
 *
 * While a song is being dragged nothing moves under the finger: reports wait until it is let
 * go (or the drag cancelled), and then count as above, before the drag's own edit.
 */
class QueueDrag {
    val rows = androidx.compose.runtime.mutableStateListOf<QueueRow>()

    /** The key of the row being dragged. */
    var dragging: String? by androidx.compose.runtime.mutableStateOf(null)
        private set

    /** The rows each edit sent and not yet reported should leave, oldest first. */
    private val expected = ArrayList<List<QueueRow>>()
    /** A report that came while a song was being dragged. */
    private var held: List<QueueRow>? = null
    /** The rows as shown when the drag began, and where the dragged one was. */
    private var before: List<QueueRow> = emptyList()
    private var from = -1

    /** The player's queue now. */
    fun show(upNext: UpNext) {
        val reported = QueueRows.of(upNext.songs, upNext.queuedAhead, upNext.uids)
        if (dragging != null) {
            held = reported
            return
        }
        follow(reported)
    }

    private fun follow(reported: List<QueueRow>) {
        val at = expected.indexOfFirst { QueueRows.sameOrder(it, reported) }
        if (at >= 0) {
            // On the way to what's shown: the edits it accounts for are done.
            repeat(at + 1) { expected.removeAt(0) }
            // All of them: the player's own rows (the same, perhaps with fresher songs).
            if (expected.isEmpty() && rows.toList() != reported) set(reported)
            return
        }
        expected.clear()
        if (rows.toList() != reported) set(reported)
    }

    private fun takeHeld() {
        held?.let { held = null; follow(it) }
    }

    fun start(key: String) {
        if (dragging != null) return
        val at = rows.indexOfFirst { it.key == key }
        if (at < 0 || rows[at] !is QueueRow.Entry) return
        dragging = key
        from = at
        before = rows.toList()
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
        val target = rows.indexOfFirst { it.key == key }
        val move = if (target >= 0) QueueRows.entryMove(before, from, target) else null
        dragging = null
        // Shown as it was when the drag began, then whatever the player said meanwhile, then
        // the move on top of that.
        set(before)
        takeHeld()
        if (move != null) edited(QueueRows.moved(rows.toList(), move))
        return move
    }

    /** The drag was cancelled: everything as it was, and nothing sent. */
    fun cancel() {
        if (dragging == null) return
        dragging = null
        set(before)
        takeHeld()
    }

    /** "Move up" (-1) or "Move down" (1) on the row `key`, as the rows are shown: the move to
     *  send, if it moves anything. */
    fun step(key: String, by: Int): EntryMove? {
        if (dragging != null) return null
        val at = rows.indexOfFirst { it.key == key }
        if (at < 0 || at + by !in rows.indices) return null
        val move = QueueRows.entryMove(rows.toList(), at, at + by) ?: return null
        edited(QueueRows.moved(rows.toList(), move))
        return move
    }

    /** A row taken out here (swiped away, or "Remove from queue"), ahead of the player's word.
     *  If the player's next report still has it, it is back. */
    fun removeNow(key: String) {
        // (Not while a song is being dragged: the player's next report shows it gone.)
        if (dragging != null) return
        val row = rows.firstOrNull { it.key == key } as? QueueRow.Entry ?: return
        edited(QueueRows.removed(rows.toList(), row.uid))
    }

    private fun edited(after: List<QueueRow>) {
        if (QueueRows.sameOrder(after, rows.toList())) return
        expected.add(after)
        set(after)
    }

    private fun set(new: List<QueueRow>) {
        rows.clear()
        rows.addAll(new)
    }
}
