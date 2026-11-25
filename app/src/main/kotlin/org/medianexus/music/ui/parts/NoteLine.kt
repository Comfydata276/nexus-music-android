package org.medianexus.music.ui.parts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.Toasts.Toast

/**
 * The passing notes waiting to be shown, **one after another, each for its time, oldest
 * first**: a newer note never cuts a showing one short (an arrival's note, with "Requests"
 * on it, was replaced a second after it came by a note about the queue). The same sentence
 * is never waiting twice, nor waiting while it shows. And the line stays short: with more
 * than [waitingAtMost] waiting, the oldest that have nothing to do on them go; one with a
 * button stays.
 *
 * Plain Kotlin, so it can be tested on its own; [ToastHost] runs it on the main thread,
 * which is the only thread that touches it.
 */
class NoteLine(private val waitingAtMost: Int = 3) {
    private val waiting = ArrayDeque<Toast>()
    private var showing: Toast? = null
    private val more = Channel<Unit>(Channel.CONFLATED)
    private var gone: CompletableDeferred<Unit>? = null

    /** What is waiting, oldest first (for the tests). */
    val queued: List<Toast> get() = waiting.toList()

    /** A note to show. */
    fun add(note: Toast) {
        if (showing?.text == note.text || waiting.any { it.text == note.text }) return
        waiting.addLast(note)
        while (waiting.size > waitingAtMost) {
            val plain = waiting.indexOfFirst { it.action == null }
            if (plain < 0) break
            waiting.removeAt(plain)
        }
        more.trySend(Unit)
    }

    /** The showing note's button was pressed: it goes now, and the next comes. */
    fun dismiss() {
        gone?.complete(Unit)
    }

    /**
     * Shows the notes as they come, for as long as this runs: `show` puts one up (true) or
     * takes it down (false). Each stays for its time ([lasts]), or until [dismiss].
     */
    suspend fun run(show: (Toast, Boolean) -> Unit, between: Long = BETWEEN_MILLIS) {
        while (true) {
            val note = waiting.removeFirstOrNull()
            if (note == null) {
                more.receive()
                continue
            }
            showing = note
            val dismissed = CompletableDeferred<Unit>().also { gone = it }
            show(note, true)
            try {
                withTimeoutOrNull(lasts(note)) { dismissed.await() }
            } finally {
                show(note, false)
                showing = null
                gone = null
            }
            // (Time for the one going to be gone before the next comes up.)
            if (waiting.isNotEmpty()) kotlinx.coroutines.delay(between)
        }
    }

    companion object {
        /** How long a note stays: one with a button long enough to press it (Windows' eight
         *  seconds), the rest three. */
        fun lasts(note: Toast): Long = if (note.action != null) 8_000 else 3_200

        /** Between one note going and the next coming up: its fade. */
        const val BETWEEN_MILLIS = 300L
    }
}
