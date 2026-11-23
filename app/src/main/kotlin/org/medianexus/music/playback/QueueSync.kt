package org.medianexus.music.playback

/**
 * Keeps the player's playlist in step with the queue.
 *
 * The queue can be very long (a 20,000-song playlist), and everything the player's playlist
 * holds is paid for again and again on the main thread: ExoPlayer builds a media source for
 * each item, every state change compares whole timelines, and the session copies the lot to
 * the system and to the car whenever it changes. So the playlist is never the whole queue.
 * It is a window of it around the song playing: a little of what came before, and the next
 * two hundred or so, which is further ahead than anyone scrolls (Windows shows 400 rows of
 * what's to come; the cost of each item here is paid at every event of the player, so the
 * phone shows half that, with the total). The queue model holds everything, and the window
 * moves along it as the songs go by.
 *
 * The window moves in steps, not with every song: its edges only change when the song
 * playing crosses a multiple of [STEP], so the playlist (and everything that copies it) is
 * rebuilt once in a hundred songs rather than at every one. Rarely, and always at the same
 * places in a queue (its 125th song, its 225th, …): each move changes every place in the
 * playlist, and a car names a queue entry by its place (see `QueuePlayer.entryMeant` for
 * what is done about a tap that crosses a move).
 *
 * Plain Kotlin against [Playlist], which ExoPlayer stands behind in the app and a list
 * stands behind in tests.
 */
object QueueSync {
    /** Songs kept before the one playing, at least; and after it, at least (when the queue
     *  has that many). */
    const val BEFORE = 25
    const val AFTER = 200
    const val STEP = 100

    /** An entry of the queue as the playlist holds it: its own number, and where its song is
     *  in the queue's list of songs. */
    data class Slot(val uid: Long, val entry: Int)

    /** The player's playlist: what ExoPlayer is to the queue. Indexes are places in it. */
    interface Playlist {
        val count: Int

        /** The entry number of the item at `index`. */
        fun uidAt(index: Int): Long?

        /** Which item is the current one; meaningless when empty. */
        val currentIndex: Int

        /** Replaces everything, to play from `index` at `positionMs`. */
        fun set(slots: List<Slot>, index: Int, positionMs: Long)

        fun clear()

        /** Removes the items from `from` up to `to`. Never asked to remove the current one. */
        fun remove(from: Int, to: Int)

        fun add(at: Int, slots: List<Slot>)

        /** Makes another item the current one. */
        fun seekTo(index: Int, positionMs: Long)
    }

    /** The places of the queue's playing order that the playlist holds when the cursor is at
     *  `cursor` of `size`. */
    fun window(cursor: Int, size: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val from = (((cursor - BEFORE).coerceAtLeast(0)) / STEP) * STEP
        val to = (from + BEFORE + STEP + AFTER).coerceAtMost(size)
        return from until to
    }

    /** The window as slots, in playing order. */
    fun slots(queue: Queue): List<Slot> = window(queue.cursor, queue.order.size).map { place ->
        val entry = queue.order[place]
        Slot(queue.uids[entry], entry)
    }

    /** Whether the playlist holds the whole queue (so the player's own "repeat all" goes
     *  round all of it). */
    fun whole(queue: Queue): Boolean = window(queue.cursor, queue.order.size).let { it.first == 0 && it.last + 1 == queue.order.size }

    /** Puts the queue's window to the playlist afresh, at the current song. */
    fun load(queue: Queue, playlist: Playlist, positionMs: Long) {
        if (queue.isEmpty) {
            playlist.clear()
            return
        }
        val slots = slots(queue)
        playlist.set(slots, queue.cursor - window(queue.cursor, queue.order.size).first, positionMs.coerceAtLeast(0))
    }

    /**
     * Makes the playlist hold the queue's window without disturbing the song that's playing:
     * what comes after it and before it is changed around it, in as few changes as possible,
     * so the next song stays loaded when it hasn't changed (which is what keeps the join
     * gapless). If the song playing has left the queue, or the window no longer reaches it
     * (a jump far away), the playlist is loaded afresh at the queue's current song. If the
     * queue's current song is another one in the window (a jump nearby), the player is sent
     * there, to `positionMs`.
     *
     * Returns whether anything was changed.
     */
    fun sync(queue: Queue, playlist: Playlist, positionMs: Long = 0): Boolean {
        if (queue.isEmpty) {
            if (playlist.count == 0) return false
            playlist.clear()
            return true
        }
        val target = slots(queue)
        val k = queue.cursor - window(queue.cursor, queue.order.size).first
        val c = playlist.currentIndex
        val playing = if (playlist.count > 0) playlist.uidAt(c) else null
        val at = if (playing == null) -1 else target.indexOfFirst { it.uid == playing }
        if (at < 0) {
            playlist.set(target, k, positionMs.coerceAtLeast(0))
            return true
        }
        var changed = false
        // After the song playing: keep what is the same at each end, and replace the middle.
        val haveAfter = (c + 1 until playlist.count).map { playlist.uidAt(it) }
        val wantAfter = target.subList(at + 1, target.size)
        var head = 0
        while (head < haveAfter.size && head < wantAfter.size && haveAfter[head] == wantAfter[head].uid) head++
        var tail = 0
        while (tail < haveAfter.size - head && tail < wantAfter.size - head &&
            haveAfter[haveAfter.size - 1 - tail] == wantAfter[wantAfter.size - 1 - tail].uid) tail++
        if (haveAfter.size - head - tail > 0) {
            playlist.remove(c + 1 + head, c + 1 + haveAfter.size - tail)
            changed = true
        }
        if (wantAfter.size - head - tail > 0) {
            playlist.add(c + 1 + head, wantAfter.subList(head, wantAfter.size - tail))
            changed = true
        }
        // Before it, the same way. (Done second: it moves the current item's index, which
        // the part after it was counted from.)
        val haveBefore = (0 until c).map { playlist.uidAt(it) }
        val wantBefore = target.subList(0, at)
        head = 0
        while (head < haveBefore.size && head < wantBefore.size && haveBefore[head] == wantBefore[head].uid) head++
        tail = 0
        while (tail < haveBefore.size - head && tail < wantBefore.size - head &&
            haveBefore[haveBefore.size - 1 - tail] == wantBefore[wantBefore.size - 1 - tail].uid) tail++
        if (haveBefore.size - head - tail > 0) {
            playlist.remove(head, haveBefore.size - tail)
            changed = true
        }
        if (wantBefore.size - head - tail > 0) {
            playlist.add(head, wantBefore.subList(head, wantBefore.size - tail))
            changed = true
        }
        // The queue has moved to another song of the window (a jump): go there.
        if (at != k) {
            playlist.seekTo(k, positionMs.coerceAtLeast(0))
            changed = true
        }
        return changed
    }
}
