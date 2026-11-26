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

/** The passing notes as they are shown: one after another, each for its time ([NoteLine]). */
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
    }

    private fun TestScope.running(line: NoteLine): Screen {
        val screen = Screen { testScheduler.currentTime }
        backgroundScope.launch { line.run(screen.show) }
        runCurrent()
        return screen
    }

    private fun TestScope.line() = NoteLine(now = { testScheduler.currentTime })

    @Test
    fun aNoteAloneLastsItsTimeAsBefore() = runTest {
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
    fun aNewerNoteWaitsForTheOneShowingAndANoteWithAButtonIsShorterWhenOthersWait() = runTest {
        val line = line()
        val screen = running(line)
        line.add(arrival)
        runCurrent()
        advanceTimeBy(1_000)
        // The queue's own note, a second later: it doesn't cut the arrival short, and the
        // arrival, with something waiting behind it, has five seconds rather than eight.
        line.add(playlist)
        runCurrent()
        advanceTimeBy(20_000)
        assertEquals(
            listOf(
                "0 up: ${arrival.text}", "5000 down: ${arrival.text}",
                "${5000 + NoteLine.BETWEEN_MILLIS} up: ${playlist.text}", "${5000 + NoteLine.BETWEEN_MILLIS + 3200} down: ${playlist.text}",
            ),
            screen.seen,
        )
    }

    @Test
    fun twoPlaylistMovesTheUndoOfferedIsTheSeconds() = runTest {
        val line = line()
        val screen = running(line)
        val undone = mutableListOf<Int>()
        line.add(Toast("Moved", "Undo") { undone += 1 })
        runCurrent()
        advanceTimeBy(1_000)
        line.add(Toast("Moved", "Undo") { undone += 2 })
        runCurrent()
        // One "Moved" on screen, and it is the second's: its Undo is the one that works.
        screen.showing!!.onAction!!()
        assertEquals(listOf(2), undone)
        assertEquals(emptyList<Toast>(), line.queued)
        // And it has its own time from when it took the place.
        advanceTimeBy(7_999)
        assertEquals("Moved", screen.showing?.text)
        advanceTimeBy(2)
        assertNull(screen.showing)
    }

    @Test
    fun aNewerNoteWithAButtonTakesTheOldersPlaceWhereItWaits() = runTest {
        val line = line()
        running(line)
        line.add(playlist)
        runCurrent()
        val first = Toast("Moved", "Undo") {}
        val second = Toast("Moved", "Undo") {}
        line.add(first)
        line.add(Toast("Saved"))
        line.add(second)
        assertEquals(listOf("Moved", "Saved"), line.queued.map { it.text })
        assert(line.queued[0] === second)
    }

    @Test
    fun aPlainSentenceTwiceIsOne() = runTest {
        val line = line()
        running(line)
        line.add(playlist)
        runCurrent()
        line.add(Toast(playlist.text))
        line.add(Toast("Saved"))
        line.add(Toast("Saved"))
        assertEquals(listOf("Saved"), line.queued.map { it.text })
    }

    @Test
    fun anErrorAmongFourPlainNotesIsKept() = runTest {
        val line = line()
        running(line)
        line.add(Toast("Added to queue"))
        runCurrent()
        val error = Toast("That didn't save. The server said no.")
        line.add(Toast("one"))
        line.add(error)
        line.add(Toast("two"))
        line.add(Toast("three"))
        assertEquals(listOf("one", error.text, "two", "three"), line.queued.map { it.text })
    }

    @Test
    fun fiveNotesWithButtonsAtMostFourWaitTheOldestGoes() = runTest {
        val line = line()
        val screen = running(line)
        line.add(Toast("Showing", "Undo") {})
        runCurrent()
        for (n in 1..5) line.add(Toast("Moved $n", "Undo") {})
        assertEquals(listOf("Moved 2", "Moved 3", "Moved 4", "Moved 5"), line.queued.map { it.text })
        // A plain one waiting goes before any with a button.
        line.add(Toast("Saved"))
        line.add(Toast("Moved 6", "Undo") {})
        assertEquals(listOf("Moved 3", "Moved 4", "Moved 5", "Moved 6"), line.queued.map { it.text })
        // Each with others behind it has five seconds; the last, alone, eight.
        advanceTimeBy(60_000)
        val ups = screen.seen.filter { " up: " in it }.map { it.substringBefore(' ').toLong() }
        assertEquals(listOf(0L, 5300, 10600, 15900, 21200), ups)
        assertEquals("29200 down: Moved 6", screen.seen.last())
    }

    @Test
    fun pressingTheButtonTakesTheNoteDownAndTheNextComes() = runTest {
        val line = line()
        val screen = running(line)
        line.add(arrival)
        line.add(playlist)
        runCurrent()
        advanceTimeBy(2_000)
        line.dismiss()
        runCurrent()
        advanceTimeBy(NoteLine.BETWEEN_MILLIS + 1)
        assertEquals(listOf("0 up: ${arrival.text}", "2000 down: ${arrival.text}", "${2000 + NoteLine.BETWEEN_MILLIS} up: ${playlist.text}"), screen.seen)
    }

    @Test
    fun signingOutEmptiesTheLineTheNoteShowingIncluded() = runTest {
        val line = line()
        val screen = running(line)
        line.add(arrival)
        line.add(Toast("Moved", "Undo") {})
        line.add(playlist)
        runCurrent()
        advanceTimeBy(1_000)
        line.clear()
        runCurrent()
        assertNull(screen.showing)
        assertEquals(emptyList<Toast>(), line.queued)
        advanceTimeBy(20_000)
        assertEquals(listOf("0 up: ${arrival.text}", "1000 down: ${arrival.text}"), screen.seen)
        // The next account's notes come as ever.
        line.add(Toast("Signed in"))
        runCurrent()
        assertEquals("Signed in", screen.showing?.text)
    }
}
