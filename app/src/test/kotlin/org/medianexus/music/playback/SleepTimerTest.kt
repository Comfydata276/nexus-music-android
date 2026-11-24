package org.medianexus.music.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windows' sleep timer (src/playback.rs): minutes, or the end of this song; it pauses. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    @Test
    fun minutes_pause_once_at_the_time_and_count_down_in_whole_minutes() = runTest {
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        var slept = 0
        timer.player = SleepTimer.Sleeper { slept++ }
        timer.set(15)
        assertEquals(15, timer.minutesLeft())
        advanceTimeBy(14 * 60_000L + 1)
        assertEquals("rounded up, at least one", 1, timer.minutesLeft())
        assertEquals(0, slept)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, slept)
        assertEquals(SleepTimer.State.Off, timer.state.value)
        advanceTimeBy(60 * 60_000L)
        assertEquals("once", 1, slept)
    }

    @Test
    fun a_new_choice_replaces_the_old_and_off_is_off() = runTest {
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        var slept = 0
        timer.player = SleepTimer.Sleeper { slept++ }
        timer.set(5)
        advanceTimeBy(4 * 60_000L)
        timer.set(10)
        advanceTimeBy(2 * 60_000L)
        assertEquals("the five minutes were replaced", 0, slept)
        timer.endOfSong()
        advanceTimeBy(20 * 60_000L)
        assertEquals("the ten were replaced by the song's end", 0, slept)
        timer.set(0)
        assertFalse(timer.songEnded())
        assertEquals(SleepTimer.State.Off, timer.state.value)
    }

    @Test
    fun the_end_of_the_song_ends_it_once() = runTest {
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        timer.endOfSong()
        assertTrue(timer.songEnded())
        assertEquals(SleepTimer.State.Off, timer.state.value)
        assertFalse("the next song plays on", timer.songEnded())
    }

    /** The minutes run out in the same instant the song ends (the player moving to the next):
     *  it pauses once, whichever comes first, and nothing plays on past it. */
    @Test
    fun with_the_song_ending_as_it_fires_it_pauses_once() = runTest {
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        var slept = 0
        timer.player = SleepTimer.Sleeper { slept++ }
        timer.set(1)
        advanceTimeBy(60_000)
        // The song ends now too: that isn't the timer's (it was minutes, not the song's end).
        assertFalse(timer.songEnded())
        runCurrent()
        assertEquals(1, slept)
        assertEquals(SleepTimer.State.Off, timer.state.value)
    }

    /** Rule 6 of the fixes: the timer counts and pauses on the player's thread, in turn with
     *  the person's choices there; nothing of it happens on another thread. */
    @Test
    fun the_pause_is_carried_out_on_the_players_thread_and_only_for_the_timer_still_set() {
        val player = TestCoroutineScheduler()
        val elsewhere = TestCoroutineScheduler()
        val timer = SleepTimer(kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(elsewhere)), { player.currentTime }, StandardTestDispatcher(player))
        var slept = 0
        timer.player = SleepTimer.Sleeper { slept++ }
        timer.set(1)
        // Time passing anywhere but on the player's thread does nothing.
        elsewhere.advanceTimeBy(10 * 60_000L)
        elsewhere.runCurrent()
        assertEquals(0, slept)
        // On the player's thread, Off made just before the minute is up: no pause.
        player.advanceTimeBy(59_000)
        timer.set(0)
        player.advanceTimeBy(60_000)
        player.runCurrent()
        assertEquals(0, slept)
        // A timer set again and left: it pauses, there.
        timer.set(1)
        player.advanceTimeBy(60_000)
        player.runCurrent()
        assertEquals(1, slept)
    }
}
