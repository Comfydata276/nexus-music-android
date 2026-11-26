package org.medianexus.music.ui.parts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.Toasts.Toast

/**
 * The passing notes as they are shown ([NoteLine]): as 0.5.1 and Windows, the newest takes
 * the place of the one showing at once; and the line is emptied at sign-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteLineTest {
    @get:Rule val limit: Timeout = Timeout.seconds(60)

    private val arrival = Toast("“Low Water” has arrived, on “Tidewater”.", "Requests") {}
    private val playlist = Toast("That playlist is no longer on your server.")

    /** What is on screen: each change as "<when> up|down: <words>", and the note showing. */
    private class Screen(private val now: () -> Long) {
        val seen = mutableListOf<String>()
        var showing: Toast? = null
        val show = { note: Toast, up: Boolean ->
            seen += "${now()} ${if (up) "up" else "down"}: ${note.text}"
            showing = if (up) note else null
        }

        /** The button on screen pressed, as the host does it. */
        fun press(line: NoteLine) {
            val note = showing!!
            line.dismiss()
            note.onAction!!()
        }
    }

    private fun TestScope.running(line: NoteLine): Screen {
        val screen = Screen { testScheduler.currentTime }
        backgroundScope.launch { line.run(screen.show) }
        runCurrent()
        return screen
    }

    private fun TestScope.line() = NoteLine(now = { testScheduler.currentTime })

    @Test
    fun aNoteAloneLastsItsTimeAsIn051() = runTest {
        val line = line()
        val screen = running(line)
        line.add(playlist)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf("0 up: ${playlist.text}", "3200 down: ${playlist.text}"), screen.seen)
        line.add(arrival)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf("10000 up: ${arrival.text}", "18000 down: ${arrival.text}"), screen.seen.drop(2))
    }

    @Test
    fun aNewerNoteTakesThePlaceOfTheOneShowingAtOnce() = runTest {
        val line = line()
        val screen = running(line)
        line.add(arrival)
        runCurrent()
        advanceTimeBy(1_000)
        // The queue's own note, a second later: it is shown now, for its own time from now.
        line.add(playlist)
        runCurrent()
        assertEquals(playlist, screen.showing)
        advanceTimeBy(20_000)
        assertEquals(listOf("0 up: ${arrival.text}", "1000 up: ${playlist.text}", "4200 down: ${playlist.text}"), screen.seen)
    }

    @Test
    fun aMoveThenARemovalTheUndoOnScreenIsTheRemovals() = runTest {
        val line = line()
        val screen = running(line)
        val undone = mutableListOf<String>()
        line.add(Toast("Moved", "Undo") { undone += "move" })
        runCurrent()
        advanceTimeBy(2_000)
        // A removal from the same playlist while "Moved" shows: the move's Undo is stale now,
        // and the one on screen at once is the removal's, which works.
        line.add(Toast("Removed from playlist", "Undo") { undone += "removal" })
        runCurrent()
        assertEquals("Removed from playlist", screen.showing?.text)
        screen.press(line)
        runCurrent()
        assertEquals(listOf("removal"), undone)
        assertNull(screen.showing)
        // Nothing comes up after it: the stale "Moved" is gone, not waiting.
        advanceTimeBy(20_000)
        assertEquals(listOf("0 up: Moved", "2000 up: Removed from playlist", "2000 down: Removed from playlist"), screen.seen)
    }

    @Test
    fun twoPlaylistMovesTheUndoOnScreenIsTheSeconds() = runTest {
        val line = line()
        val screen = running(line)
        val undone = mutableListOf<Int>()
        line.add(Toast("Moved", "Undo") { undone += 1 })
        runCurrent()
        advanceTimeBy(1_000)
        line.add(Toast("Moved", "Undo") { undone += 2 })
        runCurrent()
        // One "Moved" on screen, and it is the second's: its Undo is the one that works.
        screen.press(line)
        assertEquals(listOf(2), undone)
        runCurrent()
        assertNull(screen.showing)
        advanceTimeBy(20_000)
        assertEquals(listOf("0 up: Moved", "1000 up: Moved", "1000 down: Moved"), screen.seen)
    }

    @Test
    fun theSecondMoveHasItsOwnTimeFromWhenItCame() = runTest {
        val line = line()
        val screen = running(line)
        line.add(Toast("Moved", "Undo") {})
        runCurrent()
        advanceTimeBy(1_000)
        line.add(Toast("Moved", "Undo") {})
        runCurrent()
        advanceTimeBy(7_999)
        assertEquals("Moved", screen.showing?.text)
        advanceTimeBy(2)
        assertNull(screen.showing)
    }

    @Test
    fun anErrorAfterASuccessShowsAtOnce() = runTest {
        val line = line()
        val screen = running(line)
        line.add(Toast("Added to playlist"))
        runCurrent()
        advanceTimeBy(500)
        val error = Toast("That didn't save. The server said no.")
        line.add(error)
        runCurrent()
        assertEquals(error, screen.showing)
        advanceTimeBy(3_199)
        assertEquals(error, screen.showing)
        advanceTimeBy(2)
        assertNull(screen.showing)
    }

    @Test
    fun signingOutEmptiesTheLineTheNoteShowingIncluded() = runTest {
        val line = line()
        val screen = running(line)
        line.add(Toast("Moved", "Undo") {})
        runCurrent()
        advanceTimeBy(1_000)
        line.clear()
        runCurrent()
        assertNull(screen.showing)
        advanceTimeBy(20_000)
        assertEquals(listOf("0 up: Moved", "1000 down: Moved"), screen.seen)
        // The next account's notes come as ever.
        line.add(Toast("Signed in"))
        runCurrent()
        assertEquals("Signed in", screen.showing?.text)
    }

    @Test
    fun aNoteGivenAndClearedBeforeItIsPutUpNeverShows() = runTest {
        val line = line()
        val screen = running(line)
        var said = 0
        line.add(Toast(arrival.text, "Requests", shown = { said++ }) {})
        line.clear()
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals(emptyList<String>(), screen.seen)
        assertEquals(0, said)
    }

    // ---- a note that must know it was seen (an arrival's) ------------------------------------------

    @Test
    fun anArrivalIsSaidWhenItsNoteIsTheOneShowing() = runTest {
        val line = line()
        running(line)
        var said = 0
        line.add(Toast("Moved", "Undo") {})
        runCurrent()
        line.add(Toast(arrival.text, "Requests", shown = { said++ }) {})
        assertEquals("given, not yet up", 0, said)
        runCurrent()
        assertEquals(1, said)
        // Replaced a second later, it was still said once, and not again.
        advanceTimeBy(1_000)
        line.add(playlist)
        runCurrent()
        assertEquals(1, said)
    }

    @Test
    fun anArrivalPutUpWhileTheAppIsHiddenIsNotSaid() = runTest {
        var inFront = false
        val line = NoteLine(now = { testScheduler.currentTime }, inFront = { inFront })
        running(line)
        var said = 0
        line.add(Toast(arrival.text, "Requests", shown = { said++ }) {})
        runCurrent()
        assertEquals(0, said)
        // Offered again when the app is back (the next collection), and seen then.
        inFront = true
        line.add(Toast(arrival.text, "Requests", shown = { said++ }) {})
        runCurrent()
        assertEquals(1, said)
    }
}
