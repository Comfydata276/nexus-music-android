package org.medianexus.music.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.medianexus.music.R
import org.medianexus.music.library.Library
import org.medianexus.music.library.SearchLimits
import org.medianexus.music.net.Album
import org.medianexus.music.net.AlbumList
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/** How the car is asked to lay out a folder's contents. */
enum class CarStyle { LIST, GRID, CATEGORY_LIST, CATEGORY_GRID }

/**
 * One thing in the car's browse tree, as plain data: [CarMedia] turns it into what Media3
 * hands the car. `cover` is a cover id (shown through the cover provider); `icon` the name
 * of one of the app's drawables, for things with no cover of their own.
 */
data class CarEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    val icon: Int = 0,
    val playable: Boolean = false,
    val browsable: Boolean = false,
    /** The heading it is shown under, among the others in its list with the same one. */
    val group: String = "",
    /** How what's inside it is laid out. */
    val inside: CarStyle? = null,
    /** How it is drawn itself, when not as its list says. */
    val style: CarStyle? = null,
    val song: Song? = null,
)

/** The words the car shows that come from the app, not the library (from the string
 *  resources, so the tree can be tested without them). */
data class CarWords(
    val home: String = "Home",
    val playlists: String = "Playlists",
    val library: String = "Library",
    val albums: String = "Albums",
    val artists: String = "Artists",
    val genres: String = "Genres",
    val likedSongs: String = "Liked songs",
    val recentlyPlayed: String = "Recently played",
    val recentlyAdded: String = "Recently added",
    val songs: String = "Songs",
    val others: String = "Others",
    val songCount: (Int) -> String = { if (it == 1) "1 song" else "$it songs" },
    val albumCount: (Int) -> String = { if (it == 1) "1 album" else "$it albums" },
)

/** A list to play, from one of its songs, and where it came from. `shuffle` null plays it
 *  as that list was last played (each list remembers). */
data class PlayList(
    val songs: List<Song>,
    val start: Int,
    val context: String,
    val source: String,
    val shuffle: Boolean? = null,
    val anyStart: Boolean = false,
)

/**
 * The car's browse tree, built from the library's kept pages (so it opens at once, and with
 * no signal shows what was seen last), and what each song in it plays.
 *
 * Tabs: Home (Liked songs, then what was played and added lately), Playlists, Library
 * (Albums, Artists, Genres). Downloads joins them when there are downloads.
 *
 * Choosing a song plays its whole album or playlist from that song.
 */
class CarTree(private val library: Library, private val words: CarWords = CarWords()) {
    /** The tabs. */
    fun tabs(): List<CarEntry> = listOf(
        CarEntry(CarIds.format(CarNode.Home), words.home, icon = R.drawable.ic_house, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Playlists), words.playlists, icon = R.drawable.ic_list_music, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Library), words.library, icon = R.drawable.ic_library, browsable = true, inside = CarStyle.CATEGORY_LIST),
    )

    /**
     * What's inside a folder, or null if there's no such folder. `changed` is told when the
     * kept copy turns out to differ from the server's, so the car can be asked to look again.
     * Throws `ApiError` when there's no kept copy and the server can't be reached.
     */
    suspend fun children(node: CarNode, changed: () -> Unit = {}): List<CarEntry>? {
        val again: (Any?) -> Unit = { changed() }
        return when (node) {
            CarNode.Root -> tabs()
            CarNode.Home -> home(again)
            CarNode.Playlists -> library.quick(library.playlists(), again).map(::playlistEntry)
            CarNode.Library -> listOf(
                CarEntry(CarIds.format(CarNode.Albums), words.albums, icon = R.drawable.ic_disc_album, browsable = true, inside = CarStyle.GRID),
                CarEntry(CarIds.format(CarNode.Artists), words.artists, icon = R.drawable.ic_user_round, browsable = true, inside = CarStyle.LIST),
                CarEntry(CarIds.format(CarNode.Genres), words.genres, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.LIST),
            )
            CarNode.Albums -> {
                val all = library.quick(library.allAlbums(AlbumList.AlphabeticalByName), again)
                if (all.size <= MOST_IN_A_LIST) all.map { albumEntry(it) } else letters(all.map { letterOf(it.name) }) { CarNode.AlbumLetter(it) }
            }
            is CarNode.AlbumLetter -> library.quick(library.allAlbums(AlbumList.AlphabeticalByName), again)
                .filter { letterOf(it.name) == node.letter }.map { albumEntry(it) }
            CarNode.Artists -> {
                val all = library.quick(library.artists(), again)
                if (all.size <= MOST_IN_A_LIST) all.map(::artistEntry) else letters(all.map { letterOf(it.name) }) { CarNode.ArtistLetter(it) }
            }
            is CarNode.ArtistLetter -> library.quick(library.artists(), again).filter { letterOf(it.name) == node.letter }.map(::artistEntry)
            CarNode.Genres -> library.quick(library.genres(), again).map(::genreEntry)
            CarNode.Liked -> library.quick(library.liked(), again).songs.mapIndexed { i, song -> songEntry(song, ListRef.Liked, i) }
            is CarNode.Album -> library.quick(library.album(node.id), again).songs.mapIndexed { i, song -> songEntry(song, ListRef.Album(node.id), i) }
            is CarNode.Playlist -> library.quick(library.playlist(node.id), again).songs.mapIndexed { i, song -> songEntry(song, ListRef.Playlist(node.id), i) }
            is CarNode.Artist -> library.quick(library.artist(node.id), again).albums.map { albumEntry(it, byYear = true) }
            is CarNode.Genre -> library.quick(library.genre(node.name), again).map { albumEntry(it) }
            is CarNode.Track -> null
        }
    }

    /** One place in the tree on its own, as the car asks for it by id. Null if there's no
     *  such place. */
    suspend fun item(node: CarNode): CarEntry? = when (node) {
        CarNode.Root -> CarEntry(CarIds.ROOT, words.library, browsable = true)
        CarNode.Home, CarNode.Playlists, CarNode.Library -> tabs().firstOrNull { it.id == CarIds.format(node) }
        CarNode.Albums, CarNode.Artists, CarNode.Genres -> children(CarNode.Library)?.firstOrNull { it.id == CarIds.format(node) }
        CarNode.Liked -> CarEntry(CarIds.format(node), words.likedSongs, icon = R.drawable.ic_heart, browsable = true, inside = CarStyle.LIST)
        is CarNode.AlbumLetter -> CarEntry(CarIds.format(node), node.letter, browsable = true, inside = CarStyle.GRID)
        is CarNode.ArtistLetter -> CarEntry(CarIds.format(node), node.letter, browsable = true, inside = CarStyle.LIST)
        is CarNode.Album -> albumEntry(library.quick(library.album(node.id)))
        is CarNode.Artist -> artistEntry(library.quick(library.artist(node.id)))
        is CarNode.Playlist -> playlistEntry(library.quick(library.playlist(node.id)))
        is CarNode.Genre -> library.quick(library.genres()).firstOrNull { it.name == node.name }?.let(::genreEntry)
            ?: CarEntry(CarIds.format(node), node.name, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.GRID)
        is CarNode.Track -> play(node)?.let { list -> songEntry(list.songs[list.start], node.list, node.index) }
    }

    private suspend fun home(again: (Any?) -> Unit): List<CarEntry> = coroutineScope {
        val liked = async { runCatching { library.quick(library.liked(), again) }.getOrNull() }
        val recent = async { runCatching { library.quick(library.albums(AlbumList.Recent, 0, HOME_ROW), again) }.getOrNull() }
        val newest = async { runCatching { library.quick(library.albums(AlbumList.Newest, 0, HOME_ROW), again) }.getOrNull() }
        val (l, r, n) = Triple(liked.await(), recent.await(), newest.await())
        if (l == null && r == null && n == null) {
            // Nothing kept and nothing from the server: say why, rather than show an empty tab.
            library.quick(library.albums(AlbumList.Recent, 0, HOME_ROW))
        }
        val cover = l?.songs?.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt.orEmpty()
        buildList {
            add(CarEntry(CarIds.format(CarNode.Liked), words.likedSongs, subtitle = l?.let { words.songCount(it.songs.size) }.orEmpty(),
                cover = cover, icon = R.drawable.ic_heart, browsable = true, inside = CarStyle.LIST))
            r.orEmpty().forEach { add(albumEntry(it, group = words.recentlyPlayed, tag = "recent")) }
            n.orEmpty().forEach { add(albumEntry(it, group = words.recentlyAdded, tag = "new")) }
        }
    }

    /** Folders for each letter that names start with, in order, "#" last. */
    private fun letters(of: List<String>, node: (String) -> CarNode): List<CarEntry> =
        of.distinct().sortedWith(compareBy({ it == "#" }, { it })).map { letter ->
            val count = of.count { it == letter }
            CarEntry(CarIds.format(node(letter)), if (letter == "#") words.others else letter, subtitle = count.toString(),
                browsable = true, style = CarStyle.CATEGORY_LIST, inside = CarStyle.GRID)
        }

    // ---- Entries --------------------------------------------------------------------------

    fun albumEntry(album: Album, group: String = "", tag: String = "", byYear: Boolean = false): CarEntry {
        val node = CarNode.Album(album.id)
        return CarEntry(
            id = if (tag.isEmpty()) CarIds.format(node) else CarIds.inGroup(tag, node),
            title = album.name,
            subtitle = if (byYear && album.year > 0) album.year.toString() else album.artistLabel,
            cover = album.coverArt,
            icon = R.drawable.ic_disc_album,
            browsable = true,
            group = group,
            inside = CarStyle.LIST,
        )
    }

    fun playlistEntry(playlist: Playlist) = CarEntry(
        id = CarIds.format(CarNode.Playlist(playlist.id)),
        title = playlist.name,
        subtitle = words.songCount(playlist.songCount),
        cover = playlist.coverArt,
        icon = R.drawable.ic_list_music,
        browsable = true,
        inside = CarStyle.LIST,
    )

    fun artistEntry(artist: Artist) = CarEntry(
        id = CarIds.format(CarNode.Artist(artist.id)),
        title = artist.name,
        subtitle = words.albumCount(artist.albumCount),
        cover = artist.coverArt,
        icon = R.drawable.ic_user_round,
        browsable = true,
        inside = CarStyle.GRID,
    )

    fun genreEntry(genre: Genre) = CarEntry(
        id = CarIds.format(CarNode.Genre(genre.name)),
        title = genre.name,
        subtitle = words.albumCount(genre.albumCount),
        icon = R.drawable.ic_tags,
        browsable = true,
        inside = CarStyle.GRID,
    )

    fun songEntry(song: Song, list: ListRef, index: Int, group: String = "") = CarEntry(
        id = CarIds.format(CarNode.Track(song.id, list, index)),
        title = song.title,
        subtitle = song.artistLabel,
        cover = song.coverArt,
        icon = R.drawable.ic_music,
        playable = true,
        group = group,
        song = song,
    )

    // ---- Playing --------------------------------------------------------------------------

    /** What choosing a song plays: its list, from it. Null if the song can't be found. */
    suspend fun play(track: CarNode.Track): PlayList? {
        val (songs, context, source) = when (val list = track.list) {
            is ListRef.Album -> {
                val album = if (list.id.isEmpty()) null else runCatching { library.quick(library.album(list.id)) }.getOrNull()
                if (album == null) {
                    // A song with no album to speak of: just the song.
                    val song = library.song(track.songId)
                    return PlayList(listOf(song), 0, song.title, "")
                }
                Triple(album.songs, album.name, "album:${list.id}")
            }
            is ListRef.Playlist -> library.quick(library.playlist(list.id)).let { Triple(it.songs, it.name, "playlist:${list.id}") }
            ListRef.Liked -> Triple(library.quick(library.liked()).songs, words.likedSongs, "liked")
        }
        val start = if (songs.getOrNull(track.index)?.id == track.songId) track.index else songs.indexOfFirst { it.id == track.songId }
        if (start < 0) return null
        return PlayList(songs, start, context, source)
    }

    /** What the car's search shows: artists, albums, playlists and songs, each under a
     *  heading. Songs play their album from them. */
    suspend fun search(query: String): List<CarEntry> {
        val found = library.search.find(query, SearchLimits(artists = 10, albums = 15, songs = 30))
        val playlists = runCatching { library.quick(library.playlists()) }.getOrDefault(emptyList())
            .filter { Voice.score(query, it.name) >= 0.6 }
        return buildList {
            found.artists.forEach { add(artistEntry(it).copy(group = words.artists)) }
            found.albums.forEach { add(albumEntry(it, group = words.albums)) }
            playlists.forEach { add(playlistEntry(it).copy(group = words.playlists)) }
            found.songs.forEach { add(songEntry(it, ListRef.Album(it.albumId), -1, group = words.songs)) }
        }
    }

    /** What a spoken request plays, or null if nothing matches. (A request with nothing
     *  named is the caller's: it resumes.) */
    suspend fun forVoice(request: VoiceRequest): PlayList? {
        val found = library.search.find(request.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))
        val playlists = runCatching { library.quick(library.playlists()) }.getOrDefault(emptyList())
        val genres = if (request.focus == VoiceRequest.Focus.GENRE || request.focus == VoiceRequest.Focus.ANY) {
            runCatching { library.quick(library.genres()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        var choice = Voice.choose(request, found, playlists, genres)
        if (choice == VoiceChoice.Nothing && request.focus != VoiceRequest.Focus.ANY) {
            // The Assistant guessed the kind wrong ("play Slow Rivers" taken for a song when
            // it's an album): any kind will do.
            val any = request.copy(focus = VoiceRequest.Focus.ANY, query = request.searchWords())
            val more = library.search.find(any.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))
            choice = Voice.choose(any, more, playlists, genres)
        }
        return when (choice) {
            is VoiceChoice.OfAlbum -> library.quick(library.album(choice.album.id)).let { PlayList(it.songs, 0, it.name, "album:${it.id}") }
            is VoiceChoice.OfPlaylist -> library.quick(library.playlist(choice.playlist.id)).let { PlayList(it.songs, 0, it.name, "playlist:${it.id}") }
            is VoiceChoice.OfSong -> {
                val song = choice.song
                val album = if (song.albumId.isEmpty()) null else runCatching { library.quick(library.album(song.albumId)) }.getOrNull()
                val at = album?.songs?.indexOfFirst { it.id == song.id } ?: -1
                if (album != null && at >= 0) PlayList(album.songs, at, album.name, "album:${album.id}") else PlayList(listOf(song), 0, song.title, "")
            }
            is VoiceChoice.OfArtist -> {
                val artist = library.quick(library.artist(choice.artist.id))
                val songs = coroutineScope {
                    artist.albums.take(MOST_ALBUMS_FOR_ARTIST).map { album ->
                        async { runCatching { library.quick(library.album(album.id)).songs }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                }
                if (songs.isEmpty()) null else PlayList(songs, 0, artist.name, "artist:${artist.id}", shuffle = true, anyStart = true)
            }
            is VoiceChoice.OfGenre -> {
                val songs = library.randomSongs(GENRE_SONGS, choice.genre)
                if (songs.isEmpty()) null else PlayList(songs, 0, choice.genre, "genre:${choice.genre}", shuffle = true, anyStart = true)
            }
            VoiceChoice.Nothing -> null
        }
    }

    /** Liked songs, shuffled: what "play music" plays when there's nothing to carry on with. */
    suspend fun shuffledLiked(): PlayList? {
        val songs = library.quick(library.liked()).songs
        return if (songs.isEmpty()) null else PlayList(songs, 0, words.likedSongs, "liked", shuffle = true, anyStart = true)
    }

    companion object {
        /** A list longer than this is split into letters. */
        const val MOST_IN_A_LIST = 120
        const val HOME_ROW = 12
        const val MOST_ALBUMS_FOR_ARTIST = 30
        const val GENRE_SONGS = 100

        /** The letter a name is filed under: A to Z, or "#" for anything else. */
        fun letterOf(name: String): String {
            val first = Voice.plain(name).firstOrNull() ?: return "#"
            return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
        }

        /** Whether an error means the server couldn't be reached at all. */
        fun unreachable(error: Throwable): Boolean = error is ApiError.Network
    }
}
