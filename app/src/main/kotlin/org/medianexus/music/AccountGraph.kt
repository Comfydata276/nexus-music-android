package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
    /** The settings themselves, to change: keeping in step with the person's other devices
     *  takes in what they chose (pins, each list's choices, the theme). Null: nothing is
     *  shared (tests of other things). */
    val settingsStore: org.medianexus.music.store.SettingsStore? = null,
    /** Whether the app's screens are in front: only then is the server looked at each
     *  minute for what other devices have changed. */
    val front: StateFlow<Boolean> = MutableStateFlow(false),
    /** Makes the account's connection to Lidarr (it needs the phone: the Keystore, the
     *  folder that is out of backups). Asked only in a build that has requests. */
    val requests: ((AccountGraph) -> org.medianexus.music.requests.Requests)? = null,
    /** Runs something that must finish though the account has gone (handing a token back
     *  to a sign-in service at sign-out): on the app's own scope. */
    val afterwards: (suspend () -> Unit) -> Unit = {},
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
    /** Where its work runs (a test holds it at a gate). */
    work: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Work for this account; cancelled when it signs out. */
    val scope = CoroutineScope(SupervisorJob() + work)

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
            ratings.answered(change, refusal)
        },
        moved = {
            likes.heldChanged()
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
    val added = org.medianexus.music.store.Added(File(account.dirs.data, "playlist-added.json"), scope, io, formerly = File(account.dirs.data, "added.json"), note = wiring?.note ?: {})

    /** Changes to the account's playlists, and how a playlist is shown while they are on
     *  their way (see [org.medianexus.music.library.Playlists]). */
    val playlists: org.medianexus.music.library.Playlists = org.medianexus.music.library.Playlists(
        // Reads by the ordinary way; every change by the way that never sends one twice.
        api.once().let { once ->
            object : org.medianexus.music.library.PlaylistServer {
                override suspend fun playlist(id: String) = api.playlistCounted(id)
                override suspend fun createPlaylist(name: String, songIds: List<String>) = once.createPlaylist(name, songIds)
                override suspend fun renamePlaylist(id: String, name: String) = once.renamePlaylist(id, name)
                override suspend fun addToPlaylist(id: String, songIds: List<String>) = once.addToPlaylist(id, songIds)
                override suspend fun replacePlaylist(id: String, songIds: List<String>) = once.replacePlaylist(id, songIds)
                override suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) = once.removeManyFromPlaylist(id, positions)
                override suspend fun deletePlaylist(id: String) = once.deletePlaylist(id)
            }
        },
        pages, listSpec = { library.playlists() }, playlistSpec = { library.playlist(it) },
        reachable = { reach.value.let { !it.offline && it.sure } }, username = account.username, scope = scope,
        added = added,
    )

    /** The newest the app has seen of each song's plays and rating (a song's details). */
    val freshest: org.medianexus.music.library.Freshest = org.medianexus.music.library.Freshest(held = held.queued)

    /** Star ratings: shown at once, sent in turn, held offline (see [org.medianexus.music.library.Ratings]). */
    val ratings: org.medianexus.music.library.Ratings = org.medianexus.music.library.Ratings(freshest, submit = { change, written -> submit(change, written) }, scope)

    /** The last hundred songs played (Windows' `history.json`). */
    val recentlyPlayed = org.medianexus.music.store.RecentlyPlayed(File(account.dirs.data, "history.json"), scope, io, note = wiring?.note ?: {})

    /** Every play that counted, and when, for "Your stats" and the mixes (Windows'
     *  `plays.log`; see [org.medianexus.music.store.PlayLog]). */
    val playLog = org.medianexus.music.store.PlayLog(File(account.dirs.data, "plays.log"), io, note = wiring?.note ?: {})

    /** Recently played and the log of plays have been read from the phone for this account:
     *  until then they are not shared with other devices, nor theirs taken in (see [step]). */
    private val historyRead = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Sharing between devices is switched on, and the server may be asked now. */
    private fun mayShare(): Boolean = settings.value.sync && !reach.value.offline && !closed

    /** The account has gone ([close]): nothing more is exchanged for it. */
    @Volatile private var closed = false

    private val front: StateFlow<Boolean> = wiring?.front ?: MutableStateFlow(false)
    private val network: StateFlow<org.medianexus.music.net.NetworkNow>? = wiring?.network

    /** Songs the server has said it doesn't have, so they aren't asked for at every exchange
     *  (a song in another device's history that has since been deleted). */
    private val songsGone = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The songs with these ids, for Recently played as other devices have it: from the
     *  phone's copy of the library where it has them, else asked of the server one by one,
     *  as Windows asks. */
    private suspend fun songsById(ids: List<String>): Map<String, org.medianexus.music.net.Song> {
        val found = HashMap<String, org.medianexus.music.net.Song>()
        val wanted = ids.toHashSet()
        copy.state.value?.songs?.let { table ->
            for (i in 0 until table.size) if (table.id(i) in wanted && table.id(i) !in found) found[table.id(i)] = table.song(i)
        }
        for (id in ids) {
            if (id in found || id in songsGone) continue
            try {
                api.song(id).takeIf { it.id == id }?.let { found[id] = it }
            } catch (e: org.medianexus.music.net.ApiError.Server) {
                if (e.code == 70L) songsGone += id
            } catch (_: org.medianexus.music.net.ApiError) {
                // Asked for again at the next exchange.
            }
        }
        return found
    }

    /** This phone's side of what is shared between devices (see [step]). None in a build
     *  where keeping in step doesn't exist ([org.medianexus.music.sync.InStepBuild]). */
    private val shared: org.medianexus.music.sync.PhoneShared? = wiring?.settingsStore?.takeIf { org.medianexus.music.sync.InStepBuild.on }?.let { store ->
        org.medianexus.music.sync.PhoneShared(
            store, account.key, account.username, added, recentlyPlayed, playLog,
            read = { historyRead.isCompleted },
            playlists = { pages.kept(library.playlists())?.mapTo(HashSet()) { it.id } },
            songs = ::songsById,
            // The record as this version first finds it, kept before its first write: with
            // the account's own data, so there is one for each account, and named for the
            // version, so there is one for each version that writes.
            keptFirst = File(account.dirs.data, "shared-record-kept-${org.medianexus.music.BuildConfig.VERSION_NAME}.json"),
            note = wiring?.note ?: {},
        )
    }

    /**
     * Keeping in step with the person's other devices (Windows' src/sync.rs): the pins, each
     * list's shuffle and sort, the theme, when songs were added to playlists, Recently played
     * and the latest plays, kept as text in the comment of a playlist on the server. Null
     * where nothing is shared: in a build where it doesn't exist
     * ([org.medianexus.music.sync.InStepBuild], this one), and in tests of other things.
     * Null, nothing starts an exchange or schedules a look for one.
     *
     * The record is written by the account's client directly, beside the playlists' own way
     * of changing a playlist ([playlists]) and not through it: that way is for what the
     * person does to a playlist (it waits its turn behind their other changes, is shown on
     * the page, is refused with words, and is sent once and never again), and none of that
     * is wanted here. This write is the app's own, of the whole value, and safe to make
     * again: if it gets no answer, the next exchange reads what the server has and writes
     * only if it still adds something.
     */
    val step: org.medianexus.music.sync.InStep? = shared?.let { phone ->
        org.medianexus.music.sync.InStep(
            object : org.medianexus.music.sync.StepServer {
                override suspend fun playlists() = api.playlistsStated()
                override suspend fun playlist(id: String) = api.playlistStated(id)
                // Made once: a request sent twice would make two.
                override suspend fun create(name: String) = api.once().createPlaylist(name, emptyList()).id
                override suspend fun setComment(id: String, comment: String) = api.setPlaylistComment(id, comment)
            },
            // (Asked again before each request that changes the server: still this account,
            // not let go, with sharing on.)
            // And not while this account's Recently played is on the phone and can't be
            // read: what is in memory then is not its history, and an exchange would publish
            // the little there is and call itself done.
            phone, account.username, account.key, { scope.isActive && mayShare() && !recentlyPlayed.unreadable }, scope, pause = pause, note = wiring?.note ?: {},
        )
    }

    /** The play queue shared with the person's other devices through the server's saved
     *  queue, and "Continue here" (see [org.medianexus.music.sync.QueueStep]). Null where
     *  [step] is: the queue is then neither saved on the server nor read from it. */
    val queueStep: org.medianexus.music.sync.QueueStep? = wiring?.settingsStore?.takeIf { org.medianexus.music.sync.InStepBuild.on }?.let { store ->
        org.medianexus.music.sync.QueueStep(
            save = { device, upload -> api.savePlayQueue(device, upload.ids, upload.current, upload.position) },
            saved = { api.playQueueTimed() },
            deviceId = {
                // A made-up name for this device, made the first time it is wanted.
                if (store.value.deviceId.isEmpty()) store.update { if (it.deviceId.isEmpty()) it.copy(deviceId = org.medianexus.music.sync.newDeviceId()) else it }
                store.value.deviceId
            },
            account = account.key,
            // (Not while this phone's clock is wrong: a queue saved then would be told to
            // other devices as changed at a time that never was.)
            may = { scope.isActive && mayShare() && step?.clockIs?.value != org.medianexus.music.sync.InStep.ClockIs.WRONG }, on = { settings.value.sync }, standing = { settings.value.syncOffs }, scope = scope, pause = pause, note = wiring?.note ?: {},
        )
    }

    private val afterwards: (suspend () -> Unit) -> Unit = wiring?.afterwards ?: {}

    /**
     * Requests: asking Lidarr for music that isn't in the library. **Null unless this build
     * has them** ([org.medianexus.music.requests.RequestsBuild]): then nothing of them is
     * read, kept or asked. With them, still off until the person switches them on.
     */
    val requests: org.medianexus.music.requests.Requests? =
        if (org.medianexus.music.requests.RequestsBuild.on) wiring?.requests?.invoke(this)?.also { it.start() } else null

    private var lookedAt = 0L

    /** Picks up whatever other devices have changed, and passes on what changed here
     *  (Windows' `look_elsewhere`): the shared record, then the saved queue. `queue` false:
     *  the record alone (see [queueDue]). */
    suspend fun look(queue: Boolean = true) {
        if (!mayShare()) return
        synchronized(this) { lookedAt = System.currentTimeMillis() }
        // (Not while a change made here is settling: its own exchange is seconds away and
        // reads as this would. A look that came round first would pass on half a burst of
        // changes, which the settling is there to send as one.)
        if (step?.settling() != true) step?.exchange()
        if (queue) queueStep?.check()
    }

    /** The account's mixes of its own, made to rules (Windows' `rule_mixes`). */
    val ruleMixes = org.medianexus.music.store.RuleMixes(File(account.dirs.data, "rule-mixes.json"), io)

    /** The "Made for you" mixes, kept up to date off the main thread (see [org.medianexus.music.library.MixBook]). */
    val mixes = org.medianexus.music.library.MixBook(
        scope, copy.state, likes.liked, likes.known, recentlyPlayed.state, ruleMixes.state,
        took = { mixes, songs, millis -> if (org.medianexus.music.BuildConfig.DEBUG) android.util.Log.d("NexusMixes", "$mixes mixes from $songs songs in ${"%.1f".format(millis)} ms") },
    )

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

    /** Lyrics kept on the phone, fetched ahead, and kept with the downloads (see LyricsBook). */
    val lyrics = org.medianexus.music.lyrics.LyricsBook(File(account.dirs.cache, "lyrics"), File(account.dirs.data, "lyrics"), { api.lyrics(it) }, scope, io, offline = { reach.value.offline })

    init {
        library.likedShown = likes::songsOf
        library.playlistShown = playlists::fetched
        likes.load()
        scope.launch {
            recentlyPlayed.open()
            // With no log yet it starts empty: Recently played is not taken for plays that
            // counted (see PlayLog).
            playLog.open()
            if (recentlyPlayed.unreadable && step != null) {
                (wiring?.note ?: {})("in step: Recently played on this phone couldn't be read, so nothing is exchanged with your other devices for this account until it can be (it is tried again when the app next starts)")
            }
            historyRead.complete(Unit)
            // In step with the person's other devices: now (Windows' `start_sync`); when
            // the app's screens come to the front (unless a look was made a moment ago); and
            // every quarter of a minute from then on while they stay there. **The timer
            // itself runs only while the screens are in front**: with them gone, nothing
            // here wakes to find it has nothing to do. In the background what changes here
            // still goes out a few seconds after it does (each song that starts, each play
            // that counts), and nobody is looking at what changed elsewhere.
            if (step != null) {
                look()
                front.collectLatest { inFront ->
                    if (!inFront) return@collectLatest
                    // (Not within ten seconds of the last look. A last look that is in the
                    // future is one made before the phone's clock was put back: that is no
                    // reason to wait, and after a clock that was wrong is exactly when a
                    // look is wanted.)
                    val since = System.currentTimeMillis() - synchronized(this@AccountGraph) { lookedAt }
                    if (since < 0 || since >= LOOK_FLOOR_MILLIS) look()
                    // The record is a few small requests, and is looked at every time. The
                    // saved queue is not small (the server sends every song of it in full),
                    // so it is looked at as often only where that costs nobody anything:
                    // see [queueDue].
                    var sinceQueue = 0L
                    while (true) {
                        pause(org.medianexus.music.sync.InStep.LOOK_MILLIS)
                        sinceQueue += org.medianexus.music.sync.InStep.LOOK_MILLIS
                        val queue = queueDue(sinceQueue, unmetered = network?.value?.unmetered != false, playing = queueStep?.playingHere() == true)
                        if (queue) sinceQueue = 0
                        look(queue)
                    }
                }
            }
        }
        if (step != null) {
            // Something shared changed here: passed on once things have settled. (What an
            // exchange itself brought in is not a change made here.)
            added.changedHere = { step.shareSoon() }
            recentlyPlayed.changedHere = { step.shareSoon() }
            scope.launch {
                // (The shared core's choices as the settings hold them: when they are not as
                // the last exchange left them, the person has changed something here.)
                settings.map { if (org.medianexus.music.sync.Belongs.ready(it, account.key)) it.choices else null }
                    .distinctUntilChanged().drop(1)
                    .collect { now -> if (now != null && now != shared?.settled) step.shareSoon() }
            }
            // The server back, or sharing switched on: a look at once. Switched off, what
            // another device was playing is no longer offered.
            scope.launch {
                kotlinx.coroutines.flow.combine(reach.map { it.offline }, settings.map { it.sync }) { offline, on -> !offline && on }
                    .distinctUntilChanged().drop(1)
                    .collect { may -> if (may) { if (historyRead.isCompleted) launch { look() } } else if (!settings.value.sync) queueStep?.forget() }
            }
        }
        scope.launch { ruleMixes.open() }
        // Every copy of a playlist read from the server (or the phone) is looked at, so a song
        // that turns up in it later can be dated (Windows' `observe_playlist`).
        scope.launch {
            pages.changes.collect { (key, value) ->
                // (Noticed, for this phone's own "Date added"; not put on record for other
                // devices: see Added.sawCopy.)
                if (key.startsWith("playlist:") && value is org.medianexus.music.net.Playlist) {
                    added.sawCopy(key.removePrefix("playlist:"), value.songs, System.currentTimeMillis() / 1000)
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
        // A downloaded song has its lyrics with no signal: fetched as it arrives, and for one
        // already on the phone, when the server can next be asked (as sparingly as downloads:
        // with Wi-Fi only on, on Wi-Fi).
        downloads?.let { d ->
            scope.launch {
                // Looked at again whenever lyrics may next be fetched: the downloads change, the
                // server comes back, the network or the Wi-Fi-only setting changes.
                kotlinx.coroutines.flow.combine(
                    d.state.map { it.downloaded.keys }.distinctUntilChanged(),
                    reach.map { it.offline }.distinctUntilChanged(),
                    wiring?.network ?: kotlinx.coroutines.flow.flowOf(null),
                    settings.map { it.downloadWifiOnly }.distinctUntilChanged(),
                ) { ids, _, _, _ -> ids }
                    .collect { ids -> lyrics.downloaded(ids) { !settings.value.downloadWifiOnly || wiring?.network?.value?.unmetered != false } }
            }
        }
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

    /** Taken by [played] and [close]: the account's going can't come between a play's two
     *  halves. */
    private val going = Any()
    private var gone = false

    /**
     * A play counted (decided by `PlayReporter`): noted in the log of plays, and, if plays are
     * reported (`report`), the server's held change made, **from here, one straight after the
     * other**, so that the account going (signing out) can't come between them: both happen,
     * or, once it has gone, neither. The log's line is written by the log (its closing writes
     * what it holds); the change is put on the disk by the one sender, a write that is not
     * cancelled once begun. (What a kill of the process between the two leaves is accepted:
     * see DESIGN, "The log of plays".) `atMillis` is when it counted.
     */
    fun played(songId: String, atMillis: Long, from: String, report: Boolean) = synchronized(going) {
        if (gone || songId.isEmpty()) return@synchronized
        playLog.add(songId, atMillis / 1000, from)
        // (One of the latest plays the person's other devices are told of: passed on soon,
        // not left for whatever next sets an exchange off.)
        step?.shareSoon()
        if (report) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching { submit(Pending.Played(songId, atMillis)) } }
    }

    fun close() {
        closed = true
        synchronized(going) { gone = true }
        // The account's secrets for requests go with its password: the key forgotten, the
        // sign-in's token handed back to its service. (After this account's own work has
        // been cancelled, so on the app's.)
        requests?.let { mine -> afterwards { mine.leave() } }
        downloads?.release()
        // Everything it holds written, then the file let go for whoever opens it next.
        playLog.close()
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

        /** The app coming to the front doesn't look at the server again within this long of
         *  the last look. */
        const val LOOK_FLOOR_MILLIS = 10_000L
    }
}

/** How often the server's saved queue is looked at where it is not looked at with
 *  every look: once a minute, as it was before 0.5.1. */
internal const val QUEUE_SPARING_MILLIS = 60_000L

/**
 * Whether a look, made `since` milliseconds after the saved queue was last looked
 * at, looks at it too. With every look (every quarter of a minute) only on a
 * network Android says is not metered, and only while nothing is playing here:
 *
 * - the saved queue is the heavy part of a look: about 120 bytes a song once
 *   squeezed, so 60 KB for a queue of 500, four times a minute, is 15 MB an hour
 *   of mobile data spent on a screen that is merely open;
 * - a phone that is playing keeps its own queue whatever the server holds, and what
 *   another device played is offered for half an hour: nothing there is worth
 *   seconds.
 *
 * Otherwise once a minute, as before. Coming to the front looks at it at once
 * either way.
 */
internal fun queueDue(since: Long, unmetered: Boolean, playing: Boolean): Boolean = since >= QUEUE_SPARING_MILLIS || (unmetered && !playing)
