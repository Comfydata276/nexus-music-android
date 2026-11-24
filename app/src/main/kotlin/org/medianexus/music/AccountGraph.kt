package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.library.Likes
import org.medianexus.music.net.StarTarget
import org.medianexus.music.net.Subsonic
import org.medianexus.music.playback.QueueFiles
import org.medianexus.music.store.Account
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.HeldChanges
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.Pending
import org.medianexus.music.store.Settings

/**
 * What the app around an account gives it: the settings, where the app stands with the
 * server, and what downloads need of Android. Null in tests that need none of it.
 */
class AccountWiring(
    val settings: StateFlow<Settings>,
    val reach: StateFlow<Reach>,
    /** A request couldn't reach the server: worth asking whether it is there at all. */
    val suspect: () -> Unit,
    /** Makes the account's downloads (they need Android: Media3's manager, the network). */
    val downloads: ((AccountGraph) -> Downloads)? = null,
    /** Something for the Diagnostics page (no names, no addresses). */
    val note: (String) -> Unit = {},
    /** The phone's network: the library copy's daily refresh waits for one that isn't metered. */
    val network: StateFlow<org.medianexus.music.net.NetworkNow>? = null,
    /** Whether anyone is using the app now (its screens in front, or something playing): the
     *  copy of the library is checked against the server only then. */
    val inUse: () -> Boolean = { true },
)

/**
 * What belongs to the account that's signed in: its client for the server, its kept pages
 * and covers, its library, its likes, its downloads, the changes it holds for the server and
 * its saved queue. Made when someone signs in (or the saved sign-in is read at start-up) and
 * closed when they sign out, so nothing of one account is ever shown to another.
 */
class AccountGraph(
    val account: Account,
    val api: Subsonic,
    io: CoroutineDispatcher = Dispatchers.IO,
    wiring: AccountWiring? = null,
) {
    /** Work for this account; cancelled when it signs out. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings: StateFlow<Settings> = wiring?.settings ?: MutableStateFlow(Settings())
    private val reach: StateFlow<Reach> = wiring?.reach ?: MutableStateFlow(Reach())
    private val suspect: () -> Unit = wiring?.suspect ?: {}
    private val inUse: () -> Boolean = wiring?.inUse ?: { true }

    /** The account's name in its covers' addresses (see [artworkIdOf]). */
    val artwork: String = org.medianexus.music.store.artworkIdOf(account.dirs.data)

    val pages = PageCache(File(account.dirs.cache, "pages"), scope, io)

    /** Where downloads live: the account's own data, which the system never clears. */
    val downloadsDir = File(account.dirs.data, "downloads")

    val covers = CoverStore(
        File(account.dirs.cache, "covers"), scope, io,
        // No cover is asked of a server known to be out of reach: the fetch fails here, at
        // once, and is tried again when the server is back (CoverStore.retryFailed).
        fetch = { id, size ->
            if (reach.value.offline) throw org.medianexus.music.net.ApiError.Network("The server is out of reach.")
            api.fetchBytes(api.coverUrl(id, size), MOST_COVER_BYTES)
        },
        // Covers of downloaded music, kept with the downloads.
        pinned = File(downloadsDir, "covers"),
    )

    /** The copy of the whole library kept on the phone (see [LibraryCopy]): search and the
     *  Songs page. In the cache, as the pages are: it can always be fetched again. */
    val copy = org.medianexus.music.library.LibraryCopy(
        File(account.dirs.cache, "library"),
        object : org.medianexus.music.library.LibraryServer {
            override suspend fun scanStatus() = api.scanStatus()
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int) = api.libraryPage(artists, albums, songs, offset)
        },
        io,
        unmetered = { wiring?.network?.value?.unmetered ?: true },
        note = wiring?.note ?: {},
        pageKept = { org.medianexus.music.downloads.CrashPoints.reached("library-page-$it") },
    )

    val library: Library = Library(api, pages, org.medianexus.music.library.PhoneSearch(copy, org.medianexus.music.library.ServerSearch(api)) { id -> likes.isLiked(id) }, copy)

    /** Likes, ratings and plays on their way to the server, and the one sender they all go
     *  through, in order (see [HeldChanges] and [submit]). */
    val held = HeldChanges(
        File(account.dirs.data, "held.json"), io,
        send = { send(it) },
        offline = { reach.value.offline },
        // It stopped answering part-way, or something answered in its place: worth asking
        // whether it is there at all.
        lostTouch = { suspect() },
        note = wiring?.note ?: {},
        // The phone wouldn't take a write the sender depends on: tried again in a while.
        tryLater = { heldAgainLater() },
    )

    private var heldRetry: kotlinx.coroutines.Job? = null

    private fun heldAgainLater() {
        synchronized(this) {
            if (heldRetry?.isActive == true) return
            heldRetry = scope.launch {
                kotlinx.coroutines.delay(HELD_RETRY_MILLIS)
                synchronized(this@AccountGraph) { heldRetry = null }
                flush()
            }
        }
    }

    val likes: Likes = Likes(pages, library.liked(), send = { target, id, liked -> submit(Pending.Star(target.name.lowercase(), id, liked)) }, scope)

    /** When songs were added to playlists, as far as this phone has seen (Windows'
     *  `added.json`): "Date added" sorts by it. */
    val added = org.medianexus.music.store.Added(File(account.dirs.data, "added.json"), scope, io)

    /** The last hundred songs played (Windows' `history.json`). */
    val recentlyPlayed = org.medianexus.music.store.RecentlyPlayed(File(account.dirs.data, "history.json"), scope, io)

    /**
     * When each of a list's songs was added to it, in seconds (0 where that isn't known), for
     * "Date added" (Windows' `added_dates`): a playlist's own notes, else the day the song
     * joined the library, the nearest thing the server knows; Liked songs, when each was
     * liked; the Songs page, the day each joined the library.
     */
    fun addedDates(key: String, songs: List<org.medianexus.music.net.Song>): LongArray {
        val parse = org.medianexus.music.library.Arrange::parseTime
        val day = { song: org.medianexus.music.net.Song -> org.medianexus.music.library.Arrange.dayStart(parse(song.created)) }
        return when {
            key.startsWith("playlist:") -> {
                val noted = added.dates(key.removePrefix("playlist:"), songs)
                LongArray(songs.size) { if (noted[it] > 0) noted[it] else day(songs[it]) }
            }
            key == "liked" -> LongArray(songs.size) { parse(songs[it].starred) }
            key == Library.SONGS -> LongArray(songs.size) { day(songs[it]) }
            else -> LongArray(songs.size)
        }
    }

    /** The files the play queue is saved in, with the account's own data. What the player
     *  does with them belongs to the playback service, each time one is made (`KeptQueue`):
     *  nothing here remembers whether a saved queue has been put back. */
    val queueFiles = QueueFiles.of(account.dirs.data)

    /** Where kept songs go. Opened by the playback service, which owns them. */
    val keptSongsDir = File(account.dirs.cache, "songs")

    /** Which kept songs are kept whole (see [KeptIndex]): beside them, not inside. */
    val keptIndex = KeptIndex(File(account.dirs.cache, "kept.json"), scope, io)

    /** What is downloaded: the songs, the lists downloaded whole, the failures. */
    val catalogue = Catalogue(downloadsDir, note = wiring?.note ?: {})

    /** Which songs play with the server gone. */
    val availability = Availability(catalogue, keptIndex, cap = { settings.value.bitrate }, keeping = { settings.value.cacheLimitMb > 0 })

    /** The account's downloads; null where nothing of Android is to hand (tests). */
    val downloads: Downloads? = wiring?.downloads?.invoke(this)

    init {
        library.likedShown = likes::songsOf
        likes.load()
        scope.launch { recentlyPlayed.open() }
        // Every copy of a playlist read from the server (or the phone) is looked at, so a song
        // that turns up in it later can be dated (Windows' `observe_playlist`).
        scope.launch {
            pages.changes.collect { (key, value) ->
                if (key.startsWith("playlist:") && value is org.medianexus.music.net.Playlist) {
                    added.observe(key.removePrefix("playlist:"), value.songs, System.currentTimeMillis() / 1000)
                }
            }
        }
        // Covers past their limit go, oldest first, once a start: it reads the whole folder.
        scope.launch { covers.trim() }
        // Changes held from an earlier run go as soon as the server can be asked: now, and
        // whenever the app stops being offline (the server back, or offline mode switched off
        // by hand, which starts the sender just as the server returning does).
        scope.launch {
            reach.map { it.offline }.distinctUntilChanged().collect { offline -> if (!offline) flush() }
        }
        // The copy of the library: read from the phone at once, then checked with the server
        // (Windows' `load_library`): now, every five minutes while the app is in use (Windows'
        // timer), and whenever the server can be asked again.
        scope.launch {
            copy.open()
            var first = true
            while (true) {
                if (first || inUse()) checkCopy()
                first = false
                kotlinx.coroutines.delay(COPY_CHECK_MILLIS)
            }
        }
        scope.launch {
            reach.map { it.offline }.distinctUntilChanged().drop(1).collect { offline -> if (!offline) launch { checkCopy() } }
        }
        // A list downloaded whole follows its kept page: its order, and the songs it gains.
        downloads?.let { downloads ->
            scope.launch {
                pages.changes.collect { (key, value) ->
                    val list = catalogue.collection(key) ?: return@collect
                    val (now, songs) = org.medianexus.music.downloads.DownloadedViews.listOf(key, value, list) ?: return@collect
                    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { downloads.follow(now, songs) }
                }
            }
        }
    }

    /**
     * Makes a change (a like, a rating, a play): written to the phone, then sent in its turn
     * by the account's one sender (Windows' `submit`, but with no way round the queue).
     * Returns when the server has it or it is safely held for later. The server refusing it
     * (it is there, and said no) is thrown, for whoever asked to say so and put it back; so
     * is a change that could be neither kept nor sent.
     */
    suspend fun submit(change: Pending) {
        held.submit(change)
    }

    private suspend fun send(change: Pending) = when (change) {
        is Pending.Star -> api.setStarred(StarTarget.entries.firstOrNull { it.name.equals(change.kind, ignoreCase = true) } ?: StarTarget.SONG, change.id, change.starred)
        is Pending.Rating -> api.setRating(change.id, change.rating)
        is Pending.Played -> api.scrobbleAt(change.id, change.at)
    }

    /** Sends what is waiting (Windows' `flush_pending`), if the server may be asked. Says how
     *  many changes that had been held the server has now dealt with (taken, or turned down
     *  for good). */
    suspend fun flush(): Int {
        val sent = held.flush()
        // What the server now has (likes made offline) is asked for again.
        if (sent > 0) likes.refresh()
        return sent
    }

    /** Asks whether the copy of the library is out of date, and fetches it if it is. Not
     *  offline; a server that can't be reached is tried again at the next check. */
    private suspend fun checkCopy() {
        if (reach.value.offline) return
        try {
            copy.check()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: org.medianexus.music.net.ApiError) {
            // Out of reach, or refused: what had arrived is kept, and asked for again later.
        } catch (_: java.io.IOException) {
            // The phone's storage wouldn't take a page: tried again at the next check.
        }
    }

    fun close() {
        downloads?.release()
        scope.cancel()
    }

    private companion object {
        const val MOST_COVER_BYTES = 16L * 1024 * 1024

        /** How often the copy of the library is checked against the server while the app is
         *  in use (Windows' five minutes). */
        const val COPY_CHECK_MILLIS = 5 * 60 * 1000L

        /** How long after the phone refused a write to the held changes they are tried again. */
        const val HELD_RETRY_MILLIS = 30_000L
    }
}
