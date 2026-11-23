package org.medianexus.music.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Settings

/** Work for the car that must neither run on the main thread nor be waited on for ever. */
object OffMain {
    /**
     * Runs `work` on another thread, in `scope`, and gives its answer; or gives up after
     * `limitMs` (`TimeoutCancellationException`), at once, whether or not the work has
     * reached a point where it can be stopped. Wrapping the work itself in a time limit
     * doesn't do that: the limit only takes effect where the work next pauses, and sorting
     * and weighing a long list never pauses.
     */
    @Throws(TimeoutCancellationException::class)
    suspend fun <T> within(scope: CoroutineScope, limitMs: Long, on: CoroutineDispatcher = Dispatchers.Default, work: suspend () -> T): T {
        val doing = scope.async(on) { work() }
        return try {
            withTimeout(limitMs) { doing.await() }
        } finally {
            // Nobody is waiting for it any more: it stops where it next can.
            doing.cancel()
        }
    }
}

/** What Settings remembers of how the queue plays: shuffle and repeat, and for the list the
 *  queue came from, that list's own shuffle. */
object Remembered {
    /** Whether Settings needs to be told how the queue plays now. */
    fun differs(settings: Settings, shuffle: Boolean, repeat: Int, source: String): Boolean =
        settings.shuffle != shuffle || settings.repeat != repeat || (source.isNotEmpty() && (settings.lists[source]?.shuffle ?: false) != shuffle)

    /**
     * Settings with how the queue plays now: shuffle and repeat as they are, and the list it
     * came from (`source`, its route key) remembering its own shuffle, as on Windows.
     *
     * The list's own choice is compared with the list's own, not with the shuffle that
     * happened to be on before. It used to be recorded only when the overall shuffle
     * changed: with shuffle already on, "Shuffle" chosen at the top of a list in the car
     * changed nothing overall, so that list's choice was never written, and the next time it
     * was started it played in order.
     */
    fun with(settings: Settings, shuffle: Boolean, repeat: Int, source: String, nowSeconds: Long): Settings {
        val lists = if (source.isNotEmpty() && (settings.lists[source]?.shuffle ?: false) != shuffle) {
            settings.lists + (source to (settings.lists[source] ?: ListPrefs()).copy(shuffle = shuffle, at = nowSeconds))
        } else {
            settings.lists
        }
        return settings.copy(shuffle = shuffle, repeat = repeat, lists = lists)
    }
}
