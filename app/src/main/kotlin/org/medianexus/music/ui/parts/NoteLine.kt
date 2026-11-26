package org.medianexus.music.ui.parts

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.Toasts.Toast

/**
 * The passing notes, as 0.5.1 and the Windows app show them: **the newest takes the place of
 * the one showing, at once**, and has its own time from then. Nothing waits: a note replaced
 * is gone (its button with it: after a move and then a removal, the Undo on screen is the
 * removal's, which is the one that works). The rule, whole:
 *
 * - **How long.** Three seconds; one with a button eight (Windows' `toast_with_undo`). Its
 *   button pressed, it goes at once.
 * - **At sign-out, or another account** ([clear]), the note showing goes: nothing said under
 *   one account, button and all, stays up under the next.
 * - **A note that must know it was seen** (an arrival's: `Toast.shown`) is told when it is put
 *   up with the app in front, and at no other time. Replaced a moment later, it was still
 *   seen; the Requests page's Arrived row is its record.
 *
 * A button acts on the screen alive when it is pressed, not on the one that made the note
 * (`LiveScreen`): that is the callers' part.
 *
 * Plain Kotlin, so it can be tested on its own; [ToastHost] runs it on the main thread, which
 * is the only thread that touches it.
 */
class NoteLine(
    /** Milliseconds, from any fixed point (a test's own clock). */
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
    /** Whether the app is in front: a note put up while it isn't has not been seen, and its
     *  `shown` is not told. */
    private val inFront: () -> Boolean = { true },
) {
    /** The note to put up next, in place of any showing. */
    private var newest: Toast? = null
    private var showing = false
    private var goNow = false

    /** Something has changed that the loop must look at. */
    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** A note to show, now. */
    fun add(note: Toast) {
        newest = note
        poke.trySend(Unit)
    }

    /** The showing note's button was pressed: it goes now. */
    fun dismiss() {
        if (!showing) return
        goNow = true
        poke.trySend(Unit)
    }

    /** Sign-out, or another account: the note showing goes, and one not yet up never comes. */
    fun clear() {
        newest = null
        dismiss()
    }

    /**
     * Shows the notes as they come, for as long as this runs: `show` puts one up (true) or
     * takes it down (false).
     */
    suspend fun run(show: (Toast, Boolean) -> Unit) {
        while (true) {
            var note = newest
            if (note == null) {
                poke.receive()
                continue
            }
            showing = true
            goNow = false
            try {
                var since = 0L
                while (!goNow) {
                    val newer = newest
                    if (newer != null) {
                        newest = null
                        note = newer
                        since = now()
                        show(newer, true)
                        if (inFront()) newer.shown?.invoke()
                    }
                    val left = since + lasts(note!!) - now()
                    if (left <= 0) break
                    withTimeoutOrNull(left) { poke.receive() }
                }
            } finally {
                show(note!!, false)
                showing = false
            }
        }
    }

    companion object {
        /** How long a note stays: one with a button long enough to press it (Windows' eight
         *  seconds), the rest three (0.5.1's 3.2, the fade included). */
        fun lasts(note: Toast): Long = if (note.action != null) 8_000 else 3_200
    }
}
