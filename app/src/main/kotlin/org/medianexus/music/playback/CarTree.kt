package org.medianexus.music.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.medianexus.music.R
import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.library.LocalSearch
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
    val shuffle: String = "Shuffle",
    val mostPlayed: String = "Most played",
    val aToZ: String = "A to Z",
    val downloads: String = "Downloads",
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
 * What the car's tree needs to know about playing with no server: whether the app is offline
 * now (by hand, or the server out of reach), which songs play offline, and what is downloaded.
 */
class CarOffline(
    val now: () -> Boolean,
    val availability: Availability,
    val downloads: () -> DownloadsState,
)

/**
 * The car's browse tree, built from the library's kept pages (so it opens at once, and with
 * no signal shows what was seen last), and what each song in it plays.
 *
 * Tabs: Home (Liked songs, then what was played and added lately), Playlists, Library
 * (Albums, Artists, Genres), Downloads. Downloads is read from the downloads' own record
 * alone, so it opens with no signal at all, from a cold start.
 *
 * Offline (by hand, or with the server out of reach) the other tabs show only what can be
 * played: songs that are downloaded or kept whole, and the albums, artists, genres and
 * playlists that have some; their search, and spoken requests, look in the same songs.
 *
 * Choosing a song plays its whole album or playlist from that song; "Shuffle", at the top
 * of every list of songs, plays the whole list shuffled.
 *
 * No folder is ever longer than reaches the car whole (see [CarFolders]): one that would be
 * is shown as numbered ranges instead.
 */
class CarTree(private val library: Library, private val words: CarWords = CarWords(), private val offline: CarOffline? = null) {
    /** The tabs. */
    fun tabs(): List<CarEntry> = listOf(
        CarEntry(CarIds.format(CarNode.Home), words.home, icon = R.drawable.ic_house, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Playlists), words.playlists, icon = R.drawable.ic_list_music, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Library), words.library, icon = R.drawable.ic_library, browsable = true, inside = CarStyle.CATEGORY_LIST),
        CarEntry(CarIds.format(CarNode.Downloads), words.downloads, icon = R.drawable.ic_download, browsable = true, inside = CarStyle.GRID),
    )

    // ---- Offline ----------------------------------------------------------------------------

    private val isOffline: Boolean get() = offline?.now?.invoke() == true

    private fun plays(song: Song): Boolean = offline?.availability?.plays(song) ?: true

    /** What plays offline, by album, artist and genre: worked out once per folder. */
    private class Playable(val songs: List<Song>) {
        val albums: Set<String> = songs.mapTo(HashSet()) { it.albumId }
        val artists: Set<String> = songs.flatMapTo(HashSet()) { song -> song.artists.map { it.id } + song.artistId }
        val genres: Set<String> = songs.mapTo(HashSet()) { it.genre.lowercase() }
    }

    private fun playable() = Playable(offline?.availability?.playable().orEmpty())

    private fun downloadsNow(): DownloadsState = offline?.downloads?.invoke() ?: DownloadsState()

    /** Albums of the songs that play offline, from those songs (no page is needed). */
    private fun playableAlbums(p: Playable): List<Album> = org.medianexus.music.library.OnThePhone.albums(p.songs)

    private fun playableArtists(p: Playable): List<Artist> = org.medianexus.music.library.OnThePhone.artists(p.songs)

    /** An album's songs offline: the kept page's, else what plays of it, in disc order. */
    private suspend fun albumSongsOffline(id: String): Pair<List<Song>, String> {
        val kept = runCatching { library.pages.kept(library.album(id)) }.getOrNull()
        if (kept != null) return kept.songs to kept.name
        val of = playable().songs.filter { it.albumId == id }.sortedWith(compareBy({ it.discNumber }, { it.track }))
        return of to (of.firstOrNull()?.album ?: "")
    }

    // ---- The Downloads tab ------------------------------------------------------------------

    private fun downloadsTab(): List<CarEntry> {
        val state = downloadsNow()
        val songs = DownloadedViews.songs(state)
        if (songs.isEmpty()) return emptyList()
        return buildList {
            add(CarEntry(CarIds.format(CarNode.DownloadedSongs), words.songs, subtitle = words.songCount(songs.size), icon = R.drawable.ic_music,
                browsable = true, style = CarStyle.CATEGORY_LIST, inside = CarStyle.LIST))
            for (list in DownloadedViews.playlists(state)) {
                if (list.songs.isEmpty()) continue
                add(CarEntry(CarIds.format(CarNode.DownloadedList(list.list.key)), if (list.list.key == "liked") words.likedSongs else list.list.name,
                    subtitle = words.songCount(list.songs.size), cover = list.list.coverArt.ifEmpty { list.songs.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt.orEmpty() },
                    icon = if (list.list.key == "liked") R.drawable.ic_heart else R.drawable.ic_list_music, browsable = true, group = words.playlists, inside = CarStyle.LIST))
            }
            for (album in DownloadedViews.albums(state)) {
                add(CarEntry(CarIds.format(CarNode.DownloadedAlbum(album.id)), album.name, subtitle = album.artist, cover = album.coverArt,
                    icon = R.drawable.ic_disc_album, browsable = true, group = words.albums, inside = CarStyle.LIST))
            }
        }
    }

    /** A folder's contents before they are cut to size: what always heads it (never split),
     *  then the list itself, and how the list's own entries are laid out. */
    private class Listing(val body: List<CarEntry>, val head: List<CarEntry> = emptyList(), val inside: CarStyle = CarStyle.LIST)

    /**
     * What's inside a folder, or null if there's no such folder. `changed` is told when the
     * kept copy turns out to differ from the server's, so the car can be asked to look again.
     * Throws `ApiError` when there's no kept copy and the server can't be reached.
     */
    suspend fun children(node: CarNode, changed: () -> Unit = {}): List<CarEntry>? {
        if (node is CarNode.Part) {
            // One range of a long list. The list was looked at with the server when it was
            // opened; its ranges are read from the phone's copy, not fetched again each.
            val whole = listing(node.parent, changed, part = true) ?: return null
            return cut(whole, node.parent, node.from, node.to, head = emptyList())
        }
        val whole = listing(node, changed, part = false) ?: return null
        // What heads the folder ("Shuffle") travels with it, and counts towards what fits.
        return whole.head + cut(whole, node, 0, whole.body.size, whole.head)
    }

    /** The last list of songs laid out, and the kept list it was made from: opening the
     *  ranges of a long list one after another lays it out once, not once for each. (Read
     *  and written from whichever thread is answering the car; the latest wins.) */
    @Volatile
    private var laidOut: Triple<ListRef, List<Song>, Listing>? = null

    /**
     * A page for a folder: as [Library.quick] gives it, or, for one range of a long list
     * (`part`), the kept copy without asking the server again if it was asked in the last
     * few minutes. A list long enough to be shown in ranges is megabytes to fetch, and a car
     * opening its ranges in turn would otherwise fetch all of it for each.
     */
    private suspend fun <T> page(spec: org.medianexus.music.store.PageSpec<T>, part: Boolean, again: (Any?) -> Unit): T =
        if (part) library.later(spec, LISTING_FRESH_MILLIS, again) ?: library.quick(spec, again) else library.quick(spec, again)

    /**
     * The entries of a folder's list from `from` up to `to`, as one folder shows them: the
     * entries themselves if they fit, otherwise the ranges they fall into, each a folder.
     */
    private fun cut(whole: Listing, node: CarNode, from: Int, to: Int, head: List<CarEntry>): List<CarEntry> {
        val body = whole.body
        val start = from.coerceIn(0, body.size)
        val end = to.coerceIn(start, body.size)
        // As they are, if they fit in one folder with what heads it: a list of exactly 200
        // songs under "Shuffle" was 201 entries, and its bytes were never counted with
        // Shuffle's.
        val all = body.subList(start, end)
        if (CarFolders.fits(head + all)) return all
        // One entry, and it is too big for a folder by itself (with whatever heads this
        // one): there is nothing to split. It is shown alone, its words cut down to fit.
        // This used to be "halved" like any list that didn't fit, into itself and an empty
        // range: an exception at the top of a list, and below that a folder that held
        // itself and an id that names nothing.
        if (all.size == 1) {
            val room = CarFolders.BUDGET - head.sumOf { CarFolders.bytes(it) }
            return listOf(CarFolders.shortened(all[0], room))
        }
        // Otherwise as ranges, each a folder of its own (with nothing at its head). A list
        // that would have fitted but for its head is halved.
        val ranges = CarFolders.ranges(body, start, end)
            ?: ((start + end + 1) / 2).let { middle -> listOf(CarFolders.Range(start, middle), CarFolders.Range(middle, end)) }
        fun entry(range: CarFolders.Range) = CarEntry(
            id = CarIds.format(CarNode.Part(node, range.from, range.to)),
            title = "${range.from + 1}–${range.to}",
            subtitle = "${brief(body[range.from].title)} – ${brief(body[range.to - 1].title)}",
            browsable = true,
            style = CarStyle.CATEGORY_LIST,
            inside = whole.inside,
        )
        // And the folder of ranges is itself weighed, head and all.
        val shown = CarFolders.gathered(ranges, head, ::entry).map(::entry)
        handedOut(node, shown)
        return shown
    }

    /**
     * The folders shown as ranges, and the ranges handed out for them, by the kept page their
     * list is read from: everything the car may be holding that is named by places in that
     * list. When the list changes, every one of them is told ([stale]). It used to be only
     * those among the last few dozen folders the car had asked for, and a long list has more
     * ranges than that. The lists used lately only, and a bounded number of folders of each.
     */
    private val split = object : LinkedHashMap<String, MutableSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?) = size > MOST_SPLIT_LISTS
    }

    private fun handedOut(node: CarNode, ranges: List<CarEntry>) {
        val key = pageOf(node) ?: return
        synchronized(split) {
            val folders = split.getOrPut(key) { LinkedHashSet() }
            if (folders.size + ranges.size + 1 > MOST_SPLIT_FOLDERS) folders.clear()
            folders += CarIds.format(node)
            ranges.mapTo(folders) { it.id }
        }
    }

    /**
     * The key of the kept page a folder's list is read from ("playlist:<id>", "liked"), for
     * the folders that are one list (and so can be shown as ranges of it); null for the
     * rest. When that page changes, the folder and every range of it the car holds mean
     * something else: see [stale].
     */
    fun pageOf(node: CarNode): String? = when (node) {
        is CarNode.Part -> pageOf(node.parent)
        CarNode.Playlists -> library.playlists().key
        CarNode.Artists, is CarNode.ArtistLetter -> library.artists().key
        CarNode.Genres -> library.genres().key
        CarNode.Albums, is CarNode.AlbumLetter -> library.allAlbums(AlbumList.AlphabeticalByName).key
        CarNode.Liked -> library.liked().key
        is CarNode.Album -> library.album(node.id).key
        is CarNode.Playlist -> library.playlist(node.id).key
        is CarNode.Artist -> library.artist(node.id).key
        is CarNode.Genre -> library.genre(node.name).key
        else -> null
    }

    /**
     * The folders whose list is the kept page `key`, which has just changed: those among the
     * folders the car has asked for lately (`asked`, their ids), and every folder of that
     * list that was shown as ranges, with every range handed out for it, however long ago.
     * A range is named by places in the list ("songs 201 to 400"); once the list has changed,
     * a range the car still has open shows songs that are no longer at those places, and a
     * tap plays another. Each is to be told its children have changed, so the car asks again.
     */
    fun stale(asked: Collection<String>, key: String): List<String> {
        val ranges = synchronized(split) { split.remove(key).orEmpty().toList() }
        return (asked.filter { id -> CarIds.parse(id)?.let(::pageOf) == key } + ranges).distinct()
    }

    private fun brief(title: String): String = if (title.length <= 24) title else title.take(23).trimEnd() + "…"

    private suspend fun listing(node: CarNode, changed: () -> Unit, part: Boolean): Listing? {
        val again: (Any?) -> Unit = { changed() }
        return when (node) {
            CarNode.Root -> Listing(tabs())
            CarNode.Home -> Listing(home(again), inside = CarStyle.GRID)
            CarNode.Playlists -> Listing(playlistsShown(page = { page(library.playlists(), part, again) }), inside = CarStyle.GRID)
            CarNode.Library -> Listing(listOf(
                CarEntry(CarIds.format(CarNode.Albums), words.albums, icon = R.drawable.ic_disc_album, browsable = true, inside = CarStyle.GRID),
                CarEntry(CarIds.format(CarNode.Artists), words.artists, icon = R.drawable.ic_user_round, browsable = true, inside = CarStyle.LIST),
                CarEntry(CarIds.format(CarNode.Genres), words.genres, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.LIST),
            ))
            CarNode.Albums -> if (isOffline) Listing(playableAlbums(playable()).map { albumEntry(it, group = words.aToZ) }, inside = CarStyle.GRID) else albums(again)
            is CarNode.AlbumsBy -> {
                val order = when (node.kind) {
                    "newest" -> AlbumList.Newest
                    "recent" -> AlbumList.Recent
                    "frequent" -> AlbumList.Frequent
                    else -> return null
                }
                Listing(onlyPlayable(library.quick(library.albums(order, 0, Library.GRID_PAGE), again)).map { albumEntry(it) }, inside = CarStyle.GRID)
            }
            is CarNode.AlbumLetter -> Listing(onlyPlayable(if (isOffline) playableAlbums(playable()) else everyAlbum(again)).filter { letterOf(it.name) == node.letter }.map { albumEntry(it) }, inside = CarStyle.GRID)
            CarNode.Artists -> {
                val all = if (isOffline) playableArtists(playable()) else page(library.artists(), part, again)
                Listing(if (all.size <= MOST_IN_A_LIST) all.map(::artistEntry) else letters(all.map { letterOf(it.name) }, CarStyle.LIST) { CarNode.ArtistLetter(it) })
            }
            is CarNode.ArtistLetter -> Listing((if (isOffline) playableArtists(playable()) else page(library.artists(), part, again)).filter { letterOf(it.name) == node.letter }.map(::artistEntry))
            CarNode.Genres -> Listing(if (isOffline) playableGenres() else page(library.genres(), part, again).map(::genreEntry))
            CarNode.Liked -> songs(page(library.liked(), part, again).songs, ListRef.Liked)
            is CarNode.Album -> songs(if (isOffline) albumSongsOffline(node.id).first else page(library.album(node.id), part, again).songs, ListRef.Album(node.id))
            is CarNode.Playlist -> songs(page(library.playlist(node.id), part, again).songs, ListRef.Playlist(node.id))
            is CarNode.Artist -> Listing(
                if (isOffline) playable().songs.filter { it.artistId == node.id || it.artists.any { a -> a.id == node.id } }.let { of -> playableAlbums(Playable(of)) }.map { albumEntry(it) }
                else page(library.artist(node.id), part, again).albums.map { albumEntry(it, byYear = true) },
                inside = CarStyle.GRID,
            )
            is CarNode.Genre -> Listing(
                if (isOffline) playable().songs.filter { it.genre.equals(node.name, ignoreCase = true) }.let { of -> playableAlbums(Playable(of)) }.map { albumEntry(it) }
                else page(library.genre(node.name), part, again).map { albumEntry(it) },
                inside = CarStyle.GRID,
            )
            CarNode.Downloads -> Listing(downloadsTab(), inside = CarStyle.GRID)
            CarNode.DownloadedSongs -> songs(DownloadedViews.songs(downloadsNow()), ListRef.DownloadedSongs, offlineOnly = false)
            is CarNode.DownloadedAlbum -> songs(DownloadedViews.album(downloadsNow(), node.id)?.songs.orEmpty(), ListRef.DownloadedAlbum(node.id), offlineOnly = false)
            is CarNode.DownloadedList -> songs(DownloadedViews.playlist(downloadsNow(), node.key)?.songs.orEmpty(), ListRef.DownloadedList(node.key), offlineOnly = false)
            is CarNode.Track, is CarNode.Shuffle, is CarNode.Part -> null
        }
    }

    /** Only the albums with something that plays, when offline; all of them otherwise. */
    private fun onlyPlayable(albums: List<Album>): List<Album> {
        if (!isOffline) return albums
        val have = playable().albums
        return albums.filter { it.id in have }
    }

    private fun playableGenres(): List<CarEntry> = org.medianexus.music.library.OnThePhone.genres(playable().songs).map(::genreEntry)

    /** The playlists tab: offline, the downloaded ones and the kept ones with something
     *  that plays; otherwise, all of them. */
    private suspend fun playlistsShown(page: suspend () -> List<Playlist>): List<CarEntry> {
        if (!isOffline) return page().map(::playlistEntry)
        val all = runCatching { page() }.getOrDefault(emptyList())
        val downloaded = downloadsNow().collections.mapTo(HashSet()) { it.key }
        return all.filter { playlist ->
            "playlist:${playlist.id}" in downloaded || runCatching { library.pages.kept(library.playlist(playlist.id)) }.getOrNull()?.songs?.any(::plays) == true
        }.map(::playlistEntry)
    }

    /** A list of songs as a folder: "Shuffle" first (unless there is nothing to shuffle),
     *  then the songs, each of which plays the list from itself. Offline, only the songs that
     *  play (each still named by its place in the whole list). */
    private fun songs(songs: List<Song>, list: ListRef, offlineOnly: Boolean = true): Listing {
        if (offlineOnly && isOffline) {
            val body = songs.withIndex().filter { plays(it.value) }.map { (i, song) -> songEntry(song, list, i) }
            return Listing(body, head = if (body.isEmpty()) emptyList() else listOf(shuffleEntry(list, body.size)))
        }
        // The same kept list as last time (the very same one, not one that reads the same):
        // it is laid out already.
        laidOut?.let { (was, from, listing) -> if (was == list && from === songs) return listing }
        val listing = Listing(
            body = songs.mapIndexed { i, song -> songEntry(song, list, i) },
            head = if (songs.isEmpty()) emptyList() else listOf(shuffleEntry(list, songs.size)),
        )
        // Only worth holding on to when it is long enough to be opened in ranges.
        laidOut = if (songs.size > CarFolders.MOST) Triple(list, songs, listing) else null
        return listing
    }

    /**
     * Albums. What costs one request each comes first and at once: recently added, recently
     * played, most played. Under them the whole library A to Z, from the copy of the full
     * listing kept on the phone: on a large library over a slow connection that listing is
     * many requests, so it is never fetched while the driver waits. With no copy yet, the
     * three folders are shown now and the car is told to look again when the listing lands.
     */
    private suspend fun albums(again: (Any?) -> Unit): Listing {
        val head = listOf(
            CarEntry(CarIds.format(CarNode.AlbumsBy("newest")), words.recentlyAdded, icon = R.drawable.ic_disc_album, browsable = true, style = CarStyle.CATEGORY_LIST, inside = CarStyle.GRID),
            CarEntry(CarIds.format(CarNode.AlbumsBy("recent")), words.recentlyPlayed, icon = R.drawable.ic_disc_album, browsable = true, style = CarStyle.CATEGORY_LIST, inside = CarStyle.GRID),
            CarEntry(CarIds.format(CarNode.AlbumsBy("frequent")), words.mostPlayed, icon = R.drawable.ic_disc_album, browsable = true, style = CarStyle.CATEGORY_LIST, inside = CarStyle.GRID),
        )
        val all = library.later(library.allAlbums(AlbumList.AlphabeticalByName), LISTING_FRESH_MILLIS, again).orEmpty()
        val body = if (all.size <= MOST_IN_A_LIST) {
            all.map { albumEntry(it, group = words.aToZ) }
        } else {
            letters(all.map { letterOf(it.name) }, CarStyle.GRID, group = words.aToZ) { CarNode.AlbumLetter(it) }
        }
        return Listing(body, head, inside = CarStyle.GRID)
    }

    /** Every album, for a letter's folder: the kept listing; only if there is none (the
     *  phone cleared it away since the letter was shown) is the server waited for. */
    private suspend fun everyAlbum(again: (Any?) -> Unit): List<Album> {
        val spec = library.allAlbums(AlbumList.AlphabeticalByName)
        return library.later(spec, LISTING_FRESH_MILLIS, again) ?: library.quick(spec)
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
        is CarNode.AlbumsBy -> CarEntry(
            CarIds.format(node),
            when (node.kind) { "newest" -> words.recentlyAdded; "recent" -> words.recentlyPlayed; else -> words.mostPlayed },
            icon = R.drawable.ic_disc_album, browsable = true, inside = CarStyle.GRID,
        )
        is CarNode.Part -> CarEntry(CarIds.format(node), "${node.from + 1}–${node.to}", browsable = true)
        is CarNode.Shuffle -> shuffled(node.list)?.let { shuffleEntry(node.list, it.songs.size) }
        is CarNode.Album -> albumEntry(library.quick(library.album(node.id)))
        is CarNode.Artist -> artistEntry(library.quick(library.artist(node.id)))
        is CarNode.Playlist -> playlistEntry(library.quick(library.playlist(node.id)))
        is CarNode.Genre -> library.quick(library.genres()).firstOrNull { it.name == node.name }?.let(::genreEntry)
            ?: CarEntry(CarIds.format(node), node.name, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.GRID)
        is CarNode.Track -> play(node)?.let { list -> songEntry(list.songs[list.start], node.list, node.index) }
        CarNode.Downloads -> tabs().firstOrNull { it.id == CarIds.format(node) }
        CarNode.DownloadedSongs, is CarNode.DownloadedAlbum, is CarNode.DownloadedList -> downloadsTab().firstOrNull { it.id == CarIds.format(node) }
    }

    private suspend fun home(again: (Any?) -> Unit): List<CarEntry> = coroutineScope {
        val liked = async { runCatching { library.quick(library.liked(), again) }.getOrNull() }
        val recent = async { runCatching { library.quick(library.albums(AlbumList.Recent, 0, HOME_ROW), again) }.getOrNull() }
        val newest = async { runCatching { library.quick(library.albums(AlbumList.Newest, 0, HOME_ROW), again) }.getOrNull() }
        val (l, r, n) = Triple(liked.await(), recent.await(), newest.await())
        if (isOffline) {
            // Only what plays: Liked songs, if any of it does; the albums of the rows that do.
            val have = playable().albums
            val likedPlays = l?.songs?.count(::plays) ?: 0
            return@coroutineScope buildList {
                if (likedPlays > 0) add(CarEntry(CarIds.format(CarNode.Liked), words.likedSongs, subtitle = words.songCount(likedPlays),
                    cover = l?.songs?.firstOrNull { it.coverArt.isNotEmpty() && plays(it) }?.coverArt.orEmpty(), icon = R.drawable.ic_heart, browsable = true, inside = CarStyle.LIST))
                r.orEmpty().filter { it.id in have }.forEach { add(albumEntry(it, group = words.recentlyPlayed, tag = "recent")) }
                n.orEmpty().filter { it.id in have }.forEach { add(albumEntry(it, group = words.recentlyAdded, tag = "new")) }
            }
        }
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
    private fun letters(of: List<String>, inside: CarStyle, group: String = "", node: (String) -> CarNode): List<CarEntry> {
        val counts = of.groupingBy { it }.eachCount()
        return counts.keys.sortedWith(compareBy({ it == "#" }, { it })).map { letter ->
            CarEntry(CarIds.format(node(letter)), if (letter == "#") words.others else letter, subtitle = counts[letter].toString(),
                browsable = true, group = group, style = CarStyle.CATEGORY_LIST, inside = inside)
        }
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

    /** "Shuffle", at the top of a list of songs. */
    fun shuffleEntry(list: ListRef, count: Int) = CarEntry(
        id = CarIds.format(CarNode.Shuffle(list)),
        title = words.shuffle,
        subtitle = words.songCount(count),
        icon = R.drawable.ic_shuffle,
        playable = true,
    )

    // ---- Playing --------------------------------------------------------------------------

    /** The songs of a list the car can play, what it is called and its route key. */
    private suspend fun songsOf(list: ListRef): Triple<List<Song>, String, String>? = when (list) {
        is ListRef.Album -> if (list.id.isEmpty()) null else if (isOffline) albumSongsOffline(list.id).let { (songs, name) -> Triple(songs, name, "album:${list.id}") }
            else library.quick(library.album(list.id)).let { Triple(it.songs, it.name, "album:${list.id}") }
        is ListRef.Playlist -> library.quick(library.playlist(list.id)).let { Triple(it.songs, it.name, "playlist:${list.id}") }
        ListRef.Liked -> Triple(library.quick(library.liked()).songs, words.likedSongs, "liked")
        // The Downloads tab's lists, as the downloads have them. Their own names as sources,
        // so the queue doesn't follow a page of the server's with songs that aren't here.
        ListRef.DownloadedSongs -> Triple(DownloadedViews.songs(downloadsNow()), words.downloads, "downloads")
        is ListRef.DownloadedAlbum -> DownloadedViews.album(downloadsNow(), list.id)?.let { Triple(it.songs, it.name, "downloads:album:${list.id}") }
        is ListRef.DownloadedList -> DownloadedViews.playlist(downloadsNow(), list.key)?.let { Triple(it.songs, if (list.key == "liked") words.likedSongs else it.list.name, "downloads:${list.key}") }
    }

    /** What "Shuffle" at the top of a list plays: the whole list, shuffled, starting anywhere.
     *  Null if the list is empty. */
    suspend fun shuffled(list: ListRef): PlayList? {
        val (songs, context, source) = songsOf(list) ?: return null
        return if (songs.isEmpty()) null else PlayList(songs, 0, context, source, shuffle = true, anyStart = true)
    }

    /** What choosing a song plays: its list, from it. Null if the song can't be found. */
    suspend fun play(track: CarNode.Track): PlayList? {
        val known = if (track.list is ListRef.Album) runCatching { songsOf(track.list) }.getOrNull() else songsOf(track.list)
        if (known == null) {
            // A song with no album to speak of: just the song.
            val song = library.song(track.songId)
            return PlayList(listOf(song), 0, song.title, "")
        }
        val (songs, context, source) = known
        val start = if (songs.getOrNull(track.index)?.id == track.songId) track.index else songs.indexOfFirst { it.id == track.songId }
        if (start < 0) return null
        return PlayList(songs, start, context, source)
    }

    /** What the car's search shows: artists, albums, playlists and songs, each under a
     *  heading. Songs play their album from them. */
    suspend fun search(query: String): List<CarEntry> {
        val limits = SearchLimits(artists = 10, albums = 15, songs = 30)
        val found = if (isOffline) LocalSearch.find(query, playable().songs, limits) else library.search.find(query, limits)
        val playlists = runCatching { library.quick(library.playlists()) }.getOrDefault(emptyList())
            .filter { Voice.score(query, it.name) >= 0.6 }.take(MOST_PLAYLISTS_FOUND)
            .filter { !isOffline || runCatching { library.pages.kept(library.playlist(it.id)) }.getOrNull()?.songs?.any(::plays) == true }
        val all = buildList {
            found.artists.forEach { add(artistEntry(it).copy(group = words.artists)) }
            found.albums.forEach { add(albumEntry(it, group = words.albums)) }
            playlists.forEach { add(playlistEntry(it).copy(group = words.playlists)) }
            found.songs.forEach { add(songEntry(it, ListRef.Album(it.albumId), -1, group = words.songs)) }
        }
        // Results have no folders to fall back on: as many as reach the car whole.
        return CarFolders.first(all)
    }

    /** What a spoken request plays, or null if nothing matches. (A request with nothing
     *  named is the caller's: it resumes.) */
    suspend fun forVoice(request: VoiceRequest): PlayList? {
        val offlineNow = isOffline
        val here = if (offlineNow) playable() else null
        // Offline, only what is on the phone is looked in.
        suspend fun find(words: String, limits: SearchLimits) = if (here != null) LocalSearch.find(words, here.songs, limits) else library.search.find(words, limits)
        val found = find(request.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))
        val playlists = runCatching { library.quick(library.playlists()) }.getOrDefault(emptyList())
        val genres = if (request.focus == VoiceRequest.Focus.GENRE || request.focus == VoiceRequest.Focus.ANY) {
            if (here != null) here.songs.filter { it.genre.isNotEmpty() }.map { it.genre }.distinct().map { Genre(name = it) }
            else runCatching { library.quick(library.genres()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        var choice = Voice.choose(request, found, playlists, genres)
        if (choice == VoiceChoice.Nothing && request.focus != VoiceRequest.Focus.ANY) {
            // The Assistant guessed the kind wrong ("play Slow Rivers" taken for a song when
            // it's an album): any kind will do.
            val any = request.copy(focus = VoiceRequest.Focus.ANY, query = request.searchWords())
            val more = find(any.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))
            choice = Voice.choose(any, more, playlists, genres)
        }
        return when (choice) {
            is VoiceChoice.OfAlbum -> if (offlineNow) albumSongsOffline(choice.album.id).let { (songs, name) -> PlayList(songs, 0, name, "album:${choice.album.id}") }
                else library.quick(library.album(choice.album.id)).let { PlayList(it.songs, 0, it.name, "album:${it.id}") }
            is VoiceChoice.OfPlaylist -> library.quick(library.playlist(choice.playlist.id)).let { PlayList(it.songs, 0, it.name, "playlist:${it.id}") }
            is VoiceChoice.OfSong -> {
                val song = choice.song
                val album = if (song.albumId.isEmpty()) null else if (offlineNow) runCatching { library.pages.kept(library.album(song.albumId)) }.getOrNull()
                    else runCatching { library.quick(library.album(song.albumId)) }.getOrNull()
                val at = album?.songs?.indexOfFirst { it.id == song.id } ?: -1
                if (album != null && at >= 0) PlayList(album.songs, at, album.name, "album:${album.id}") else PlayList(listOf(song), 0, song.title, "")
            }
            is VoiceChoice.OfArtist -> if (here != null) {
                val songs = here.songs.filter { it.artistId == choice.artist.id || it.artists.any { a -> a.id == choice.artist.id } }
                if (songs.isEmpty()) null else PlayList(songs, 0, choice.artist.name, "artist:${choice.artist.id}", shuffle = true, anyStart = true)
            } else {
                val artist = library.quick(library.artist(choice.artist.id))
                val songs = coroutineScope {
                    artist.albums.take(MOST_ALBUMS_FOR_ARTIST).map { album ->
                        async { runCatching { library.quick(library.album(album.id)).songs }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                }
                if (songs.isEmpty()) null else PlayList(songs, 0, artist.name, "artist:${artist.id}", shuffle = true, anyStart = true)
            }
            is VoiceChoice.OfGenre -> {
                val songs = here?.songs?.filter { it.genre.equals(choice.genre, ignoreCase = true) }?.shuffled()?.take(GENRE_SONGS) ?: library.randomSongs(GENRE_SONGS, choice.genre)
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
        const val MOST_PLAYLISTS_FOUND = 20

        /** How long the full listing of albums is shown before the server is asked for it
         *  again: it is many requests on a large library, and changes rarely. */
        const val LISTING_FRESH_MILLIS = 10 * 60 * 1000L
        const val GENRE_SONGS = 100

        /** Lists shown as ranges that are remembered, and folders (the list's own, and its
         *  ranges) remembered for each: a 20,000-song playlist is a hundred or so. */
        const val MOST_SPLIT_LISTS = 32
        const val MOST_SPLIT_FOLDERS = 2_000

        /** The letter a name is filed under: A to Z, or "#" for anything else. */
        fun letterOf(name: String): String {
            val first = Voice.plain(name).firstOrNull() ?: return "#"
            return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
        }

        /** Whether an error means the server couldn't be reached at all. */
        fun unreachable(error: Throwable): Boolean = error is ApiError.Network
    }
}
