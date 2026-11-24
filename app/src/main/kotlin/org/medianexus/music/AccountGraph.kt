package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.library.Likes
import org.medianexus.music.net.ApiError
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
        fetch = { id, size -> api.fetchBytes(api.coverUrl(id, size), MOST_COVER_BYTES) },
        // Covers of downloaded music, kept with the downloads.
        pinned = File(downloadsDir, "covers"),
    )

    val library = Library(api, pages)

    /** Likes, ratings and plays waiting for the server (see [submit]). */
    val held = HeldChanges(File(account.dirs.data, "held.json"), io)

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
    val catalogue = Catalogue(downloadsDir)

    /** Which songs play with the server gone. */
    val availability = Availability(catalogue, keptIndex, cap = { settings.value.bitrate }, keeping = { settings.value.cacheLimitMb > 0 })

    /** The account's downloads; null where nothing of Android is to hand (tests). */
    val downloads: Downloads? = wiring?.downloads?.invoke(this)

    init {
        library.likedShown = likes::songsOf
        likes.load()
        // Covers past their limit go, oldest first, once a start: it reads the whole folder.
        scope.launch { covers.trim() }
        // Changes held from an earlier run go as soon as the server can be asked.
        scope.launch { if (!reach.value.offline) flush() }
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
     * Sends a change to the server, or holds it for later when that isn't possible (Windows'
     * `submit`). Offline, it is held. The server refusing it (it is there, and said no) is
     * thrown, for whoever asked to say so and put it back; anything else (no connection, a
     * proxy answering in the server's place, a sign-in it won't take) holds it, and the
     * server is asked whether it is there at all.
     */
    suspend fun submit(change: Pending) {
        if (reach.value.offline) {
            held.hold(change)
            return
        }
        try {
            send(change)
        } catch (e: ApiError) {
            if (e is ApiError.Server && !e.signInRefused) throw e
            held.hold(change)
            suspect()
        }
    }

    private suspend fun send(change: Pending) = when (change) {
        is Pending.Star -> api.setStarred(StarTarget.entries.firstOrNull { it.name.equals(change.kind, ignoreCase = true) } ?: StarTarget.SONG, change.id, change.starred)
        is Pending.Rating -> api.setRating(change.id, change.rating)
        is Pending.Played -> api.scrobbleAt(change.id, change.at)
    }

    /** Sends what was held back (Windows' `flush_pending`). What the server dealt with goes;
     *  if it stopped answering part-way, it is asked whether it is there. */
    suspend fun flush() {
        val sent = held.flush(::send) ?: return
        // What the server now has (likes made offline) is asked for again.
        if (sent.done > 0) likes.refresh()
        if (sent.lostTouch) suspect()
    }

    fun close() {
        downloads?.release()
        scope.cancel()
    }

    private companion object {
        const val MOST_COVER_BYTES = 16L * 1024 * 1024
    }
}
