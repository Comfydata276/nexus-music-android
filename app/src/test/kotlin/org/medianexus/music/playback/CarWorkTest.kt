package org.medianexus.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Settings

class CarWorkTest {
    // ---- A list's own shuffle ---------------------------------------------------------------

    @Test
    fun shuffleChosenOnAListIsThatListsOwnWhateverShuffleWasBefore() {
        // Shuffle is already on (the last thing played was shuffled). In the car, "Shuffle"
        // at the top of another playlist is chosen: nothing changes overall, so nothing used
        // to be written, and that playlist played in order the next time it was started.
        val before = Settings(shuffle = true, repeat = Repeat.OFF)
        assertTrue(Remembered.differs(before, shuffle = true, repeat = Repeat.OFF, source = "playlist:p1"))
        val after = Remembered.with(before, shuffle = true, repeat = Repeat.OFF, source = "playlist:p1", nowSeconds = 1_800_000_000)
        assertEquals(ListPrefs(shuffle = true, at = 1_800_000_000), after.lists["playlist:p1"])
        assertTrue(after.shuffle)
        // Remembered: nothing more to write while it plays on.
        assertFalse(Remembered.differs(after, shuffle = true, repeat = Repeat.OFF, source = "playlist:p1"))
        // A list played in order when its own choice was shuffle is told so too.
        val off = Remembered.with(after, shuffle = false, repeat = Repeat.OFF, source = "playlist:p1", nowSeconds = 1_800_000_100)
        assertEquals(false, off.lists["playlist:p1"]!!.shuffle)
        // What it had chosen besides (its sort) is kept.
        val sorted = Settings(shuffle = true, lists = mapOf("album:a" to ListPrefs(sort = 3, descending = true, sortChosen = true)))
        assertEquals(ListPrefs(shuffle = true, sort = 3, descending = true, sortChosen = true, at = 5), Remembered.with(sorted, true, Repeat.OFF, "album:a", 5).lists["album:a"])
        // A list never shuffled, played in order: nothing to remember. Nor for songs that
        // came from no list.
        assertFalse(Remembered.differs(Settings(), shuffle = false, repeat = Repeat.OFF, source = "playlist:p2"))
        assertTrue(Remembered.with(Settings(), true, Repeat.ALL, source = "", nowSeconds = 5).lists.isEmpty())
        assertEquals(Repeat.ALL, Remembered.with(Settings(), true, Repeat.ALL, source = "", nowSeconds = 5).repeat)
    }

    // ---- Work for the car, off the main thread ----------------------------------------------------

    @Test
    fun workThatNeverPausesIsStillGivenUpOnAndRunsOnAnotherThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val asking = Thread.currentThread()
        var worked: Thread? = null
        val began = System.nanoTime()
        try {
            // Laying out a very long list: it does not pause anywhere a time limit could
            // reach it. Wrapped in a limit on the thread that asked, it held that thread
            // (the main thread) until it had finished, limit or no limit.
            OffMain.within(scope, limitMs = 200) {
                worked = Thread.currentThread()
                val until = System.nanoTime() + 3_000_000_000L
                @Suppress("ControlFlowWithEmptyBody")
                while (System.nanoTime() < until) { }
                "too late"
            }
            fail("the answer was waited for")
        } catch (_: TimeoutCancellationException) {
            // Given up on at the limit, not when the work chose to finish. (Generously
            // judged: this machine is shared.)
            val waited = (System.nanoTime() - began) / 1_000_000
            assertTrue("gave up after $waited ms", waited < 2_000)
        }
        assertNotEquals(asking, worked)
        // And an answer in time is the answer.
        assertEquals("the songs", OffMain.within(scope, limitMs = 5_000) { "the songs" })
        scope.cancel()
    }
}
