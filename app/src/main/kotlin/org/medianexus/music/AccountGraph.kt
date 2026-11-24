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
    /** Waits this many milliseconds before something is tried again (a test's own clock). */
    val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
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
    private val pause: suspend (Long) -> Unit = wiring?.pause ?: { kotlinx.coroutines.delay(it) }

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
    val held: HeldChanges = HeldChanges(
        File(account.dirs.data, "held.json"), io,
        send = { send(it) },
        offline = { reach.value.offline },
        // It stopped answering part-way, or something answered in its place: worth asking
        // whether it is there at all. If it has gone, its coming back sends what is held. If
        // it is there after all (one request lost as the phone changed network, a proxy's
        // page for one answer) the app was never offline and nothing comes back: so what is
        // held is also tried again in a while, whatever the answer. (It used to wait for the
        // next change to be made, or the next time the server was lost and found.)
        lostTouch = {
            suspect()
            heldAgainLater()
        },
        note = wiring?.note ?: {},
        // The phone wouldn't take a write the sender depends on, or the server answered a
        // change with an error that says nothing about it: tried again in a while.
        tryLater = { heldAgainLater() },
        // A change that had been held has had its answer, however long after it was made:
        // whoever made it is told, as a change sent straight away tells its maker on return.
        answered = { change, refusal ->
            likes.answered(change, refusal == null)
            freshest.settled(change, refusal == null)
            playlists.answered(change, refusal)
            ratings.answered(change, refusal)
        },
        moved = {
            likes.heldChanged()
            playlists.heldChanged()
            freshest.heldChanged()
        },
    )

    private var heldRetry: kotlinx.coroutines.Job? = null

    /** How long the next wait before what is held is tried again: half a minute, and twice
     *  as long each time nothing comes of it, up to half an hour. */
    private var heldPause = HELD_RETRY_MILLIS

    /** Sends what is held again in a while (once, however many ask meanwhile). */
    private fun heldAgainLater() {
        synchronized(this) {
            if (heldRetry?.isActive == true) return
            val wait = heldPause
            heldPause = minOf(heldPause * 2, HELD_RETRY_MOST_MILLIS)
            heldRetry = scope.launch {
                pause(wait)
                synchronized(this@AccountGraph) { heldRetry = null }
                flush()
            }
        }
    }

    val likes: Likes = Likes(
        pages, library.liked(),
        send = { target, id, liked, song -> submit(Pending.Star(target.name.lowercase(), id, liked, at = System.currentTimeMillis(), song = song)) },
        scope, held = held.queued,
    )

    /** When songs were added to playlists, as far as this phone has seen (Windows'
     *  `playlist-added.json`, the name it has there; kept as `added.json` before): "Date
     *  added" sorts by it. */
    val added = org.medianexus.music.store.Added(File(account.dirs.data, "playlist-added.json"), scope, io, formerly = File(account.dirs.data, "added.json"))

    /** Changes to the account's playlists, and how a playlist is shown while they are on
     *  their way (see [org.medianexus.music.library.Playlists]). */
    val playlists: org.medianexus.music.library.Playlists = org.medianexus.music.library.Playlists(
        object : org.medianexus.music.library.PlaylistServer {
            override suspend fun playlist(id: String) = api.playlist(id)
            override suspend fun createPlaylist(name: String, songIds: List<String>) = api.createPlaylist(name, songIds)
            override suspend fun renamePlaylist(id: String, name: String) = api.renamePlaylist(id, name)
            override suspend fun addToPlaylist(id: String, songIds: List<String>) = api.addToPlaylist(id, songIds)
            override suspend fun replacePlaylist(id: String, songIds: List<String>) = api.replacePlaylist(id, songIds)
            override suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) = api.removeManyFromPlaylist(id, positions)
            override suspend fun deletePlaylist(id: String) = api.deletePlaylist(id)
        },
        pages, listSpec = { library.playlists() }, playlistSpec = { library.playlist(it) },
        submit = { change, written -> submit(change, written) },
        held = held.queued, offline = { reach.value.offline }, username = account.username, scope = scope,
        added = added,
    )

    /** The newest the app has seen of each song's plays and rating (a song's details). */
    val freshest: org.medianexus.music.library.Freshest = org.medianexus.music.library.Freshest(held = held.queued)

    /** Star ratings: shown at once, sent in turn, held offline (see [org.medianexus.music.library.Ratings]). */
    val ratings: org.medianexus.music.library.Ratings = org.medianexus.music.library.Ratings(freshest, submit = { change, written -> submit(change, written) }, scope)

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
            // (The Songs page's list is places in the copy: read without making each a song.)
            key == Library.SONGS && songs is org.medianexus.music.library.PlacedSongs ->
                LongArray(songs.size) { org.medianexus.music.library.Arrange.dayStart(songs.createdSeconds(it)) }
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
        library.playlistShown = playlists::fetched
        library.playlistsShown = playlists::onTopOfList
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
        // Every page from the server says how often its songs have been played, and how
        // they are rated, now: newer than the copy of the library a song may be shown from.
        // (When each was asked for: the later reading wins; and a newer copy of the library
        // is newer than what was read before it.)
        scope.launch { pages.heard.collect { freshest.sawPage(it.value, it.askedAt) } }
        scope.launch { copy.state.collect { snap -> snap?.let { freshest.copyArrived(it.stamp.fetched * 1000) } } }
        // Covers past their limit go, oldest first, once a start: it reads the whole folder.
        scope.launch { covers.trim() }
        // Changes held from an earlier run are read at once (they are part of what is liked,
        // and of what wants to know whether the server is there), and go as soon as the
        // server can be asked: now, and
        // whenever the app stops being offline (the server back, or offline mode switched off
        // by hand, which starts the sender just as the server returning does).
        scope.launch {
            held.open()
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
    suspend fun submit(change: Pending, written: () -> Unit = {}) {
        freshest.reported(change)
        // Settled when the server has it, or has refused it: what is shown follows the server
        // again. (Held for later, it settles in a later pass, which tells `answered` above.)
        val outcome = try {
            held.submit(change, written)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            freshest.settled(change, taken = false)
            throw e
        }
        if (outcome == org.medianexus.music.store.HeldChanges.Outcome.TAKEN) freshest.settled(change, taken = true)
    }

    private suspend fun send(change: Pending) = when (change) {
        is Pending.Star -> api.setStarred(StarTarget.entries.firstOrNull { it.name.equals(change.kind, ignoreCase = true) } ?: StarTarget.SONG, change.id, change.starred)
        is Pending.Rating -> api.setRating(change.id, change.rating)
        is Pending.Played -> api.scrobbleAt(change.id, change.at)
        is Pending.PlaylistChange -> playlists.send(change)
    }

    /** Sends what is waiting (Windows' `flush_pending`), if the server may be asked. */
    suspend fun flush() {
        val before = held.accepted.value
        val dealtWith = held.flush()
        // Nothing left waiting: the next thing held starts from half a minute again.
        if (held.waiting.value == 0) synchronized(this) { heldPause = HELD_RETRY_MILLIS }
        // What the server now has (likes made offline), or has turned down, is asked for again.
        if (dealtWith > 0 || held.accepted.value != before) likes.refresh()
    }

    /** How many held changes the server has accepted that nobody has yet been told of. */
    private var saidUpTo = held.accepted.value

    /**
     * How many changes that had been held the server has accepted since this was last
     * asked: what "the changes you made offline have been sent" counts. Accepted, not
     * merely dealt with (one the server turned down for good was not sent in any sense the
     * person means); and each counted once, whichever pass sent it and however many things
     * ask for a pass at the same moment (two do, when the server comes back).
     */
    @Synchronized
    fun acceptedToSay(): Int {
        val now = held.accepted.value
        val more = (now - saidUpTo).toInt()
        saidUpTo = now
        return more
    }

    /** Asks whether the copy of the library is out of date, and fetches it if it is. Not
     *  offline; a server that can't be reached is tried again at the next check. */
    private suspend fun checkCopy() {
        if (reach.value.offline) return
        try {
            // A fresh copy: which songs are liked is asked again too, as Windows does (search
            // ranks liked songs first).
            if (copy.check()) likes.refresh()
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

        /** How long after the phone refused a write to the held changes, or the server
         *  couldn't be asked for one of them while it was taken to be there, they are tried
         *  again. */
        const val HELD_RETRY_MILLIS = 30_000L
        const val HELD_RETRY_MOST_MILLIS = 30 * 60_000L
    }
}
