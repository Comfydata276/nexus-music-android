package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' connection to the player, tried again by itself after a failure or a drop. */
class ReconnectTest {
    /** What is waiting to run, and after how long: run by the test, never by a clock. */
    private val waiting = mutableListOf<Pair<Runnable, Long>>()
    private var tries = 0
    private val again = Reconnect(
        post = { run, delay -> waiting += run to delay },
        cancel = { run -> waiting.removeAll { it.first === run } },
        connect = { tries++ },
    )

    private fun runWaiting() {
        val now = waiting.toList()
        waiting.clear()
        now.forEach { it.first.run() }
    }

    @Test
    fun aFailureIsTriedAgainAfterAPauseThatGrows() {
        again.wanted()
        again.lost()
        assertEquals(listOf(1_000L), waiting.map { it.second })
        runWaiting()
        assertEquals(1, tries)
        again.lost()
        assertEquals(listOf(2_000L), waiting.map { it.second })
        runWaiting()
        repeat(6) { again.lost(); runWaiting() }
        again.lost()
        // Never more than half a minute apart.
        assertEquals(listOf(30_000L), waiting.map { it.second })
        // Connected: the count starts again, and nothing is waiting.
        again.connected()
        assertTrue(waiting.isEmpty())
        again.lost()
        assertEquals(listOf(1_000L), waiting.map { it.second })
    }

    @Test
    fun nothingIsTriedOnceTheScreensHaveLetGo() {
        again.wanted()
        again.lost()
        again.stop()
        assertTrue(waiting.isEmpty())
        // A drop reported after letting go (releasing reports one) asks nothing.
        again.lost()
        assertTrue(waiting.isEmpty())
        assertEquals(0, tries)
    }
}
