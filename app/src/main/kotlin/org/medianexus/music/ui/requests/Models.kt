package org.medianexus.music.ui.requests

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import org.medianexus.music.core.Outside
import org.medianexus.music.core.OutsideArtist
import org.medianexus.music.core.Popular
import org.medianexus.music.requests.Found

// What the screens of requests hold while they are shown, as plain Kotlin so it can be
// tested on its own. Each asks only while its `run` runs, which the screen does for as long
// as it is on screen and the app is in front: leaving it cancels what it asked for (the
// requests in flight are cut off, and nothing is delivered). Asking for an album, adding an
// artist and taking one off the list are not looks, and are not here: they outlive the page
// (see `Asker` in `Here.kt`).

/**
 * "Not in your library" under a search: what is found beyond the library for the words in
 * the box, filling in as more of it is known. It waits for the typing to pause (each look
 * costs questions of MusicBrainz and Lidarr), and typing again, or clearing the box, stops
 * the look under way. Whatever the library hands over afterwards for a look that has been
 * left (`soFar` comes on one of its threads, and can come once more just after a stop) is
 * dropped. A look that finished is not asked again for the same words when the page comes
 * back ([kept]).
 */
class OutsideSearch(
    private val find: suspend (String, (Outside) -> Unit) -> Found<Outside>,
    private val waitMillis: Long = WAIT_MILLIS,
    kept: State? = null,
) {
    /** Where the look for `query` stands: what has been found so far (null before anything
     *  is), whether it has finished, and why not, in the library's sentence, if it failed. */
    data class State(val query: String = "", val found: Outside? = null, val done: Boolean = false, val why: String? = null) {
        val looking: Boolean get() = query.isNotEmpty() && !done
    }

    /** The words to look for, and which ask of them this is ([again] asks once more). */
    private data class Ask(val query: String, val serial: Int = 0)

    private val typed = MutableStateFlow(Ask(kept?.query.orEmpty()))
    private val now = MutableStateFlow(kept?.takeIf { it.done } ?: State())
    val state: StateFlow<State> = now.asStateFlow()

    /** Which look is the current one: a number of its own each time. */
    private val looks = AtomicLong()

    /** The words in the box now. */
    fun type(text: String) {
        val query = text.trim()
        if (query == typed.value.query) return
        typed.value = Ask(query)
        now.update { if (it.query == query) it else State(query) }
    }

    /** Looks again for the same words, what was found staying on show meanwhile: something
     *  has been asked for, and the page is to say so ("Requested"). */
    fun again() {
        val ask = typed.value
        if (ask.query.isEmpty()) return
        now.update { if (it.query == ask.query) it.copy(done = false, why = null) else it }
        typed.value = ask.copy(serial = ask.serial + 1)
    }

    /** Looks, as the words change, for as long as this runs. A change of words stops the
     *  look under way at once, not when the typing has paused. */
    suspend fun run() {
        typed.collectLatest { ask ->
            val query = ask.query
            if (query.isEmpty()) {
                now.value = State()
                return@collectLatest
            }
            // (Back on the page, with the same words: what was found stands.)
            if (now.value.query == query && now.value.done) return@collectLatest
            if (ask.serial == 0) delay(waitMillis)
            look(query)
        }
    }

    private suspend fun look(query: String) {
        val mine = looks.incrementAndGet()
        // (Looked for again: what is on show stays until more is known.)
        now.update { if (it.query == query) it.copy(done = false, why = null) else State(query) }
        try {
            val found = find(query) { soFar ->
                if (looks.get() == mine) now.update { if (it.query == query && !it.done) it.copy(found = soFar) else it }
            }
            if (looks.get() != mine) return
            now.update {
                if (it.query != query) it
                else when (found) {
                    is Found.Got -> it.copy(found = found.value, done = true, why = null)
                    is Found.Not -> it.copy(done = true, why = found.why)
                }
            }
        } finally {
            // (Left, or overtaken: nothing more of this one counts.)
            looks.compareAndSet(mine, mine + 1)
        }
    }

    companion object {
        /** How long the typing must pause before a look begins. */
        const val WAIT_MILLIS = 450L
    }
}

/**
 * A page that is one call of `RequestsPages` (an album beyond the library, what is on its
 * way): asked when the page is shown, and again when the person pulls it down or something
 * done on it has changed what it says. What is shown stays while it is asked again; a
 * failure with something on show is said beside it, not in its place.
 */
class PageLoad<T>(private val ask: suspend () -> Found<T>) {
    /** What is shown (null until something is), why the last ask didn't come to anything
     *  (the library's sentence), and whether it is being asked. */
    data class State<T>(val value: T? = null, val why: String? = null, val asking: Boolean = true)

    private val asks = MutableStateFlow(0)
    private val now = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = now.asStateFlow()

    /** Asks again (pulled down, "Try again", or after something done on the page). */
    fun again() {
        now.update { it.copy(asking = true) }
        asks.value++
    }

    suspend fun run() {
        asks.collectLatest {
            now.update { it.copy(asking = true) }
            val found = ask()
            now.update {
                when (found) {
                    is Found.Got -> State(found.value, null, asking = false)
                    is Found.Not -> it.copy(why = found.why, asking = false)
                }
            }
        }
    }
}

/**
 * An artist beyond the library: their page, then, once it is on screen, the songs they are
 * most played for (the slow part, asked after). Until those have come there is room for
 * them (`popular` null); if there are none, or they couldn't be had, there is nothing.
 */
class OutsideArtistModel(
    private val page: suspend () -> Found<OutsideArtist>,
    private val popular: suspend (String) -> Found<Popular>,
) {
    data class State(
        val artist: OutsideArtist? = null,
        val why: String? = null,
        val asking: Boolean = true,
        /** Null while being asked; then what to show (with no songs, nothing). */
        val popular: Popular? = null,
    )

    private val asks = MutableStateFlow(0)
    private val now = MutableStateFlow(State())
    val state: StateFlow<State> = now.asStateFlow()

    fun again() {
        now.update { it.copy(asking = true) }
        asks.value++
    }

    suspend fun run() {
        asks.collectLatest {
            now.update { it.copy(asking = true) }
            when (val found = page()) {
                is Found.Not -> now.update { it.copy(why = found.why, asking = false, popular = it.popular ?: Popular()) }
                is Found.Got -> {
                    val artist = found.value
                    // (What was most played is kept while it is asked again: it doesn't
                    // change from one minute to the next.)
                    now.update { State(artist, null, asking = false, popular = it.popular) }
                    if (artist.failed || artist.name.isEmpty()) {
                        now.update { it.copy(popular = Popular()) }
                        return@collectLatest
                    }
                    val most = (popular(artist.name) as? Found.Got)?.value ?: Popular()
                    now.update { it.copy(popular = most) }
                }
            }
        }
    }
}
