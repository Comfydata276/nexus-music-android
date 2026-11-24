package org.medianexus.music.library

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.medianexus.music.net.Album
import org.medianexus.music.net.AlbumList
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Page
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/**
 * The library as screens and the car see it: every list they show, each kept on the phone
 * by the page cache and checked with the server. The keys are the Windows app's route keys
 * where it has the same page ("album:<id>", "playlist:<id>", "liked"), so a list's choices
 * (shuffle, sort) are remembered under the same names on both.
 *
 * Each method returns a [PageSpec]; [page] gives it to a screen (kept copy, then the
 * server's), [quick] to the car (one answer as soon as possible).
 */
class Library(
    private val api: Subsonic,
    val pages: PageCache,
    val search: Search = ServerSearch(api),
    /** The phone's copy of the whole library (the Songs page; search), where there is one. */
    val copy: LibraryCopy? = null,
) {
    fun <T> page(spec: PageSpec<T>): Flow<Page<T>> = pages.page(spec)

    /** A page as a screen shows it: the kept copy, the server's, then any change made here
     *  while it's on screen. `freshFor` 0 asks the server even if it was asked just now
     *  (pulling down to refresh). */
    fun <T> live(spec: PageSpec<T>, freshFor: Long = PageCache.FRESH_FOR_MILLIS): Flow<Page<T>> = pages.live(spec, freshFor)

    suspend fun <T> quick(spec: PageSpec<T>, changed: ((T) -> Unit)? = null): T = pages.quick(spec, changed)

    suspend fun <T> fresh(spec: PageSpec<T>): T = pages.fresh(spec)

    /** What is kept of a page, without waiting for the server, which is asked afterwards
     *  (see [PageCache.later]). */
    suspend fun <T> later(spec: PageSpec<T>, freshFor: Long, changed: ((T) -> Unit)? = null): T? = pages.later(spec, freshFor, changed)

    // ---- Playlists ------------------------------------------------------------------------

    fun playlists() = PageSpec("playlists", ListSerializer(Playlist.serializer())) { playlistsShown(api.playlists()) }

    /** One playlist with its songs. */
    fun playlist(id: String) = PageSpec("playlist:$id", Playlist.serializer()) { playlistShown(id) { api.playlist(id) } }

    /** What the server sends of a playlist, as the phone shows it: set by the account to its
     *  playlists' `fetched`, so that changes still on their way are carried out on top. */
    @Volatile var playlistShown: suspend (id: String, fetch: suspend () -> Playlist) -> Playlist = { _, fetch -> fetch() }

    /** The same for the list of playlists (renames and deletions on their way). */
    @Volatile var playlistsShown: (List<Playlist>) -> List<Playlist> = { it }

    /** The internet radio stations set up on the server, as entries that play (Windows'
     *  Radio page). */
    fun stations() = PageSpec("radio", ListSerializer(Song.serializer())) { api.radioStations() }

    // ---- Albums ---------------------------------------------------------------------------

    /** One page of an album list: `size` albums from `offset` on. */
    fun albums(list: AlbumList, offset: Int = 0, size: Int = GRID_PAGE) =
        PageSpec("albums:${listKey(list)}:$offset:$size", ListSerializer(Album.serializer())) {
            api.albumList(list, size, offset)
        }

    /** Every album in a list, fetched a few hundred at a time. For the car's A to Z, which
     *  sorts them into letters. */
    fun allAlbums(list: AlbumList) =
        PageSpec("albums-all:${listKey(list)}", ListSerializer(Album.serializer())) {
            val all = mutableListOf<Album>()
            while (true) {
                val page = api.albumList(list, ALL_PAGE, all.size)
                all += page
                if (page.size < ALL_PAGE || all.size >= MOST_ALBUMS) break
            }
            all
        }

    /** One album with its songs. */
    fun album(id: String) = PageSpec("album:$id", Album.serializer()) { api.album(id) }

    // ---- Artists and genres ---------------------------------------------------------------

    fun artists() = PageSpec("artists", ListSerializer(Artist.serializer())) { api.artists() }

    /** One artist with their albums. */
    fun artist(id: String) = PageSpec("artist:$id", Artist.serializer()) { api.artist(id) }

    /** The genres that have music, A to Z, as Windows lists them. */
    fun genres() = PageSpec("genres", ListSerializer(Genre.serializer())) {
        api.genres().filter { it.name.isNotEmpty() }.sortedBy { it.name.lowercase() }
    }

    /** The albums of one genre, all of them (a genre rarely has more than a few hundred). */
    fun genre(name: String) = allAlbums(AlbumList.ByGenre(name)).let {
        PageSpec("genre:$name", it.serializer, it.fetch)
    }

    // ---- Liked ----------------------------------------------------------------------------

    /** Everything liked: songs (Liked songs, newest first as the server gives them), albums
     *  and artists. */
    fun liked() = PageSpec("liked", Found.serializer()) { api.starred() }

    /**
     * The songs of a list as the phone has it kept, by the list's route key ("album:<id>",
     * "playlist:<id>", "liked"); null if it isn't a list of that kind, or isn't kept. For
     * starting a list that a screen is showing without the screen handing over every song:
     * the screen shows the kept copy, so this is the same list.
     */
    suspend fun keptSongs(source: String): List<Song>? = when {
        // Every song, newest first, from the phone's copy of the library (the Songs page): as
        // places in it, not 50,000 songs (see PlacedSongs).
        source == SONGS -> copy?.state?.value?.let { copy -> PlacedSongs.of(copy.songs, copy.newestFirst) }
        // As the page shows it: with the likes and unlikes still being sent (see [likedShown]).
        source == "liked" -> likedShown(pages.kept(liked()))
        source.startsWith("album:") -> pages.kept(album(source.removePrefix("album:")))?.songs
        source.startsWith("playlist:") -> pages.kept(playlist(source.removePrefix("playlist:")))?.songs
        else -> null
    }

    /** Liked songs as its page shows it, from the server's list as kept: set by the account
     *  to its likes' `songsOf`, so a list started by name is the list on screen, a like or
     *  unlike still on its way included, and the song tapped is the song played. */
    @Volatile var likedShown: (Found?) -> List<Song>? = { it?.songs }

    // ---- Asked for each time ----------------------------------------------------------------

    /** One song, from the server. */
    suspend fun song(id: String): Song = api.song(id)

    /** Songs the server reckons like this one (it needs Last.fm or the like: none without). */
    suspend fun similarSongs(id: String, count: Int): List<Song> = api.similarSongs(id, count)

    /** An artist's most popular songs that the library has, by the server's word (Last.fm). */
    suspend fun topSongs(artistName: String, count: Int): List<Song> = api.topSongs(artistName, count)

    /** What the server knows about an artist besides their albums: similar artists above all. */
    suspend fun artistInfo(id: String): org.medianexus.music.net.ArtistInfo = api.artistInfo(id)

    /** Random songs, optionally of one genre: never kept, as they're different each time. */
    suspend fun randomSongs(count: Int, genre: String? = null): List<Song> = api.randomSongs(count, genre)

    companion object {
        /** Albums asked for at a time in a grid, as on Windows. */
        const val GRID_PAGE = 60

        /** Albums asked for at a time when fetching a whole list. */
        const val ALL_PAGE = 500

        /** The Songs page's key: every song in the library, as the copy on the phone has
         *  them (Windows' route key for its Songs page). */
        const val SONGS = "songs"

        /** A list is fetched no further than this. */
        const val MOST_ALBUMS = 20_000

        internal fun listKey(list: AlbumList): String = when (list) {
            AlbumList.Newest -> "newest"
            AlbumList.Recent -> "recent"
            AlbumList.Frequent -> "frequent"
            AlbumList.Random -> "random"
            AlbumList.Starred -> "starred"
            AlbumList.AlphabeticalByName -> "name"
            AlbumList.AlphabeticalByArtist -> "artist"
            AlbumList.ByYearDescending -> "year"
            is AlbumList.ByGenre -> "genre=${list.genre}"
        }
    }
}

/** How many of each kind a search asks for. */
@Serializable
data class SearchLimits(val artists: Int = 20, val albums: Int = 20, val songs: Int = 50)

/**
 * Searching the library: the phone's copy of it once there is one ([PhoneSearch], forgiving
 * typing mistakes as Windows does, src/search.rs), the server's search (`search3`) until
 * then. The Search tab, the car's search and spoken requests all come here.
 */
fun interface Search {
    suspend fun find(query: String, limits: SearchLimits): Found

    /** Whether an answer comes at once, with no server to wait for: typing needn't pause
     *  before it is asked. */
    val instant: Boolean get() = false
}

/**
 * Searching the phone's copy of the library ([LibraryCopy]), with Windows' index and
 * scores, once the first copy is whole; until then, the server (`server`), as Windows does.
 * Off the main thread; a search that more typing has replaced stops part-way (its caller
 * cancels it). `liked` says whether a song is liked now: those rank above others.
 */
class PhoneSearch(private val copy: LibraryCopy, private val server: Search, private val liked: (String) -> Boolean) : Search {
    override suspend fun find(query: String, limits: SearchLimits): Found {
        val ready = copy.ready ?: return server.find(query, limits)
        if (query.isBlank()) return Found()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            ready.find(query.trim(), limits, liked) { job?.isActive == false } ?: throw kotlinx.coroutines.CancellationException("a newer search replaced it")
        }
    }

    override val instant: Boolean get() = copy.ready != null
}

/** Searching by asking the server. */
class ServerSearch(private val api: Subsonic) : Search {
    override suspend fun find(query: String, limits: SearchLimits): Found =
        if (query.isBlank()) Found() else api.search(query.trim(), limits.artists, limits.albums, limits.songs)
}
