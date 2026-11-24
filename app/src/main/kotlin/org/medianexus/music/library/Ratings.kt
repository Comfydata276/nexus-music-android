package org.medianexus.music.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Song
import org.medianexus.music.store.Pending

/** A rating that didn't save, already put back to what it was. */
data class RatingRefused(val song: String, val rating: Int, val error: ApiError)

/**
 * Star ratings, one to five, as Windows' `rate_song`: shown at once (the newest the app knows,
 * see [Freshest]), then sent through the account's one ordered sender, and held while offline
 * like a like. A rating the server refuses goes back to what it was, and is said.
 */
class Ratings(
    private val freshest: Freshest,
    /** The account's one sender (it tells [freshest] what was made and when it settles);
     *  `written` is told once the change is in its queue. */
    private val submit: suspend (Pending, written: () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val order = kotlinx.coroutines.sync.Mutex()

    private val refusals = MutableSharedFlow<RatingRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<RatingRefused> = refusals.asSharedFlow()

    /** The rating shown for `song`, 0 for none. */
    fun of(song: Song): Int = freshest.ratingOf(song)

    /** Rates `song` (0 removes its rating). */
    fun rate(song: Song, rating: Int) {
        if (song.id.isEmpty() || song.radioUrl.isNotEmpty()) return
        val change = Pending.Rating(song.id, rating.coerceIn(0, 5), at = clock())
        // Started here and now, and queued in turn: two quick ratings go in the order made.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            order.lock()
            var let = false
            val letGo = { if (!let) { let = true; order.unlock() } }
            try {
                submit(change, letGo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                refusals.emit(RatingRefused(change.id, change.rating, e))
            } finally {
                letGo()
            }
        }
    }

    /** A rating that had been held has had its answer; refused, it is said. */
    fun answered(change: Pending, refusal: ApiError?) {
        if (change is Pending.Rating && refusal != null) refusals.tryEmit(RatingRefused(change.id, change.rating, refusal))
    }
}
