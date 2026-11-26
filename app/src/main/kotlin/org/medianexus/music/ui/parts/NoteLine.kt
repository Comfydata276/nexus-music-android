package org.medianexus.music.ui.parts

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.Toasts.Toast

/**
 * The passing notes, **one after another, oldest first**: a newer note never cuts a showing
 * one short (an arrival's note, with "Requests" on it, was replaced a second after it came by
 * a note about the queue). The rule, whole:
 *
 * - **The same words twice.** Neither with a button: one is enough, the newer is not added.
 *   With a button: the newer takes the older's place where it stands, showing or waiting,
 *   because the older's button is the stale one (two playlist moves: the Undo offered is the
 *   second's, which is the one that works).
 * - **At most [waitingAtMost] waiting.** Over that, the oldest waiting with no button goes;
 *   then the oldest waiting of any kind. The one showing is never cut short by this.
 * - **How long.** Three seconds; one with a button eight, or five while others are waiting.
 *   Its button pressed, it goes at once and the next comes.
 * - **At sign-out, or another account** ([clear]), the line is emptied, the note showing too:
 *   nothing said under one account, button and all, is shown under the next.
 *
 * Plain Kotlin, so it can be tested on its own; [ToastHost] runs it on the main thread, which
 * is the only thread that touches it.
 */
class NoteLine(
    private val waitingAtMost: Int = 4,
    /** Milliseconds, from any fixed point (a test's own clock). */
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private val waiting = ArrayDeque<Toast>()
    private var showing: Toast? = null

    /** What the note showing is to do: be replaced (by the note here), or go. */
    private var replaceWith: Toast? = null
    private var goNow = false

    /** Something has changed that the loop must look at. */
    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** What is waiting, oldest first (for the tests). */
    val queued: List<Toast> get() = waiting.toList()

    /** A note to show. */
    fun add(note: Toast) {
        val buttons = note.action != null
        val current = replaceWith ?: showing
        if (current != null && current.text == note.text) {
            if (!buttons && current.action == null) return
            replaceWith = note
            poke.trySend(Unit)
            return
        }
        val same = waiting.indexOfFirst { it.text == note.text }
        if (same >= 0) {
            if (!buttons && waiting[same].action == null) return
            waiting[same] = note
            poke.trySend(Unit)
            return
        }
        waiting.addLast(note)
        while (waiting.size > waitingAtMost) {
            val plain = waiting.indexOfFirst { it.action == null }
            waiting.removeAt(if (plain >= 0) plain else 0)
        }
        poke.trySend(Unit)
    }

    /** The showing note's button was pressed: it goes now, and the next comes. */
    fun dismiss() {
        if (showing == null) return
        goNow = true
        poke.trySend(Unit)
    }

    /** Sign-out, or another account: nothing waits, and the note showing goes. */
    fun clear() {
        waiting.clear()
        replaceWith = null
        dismiss()
    }

    /**
     * Shows the notes as they come, for as long as this runs: `show` puts one up (true) or
     * takes it down (false).
     */
    suspend fun run(show: (Toast, Boolean) -> Unit, between: Long = BETWEEN_MILLIS) {
        while (true) {
            val first = waiting.removeFirstOrNull()
            if (first == null) {
                poke.receive()
                continue
            }
            var note: Toast = first
            showing = note
            goNow = false
            var since = now()
            show(note, true)
            try {
                while (!goNow) {
                    val newer = replaceWith
                    if (newer != null) {
                        replaceWith = null
                        note = newer
                        showing = newer
                        since = now()
                        show(newer, true)
                    }
                    val left = since + lasts(note, othersWaiting = waiting.isNotEmpty()) - now()
                    if (left <= 0) break
                    withTimeoutOrNull(left) { poke.receive() }
                }
            } finally {
                show(note, false)
                showing = null
                replaceWith = null
            }
            // (Time for the one going to be gone before the next comes up.)
            if (waiting.isNotEmpty()) delay(between)
        }
    }

    companion object {
        /** How long a note stays: one with a button long enough to press it (Windows' eight
         *  seconds), or five while others wait their turn; the rest three. */
        fun lasts(note: Toast, othersWaiting: Boolean = false): Long = when {
            note.action == null -> 3_200
            othersWaiting -> 5_000
            else -> 8_000
        }

        /** Between one note going and the next coming up: its fade. */
        const val BETWEEN_MILLIS = 300L
    }
}
