package org.medianexus.music.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Album
import org.medianexus.music.net.Found
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.library.SearchRank

/** What was found, and the words it was found for: the two go together everywhere (what is
 *  shown, and what tapping a song plays), so results are never shown under other words. */
data class Results(val query: String, val found: Found)

/** A search that failed, and the words it was for. */
data class Failure(val query: String, val error: ApiError)

/** How a search is going: the words in the box, what was found for them (only ever for
 *  these words), and whether it is still looking or failed (a failure, like what was found,
 *  belongs to the words it was for). */
data class SearchState(
    val query: String = "",
    val results: Results? = null,
    val searching: Boolean = false,
    val failed: Failure? = null,
) {
    /** What was found, if it was found for the words in the box. */
    val found: Found? get() = results?.takeIf { it.query == query }?.found

    /** Why the search for the words in the box failed, if it did. */
    val error: ApiError? get() = failed?.takeIf { it.query == query }?.error

    /** What was found, only if it was found for `typed`, the words in the box as they are this
     *  moment: until the search has caught up with the box (a keystroke ago), the rows of
     *  the words before are not shown, nor tappable. */
    fun resultsFor(typed: String): Results? = results?.takeIf { it.query == query && it.query == typed.trim() }
}

/**
 * Searching as the words are typed: each change waits a moment for the next keystroke
 * (`waitMillis`), and a newer search always replaces an older one still on its way, so what
 * is shown is never for words that have since changed. Every change of words, and every
 * "Try again", is a request of its own: going back to words already searched for (or that
 * failed) asks again. "Try again" goes the same way as typing, so a slow retry can't arrive
 * after, and over, a newer search either. It searches only while [run] runs (the page runs it
 * while the app is in front); stopped part-way, it asks again for the words in the box when it
 * runs again. Plain Kotlin, so it can be tested on its own.
 */
@OptIn(FlowPreview::class)
class SearchModel(
    scope: CoroutineScope?,
    private val search: suspend (String) -> Found,
    private val waitMillis: Long = WAIT_MILLIS,
) {
    /** The words, which change of them this is, and whether it is to go at once. */
    private data class Ask(val query: String, val serial: Long, val now: Boolean = false)

    private var serials = 0L
    private val typed = MutableStateFlow(Ask("", 0))
    /** The last ask carried through to an answer or a failure. */
    @Volatile private var done = -1L
    private val now = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = now.asStateFlow()

    init {
        // Given a scope, it runs in it for as long as the scope lasts (the tests).
        scope?.launch { run() }
    }

    /** Searches as asked, for as long as this runs. */
    suspend fun run() {
        typed
            // Clearing the box, or trying again, is acted on at once; words wait for the
            // typing to pause.
            .debounce { if (it.query.isBlank() || it.now) 0 else waitMillis }
            .collectLatest { ask -> if (ask.serial != done) carryOut(ask) }
    }

    /** The words in the box now. */
    fun type(text: String) {
        val query = text.trim()
        if (query == typed.value.query) return
        typed.value = Ask(query, ++serials)
        // Whatever was found for other words stops being shown at once, not when the next
        // search answers.
        now.update { it.copy(query = query, searching = query.isNotBlank(), failed = null) }
    }

    /** Asks again for the words shown, after a failure. */
    fun retry() {
        val ask = typed.value
        if (ask.query.isBlank()) return
        typed.value = Ask(ask.query, ++serials, now = true)
        now.update { if (it.query == ask.query) it.copy(searching = true, failed = null) else it }
    }

    private suspend fun carryOut(ask: Ask) {
        val query = ask.query
        if (query.isBlank()) {
            now.value = SearchState()
            done = ask.serial
            return
        }
        now.update { if (it.query == query) it.copy(searching = true, failed = null) else it }
        val found = try {
            search(query)
        } catch (e: ApiError) {
            // What was found before for these same words stays, with the error beside it;
            // under other words (typed since, still within the pause) it isn't said at all.
            now.update { if (it.query == query) it.copy(searching = false, failed = Failure(query, e)) else it }
            done = ask.serial
            return
        }
        now.update { if (it.query == query) SearchState(query, Results(query, found)) else it }
        done = ask.serial
    }

    companion object {
        const val WAIT_MILLIS = 250L
    }
}

/** The kinds a search can be narrowed to, as its chips name them (Windows'
 *  `SEARCH_FILTERS`): All, Songs, Artists, Albums, Playlists. */
enum class SearchKind { ALL, SONGS, ARTISTS, ALBUMS, PLAYLISTS }

/** What a search page shows with one of its chips chosen. */
data class SearchView(
    /** An artist matches the words better than any song: the artists go above the songs
     *  (Windows' `artist_first`). */
    val artistsFirst: Boolean = false,
    val songs: List<Song> = emptyList(),
    /** More songs than are shown: "Show all" leads to the Songs chip. */
    val moreSongs: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()

    companion object {
        /** Songs "All" shows before "Show all", as on Windows. */
        const val TOP_SONGS = 4

        /**
         * Arranges what was found as Windows does (`search` and `search_view` in
         * src/pages.rs): under "All", the top few songs, then artists, albums and the
         * playlists whose names match, an artist who matches the words better than any song
         * coming first; under any other chip, that kind in full.
         */
        fun of(query: String, found: Found, playlists: List<Playlist>, kind: SearchKind): SearchView {
            val matching = SearchRank.playlistsMatching(query, playlists)
            return when (kind) {
                SearchKind.ALL -> SearchView(
                    artistsFirst = SearchRank.artistFirst(query, found.songs, found.artists),
                    songs = found.songs.take(TOP_SONGS),
                    moreSongs = found.songs.size > TOP_SONGS,
                    artists = found.artists,
                    albums = found.albums,
                    playlists = matching,
                )
                SearchKind.SONGS -> SearchView(songs = found.songs)
                SearchKind.ARTISTS -> SearchView(artists = found.artists)
                SearchKind.ALBUMS -> SearchView(albums = found.albums)
                SearchKind.PLAYLISTS -> SearchView(playlists = matching)
            }
        }
    }
}
