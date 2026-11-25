package org.medianexus.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
        // The list's own choice is what is remembered (as a change, for other devices too:
        // sync/Chosen.kt), beside how the queue plays overall.
        fun remembered(settings: Settings, shuffle: Boolean, repeat: Int, source: String, now: Long): Settings {
            val overall = Remembered.with(settings, shuffle, repeat)
            return Remembered.listChoice(settings, shuffle, source)?.let { org.medianexus.music.sync.Chosen.made(overall, it, now) } ?: overall
        }
        val after = remembered(before, shuffle = true, repeat = Repeat.OFF, source = "playlist:p1", now = 1_800_000_000)
        assertEquals(ListPrefs(shuffle = true, at = 1_800_000_000), after.lists["playlist:p1"])
        assertTrue(after.shuffle)
        // Remembered: nothing more to write while it plays on.
        assertFalse(Remembered.differs(after, shuffle = true, repeat = Repeat.OFF, source = "playlist:p1"))
        // A list played in order when its own choice was shuffle is told so too.
        val off = remembered(after, shuffle = false, repeat = Repeat.OFF, source = "playlist:p1", now = 1_800_000_100)
        assertEquals(false, off.lists["playlist:p1"]!!.shuffle)
        // What it had chosen besides (its sort) is kept.
        val sorted = Settings(shuffle = true, lists = mapOf("album:a" to ListPrefs(sort = 3, descending = true, sortChosen = true)))
        assertEquals(ListPrefs(shuffle = true, sort = 3, descending = true, sortChosen = true, at = 5), remembered(sorted, true, Repeat.OFF, "album:a", 5).lists["album:a"])
        // A list never shuffled, played in order: nothing to remember. Nor for songs that
        // came from no list.
        assertFalse(Remembered.differs(Settings(), shuffle = false, repeat = Repeat.OFF, source = "playlist:p2"))
        assertTrue(remembered(Settings(), true, Repeat.ALL, source = "", now = 5).lists.isEmpty())
        assertEquals(Repeat.ALL, remembered(Settings(), true, Repeat.ALL, source = "", now = 5).repeat)
    }

    // ---- Work for the car, off the main thread ----------------------------------------------------

    @Test
    fun workThatNeverPausesIsStillGivenUpOnAndRunsOnAnotherThread() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val asking = Thread.currentThread()
        var worked: Thread? = null
        // Laying out a very long list: it does not pause anywhere a time limit could reach
        // it. Here it is work that has begun and is held at a gate, on its own thread, for
        // as long as the test likes. Wrapped in a limit on the thread that asked, such work
        // held that thread (the main thread) until it had finished, limit or no limit.
        val begun = CountDownLatch(1)
        val gate = CountDownLatch(1)
        try {
            // (The limit runs on this test's clock, which moves on by itself when nothing
            // else can: no real time is waited, however long the limit is.)
            // On a thread of its own that has begun the work before the limit is armed:
            // handed to Dispatchers.Default, the work could still be waiting for a thread
            // when the test's clock (which has nothing else to wait for) reached the limit,
            // and was then never begun at all, and the test waited for it for ever (seen with
            // the machine busy).
            val startedFirst = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    Thread(block).start()
                    begun.await()
                }
            }
            OffMain.within(scope, limitMs = 7_000, on = startedFirst) {
                worked = Thread.currentThread()
                begun.countDown()
                gate.await()
                "too late"
            }
            fail("the answer was waited for")
        } catch (_: TimeoutCancellationException) {
            // Given up on at the limit, with the work still where it was: not when the
            // work chose to finish, which it has not.
            assertEquals("the work finished before it was given up on", 1L, gate.count)
        }
        begun.await()
        assertNotEquals(asking, worked)
        gate.countDown()
        // And an answer in time is the answer. (Worked out on the test's own clock too:
        // on a real thread it would be racing a limit that takes no real time to pass.)
        assertEquals("the songs", OffMain.within(scope, limitMs = 5_000, on = StandardTestDispatcher(testScheduler)) { "the songs" })
        scope.cancel()
    }
}
