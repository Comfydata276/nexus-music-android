package org.medianexus.music.ui.player

import org.medianexus.music.net.Song

/** A row of the queue sheet's list of what's to come: a song (its place after the one
 *  playing), or the heading between "Next in queue" and the rest of the list. */
sealed interface QueueRow {
    /** `uid`-like key for the list: the place and the song, which is enough while nothing
     *  moves, and moves with the row while it is dragged. */
    val key: String

    data class Entry(val offset: Int, val song: Song, val queued: Boolean) : QueueRow {
        override val key: String get() = "song/$offset/${song.id}"
    }

    data object Gap : QueueRow {
        override val key: String get() = "gap"
    }
}

/** A song moved in the queue sheet, as the queue is told it: from and to places after the
 *  song playing, and whether it is now in "Next in queue". */
data class QueueMove(val from: Int, val to: Int, val queued: Boolean)

/**
 * The queue sheet's list, as the Windows queue panel lays it out (`show_queue` in
 * src/playback.rs): the songs to come, with a heading row between the ones queued by hand
 * and the rest of the list when there are both. The heading takes a row of its own, so a song
 * can be dragged across it.
 */
object QueueRows {
    fun of(upcoming: List<Song>, queuedAhead: Int): List<QueueRow> {
        val ahead = queuedAhead.coerceIn(0, upcoming.size)
        val rows = upcoming.mapIndexed { offset, song -> QueueRow.Entry(offset, song, offset < ahead) }.toMutableList<QueueRow>()
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
}
