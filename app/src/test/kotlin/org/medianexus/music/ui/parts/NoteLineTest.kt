package org.medianexus.music.ui.parts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    /** Each change of what is on screen, as "up: …" or "down: …", with when. */
    private class Screen {
        val seen = mutableListOf<String>()
        var now: () -> Long = { 0 }
        val show = { note: Toast, up: Boolean -> seen += "${now()} ${if (up) "up" else "down"}: ${note.text}"; Unit }
    }

    @Test
    fun aNoteAloneLastsItsTimeAsBefore() = runTest {
        val line = NoteLine()
        val screen = Screen().apply { now = { testScheduler.currentTime } }
        backgroundScope.launch { line.run(screen.show) }
        line.add(playlist)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf("0 up: ${playlist.text}", "3200 down: ${playlist.text}"), screen.seen)
        line.add(arrival)
        advanceTimeBy(1)
        advanceTimeBy(10_000)
        assertEquals(listOf("10000 up: ${arrival.text}", "18000 down: ${arrival.text}"), screen.seen.drop(2))
    }

    @Test
    fun aNewerNoteWaitsForTheOneShowingOldestFirst() = runTest {
        val line = NoteLine()
        val screen = Screen().apply { now = { testScheduler.currentTime } }
        backgroundScope.launch { line.run(screen.show) }
        line.add(arrival)
        runCurrent()
        advanceTimeBy(1_000)
        // The queue's own note, a second later: it does not cut the arrival short.
        line.add(playlist)
        advanceTimeBy(20_000)
        assertEquals(
            listOf(
                "0 up: ${arrival.text}", "8000 down: ${arrival.text}",
                "${8000 + NoteLine.BETWEEN_MILLIS} up: ${playlist.text}", "${8000 + NoteLine.BETWEEN_MILLIS + 3200} down: ${playlist.text}",
            ),
            screen.seen,
        )
    }

    @Test
    fun pressingTheButtonTakesTheNoteDownAndTheNextComes() = runTest {
        val line = NoteLine()
        val screen = Screen().apply { now = { testScheduler.currentTime } }
        backgroundScope.launch { line.run(screen.show) }
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
    fun theSameSentenceIsNeverQueuedTwice() = runTest {
        val line = NoteLine()
        val screen = Screen()
        backgroundScope.launch { line.run(screen.show) }
        line.add(playlist)
        runCurrent()
        // Showing: not again.
        line.add(Toast(playlist.text))
        line.add(arrival)
        line.add(Toast(arrival.text, "Requests") {})
        assertEquals(listOf(arrival.text), line.queued.map { it.text })
    }

    @Test
    fun theLineStaysShortTheOldestWithoutAButtonGoFirst() = runTest {
        val line = NoteLine()
        // (Nothing showing: everything waits.)
        line.add(Toast("one"))
        line.add(arrival)
        line.add(Toast("two"))
        line.add(Toast("three"))
        assertEquals(listOf(arrival.text, "two", "three"), line.queued.map { it.text })
        line.add(Toast("four", "Undo") {})
        assertEquals(listOf(arrival.text, "three", "four"), line.queued.map { it.text })
        // Only notes with something to do on them: they all stay.
        line.add(Toast("five", "Undo") {})
        line.add(Toast("six", "Undo") {})
        assertEquals(listOf(arrival.text, "four", "five", "six"), line.queued.map { it.text })
    }
}
