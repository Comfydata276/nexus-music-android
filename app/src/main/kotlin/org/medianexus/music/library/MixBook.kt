package org.medianexus.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.store.Played

/** The mixes as they stand, and what they were made from (for a mix that isn't among them:
 *  a genre no longer played most, opened from an earlier visit). */
class MixSet(val mixes: List<Mix>, val from: Mixes.From) {
    fun find(id: String): Mix? = mixes.firstOrNull { it.id == id }
}

/**
 * The "Made for you" mixes of the account, kept up to date: worked out again, off the main
 * thread, whenever what they are made from changes (a newer copy of the library, a like,
 * a song started, a mix of one's own made or changed) and when the day changes (the shuffled
 * ones are shuffled afresh each day, by the UTC date, as on Windows). Home, the mixes' pages
 * and the car read [state]; nothing waits for it, and the row fills in when it is ready.
 *
 * Null until there is a whole copy of the library: mixes are chosen from it, as Windows
 * chooses them from its own (`search::current`).
 */
class MixBook(
    scope: CoroutineScope,
    copy: StateFlow<LibrarySnapshot?>,
    liked: StateFlow<Set<String>>,
    /** Whether `liked` is the whole of what's liked yet (see `Likes.known`). */
    likesKnown: StateFlow<Boolean>,
    recent: StateFlow<List<Played>>,
    rules: StateFlow<List<MixRules>>,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** How long working them out took (for Diagnostics' debug log). */
    private val took: (mixes: Int, songs: Int, millis: Double) -> Unit = { _, _, _ -> },
) {
    private val current = MutableStateFlow<MixSet?>(null)
    val state: StateFlow<MixSet?> = current.asStateFlow()

    /** The day (since 1970, UTC) now, and again each time it changes. */
    private val days = flow {
        while (true) {
            val at = now()
            emit(at / Mixes.DAY)
            // Just past the next midnight, UTC.
            delay(((at / Mixes.DAY + 1) * Mixes.DAY - at) * 1000 + 1000)
        }
    }

    init {
        scope.launch {
            combine(copy, combine(liked, likesKnown) { likes, known -> likes.takeIf { known } }, recent, rules, days) { snapshot, likes, played, own, _ -> Inputs(snapshot, likes, played, own) }
                .collectLatest { inputs ->
                    val snapshot = inputs.snapshot?.takeIf { it.complete } ?: return@collectLatest
                    // (A burst of changes, a like and the copy at once, is worked out once.)
                    delay(SETTLE_MILLIS)
                    current.value = withContext(Dispatchers.Default) {
                        val started = System.nanoTime()
                        // Until the likes are known, the star each song carries in the copy (as
                        // Windows' `is_liked` falls back to the song's own): a phone offline with
                        // no kept Liked songs page would otherwise like nothing.
                        val likes = inputs.likes ?: starredIn(snapshot.songs)
                        val from = Mixes.From(snapshot.songs, { it in likes }, inputs.recent.map { it.song.id to it.at }, inputs.rules, now())
                        MixSet(Mixes.all(from), from).also { took(it.mixes.size, snapshot.songs.size, (System.nanoTime() - started) / 1e6) }
                    }
                }
        }
    }

    /** `likes` is null until the whole of Liked songs has been heard. */
    private class Inputs(val snapshot: LibrarySnapshot?, val likes: Set<String>?, val recent: List<Played>, val rules: List<MixRules>)

    /** The songs the copy itself says are liked. */
    private fun starredIn(table: org.medianexus.music.store.SongTable): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until table.size) if (table.starred(i)) out += table.id(i)
        return out
    }

    private companion object {
        const val SETTLE_MILLIS = 100L
    }
}
