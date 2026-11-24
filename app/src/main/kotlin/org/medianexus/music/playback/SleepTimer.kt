package org.medianexus.music.playback

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sleep timer, as Windows has it (src/playback.rs, "sleep timer"): a number of minutes,
 * or "when this song ends", after which the music pauses. It belongs to the app, not to a
 * screen (the [org.medianexus.music.Graph] holds it), so it carries on with every screen
 * closed while music plays; the playback service does the pausing ([player]). Pausing keeps
 * the queue and the place, as any pause does. Off again once it has paused.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** The player's thread, where the person's choices are made and the music is paused. */
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    sealed interface State {
        data object Off : State

        /** Pauses at `deadline`, on the [SystemClock.elapsedRealtime] clock. */
        data class At(val deadline: Long, val minutes: Int) : State

        /** Pauses when the song playing ends. */
        data object EndOfSong : State
    }

    /** What the playback service does when the time is up: pause, if anything is playing.
     *  Called on the player's thread, and only while the timer that asked is still the one
     *  set. Set while the service runs; with no service nothing is playing, and nothing need
     *  be. */
    fun interface Sleeper {
        fun sleep()
    }

    private val current = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = current.asStateFlow()

    @Volatile
    var player: Sleeper? = null

    private var job: Job? = null

    /** Pauses in `minutes` (0 is off). Replaces whatever was set. */
    fun set(minutes: Int) {
        job?.cancel()
        job = null
        if (minutes <= 0) {
            current.value = State.Off
            return
        }
        val at = State.At(clock() + minutes * 60_000L, minutes)
        current.value = at
        // Counted, and carried out, on the player's thread, in turn with everything the
        // person does there (Off, another timer, Play): a pause goes ahead only if this timer
        // is still the one set at that moment.
        job = scope.launch(main) {
            delay(minutes * 60_000L)
            if (current.value === at) fire()
        }
    }

    /** Pauses when the song playing ends (Windows' "End of this song"). */
    fun endOfSong() {
        job?.cancel()
        job = null
        current.value = State.EndOfSong
    }

    /** The song ended with "end of this song" set, however it ended: played to its end (the
     *  player pauses there by itself: the service asks it to while this is set) or failed
     *  and passed over (the service pauses with the next one lined up). Returns whether the
     *  timer was waiting for it; it is done now. */
    fun songEnded(): Boolean {
        if (current.value != State.EndOfSong) return false
        current.value = State.Off
        return true
    }

    /** Minutes left, rounded up, at least one, as Windows shows them; 0 when not counting. */
    fun minutesLeft(state: State = current.value): Int = when (state) {
        is State.At -> (((state.deadline - clock()).coerceAtLeast(0) + 59_999) / 60_000).toInt().coerceAtLeast(1)
        else -> 0
    }

    private fun fire() {
        job = null
        current.value = State.Off
        player?.sleep()
    }
}
