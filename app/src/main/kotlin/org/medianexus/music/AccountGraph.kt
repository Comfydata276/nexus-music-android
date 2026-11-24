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

    val library = Library(api, pages)

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
    )

    val likes = Likes(pages, library.liked(), send = { target, id, liked -> submit(Pending.Star(target.name.lowercase(), id, liked)) }, scope)

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
        // Covers past their limit go, oldest first, once a start: it reads the whole folder.
        scope.launch { covers.trim() }
        // Changes held from an earlier run go as soon as the server can be asked: now, and
        // whenever the app stops being offline (the server back, or offline mode switched off
        // by hand, which starts the sender just as the server returning does).
        scope.launch {
            reach.map { it.offline }.distinctUntilChanged().collect { offline -> if (!offline) flush() }
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

    fun close() {
        downloads?.release()
        scope.cancel()
    }

    private companion object {
        const val MOST_COVER_BYTES = 16L * 1024 * 1024
    }
}
