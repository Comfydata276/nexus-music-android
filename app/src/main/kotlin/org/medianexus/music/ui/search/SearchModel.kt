package org.medianexus.music.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** How a search is going: the words in the box, what was found for them (only ever for
 *  these words), and whether it is still looking or failed. */
data class SearchState(
    val query: String = "",
    val results: Results? = null,
    val searching: Boolean = false,
    val error: ApiError? = null,
) {
    /** What was found, if it was found for the words in the box. */
    val found: Found? get() = results?.takeIf { it.query == query }?.found
}

/**
 * Searching as the words are typed: each change waits a moment for the next keystroke
 * (`waitMillis`), and a newer search always replaces an older one still on its way, so what
 * is shown is never for words that have since changed. "Try again" goes the same way as
 * typing, so a slow retry can't arrive after, and over, a newer search either. Plain Kotlin,
 * so it can be tested on its own.
 */
@OptIn(FlowPreview::class)
class SearchModel(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> Found,
    waitMillis: Long = WAIT_MILLIS,
) {
    /** The words, and which try at them this is. */
    private data class Ask(val query: String, val attempt: Int = 0)

    private val typed = MutableStateFlow(Ask(""))
    private val now = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = now.asStateFlow()

    init {
        scope.launch {
            typed
                // Clearing the box, or trying again, is acted on at once; words wait for the
                // typing to pause.
                .debounce { if (it.query.isBlank() || it.attempt > 0) 0 else waitMillis }
                .distinctUntilChanged()
                .collectLatest { ask -> run(ask.query) }
        }
    }

    /** The words in the box now. */
    fun type(text: String) {
        val query = text.trim()
        if (query == typed.value.query) return
        typed.value = Ask(query)
        // Whatever was found for other words stops being shown at once, not when the next
        // search answers.
        now.update { it.copy(query = query, searching = query.isNotBlank(), error = null) }
    }

    /** Asks again for the words shown, after a failure. */
    fun retry() {
        val ask = typed.value
        if (ask.query.isNotBlank()) typed.value = ask.copy(attempt = ask.attempt + 1)
    }

    private suspend fun run(query: String) {
        if (query.isBlank()) {
            now.value = SearchState()
            return
        }
        now.update { it.copy(query = query, searching = true, error = null) }
        val found = try {
            search(query)
        } catch (e: ApiError) {
            // What was found before for these same words stays, with the error beside it.
            now.update { it.copy(searching = false, error = e) }
            return
        }
        now.update { if (it.query == query) SearchState(query, Results(query, found)) else it }
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
