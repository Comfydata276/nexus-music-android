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
import org.medianexus.music.playback.Voice

/** How a search is going: the words it is for, what was found for them, and whether it is
 *  still looking or failed. */
data class SearchState(
    val query: String = "",
    val found: Found? = null,
    val searching: Boolean = false,
    val error: ApiError? = null,
)

/**
 * Searching as the words are typed: each change waits a moment for the next keystroke
 * (`waitMillis`), and a newer search always replaces an older one still on its way, so what
 * is shown is never for words that have since changed. Plain Kotlin, so it can be tested on
 * its own.
 */
@OptIn(FlowPreview::class)
class SearchModel(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> Found,
    waitMillis: Long = WAIT_MILLIS,
) {
    private val typed = MutableStateFlow("")
    private val now = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = now.asStateFlow()

    init {
        scope.launch {
            typed
                // Clearing the box is shown at once; words wait for the typing to pause.
                .debounce { if (it.isBlank()) 0 else waitMillis }
                .distinctUntilChanged()
                .collectLatest { query -> run(query) }
        }
    }

    /** The words in the box now. */
    fun type(text: String) {
        typed.value = text.trim()
    }

    /** Asks again for the words shown, after a failure. */
    fun retry() {
        val query = now.value.query
        if (query.isNotBlank()) scope.launch { run(query) }
    }

    private suspend fun run(query: String) {
        if (query.isBlank()) {
            now.value = SearchState()
            return
        }
        now.update { it.copy(query = query, searching = true, error = null) }
        now.value = try {
            SearchState(query, search(query))
        } catch (e: ApiError) {
            // What was found before stays, with the error beside it.
            now.value.copy(searching = false, error = e)
        }
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
            val matching = playlists.filter { Voice.score(query, it.name) >= 0.6 }
            val artistScore = found.artists.maxOfOrNull { Voice.score(query, it.name) } ?: 0.0
            val songScore = found.songs.maxOfOrNull { Voice.score(query, it.title) } ?: 0.0
            return when (kind) {
                SearchKind.ALL -> SearchView(
                    artistsFirst = artistScore > 0.0 && artistScore >= songScore,
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
