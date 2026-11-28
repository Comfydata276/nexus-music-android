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
    val radio: String = "Radio",
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
    val madeForYou: String = "Made for you",
    /** A mix's name, and the line under it (Windows' words: `MixText`). */
    val mixTitle: (org.medianexus.music.library.Mix) -> String = { it.name.ifEmpty { it.id } },
    val mixAbout: (org.medianexus.music.library.Mix) -> String = { "" },
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
    /** A song was chosen (a row tapped): offline, if it can't play, nothing starts. */
    val chosen: Boolean = false,
    /** `shuffle` is the person's own choice for this list ("Shuffle" at its top), which the
     *  list then remembers; not the app's (a spoken request for an artist is shuffled). */
    val shuffleChosen: Boolean = false,
)

/**
 * What the car's tree needs to know about playing with no server: whether the app is offline
 * now (by hand, or the server out of reach), which songs play offline, and what is downloaded.
 */
class CarOffline(
    val now: () -> Boolean,
    val availability: Availability,
    val downloads: () -> DownloadsState,
    /** Just after start-up, before the server has answered or failed to: not known yet
     *  whether it is there. Then nothing waits on it that has an answer on the phone. */
    val unsure: () -> Boolean = { false },
    /** Waits until it is known (the server has answered, or failed to): a few seconds at most. */
    val settle: suspend () -> Unit = {},
    /** Whether a song is liked now: a liked song comes ahead of others that match a search on
     *  the phone as well, as it does in the phone's copy of the library. */
    val liked: (String) -> Boolean = { false },
    /** **The music login is set aside** (refused, waiting for its renewal): what is online is as
     *  out of reach as with the server gone; what is on the phone plays as ever. */
    val refused: () -> Boolean = { false },
    /** The machine's line for the car while the login is set aside (`Shown.car`: "Online music
     *  is unavailable until your sign-in is renewed"), shown at the top of the online tabs. */
    val line: () -> String? = { null },
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
/** What a queue of radio stations says it came from, on the phone and in the car. */
const val RADIO_STATIONS = "radio-stations"

class CarTree(
    private val library: Library,
    private val words: CarWords = CarWords(),
    private val offline: CarOffline? = null,
    /** The "Made for you" mixes as they stand (see `MixBook`): under Home, as lists of songs. */
    private val mixes: () -> org.medianexus.music.library.MixSet? = { null },
) {
    /** The tabs. */
    fun tabs(): List<CarEntry> = listOf(
        CarEntry(CarIds.format(CarNode.Home), words.home, icon = R.drawable.ic_house, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Playlists), words.playlists, icon = R.drawable.ic_list_music, browsable = true, inside = CarStyle.GRID),
        CarEntry(CarIds.format(CarNode.Library), words.library, icon = R.drawable.ic_library, browsable = true, inside = CarStyle.CATEGORY_LIST),
        CarEntry(CarIds.format(CarNode.Downloads), words.downloads, icon = R.drawable.ic_download, browsable = true, inside = CarStyle.GRID),
    )

    // ---- Offline ----------------------------------------------------------------------------

    private val isOffline: Boolean get() = offline?.now?.invoke() == true || offline?.refused?.invoke() == true

    /** The machine's line, heading the online tabs while the login is set aside. */
    private fun lineFor(node: CarNode): List<CarEntry> {
        if (node != CarNode.Home && node != CarNode.Playlists && node != CarNode.Library) return emptyList()
        if (offline?.refused?.invoke() != true) return emptyList()
        val line = offline.line().takeIf { !it.isNullOrEmpty() } ?: return emptyList()
        return listOf(CarEntry("$NOTICE_ID${node.javaClass.simpleName.lowercase()}", line))
    }

    private fun plays(song: Song): Boolean = offline?.availability?.plays(song) ?: true

    private val isUnsure: Boolean get() = offline?.unsure?.invoke() == true

    /** The kept copy of a page and nothing else: never the server. */
    private suspend fun <T> kept(spec: org.medianexus.music.store.PageSpec<T>): T? = runCatching { library.pages.kept(spec) }.getOrNull()

    /**
     * A page for the car, **from the phone first**:
     *
     * - Offline (by hand, or the server out of reach): the kept copy, else what the
     *   downloads' own record makes of it (`here`), else nothing. The server is never
     *   asked, so nothing waits on a network that isn't there.
     * - **Not yet known whether the server is there** (the first seconds of a cold start,
     *   or just after offline mode is switched off): the kept copy if there is one, else
     *   `here` if it has anything, **at once either way**, with the server asked behind it
     *   and `again` told when its answer lands (so the car fills in what it was shown).
     *   Only with neither is anything waited for, and then only to *know* (a few seconds):
     *   after which it is the server's answer, or the phone's nothing.
     * - Otherwise as [page]: the kept copy at once and checked, or the server's.
     *
     * Android may clear the kept pages while the downloads stay (they are in different
     * places for that reason), so "kept, or else the server" is not enough for what is
     * downloaded.
     */
    private suspend fun <T> first(spec: org.medianexus.music.store.PageSpec<T>, part: Boolean, again: (Any?) -> Unit, phoneOnly: Boolean = false, here: () -> T? = { null }): T? {
        // (`phoneOnly`: whoever asks has already answered from the phone, and what follows
        // from that answer is taken from the phone too, whatever has become of the server
        // meanwhile: see [forVoice].)
        if (isOffline || phoneOnly) {
            // The kept copy, if it has anything that plays: it has the whole list, so each
            // song keeps its place in it. If it has nothing that plays (it is not there; or
            // it is an old copy, from before the server gave these songs other ids), the
            // downloads' own record, which is always of what is on the phone.
            val page = kept(spec)
            return if (page != null && anyPlays(page)) page else here() ?: page
        }
        if (isUnsure) {
            // What is kept, without waiting; the server is asked behind it, whatever is
            // given (see `PageCache.later`). But a kept list of songs with nothing in it
            // that plays, of something the phone does have songs of (an old copy, from
            // before the server gave its songs other ids), is passed over for the songs on
            // the phone, as it is offline: shown, it was a folder of nothing that would play.
            val page = runCatching { library.later(spec, org.medianexus.music.store.PageCache.FRESH_FOR_MILLIS, again) }.getOrNull()
            if (page != null && anyPlays(page)) return page
            here()?.let { return it }
            if (page != null) return page
            // Nothing of it on the phone: the server it is, once it is known to be there
            // (a few seconds at most); if it turns out not to be, nothing, at once.
            if (settled()) return here()
        }
        return page(spec, part, again)
    }

    /** Whether a kept page of songs has any that play with no server (a page of anything
     *  else counts as having). */
    private fun anyPlays(page: Any?): Boolean = when (page) {
        is org.medianexus.music.net.Found -> page.songs.any(::plays)
        is Playlist -> page.songs.any(::plays)
        is Album -> page.songs.any(::plays)
        else -> true
    }

    /** Waits out the moment of not knowing whether the server is there; says whether it
     *  turned out to be out of reach. */
    private suspend fun settled(): Boolean {
        offline?.settle?.invoke()
        return isOffline
    }

    /** Liked songs: see [first]. Offline with no kept copy, the downloaded ones in their order. */
    private suspend fun likedSongs(part: Boolean, again: (Any?) -> Unit): List<Song> =
        first(library.liked(), part, again) { DownloadedViews.playlist(downloadsNow(), "liked")?.songs?.takeIf { it.isNotEmpty() }?.let { org.medianexus.music.net.Found(songs = it) } }?.songs.orEmpty()

    /** A playlist's songs and its name: see [first]. */
    private suspend fun playlistSongs(id: String, part: Boolean, phoneOnly: Boolean = false, again: (Any?) -> Unit): Pair<List<Song>, String> {
        val page = first(library.playlist(id), part, again, phoneOnly) {
            DownloadedViews.playlist(downloadsNow(), "playlist:$id")?.takeIf { it.songs.isNotEmpty() }?.let { Playlist(id = id, name = it.list.name, songs = it.songs, songCount = it.songs.size, coverArt = it.list.coverArt) }
        }
        return page?.songs.orEmpty() to page?.name.orEmpty()
    }

    /**
     * The playlists there are to show or look in. Offline: the ones downloaded whole (always,
     * from the downloads' own record, whether or not the server's list of playlists is kept),
     * then the kept list's others that have something that plays. Never the server then.
     *
     * Not yet known whether the server is there: the kept list and the downloaded ones, at
     * once, with the server's list asked for behind them (`again` is told when it lands).
     * With nothing of either, the server's list is waited for only if `wait` (a folder that
     * would otherwise be empty); a search, or a spoken request, never waits for playlists.
     *
     * `phoneOnly`: whoever asks has already worked out its answer from the phone (a search,
     * a spoken request): the playlists the phone knows of, and never the server's list,
     * **whatever has become of the server since** (it used to look again at whether the
     * server was there, and if it had come back in that moment, wait on a fresh list of
     * playlists with the answer already in hand).
     */
    private suspend fun playlistsKnown(wait: Boolean, again: (Any?) -> Unit = {}, phoneOnly: Boolean = false, page: suspend () -> List<Playlist>): List<Playlist> {
        if (!phoneOnly && !isOffline && !isUnsure) return page()
        val downloaded = DownloadedViews.playlists(downloadsNow()).filter { it.list.key.startsWith("playlist:") && it.songs.isNotEmpty() }
            .map { Playlist(id = it.list.key.removePrefix("playlist:"), name = it.list.name, songCount = it.songs.size, coverArt = it.list.coverArt) }
        val listed = if (isOffline || phoneOnly) kept(library.playlists()) else runCatching { library.later(library.playlists(), org.medianexus.music.store.PageCache.FRESH_FOR_MILLIS, again) }.getOrNull()
        if (listed == null && !isOffline && !phoneOnly) {
            // Not known yet whether the server is there, and no list kept: what is downloaded
            // if there is any; else, where a wait is allowed, to know whether the server is
            // there (and its list if it is).
            if (downloaded.isNotEmpty() || !wait) return downloaded
            return if (settled()) playlistsKnown(wait, again, phoneOnly, page) else page()
        }
        val have = downloaded.mapTo(HashSet()) { it.id }
        val others = listed.orEmpty().filter { it.id !in have && (!isOffline || kept(library.playlist(it.id))?.songs?.any(::plays) == true) }
        // In the server's order where it is known, the downloaded ones it doesn't list first.
        val known = listed.orEmpty().mapNotNull { p -> if (p.id in have) downloaded.first { it.id == p.id }.let { d -> p.copy(songCount = if (isOffline) d.songCount else p.songCount) } else others.firstOrNull { it.id == p.id } }
        return downloaded.filter { d -> known.none { it.id == d.id } } + known
    }

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
        val kept = kept(library.album(id))
        if (kept != null && kept.songs.any(::plays)) return kept.songs to kept.name
        // No kept page (Android cleared it, or it was never opened), or one with nothing that
        // plays (an old copy, from before the server gave these songs other ids): the
        // downloads' own record of the album, in its own order, then anything else of it
        // that plays.
        DownloadedViews.album(downloadsNow(), id)?.let { album ->
            val also = playable().songs.filter { it.albumId == id && album.songs.none { s -> s.id == it.id } }
            return (album.songs + also) to album.name
        }
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
        return lineFor(node) + whole.head + cut(whole, node, 0, whole.body.size, whole.head)
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
        CarNode.Radio -> library.stations().key
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
            // (Where linking the person's devices is running: without the playlist its record is in.)
            CarNode.Playlists -> Listing(playlistsKnown(wait = true, again) { page(library.playlists(), part, again) }.map(::playlistEntry), inside = CarStyle.GRID)
            CarNode.Library -> Listing(listOf(
                CarEntry(CarIds.format(CarNode.Albums), words.albums, icon = R.drawable.ic_disc_album, browsable = true, inside = CarStyle.GRID),
                CarEntry(CarIds.format(CarNode.Artists), words.artists, icon = R.drawable.ic_user_round, browsable = true, inside = CarStyle.LIST),
                CarEntry(CarIds.format(CarNode.Genres), words.genres, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.LIST),
                // The stations stream live from the internet: offline the folder is empty.
                CarEntry(CarIds.format(CarNode.Radio), words.radio, icon = R.drawable.ic_radio, browsable = true, inside = CarStyle.LIST),
            ))
            CarNode.Albums -> if (isOffline) Listing(playableAlbums(playable()).map { albumEntry(it, group = words.aToZ) }, inside = CarStyle.GRID) else albums(again)
            is CarNode.AlbumsBy -> {
                val order = when (node.kind) {
                    "newest" -> AlbumList.Newest
                    "recent" -> AlbumList.Recent
                    "frequent" -> AlbumList.Frequent
                    else -> return null
                }
                // Offline: the kept copy of that list, if there is one, and of it what plays;
                // with no copy kept, the albums on the phone (in no such order, which only
                // the server knows: but something to choose from, and at once). It had no
                // answer of its own: empty offline, and a wait while it wasn't known
                // whether the server was there.
                // (Every branch below goes by [first]: nothing here asks the server while it
                // is out of reach, or waits on it while that isn't known and the phone has
                // an answer.)
                val albums = first(library.albums(order, 0, Library.GRID_PAGE), part, again) { playableAlbums(playable()).takeIf { it.isNotEmpty() } }.orEmpty()
                // (Offline, a kept list with nothing on it that plays is no answer: the albums
                // on the phone, as with no list kept. It was an empty folder.)
                val shown = onlyPlayable(albums).ifEmpty { if (isOffline) playableAlbums(playable()) else emptyList() }
                Listing(shown.map { albumEntry(it) }, inside = CarStyle.GRID)
            }
            is CarNode.AlbumLetter -> Listing(onlyPlayable(if (isOffline) playableAlbums(playable()) else everyAlbum(part, again)).filter { letterOf(it.name) == node.letter }.map { albumEntry(it) }, inside = CarStyle.GRID)
            CarNode.Artists -> {
                val all = everyArtist(part, again)
                Listing(if (all.size <= MOST_IN_A_LIST) all.map(::artistEntry) else letters(all.map { letterOf(it.name) }, CarStyle.LIST) { CarNode.ArtistLetter(it) })
            }
            is CarNode.ArtistLetter -> Listing(everyArtist(part, again).filter { letterOf(it.name) == node.letter }.map(::artistEntry))
            CarNode.Genres -> Listing(
                (if (isOffline) playableGenres() else first(library.genres(), part, again) { playableGenres().takeIf { it.isNotEmpty() } }.orEmpty()).map(::genreEntry),
            )
            CarNode.Liked -> songs(likedSongs(part, again), ListRef.Liked)
            // Live stations: nothing to shuffle.
            CarNode.Radio -> songs(stations(part, again), ListRef.Radio).let { Listing(it.body, inside = it.inside) }
            is CarNode.Album -> songs(albumSongs(node.id, part, again).first, ListRef.Album(node.id))
            is CarNode.Playlist -> songs(playlistSongs(node.id, part, again = again).first, ListRef.Playlist(node.id))
            is CarNode.Artist -> {
                // What plays of theirs, by album: all there is offline, and what stands in
                // while it isn't known whether the server is there and no page of them is kept.
                val here = { playable().songs.filter { it.artistId == node.id || it.artists.any { a -> a.id == node.id } }.let { of -> playableAlbums(Playable(of)) } }
                Listing(
                    if (isOffline) here().map { albumEntry(it) }
                    else first(library.artist(node.id), part, again) { here().takeIf { it.isNotEmpty() }?.let { Artist(id = node.id, albums = it) } }?.albums.orEmpty().map { albumEntry(it, byYear = true) },
                    inside = CarStyle.GRID,
                )
            }
            is CarNode.Genre -> {
                val here = { playable().songs.filter { it.genre.equals(node.name, ignoreCase = true) }.let { of -> playableAlbums(Playable(of)) } }
                Listing(
                    (if (isOffline) here() else first(library.genre(node.name), part, again) { here().takeIf { it.isNotEmpty() } }.orEmpty()).map { albumEntry(it) },
                    inside = CarStyle.GRID,
                )
            }
            CarNode.Downloads -> Listing(downloadsTab(), inside = CarStyle.GRID)
            CarNode.DownloadedSongs -> songs(DownloadedViews.songs(downloadsNow()), ListRef.DownloadedSongs, offlineOnly = false)
            is CarNode.DownloadedAlbum -> songs(DownloadedViews.album(downloadsNow(), node.id)?.songs.orEmpty(), ListRef.DownloadedAlbum(node.id), offlineOnly = false)
            is CarNode.DownloadedList -> songs(DownloadedViews.playlist(downloadsNow(), node.key)?.songs.orEmpty(), ListRef.DownloadedList(node.key), offlineOnly = false)
            // (Offline, the songs that play, each named by its place in the whole mix.)
            is CarNode.Mix -> songs(mixOf(node.id)?.songs.orEmpty(), ListRef.Mix(node.id))
            is CarNode.Track, is CarNode.Shuffle, is CarNode.Part -> null
        }
    }

    /** Only the albums with something that plays, when offline; all of them otherwise. */
    private fun onlyPlayable(albums: List<Album>): List<Album> {
        if (!isOffline) return albums
        val have = playable().albums
        return albums.filter { it.id in have }
    }

    /** The radio stations, as the server lists them (kept, then checked); none offline,
     *  where none plays. */
    private suspend fun stations(part: Boolean, again: (Any?) -> Unit): List<Song> =
        if (isOffline) emptyList() else first(library.stations(), part, again).orEmpty()

    private fun playableGenres(): List<Genre> = org.medianexus.music.library.OnThePhone.genres(playable().songs)

    /** Every artist: offline, those of the songs that play; otherwise the page, from the
     *  phone first (see [first]). */
    private suspend fun everyArtist(part: Boolean, again: (Any?) -> Unit): List<Artist> =
        if (isOffline) playableArtists(playable()) else first(library.artists(), part, again) { playableArtists(playable()).takeIf { it.isNotEmpty() } }.orEmpty()

    /** An album's songs and its name: offline, from the phone ([albumSongsOffline]); not yet
     *  known whether the server is there, the kept page, else what the phone has of the
     *  album (its downloads, anything of it kept), at once; otherwise the page. */
    private suspend fun albumSongs(id: String, part: Boolean, again: (Any?) -> Unit): Pair<List<Song>, String> {
        if (isOffline) return albumSongsOffline(id)
        val page = first(library.album(id), part, again) {
            val downloaded = DownloadedViews.album(downloadsNow(), id)
            val songs = downloaded?.songs ?: playable().songs.filter { it.albumId == id }.sortedWith(compareBy({ it.discNumber }, { it.track }))
            if (songs.isEmpty()) null else Album(id = id, name = downloaded?.name ?: songs.first().album, songs = songs, songCount = songs.size)
        }
        // (Found, while it was being looked for, to be out of reach: as offline.)
        return page?.let { it.songs to it.name } ?: albumSongsOffline(id)
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
     *  phone cleared it away since the letter was shown) is the server waited for, and not
     *  while it isn't known to be there and the phone has albums of its own to show. */
    private suspend fun everyAlbum(part: Boolean, again: (Any?) -> Unit): List<Album> {
        val spec = library.allAlbums(AlbumList.AlphabeticalByName)
        if (isUnsure) return first(spec, part, again) { playableAlbums(playable()).takeIf { it.isNotEmpty() } }.orEmpty()
        return library.later(spec, LISTING_FRESH_MILLIS, again) ?: library.quick(spec)
    }

    /** One place in the tree on its own, as the car asks for it by id. Null if there's no
     *  such place. */
    suspend fun item(node: CarNode): CarEntry? = when (node) {
        CarNode.Root -> CarEntry(CarIds.ROOT, words.library, browsable = true)
        CarNode.Home, CarNode.Playlists, CarNode.Library -> tabs().firstOrNull { it.id == CarIds.format(node) }
        CarNode.Albums, CarNode.Artists, CarNode.Genres, CarNode.Radio -> children(CarNode.Library)?.firstOrNull { it.id == CarIds.format(node) }
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
        is CarNode.Mix -> mixOf(node.id)?.let { mix -> CarEntry(CarIds.format(node), words.mixTitle(mix), subtitle = words.mixAbout(mix), cover = mix.cardCover, icon = R.drawable.ic_list_music, browsable = true, inside = CarStyle.LIST) }
        // Offline, each from what is on the phone (and null if it isn't): never the server.
        // Not yet known whether the server is there: from the phone if the phone has it.
        is CarNode.Album -> if (isOffline) (kept(library.album(node.id)) ?: playableAlbums(playable()).firstOrNull { it.id == node.id })?.let { albumEntry(it) }
            else first(library.album(node.id), part = false, again = {}) { playableAlbums(playable()).firstOrNull { it.id == node.id } }?.let { albumEntry(it) }
        is CarNode.Artist -> if (isOffline) (kept(library.artist(node.id)) ?: playableArtists(playable()).firstOrNull { it.id == node.id })?.let(::artistEntry)
            else first(library.artist(node.id), part = false, again = {}) { playableArtists(playable()).firstOrNull { it.id == node.id } }?.let(::artistEntry)
        // (A playlist from what the phone has of it itself, first: its downloads, its own
        // kept page. It used to be looked for in the list of playlists alone, and with that
        // list not kept, waited to know whether the server was there.)
        is CarNode.Playlist -> if (isOffline || isUnsure) (playlistHere(node.id) ?: playlistsKnown(wait = true) { library.quick(library.playlists()) }.firstOrNull { it.id == node.id })?.let(::playlistEntry)
            else playlistEntry(library.quick(library.playlist(node.id)))
        is CarNode.Genre -> (if (isOffline) kept(library.genres()).orEmpty() else first(library.genres(), part = false, again = {}) { playableGenres().takeIf { it.isNotEmpty() } }.orEmpty()).firstOrNull { it.name == node.name }?.let(::genreEntry)
            ?: CarEntry(CarIds.format(node), node.name, icon = R.drawable.ic_tags, browsable = true, inside = CarStyle.GRID)
        is CarNode.Track -> play(node)?.let { list -> songEntry(list.songs[list.start], node.list, node.index) }
        CarNode.Downloads -> tabs().firstOrNull { it.id == CarIds.format(node) }
        CarNode.DownloadedSongs, is CarNode.DownloadedAlbum, is CarNode.DownloadedList -> downloadsTab().firstOrNull { it.id == CarIds.format(node) }
    }

    /** A playlist as the phone itself has it: downloaded whole, or its own page kept (offline,
     *  only if something of it plays). Never the server. */
    private suspend fun playlistHere(id: String): Playlist? {
        DownloadedViews.playlist(downloadsNow(), "playlist:$id")?.takeIf { it.songs.isNotEmpty() }?.let {
            return Playlist(id = id, name = it.list.name, songCount = it.songs.size, coverArt = it.list.coverArt)
        }
        return kept(library.playlist(id))?.takeIf { !isOffline || it.songs.any(::plays) }
    }

    /** Home with the server out of reach: from the phone alone, and nothing waited for.
     *  Liked songs if any of it plays (the kept list, else the downloaded one); the albums of
     *  the kept rows that play; and, with no rows kept, the albums that play, so the tab is
     *  not bare. */
    private suspend fun homeOffline(again: (Any?) -> Unit): List<CarEntry> {
        val songs = likedSongs(part = false, again)
        val r = kept(library.albums(AlbumList.Recent, 0, HOME_ROW))
        val n = kept(library.albums(AlbumList.Newest, 0, HOME_ROW))
        val have = playable()
        val likedPlays = songs.count(::plays)
        return buildList {
            if (likedPlays > 0) add(CarEntry(CarIds.format(CarNode.Liked), words.likedSongs, subtitle = words.songCount(likedPlays),
                cover = songs.firstOrNull { it.coverArt.isNotEmpty() && plays(it) }?.coverArt.orEmpty(), icon = R.drawable.ic_heart, browsable = true, inside = CarStyle.LIST))
            addAll(madeForYou())
            r.orEmpty().filter { it.id in have.albums }.forEach { add(albumEntry(it, group = words.recentlyPlayed, tag = "recent")) }
            n.orEmpty().filter { it.id in have.albums }.forEach { add(albumEntry(it, group = words.recentlyAdded, tag = "new")) }
            if (r == null && n == null) playableAlbums(have).take(2 * HOME_ROW).forEach { add(albumEntry(it, group = words.albums)) }
        }
    }

    private fun homeOf(l: org.medianexus.music.net.Found?, r: List<Album>?, n: List<Album>?): List<CarEntry> = buildList {
        val cover = l?.songs?.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt.orEmpty()
        add(CarEntry(CarIds.format(CarNode.Liked), words.likedSongs, subtitle = l?.let { words.songCount(it.songs.size) }.orEmpty(),
            cover = cover, icon = R.drawable.ic_heart, browsable = true, inside = CarStyle.LIST))
        addAll(madeForYou())
        r.orEmpty().forEach { add(albumEntry(it, group = words.recentlyPlayed, tag = "recent")) }
        n.orEmpty().forEach { add(albumEntry(it, group = words.recentlyAdded, tag = "new")) }
    }

    private suspend fun home(again: (Any?) -> Unit): List<CarEntry> = coroutineScope {
        if (isOffline) return@coroutineScope homeOffline(again)
        val rows = listOf(library.albums(AlbumList.Recent, 0, HOME_ROW), library.albums(AlbumList.Newest, 0, HOME_ROW))
        if (isUnsure) {
            // Not yet known whether the server is there. **Each of the three pages by
            // itself**: whichever are kept are shown now, and the rest are asked for behind
            // them (the car is told to look again as each lands). It used to wait for all
            // three if any one was not kept.
            val fresh = org.medianexus.music.store.PageCache.FRESH_FOR_MILLIS
            val l = runCatching { library.later(library.liked(), fresh, again) }.getOrNull()
                ?: DownloadedViews.playlist(downloadsNow(), "liked")?.songs?.takeIf { it.isNotEmpty() }?.let { org.medianexus.music.net.Found(songs = it) }
            val (r, n) = rows.map { runCatching { library.later(it, fresh, again) }.getOrNull() }
            // (With neither row of albums kept, the albums the phone has by itself stand in
            // for them, so the tab is not Liked songs and nothing else.)
            val own = if (r == null && n == null) playableAlbums(playable()).take(2 * HOME_ROW).map { albumEntry(it, group = words.albums) } else emptyList()
            if (l != null || r != null || n != null) return@coroutineScope homeOf(l, r, n) + own
            // None of the three is kept, and Liked songs is not downloaded: what the phone
            // has by itself, if anything; with nothing at all, wait to know (a few seconds),
            // and then as it is.
            if (own.isNotEmpty()) return@coroutineScope own
            if (settled()) return@coroutineScope homeOffline(again)
        }
        val liked = async { runCatching { library.quick(library.liked(), again) }.getOrNull() }
        val recent = async { runCatching { library.quick(rows[0], again) }.getOrNull() }
        val newest = async { runCatching { library.quick(rows[1], again) }.getOrNull() }
        val (l, r, n) = Triple(liked.await(), recent.await(), newest.await())
        if (l == null && r == null && n == null) {
            // Nothing kept and nothing from the server: say why, rather than show an empty tab.
            library.quick(library.albums(AlbumList.Recent, 0, HOME_ROW))
        }
        homeOf(l, r, n)
    }

    /**
     * Home's "Made for you" (as the phone's Home row): each mix a folder of its songs, from
     * the mixes as they stand, worked out on the phone (no server, no waiting). Offline, a mix
     * with nothing that plays isn't shown; one that has something lists what plays.
     */
    private fun madeForYou(): List<CarEntry> = mixes()?.mixes.orEmpty().mapNotNull { mix ->
        val playing = if (isOffline) mix.songs.filter(::plays) else null
        if (playing != null && playing.isEmpty()) return@mapNotNull null
        val cover = playing?.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt ?: mix.cardCover
        CarEntry(CarIds.format(CarNode.Mix(mix.id)), words.mixTitle(mix), subtitle = words.mixAbout(mix), cover = cover,
            icon = R.drawable.ic_list_music, browsable = true, group = words.madeForYou, inside = CarStyle.LIST)
    }

    /** What Home's "Made for you" shows now (for telling the car when it changes). */
    fun madeForYouShown(): List<CarEntry> = madeForYou().map { it.copy(song = null) }

    /** What a mix's folder holds now: each song's id at its place, offline only those that
     *  play (for telling the car when it changes: different songs at the same places are a
     *  change, and so is a song coming to play offline). */
    fun mixShown(id: String): List<String> {
        val mix = mixOf(id) ?: return emptyList()
        val offline = isOffline
        val out = ArrayList<String>(mix.size)
        for (i in 0 until mix.size) {
            val song = mix.songs[i]
            if (!offline || plays(song)) out += "$i/${song.id}"
        }
        return out
    }

    /** A mix by its id: among those on Home, or worked out by itself (one no longer among
     *  them: a car that kept its folder). Null with no copy of the library yet. */
    private fun mixOf(id: String): org.medianexus.music.library.Mix? {
        val set = mixes() ?: return null
        return set.find(id) ?: org.medianexus.music.library.Mixes.one(set.from, id)
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

    /**
     * An album to play as the downloads' own record has it, **when no page of it is kept and
     * the record has what was asked for**: the song `with`, if one was chosen; otherwise the
     * whole album, downloaded whole and all arrived. Then it is played from the phone at once
     * rather than from the server (which the car used to wait on, the songs on the phone).
     * Null otherwise, and offline (which has its own way: [albumSongsOffline]).
     */
    private suspend fun downloadedInstead(id: String, with: String? = null): Pair<List<Song>, String>? {
        if (isOffline || id.isEmpty()) return null
        val state = downloadsNow()
        val album = DownloadedViews.album(state, id) ?: return null
        val has = if (with != null) album.songs.any { it.id == with } else album.whole && state.collections.firstOrNull { it.key == "album:$id" }?.songs?.all { s -> album.songs.any { it.id == s } } == true
        if (!has || kept(library.album(id)) != null) return null
        return album.songs to album.name
    }

    /** The songs of a list the car can play, what it is called and its route key (`with`:
     *  the song chosen in it, if one was). */
    private suspend fun songsOf(list: ListRef, with: String? = null): Triple<List<Song>, String, String>? = when (list) {
        is ListRef.Album -> if (list.id.isEmpty()) null else (downloadedInstead(list.id, with) ?: albumSongs(list.id, part = false) {}).let { (songs, name) -> Triple(songs, name, "album:${list.id}") }
        is ListRef.Playlist -> playlistSongs(list.id, part = false) {}.let { (songs, name) -> Triple(songs, name, "playlist:${list.id}") }
        ListRef.Liked -> Triple(likedSongs(part = false) {}, words.likedSongs, "liked")
        ListRef.Radio -> Triple(stations(part = false) {}, words.radio, RADIO_STATIONS)
        // The Downloads tab's lists, as the downloads have them. Their own names as sources,
        // so the queue doesn't follow a page of the server's with songs that aren't here.
        ListRef.DownloadedSongs -> Triple(DownloadedViews.songs(downloadsNow()), words.downloads, "downloads")
        is ListRef.DownloadedAlbum -> DownloadedViews.album(downloadsNow(), list.id)?.let { Triple(it.songs, it.name, "downloads:album:${list.id}") }
        is ListRef.DownloadedList -> DownloadedViews.playlist(downloadsNow(), list.key)?.let { Triple(it.songs, if (list.key == "liked") words.likedSongs else it.list.name, "downloads:${list.key}") }
        // A mix, as it stands, under Windows' name for where it was played from.
        is ListRef.Mix -> mixOf(list.id)?.let { Triple(it.songs, words.mixTitle(it), "mix:${list.id}") }
    }

    /** What "Shuffle" at the top of a list plays: the whole list, shuffled, starting anywhere.
     *  Null if the list is empty. */
    suspend fun shuffled(list: ListRef): PlayList? {
        val (songs, context, source) = songsOf(list) ?: return null
        return if (songs.isEmpty()) null else PlayList(songs, 0, context, source, shuffle = true, anyStart = true, shuffleChosen = true)
    }

    /** What choosing a song plays: its list, from it. Null if the song can't be found. */
    suspend fun play(track: CarNode.Track): PlayList? {
        val known = if (track.list is ListRef.Album) runCatching { songsOf(track.list, track.songId) }.getOrNull() else songsOf(track.list, track.songId)
        val start = known?.first?.let { songs -> if (songs.getOrNull(track.index)?.id == track.songId) track.index else songs.indexOfFirst { it.id == track.songId } } ?: -1
        // A station is chosen by its own id among the stations, and nothing else: one that is
        // no longer there is not found (it was looked up as a song).
        if (track.list == ListRef.Radio && start < 0) return null
        if (known == null || start < 0) {
            // A song with no album to speak of, or one its album doesn't hold (an old kept
            // page of it, a song kept from it separately): just the song, as a spoken request
            // plays it (offline, if it is on the phone). (Not yet known whether the server is
            // there: the phone's own, if it has it.)
            // (A downloaded song from the downloads' own record, whatever the server.)
            val here = downloadsNow().downloaded[track.songId]?.song ?: if (isOffline || isUnsure) playable().songs.firstOrNull { it.id == track.songId } else null
            val song = here ?: if (isOffline || (isUnsure && settled())) return null else library.song(track.songId)
            return PlayList(listOf(song), 0, song.title, "", chosen = true)
        }
        val (songs, context, source) = known
        return PlayList(songs, start, context, source, chosen = true)
    }

    /** Whether a song is liked now, for searches of what is on the phone (see [CarOffline.liked]). */
    private val likedNow: (String) -> Boolean get() = offline?.liked ?: { false }

    /** What the car's search shows: artists, albums, playlists and songs, each under a
     *  heading. Songs play their album from them. */
    suspend fun search(query: String): List<CarEntry> {
        val limits = SearchLimits(artists = 10, albums = 15, songs = 30)
        // Offline, what is on the phone. Not yet known whether the server is there (just
        // after start-up): what is on the phone if anything of it matches, rather than a wait.
        // (Decided once: what follows goes by this, not by another look at the server.)
        val here = if (isOffline || isUnsure) LocalSearch.find(query, playable().songs, limits, likedNow) else null
        // (Offline: only the playlists with something that plays, the downloaded ones among
        // them. Not yet known whether the server is there: the ones the phone knows of, and
        // never a wait for the server's list: a song found on the phone is not held up for it.)
        val playlists = runCatching { playlistsKnown(wait = false, phoneOnly = here != null) { library.quick(library.playlists()) } }.getOrDefault(emptyList())
            .filter { Voice.score(query, it.name) >= 0.6 }.take(MOST_PLAYLISTS_FOUND)
        val found = when {
            here == null -> library.search.find(query, limits)
            isOffline || here.songs.isNotEmpty() || here.albums.isNotEmpty() || here.artists.isNotEmpty() -> here
            // Nothing that plays matches, and it isn't known yet whether the server is
            // there. The phone's copy of the whole library answers at once, if there is one.
            library.search.instant -> library.search.find(query, limits)
            // A playlist the phone knows of matches: that is an answer, and is given now.
            playlists.isNotEmpty() -> here
            // Otherwise once it is known (a few seconds at most): the server's answer, or
            // the phone's nothing.
            settled() -> here
            else -> library.search.find(query, limits)
        }
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
        // Offline, only what is on the phone is looked in. And so it is when it isn't yet
        // known whether the server is there (just after start-up) and something on the phone
        // is named exactly by the words: that plays now, rather than after a wait to find
        // out. A near match on the phone (a slip forgiven: "Duke" finding "Dune") waits to
        // know, and then, with the server there, its search answers, where an exact match
        // beats the phone's near one. The playlists the phone knows of count as something on
        // the phone too: a playlist asked for by name, and downloaded, used to wait out the
        // not-knowing first.
        val offlineNow = isOffline || (isUnsure && (playable().let { p ->
            val local = LocalSearch.find(request.searchWords(), p.songs, SearchLimits(artists = 3, albums = 3, songs = 3), likedNow)
            val lists = playlistsKnown(wait = false, phoneOnly = true) { emptyList() }
            val genres = p.songs.filter { it.genre.isNotEmpty() }.map { it.genre }.distinct().map { Genre(name = it) }
            Voice.choose(request, local, lists, genres, exactOnly = true) != VoiceChoice.Nothing ||
                Voice.choose(request.copy(focus = VoiceRequest.Focus.ANY, query = request.searchWords()), local, lists, genres, exactOnly = true) != VoiceChoice.Nothing
        } || settled()))
        val here = if (offlineNow) playable() else null
        // Offline, only what is on the phone is looked in.
        suspend fun find(words: String, limits: SearchLimits) = if (here != null) LocalSearch.find(words, here.songs, limits, likedNow) else library.search.find(words, limits)
        val found = find(request.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))
        // (Answered from the phone, the playlists are the phone's too, and so is the list
        // that is then played: nothing waits on a server that has come back meanwhile.)
        val playlists = runCatching { playlistsKnown(wait = false, phoneOnly = offlineNow) { library.quick(library.playlists()) } }.getOrDefault(emptyList())
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
        val named = Voice.artistNamed(request)
        if (choice == VoiceChoice.Nothing && named != null) {
            // Nothing by the artist named matches what was asked for ("Duke by Paper
            // Lanterns", and they have no "Duke"): never something by another artist. That
            // artist's music, if the artist is known; otherwise the words as said, searched
            // as they are.
            val artist = VoiceRequest(query = named, focus = VoiceRequest.Focus.ARTIST, artist = named)
            choice = Voice.choose(artist, find(named, SearchLimits(artists = 10, albums = 0, songs = 0)), emptyList(), emptyList())
            if (choice == VoiceChoice.Nothing) {
                val words = Voice.spoken(request.query).ifBlank { request.searchWords() }
                choice = Voice.choose(VoiceRequest(query = words), find(words, SearchLimits(artists = 10, albums = 10, songs = 25)), playlists, genres, plain = true)
            }
        }
        return when (choice) {
            is VoiceChoice.OfAlbum -> if (offlineNow) albumSongsOffline(choice.album.id).let { (songs, name) -> PlayList(songs, 0, name, "album:${choice.album.id}") }
                else downloadedInstead(choice.album.id)?.let { (songs, name) -> PlayList(songs, 0, name, "album:${choice.album.id}") }
                    ?: library.quick(library.album(choice.album.id)).let { PlayList(it.songs, 0, it.name, "album:${it.id}") }
            is VoiceChoice.OfPlaylist -> playlistSongs(choice.playlist.id, part = false, phoneOnly = offlineNow) {}.let { (songs, name) ->
                PlayList(songs, 0, name.ifEmpty { choice.playlist.name }, "playlist:${choice.playlist.id}")
            }
            is VoiceChoice.OfSong -> {
                val song = choice.song
                val album = if (song.albumId.isEmpty()) null else runCatching { if (offlineNow) albumSongsOffline(song.albumId) else downloadedInstead(song.albumId, song.id) ?: albumSongs(song.albumId, part = false) {} }.getOrNull()
                val at = album?.first?.indexOfFirst { it.id == song.id } ?: -1
                if (album != null && at >= 0) PlayList(album.first, at, album.second, "album:${song.albumId}") else PlayList(listOf(song), 0, song.title, "")
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
        val songs = likedSongs(part = false) {}
        return if (songs.isEmpty()) null else PlayList(songs, 0, words.likedSongs, "liked", shuffle = true, anyStart = true)
    }

    companion object {
        /** The id of the machine's line at the head of a tab: neither played nor opened. */
        const val NOTICE_ID = "notice:"
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
