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
class Library(private val api: Subsonic, val pages: PageCache, val search: Search = ServerSearch(api)) {
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

    fun playlists() = PageSpec("playlists", ListSerializer(Playlist.serializer())) { api.playlists() }

    /** One playlist with its songs. */
    fun playlist(id: String) = PageSpec("playlist:$id", Playlist.serializer()) { api.playlist(id) }

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

    // ---- Asked for each time ----------------------------------------------------------------

    /** One song, from the server. */
    suspend fun song(id: String): Song = api.song(id)

    /** Random songs, optionally of one genre: never kept, as they're different each time. */
    suspend fun randomSongs(count: Int, genre: String? = null): List<Song> = api.randomSongs(count, genre)

    companion object {
        /** Albums asked for at a time in a grid, as on Windows. */
        const val GRID_PAGE = 60

        /** Albums asked for at a time when fetching a whole list. */
        const val ALL_PAGE = 500

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
 * Searching the library. For now the server does it (`search3`); a later version searches
 * the copy of the library kept on the phone, forgiving typos as Windows does
 * (src/search.rs), behind this same interface.
 */
fun interface Search {
    suspend fun find(query: String, limits: SearchLimits): Found
}

/** Searching by asking the server. */
class ServerSearch(private val api: Subsonic) : Search {
    override suspend fun find(query: String, limits: SearchLimits): Found =
        if (query.isBlank()) Found() else api.search(query.trim(), limits.artists, limits.albums, limits.songs)
}
