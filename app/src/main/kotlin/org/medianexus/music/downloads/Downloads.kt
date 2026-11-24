package org.medianexus.music.downloads

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.medianexus.music.Reach
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.NetworkWatch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.playback.songUri
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.Settings

/** Where one song stands among the downloads. */
sealed interface SongState {
    data object None : SongState
    data object Waiting : SongState
    /** On its way: `fraction` of it has arrived (0 to 1; -1 when its size isn't known). */
    data class Fetching(val fraction: Float) : SongState
    data object Done : SongState
    data class Failed(val reason: String) : SongState
}

/** A song waiting to be fetched, or being fetched now. */
data class Active(val song: Song, val state: SongState)

/** Why downloads that are waiting aren't moving. */
enum class Held {
    NOTHING, WIFI, NETWORK, OFFLINE, SERVER,

    /** Android has stopped the job the downloads were running under: they wait until it
     *  runs it again (or the app is opened). */
    ANDROID,
}

/**
 * Everything about one account's downloads, at one moment, for the screens and the car: what
 * is downloaded, what is on its way and how far it has got, what failed and why, the lists
 * downloaded whole, and how the current batch is going.
 */
data class DownloadsState(
    val downloaded: Map<String, Downloaded> = emptyMap(),
    /** On their way, in the order they'll arrive. */
    val active: List<Active> = emptyList(),
    val failed: List<Failed> = emptyList(),
    val collections: List<DownloadedList> = emptyList(),
    val held: Held = Held.NOTHING,
    /** This batch: how many songs it has, and how many of them are done or failed (Windows'
     *  `batch_total`, `batch_done`, `batch_failed`). */
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val batchFailed: Int = 0,
    /** Media3's manager has read what was waiting from its list: until then "nothing on its
     *  way" only means "not read yet". */
    val settled: Boolean = false,
    /** Everything the downloads take up on the phone, in bytes, as last measured: the songs,
     *  and also what is part-way, the covers kept with them, and anything left lying. -1
     *  until it has been measured. */
    val occupied: Long = -1,
) {
    private val activeById: Map<String, Active> by lazy { active.associateBy { it.song.id } }
    private val failedById: Map<String, Failed> by lazy { failed.associateBy { it.song.id } }

    fun stateOf(id: String): SongState = when {
        id in downloaded -> SongState.Done
        else -> activeById[id]?.state ?: failedById[id]?.let { SongState.Failed(it.reason) } ?: SongState.None
    }

    val busy: Boolean get() = active.isNotEmpty()

    /** How far the whole batch has got, from 0 to 1, counting songs part-way (Windows'
     *  `batch_progress`). */
    val progress: Float
        get() {
            val partial = active.sumOf { ((it.state as? SongState.Fetching)?.fraction?.coerceAtLeast(0f) ?: 0f).toDouble() }
            return (((batchDone + batchFailed) + partial) / batchTotal.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        }

    /** "Downloading 3 of 12" (Windows' sidebar line): the number of the song now arriving. */
    val at: Int get() = (batchDone + batchFailed + 1).coerceAtMost(batchTotal.coerceAtLeast(1))

    /** The room the downloads take on the phone, as the person is told it: everything in
     *  their folder (see [occupied]); until that has been measured, the downloaded songs. */
    val bytes: Long get() = if (occupied >= 0) occupied else downloaded.values.sumOf { it.bytes }
}

/** What became of asking for songs to be downloaded, for the screens to say. */
sealed interface Asked {
    data class Started(val count: Int, val first: Song) : Asked
    data object AlreadyDownloaded : Asked
    /** Not enough room: `needed` and `free` in bytes (half a gigabyte is always left). */
    data class NoRoom(val needed: Long, val free: Long) : Asked
    data object Offline : Asked
}

/** What a song's download is asked for with, kept in Media3's list of downloads. */
@Serializable
internal data class Ask(val song: Song, val bitrate: Int, val suffix: String)

/**
 * One account's downloads (Windows' `Downloads` and the controller's side of it, in
 * src/downloads.rs).
 *
 * **The frame is Media3's download manager**: it keeps the list of what is waiting in its own
 * database (in a table of this account's), runs a few at a time, waits for the network the
 * settings ask for (any, or Wi-Fi only) and stops what is under way when that goes, tries a
 * failing download again a few times, and carries on with what was waiting after the app or
 * the phone has been stopped. **The downloading is our own** ([SongFetch]): Media3's own
 * downloader writes into a cache, a piece at a time, which is no way to tell a whole song
 * from part of one. Ours writes the song to a file of its own, whole or not at all, and the
 * [Catalogue] entry it makes at the end is the mark that it is.
 *
 * What keeps the app running while downloads are under way, and brings it back to finish
 * them, is [DownloadKeeper]'s.
 *
 * Its own methods are called on the main thread, where Media3's manager must be used.
 */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    val catalogue: Catalogue,
    private val api: Subsonic,
    private val http: OkHttpClient,
    database: DatabaseProvider,
    /** The account's key: the name of its table in Media3's database. */
    accountKey: String,
    private val settings: StateFlow<Settings>,
    private val reach: StateFlow<Reach>,
    private val covers: CoverStore,
    /** Tells the keeper whether there is work, and on which network it may be done. */
    private val keeper: (busy: Boolean, wifiOnly: Boolean) -> Unit,
    private val scope: CoroutineScope,
    /** Room always left spare on the phone (smaller in a test build, to see the refusal). */
    private val spare: () -> Long = { SPARE },
    private val note: (String) -> Unit = {},
    /** The phone's network, as it changes (see [NetworkWatch]): downloads run when there is
     *  one, and it is unmetered if Wi-Fi only is on. */
    private val network: StateFlow<NetworkNow> = MutableStateFlow(NetworkNow()),
    /** What says whether a file that has arrived is the song (a stand-in, in tests whose
     *  songs are not audio). */
    private val check: AudioCheck = Media3AudioCheck.system,
    /** Where the catalogue is written from the main thread's side, and the start-up sweep of
     *  the folder runs: one thing at a time, in the order asked, never on the main thread.
     *  (A test's own, to hold it.) */
    private val disk: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = MutableStateFlow(DownloadsState())

    /** Where the downloads stand, as it changes; the screens and the car follow this. */
    val state: StateFlow<DownloadsState> = now.asStateFlow()

    private lateinit var manager: DownloadManager

    /** Done once Media3's manager has read what was waiting and the start-up sweep of the
     *  folder has finished: until then no work is accepted (see [Catalogue.sweep]). */
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var released = false

    /** Songs on their way, by id, as they were asked for (and in what form). Known here the
     *  moment a song is asked for, before Media3's own list of downloads says so. */
    private val asks = HashMap<String, Ask>()
    /** The songs of this batch (Windows' `batch_total` counts them): what has become of
     *  each is read from the catalogue, not counted from Media3's events, which a song asked
     *  for again, or removed meanwhile, would throw out. */
    private val batch = LinkedHashSet<String>()
    private var ticking: Job? = null
    private var initialized = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Android has stopped the job the downloads were running under (see [stoppedByAndroid]). */
    private var stopped = false

    /** Until the start-up work is done (see `onInitialized`): an earlier engine's fetches
     *  into the same folder have unwound, and the folder has been swept. Nothing runs before. */
    private var opening = true

    /** The client transfers go out on: the app's own, or one bound to the network Android's
     *  job was given (see [useNetwork]). */
    @Volatile private var route: OkHttpClient = http

    /** The settings, the network and the server, as each changes. */
    private fun follow() {
        // A song entered in the catalogue (on a download's own thread), removed, or failed:
        // published at once, not when Media3 next says something.
        scope.launch(Dispatchers.Main.immediate) {
            catalogue.state.collect {
                publish()
                if (initialized) measure()
            }
        }
        scope.launch(Dispatchers.Main.immediate) { settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { gate() } }
        scope.launch(Dispatchers.Main.immediate) { network.collect { gate() } }
        scope.launch(Dispatchers.Main.immediate) { reach.map { it.offline }.distinctUntilChanged().collect { gate() } }
    }

    /**
     * Whether downloads may run now, decided here and nowhere else, at every change of
     * anything it rests on: not offline (by hand, or our own server out of reach: what is
     * under way stops and is picked up again later, rather than failing for want of a
     * server); a network there, and an unmetered one if Wi-Fi only is on, **as the system
     * reports the network** (never whether Android has validated it as reaching the
     * internet: whether our server answers is asked of our server); and not stopped by
     * Android. What is under way is stopped at once when it turns to no.
     */
    private fun gate() {
        if (!::manager.isInitialized || released) return
        if (opening || holdsFor() != Held.NOTHING) manager.pauseDownloads() else manager.resumeDownloads()
        publish()
    }

    /** Why downloads may not run now ([Held.NOTHING]: they may). */
    private fun holdsFor(): Held {
        val net = network.value
        val wifiOnly = settings.value.downloadWifiOnly
        return when {
            reach.value.manual -> Held.OFFLINE
            reach.value.unreachable -> Held.SERVER
            !net.connected -> if (wifiOnly) Held.WIFI else Held.NETWORK
            wifiOnly && !net.unmetered -> Held.WIFI
            stopped -> Held.ANDROID
            else -> Held.NOTHING
        }
    }

    /**
     * Android has stopped the job the downloads were running under (`true`: its time ran out,
     * the network it was given went, the phone needs the memory), or is running it again
     * (`false`). Stopped, the transfers stop too, here and now, and wait: the app must not go
     * on downloading in a process Android has told to stop, on whatever network happens to
     * be the default.
     */
    fun stoppedByAndroid(on: Boolean) = onMain {
        if (stopped == on) return@onMain
        stopped = on
        gate()
    }

    /**
     * The network Android gave the job the downloads run under, or null when there is none
     * (no job; or it has ended): transfers started from now on go out on it, not on whatever
     * the phone's default route is (a job asked for an unmetered network must not download
     * over mobile data because that is the default at that moment). If a transfer on it can't
     * reach the server at all, the phone's own route is gone back to (see `SongDownloader`):
     * a network that is "the job's" and has no way to the server must not stop every download.
     */
    fun useNetwork(given: android.net.Network?) {
        route = if (given == null) http else http.newBuilder()
            .socketFactory(given.socketFactory)
            .dns { host -> given.getAllByName(host).toList() }
            // Connections belong to the network they were made on: none are shared with the
            // app's own.
            .connectionPool(okhttp3.ConnectionPool(AT_ONCE, 1, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    // ---- Asking for downloads --------------------------------------------------------------

    /** One request at a time: each is decided on what the one before it left (what is queued,
     *  the room it will take), not on what stood before either began. */
    private val asking = Mutex()

    /** A write to the catalogue from the main thread's side: done on [disk], in order. */
    private suspend fun <T> onDisk(write: () -> T): T = withContext(disk) { write() }

    /** The same, for where nothing waits for it. */
    private fun later(write: () -> Unit) {
        scope.launch(disk) { write() }
    }

    /**
     * Downloads these songs. Ones already downloaded or on their way are passed over (asking
     * again for one on its way does not start it again). Nothing is started that would not
     * fit, with half a gigabyte to spare (as Windows checks), **counting the room that what is
     * already queued and arriving still needs**. `list` is the album, playlist or Liked songs
     * they are, downloaded whole: it stays downloaded, and songs it gains are fetched too.
     * `own`: the songs were asked for by themselves (a song's own Download), and keep their
     * files whatever becomes of any list they are in.
     */
    suspend fun download(wanted: List<Song>, list: DownloadedList? = null, own: Boolean = false): Asked = asking.withLock { ask(wanted, list, own) }

    private suspend fun ask(wanted: List<Song>, list: DownloadedList?, own: Boolean): Asked {
        ready.await()
        // (Whatever was being written to the catalogue has been, before it is read.)
        onDisk {}
        val quality = settings.value.downloadQuality
        val seen = HashSet<String>()
        val proper = wanted.filter { it.radioUrl.isEmpty() && it.id.isNotEmpty() && seen.add(it.id) }
        val fresh = proper.filter { !catalogue.has(it.id) && it.id !in asks }
        val needed = fresh.sumOf { expectedBytes(it, quality) }
        if (fresh.isNotEmpty()) {
            val owed = owed()
            val free = withContext(Dispatchers.IO) { catalogue.dir.usableSpace }
            if (needed + owed + spare() > free) return Asked.NoRoom(needed + owed, free)
        }
        onDisk {
            if (list != null) catalogue.keep(list.copy(at = list.at.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)))
            if (own) catalogue.single(proper.map { it.id })
            if (fresh.isNotEmpty()) catalogue.clearFailures(fresh.map { it.id })
        }
        if (fresh.isEmpty()) return Asked.AlreadyDownloaded
        if (asks.isEmpty()) batch.clear()
        fresh.mapTo(batch) { it.id }
        for (song in fresh) {
            val form = savedForm(song, quality)
            val ask = Ask(song.copy(queued = false), form.bitrate, form.suffix)
            asks[song.id] = ask
            val request = DownloadRequest.Builder(song.id, Uri.parse(songUri(song.id)))
                .setData(json.encodeToString(Ask.serializer(), ask).toByteArray())
                .build()
            manager.addDownload(request)
        }
        publish()
        // Covers for the lists, kept where the system won't clear them.
        list?.coverArt?.takeIf { it.isNotEmpty() }?.let { scope.launch { covers.pin(it) } }
        return if (reach.value.offline) Asked.Offline else Asked.Started(fresh.size, fresh.first())
    }

    /** The room that what is queued or arriving still needs: what each is expected to come
     *  to, less what has arrived of it. */
    private fun owed(): Long {
        val arrived = manager.currentDownloads.associate { it.request.id to it.bytesDownloaded }
        return asks.values.sumOf { ask ->
            val whole = if (ask.bitrate > 0) ask.song.duration.toLong() * ask.bitrate * 125 else ask.song.size.coerceAtLeast(0)
            (whole - (arrived[ask.song.id] ?: 0)).coerceAtLeast(0)
        }
    }

    /**
     * The download button on an album, playlist or Liked songs (Windows' `toggle_page_download`):
     * cancels what is under way, downloads what is missing, or, if all of it is downloaded,
     * removes it.
     *
     * **Only this list's standing changes, and only songs nothing else wants are touched**
     * (see [withdraw], which every removal or cancelling of a group goes through).
     */
    suspend fun toggle(list: DownloadedList, songs: List<Song>): Toggled = asking.withLock {
        ready.await()
        onDisk {}
        val ids = songs.map { it.id }
        val waiting = ids.filter { it in asks }
        // Whether it is this list that is on its way: a song of it arriving for another list,
        // or by itself, doesn't make its button a Cancel (there would be nothing of this
        // list's to cancel); pressed then, it downloads the list, as it would with nothing
        // arriving.
        val kept = catalogue.collection(list.key) != null
        when {
            waiting.isNotEmpty() && kept -> withdraw(list.key, waiting, files = false)
            waiting.isEmpty() && songs.isNotEmpty() && songs.all { catalogue.has(it.id) } -> withdraw(list.key, ids, files = true)
            else -> Toggled.Asked(ask(songs, list.copy(songs = ids), own = false))
        }
    }

    /**
     * A group as the Downloads page shows it (an album with anything downloaded, a playlist
     * or Liked songs downloaded whole: `key` is its list's, `ids` the songs shown), by its
     * button there: cancelled if any of it is on its way, else removed. The same path as a
     * list's own button, so the same rule about who else wants each song.
     */
    suspend fun removeGroup(key: String, ids: List<String>): Toggled = asking.withLock {
        ready.await()
        onDisk {}
        val waiting = ids.filter { it in asks }
        if (waiting.isNotEmpty()) withdraw(key, waiting, files = false) else withdraw(key, ids, files = true)
    }

    /**
     * **The one way a group is removed or cancelled**: the wish for the group is withdrawn,
     * and of `ids` only the songs nothing else wants are touched (deleted, with `files`;
     * otherwise only stopped on their way). A song another list downloaded whole holds stays
     * downloaded (or goes on arriving), and that list stays downloaded whole.
     *
     * What "the wish for the group" is: the list `key`, if it is downloaded whole; then a
     * song of it that was also asked for by itself keeps its file, as its own download. If
     * `key` is no list downloaded whole (an album on the Downloads page made of songs
     * downloaded one at a time, or its own page showing every song downloaded), the group
     * *is* those songs' own downloads, and it is those that are withdrawn: otherwise its
     * button could never remove anything.
     *
     * Decided on the disk's own thread, in turn with every other change, under [asking].
     */
    private suspend fun withdraw(key: String, ids: List<String>, files: Boolean): Toggled {
        val others = onDisk {
            if (catalogue.collection(key) != null) forgetList(key) else catalogue.unsingle(ids)
            catalogue.wantedBesides(key)
        }
        val going = ids.filter { it !in others }
        if (!files) {
            stop(going)
            return Toggled.Cancelled
        }
        drop(going)
        // Nothing of it went: every song of it is held by something else downloaded.
        return if (going.isEmpty()) Toggled.HeldElsewhere else Toggled.Removed
    }

    sealed interface Toggled {
        data object Cancelled : Toggled
        data object Removed : Toggled

        /** Asked to be removed, and all of its songs stay: other downloads hold them. */
        data object HeldElsewhere : Toggled
        data class Asked(val asked: org.medianexus.music.downloads.Asked) : Toggled
    }

    /**
     * Stops these songs' downloads, waiting or part-way, by hand, **and nothing puts them
     * back**: they are no longer wanted for themselves, and a list downloaded whole that one
     * of them belongs to is no longer kept whole (as removing a song by hand has always done:
     * otherwise the next look at the list, or the server coming back, would fetch them all
     * again, and the Cancel would have been for nothing). What had already arrived of such a
     * list stays downloaded.
     */
    fun cancel(ids: Collection<String>) = onMain {
        val asked = ids.toSet()
        later {
            for (list in catalogue.state.value.collections) if (list.songs.any { it in asked }) forgetList(list.key)
            catalogue.unsingle(asked)
        }
        stop(asked)
    }

    /** Stops these songs' downloads, and nothing more. */
    private fun stop(ids: Collection<String>) = onMain {
        if (!::manager.isInitialized) return@onMain
        for (id in ids) {
            manager.removeDownload(id)
            asks.remove(id)
        }
        batch.removeAll(ids.filter { !catalogue.has(it) }.toSet())
        publish()
    }

    /** Stops everything on its way, waiting or part-way, as [cancel] does: the lists those
     *  songs were for are no longer kept whole, so nothing fetches them again. */
    fun cancelAll() = onMain { cancel(asks.keys + now.value.active.map { it.song.id }) }

    /** Has another go at the songs that couldn't be downloaded. */
    suspend fun retryFailed(onlyNetwork: Boolean = false): Asked? {
        val failed = catalogue.state.value.failed.values.filter { !onlyNetwork || it.network }
        if (failed.isEmpty()) return null
        return download(failed.map { it.song })
    }

    /**
     * Deletes these songs' downloaded copies, by hand, and stops any still on their way. A
     * list downloaded whole that one of them belongs to is no longer kept whole (its other
     * songs stay downloaded): otherwise the next look at it would fetch the song straight
     * back. (Removing a *list* is [toggle]'s, and changes no other list.)
     */
    fun remove(ids: Collection<String>) = onMain {
        val asked = ids.toSet()
        later {
            for (list in catalogue.state.value.collections) if (list.songs.any { it in asked }) forgetList(list.key)
            catalogue.unsingle(ids)
        }
        drop(ids)
    }

    /** Deletes these songs' downloaded copies and stops any on their way, and nothing more:
     *  the files go off the main thread, in turn with every other change to the catalogue. */
    private fun drop(ids: Collection<String>) = onMain {
        if (::manager.isInitialized) for (id in ids) manager.removeDownload(id)
        ids.forEach { asks.remove(it) }
        later {
            val gone = ids.mapNotNull { catalogue.entry(it)?.song?.coverArt }
            catalogue.remove(ids)
            unpin(gone)
            main.post { measure() }
        }
        publish()
    }

    /** Everything downloaded goes, and every list downloaded whole is forgotten. */
    fun removeAll() = onMain {
        val ids = catalogue.state.value.songs.keys + asks.keys + now.value.active.map { it.song.id }
        later {
            catalogue.state.value.collections.forEach { forgetList(it.key) }
            catalogue.unsingle(catalogue.state.value.singles)
        }
        drop(ids.toList())
    }

    /** The lists downloaded whole: fetches whatever they have gained (Windows'
     *  `sync_collections`). `fresh` gets a list as the server has it now, with its songs. */
    suspend fun topUp(fresh: suspend (DownloadedList) -> Pair<DownloadedList, List<Song>>?) {
        if (reach.value.offline) return
        for (list in catalogue.state.value.collections) {
            val (now, songs) = try {
                fresh(list)
            } catch (_: org.medianexus.music.net.ApiError) {
                null
            } ?: continue
            follow(now, songs)
        }
    }

    /**
     * A list downloaded whole, as it is now (a page of it has come from the server): its name,
     * cover and order are kept for browsing offline, and what it has gained is downloaded,
     * quietly. Nothing is taken out: a song gone from the list stays downloaded, as on
     * Windows. Does nothing for a list that isn't downloaded whole.
     */
    suspend fun follow(list: DownloadedList, songs: List<Song>) {
        onDisk {}
        val was = catalogue.collection(list.key) ?: return
        val next = list.copy(songs = songs.map { it.id }, at = was.at)
        // (Only if it is still downloaded whole by the time it is written.)
        if (next != was) onDisk { if (catalogue.collection(list.key) != null) catalogue.keep(next) }
        if (reach.value.offline) return
        // Asked for only if the list is still downloaded whole when its turn to ask comes: it
        // may have been cancelled or removed while this waited (everything written before
        // now has been, by then), and what was cancelled must not be put back.
        asking.withLock {
            ready.await()
            onDisk {}
            if (catalogue.collection(list.key) == null) return
            val missing = songs.filter { !catalogue.has(it.id) }
            if (missing.isNotEmpty()) ask(missing, null, own = false)
        }
    }

    // ---- Covers -----------------------------------------------------------------------------

    /** A list is no longer downloaded whole: forgotten, and its own cover (kept with the
     *  downloads when it was asked for) let go if nothing downloaded still uses it. (Off the
     *  main thread.) */
    private fun forgetList(key: String) {
        val cover = catalogue.collection(key)?.coverArt
        catalogue.forget(key)
        if (!cover.isNullOrEmpty()) unpin(listOf(cover))
    }

    /** Lets go of the covers nothing downloaded uses any more. (Off the main thread.) */
    private fun unpin(coverIds: List<String>) {
        val still = HashSet<String>()
        catalogue.state.value.songs.values.mapTo(still) { it.song.coverArt }
        catalogue.state.value.collections.mapTo(still) { it.coverArt }
        coverIds.filter { it.isNotEmpty() && it !in still }.distinct().forEach { covers.unpin(it) }
    }

    // ---- What Media3 says -------------------------------------------------------------------

    private inner class Listener : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            // What was waiting when the app last stopped: its songs, from what they were asked
            // for with. And what a download cut short left in the folder (part files with no
            // download waiting) is cleared away.
            for (download in downloadManager.currentDownloads) if (!download.isTerminalState && download.state != Download.STATE_REMOVING) ask(download)?.let { asks[it.song.id] = it }
            if (batch.isEmpty()) batch += asks.keys
            // Every download Media3 still holds, whatever was readable of it: the sweep must
            // leave their files alone.
            val busy = downloadManager.currentDownloads.mapTo(HashSet()) { it.request.id } + asks.keys
            initialized = true
            gate()
            // What a download cut short left in the folder is cleared away, once, now: before
            // any work is accepted ([ready]), so nothing asked for from here on can have a
            // file of its own in the folder while the sweep looks.
            scope.launch(disk) {
                try {
                    // A quick sign-out and sign-in to the same account makes this engine while
                    // the last one's fetches are still unwinding (Media3 interrupts its tasks
                    // on release and doesn't wait for them), and the two would write the same
                    // part files. The old one's are waited for.
                    if (!InFlight.awaitNone(catalogue.dir)) note("downloads: an earlier fetch had not let go of the folder after half a minute; carried on without waiting longer")
                    catalogue.sweep(busy)
                } finally {
                    ready.complete(Unit)
                    main.post {
                        opening = false
                        gate()
                        measure()
                        recheckOld()
                    }
                }
            }
            // Downloads whose files were found, at sign-in, to be missing or not the length
            // that was recorded: not downloads any more, and fetched again.
            val again = catalogue.wanting.map { it.song }.filter { !catalogue.has(it.id) }
            if (again.isNotEmpty()) {
                note("downloads: ${again.size} marked whole were not, and are fetched again")
                scope.launch(Dispatchers.Main.immediate) { download(again) }
            }
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val id = download.request.id
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    asks.remove(id)
                    catalogue.entry(id)?.song?.coverArt?.takeIf { it.isNotEmpty() }?.let { cover ->
                        // Have the artwork kept too, for when the server can't be reached.
                        scope.launch { covers.pin(cover) }
                    }
                }
                Download.STATE_FAILED -> {
                    val song = asks.remove(id)?.song ?: ask(download)?.song
                    if (song != null) {
                        val failure = finalException?.let { generateSequence<Throwable>(it) { e -> e.cause }.firstNotNullOfOrNull { e -> e as? DownloadFailure } }
                        // Written off the main thread (and a write that fails, the phone
                        // being full, is said and not thrown: see Catalogue).
                        later { catalogue.failed(Failed(song, failure?.reason ?: "It couldn't be downloaded.", failure?.network ?: true)) }
                        note("downloads: one failed (${if (failure?.network == true) "the server out of reach" else "see the Downloads list"})")
                    }
                }
                Download.STATE_REMOVING -> asks.remove(id)
                else -> if (id !in asks) ask(download)?.let { asks[id] = it }
            }
            publish()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            asks.remove(download.request.id)
            publish()
            // (What had arrived of it has gone with it.)
            measure()
        }

        override fun onIdle(downloadManager: DownloadManager) {
            publish()
            measure()
        }


        override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) = publish()
    }

    private fun ask(download: Download): Ask? = download.request.data.takeIf { it.isNotEmpty() }?.let {
        runCatching { json.decodeFromString(Ask.serializer(), String(it)) }.getOrNull()
    }

    /** Works out the state from Media3's list and the catalogue, and tells the keeper. */
    private fun publish() {
        if (!::manager.isInitialized) return
        val snapshot = catalogue.state.value
        val current = manager.currentDownloads
        val active = current.mapNotNull { download ->
            if (download.state == Download.STATE_REMOVING || download.state == Download.STATE_COMPLETED || download.state == Download.STATE_FAILED) return@mapNotNull null
            val song = asks[download.request.id]?.song ?: ask(download)?.song ?: return@mapNotNull null
            val state = if (download.state == Download.STATE_DOWNLOADING) {
                val percent = download.percentDownloaded
                SongState.Fetching(if (percent == C.PERCENTAGE_UNSET.toFloat()) -1f else (percent / 100f).coerceIn(0f, 0.99f))
            } else {
                SongState.Waiting
            }
            Active(song, state)
        }.sortedBy { if (it.state is SongState.Fetching) 0 else 1 }
        // (The batch is counted afresh when downloads are next asked for with nothing on its
        // way: not here, where a song just asked for may not be in Media3's list yet.)
        val held = if (active.isEmpty()) Held.NOTHING else holdsFor()
        val next = DownloadsState(
            downloaded = snapshot.songs,
            active = active,
            failed = snapshot.failed.values.toList().asReversed(),
            collections = snapshot.collections,
            held = held,
            batchTotal = batch.size,
            batchDone = batch.count { it in snapshot.songs },
            batchFailed = batch.count { it in snapshot.failed },
            settled = initialized,
            occupied = occupied,
        )
        now.value = next
        // Offline by hand, nothing is to be done until it is switched off.
        keeper(active.isNotEmpty() && !reach.value.manual, settings.value.downloadWifiOnly)
        if (active.any { it.state is SongState.Fetching }) tick() else {
            ticking?.cancel()
            ticking = null
        }
    }

    /** While something is arriving, how far it has got is looked at twice a second. */
    private fun tick() {
        if (ticking?.isActive == true) return
        ticking = scope.launch(Dispatchers.Main.immediate) {
            var ticks = 0
            while (true) {
                delay(TICK_MILLIS)
                publish()
                // (What is part-way takes room too.)
                if (++ticks % MEASURE_EVERY_TICKS == 0) measure()
            }
        }
    }

    /** Everything in the downloads folder, as last added up (see [DownloadsState.occupied]). */
    private var occupied = -1L
    private var measuring: Job? = null
    private var measureAgain = false

    /**
     * Adds up what the downloads folder holds, off the main thread, one count at a time (a
     * change while one is out is counted after it): every file under it, whoever's it is.
     * What the person is told the downloads take must be what removing them would give back.
     */
    private fun measure() {
        if (measuring?.isActive == true) {
            measureAgain = true
            return
        }
        measuring = scope.launch(Dispatchers.IO) {
            val total = runCatching { catalogue.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(-1L)
            withContext(Dispatchers.Main.immediate) {
                measuring = null
                if (total >= 0 && total != occupied) {
                    occupied = total
                    publish()
                }
                if (measureAgain) {
                    measureAgain = false
                    measure()
                }
            }
        }
    }

    /** The catalogue changed (a song arrived, was removed): published. */
    fun refresh() = onMain { publish() }

    /** The account has gone: its downloads stop here (they carry on when it is back). */
    fun release() = onMain {
        released = true
        ticking?.cancel()
        if (::manager.isInitialized) manager.release()
        route = http
    }

    // ---- The downloader ---------------------------------------------------------------------

    /** Makes our own downloader for each song (see the class). */
    private inner class Factory : DownloaderFactory {
        override fun createDownloader(request: DownloadRequest): Downloader {
            val ask = request.data.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(Ask.serializer(), String(it)) }.getOrNull() }
            return SongDownloader(request.id, ask)
        }
    }

    private inner class SongDownloader(private val id: String, private val ask: Ask?) : Downloader {
        private val fetch = SongFetch(route, catalogue, check, ::asTheServerHasIt)

        override fun download(progressListener: Downloader.ProgressListener?) {
            val ask = ask ?: throw IllegalStateException("A download with nothing to say what it is")
            val form = SavedForm(ask.bitrate, ask.suffix)
            InFlight.enter(catalogue.dir)
            try {
                fetchUntilSettled(ask, form, progressListener)
            } finally {
                InFlight.leave(catalogue.dir)
            }
            main.post { publish() }
        }

        private fun fetchUntilSettled(ask: Ask, asked: SavedForm, progressListener: Downloader.ProgressListener?) {
            var tries = 0
            // A file this phone was found to have no decoder for is not fetched as it is
            // again: the server's conversion, as streaming asks for one it can't play.
            var form = if (!asked.converted && ask.song.id in undecodable) convertedForm() else asked
            while (true) {
                try {
                    fetch.fetch(ask.song, form, api.streamUrl(ask.song.id, form.bitrate)) { got, total ->
                        progressListener?.onProgress(total, got, if (total > 0) 100f * got / total else C.PERCENTAGE_UNSET.toFloat())
                    }
                    break
                } catch (e: Cancelled) {
                    // Stopped by Media3 (cancelled, the network went, offline): not a failure.
                    // (Only then: Media3 takes an interruption it didn't ask for as success.)
                    throw InterruptedException("Stopped")
                } catch (e: DownloadFailure) {
                    // The server can't be reached: Media3 tries again a few times, and meanwhile
                    // the server is asked whether it is there at all (which pauses everything if
                    // it isn't).
                    if (e.network) {
                        // On the network the job was given, and it didn't get there: the next
                        // try (Media3's, in a moment) goes by the phone's own route instead.
                        // Which network that is, and whether it may be used (unmetered, for
                        // Wi-Fi only), is still [gate]'s to say.
                        if (route !== http) {
                            route = http
                            note("downloads: the network Android gave the job didn't reach the server; the phone's own route is used")
                        }
                        reachedFailed()
                        throw e
                    }
                    // The file as it is arrived whole, and nothing on this phone decodes
                    // what is in it (an ALAC in an .m4a, say): never marked as a download.
                    // The server's conversion is downloaded in its place.
                    if (e.undecodable && !form.converted) {
                        undecodable += ask.song.id
                        form = convertedForm()
                        note("downloads: the phone has no decoder for one as it is; the server's conversion is downloaded instead")
                        continue
                    }
                    // What arrived was not the song, whole: fetched afresh, a few times, by
                    // itself (the server's next answer may be right). Anything else won't
                    // come right by asking again at once: final.
                    if (e.again && ++tries < VERIFY_TRIES) {
                        note("downloads: one didn't pass its checks, and is fetched again")
                        continue
                    }
                    throw Final(e)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // Anything else (it shouldn't happen): final, in words of the app's own.
                    // Media3 writes a download's final failure to the system log with its
                    // message and causes, and an exception from the network's own code can
                    // name the server.
                    throw Final(DownloadFailure("It couldn't be downloaded (${e.javaClass.simpleName})."))
                }
            }
        }

        override fun cancel() = fetch.cancel()

        override fun remove() {
            // Removed or cancelled: whatever arrived of it goes, and the download with it.
            catalogue.remove(listOf(id))
            val stem = SongFetch.stem(id)
            catalogue.dir.listFiles()?.forEach { if (SongFetch.stemOfPart(it.name) == stem) it.delete() }
        }
    }

    /** Songs whose files, as they are, this phone was found to have no decoder for. */
    private val undecodable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The form a song the phone can't play as it is is downloaded in (see [savedForm]). */
    private fun convertedForm(): SavedForm = settings.value.downloadQuality.let { SavedForm(if (it == 0) 320 else it, "mp3") }

    // ---- Downloads that turn out not to be whole ---------------------------------------------

    /** Songs unmarked and fetched again in this run of the app, because their files didn't
     *  play or didn't pass the check: one that fails again is not fetched a third time. */
    private val demoted = HashSet<String>()

    /**
     * A downloaded file turned out not to be what "Done" promised (it failed when played,
     * for a reason that is the file's; or, marked by an earlier build, it does not pass the
     * check a download must pass now): **it stops being a download**. It is unmarked (and
     * its file goes), shown as on its way again, and fetched again as soon as the server can
     * be reached: until then it waits, as any download does. What wanted it (a list
     * downloaded whole, its own Download) still does.
     *
     * Only that file: `file` and `modified` are the name and modification time of the copy
     * that failed (0: whatever the entry has), and a download that has been replaced since is
     * left alone. And only once in a run of the app: a fresh copy that fails the same way is
     * listed as failed, with the reason, not fetched over and over.
     */
    fun demote(id: String, file: String, modified: Long, why: String) {
        scope.launch(Dispatchers.Main.immediate) {
            ready.await()
            val entry = onDisk {
                val entry = catalogue.entry(id)?.takeIf { it.file == file && (modified == 0L || it.modified == 0L || it.modified == modified) } ?: return@onDisk null
                entry.takeIf { catalogue.remove(listOf(id)).isNotEmpty() }
            } ?: return@launch
            if (::manager.isInitialized && !released) manager.removeDownload(id)
            note("downloads: one marked whole $why: it is no longer a download" + if (id in demoted) "" else ", and is fetched again")
            if (!demoted.add(id)) {
                later { catalogue.failed(Failed(entry.song, "It was downloaded again and still wouldn't play.")) }
                return@launch
            }
            refetch(listOf(entry.song))
        }
    }

    /**
     * Fetches again songs that are meant to be downloaded and are not (a download found
     * wanting). What can't be queued for want of room is **listed as failed for want of
     * room**, not dropped with nothing said.
     */
    private suspend fun refetch(songs: List<Song>) {
        val asked = download(songs)
        if (asked is Asked.NoRoom) {
            note("downloads: ${songs.size} to be fetched again could not be queued: not enough room on the phone")
            later { for (song in songs) if (!catalogue.has(song.id)) catalogue.failed(Failed(song, "Not enough room on the phone to download it again.")) }
        }
    }

    /** What is wrong with a download's file, read through now; null if nothing is, or it
     *  can't be said (the file can't be read, or the look was called off). */
    private fun faultIn(was: Downloaded): Fault? {
        val file = catalogue.wholeFileOf(was.song.id) ?: return null
        val seen = try {
            check.inspect(file, was.suffix) { released }
        } catch (_: IOException) {
            return null
        }
        val form = SavedForm(was.bitrate, was.suffix)
        return SongFetch.faultOf(was.song, form, seen, wholeByLength = !form.converted && was.song.size > 0 && was.song.size == was.bytes)
    }

    private fun wordsFor(fault: Fault): String = when (fault) {
        Fault.NO_DECODER -> "holds what this phone has no decoder for"
        Fault.CUT_SHORT, Fault.WRONG_LENGTH -> "stops before the song's end"
        Fault.DAMAGED -> "is damaged"
        Fault.NOT_A_SONG -> "is not a song"
    }

    /**
     * A download stopped while it was playing, for a reason that says nothing by itself
     * about the file (a decoder that fell over): its file is read through now, off the main
     * thread, and if the file is at fault it is demoted (see [demote]).
     */
    fun verifyNow(id: String) {
        scope.launch(Dispatchers.IO) {
            val was = catalogue.entry(id) ?: return@launch
            val fault = faultIn(was) ?: return@launch
            demote(id, was.file, was.modified, wordsFor(fault))
        }
    }

    /**
     * **Downloads marked whole by an earlier build are checked once, the way a download is
     * checked now** (`Downloaded.checked`): read through to the end, in the background, one
     * at a time. One that passes is noted and never read again; one that doesn't is demoted
     * and fetched again. Only while the server can be asked: until it can, a copy that would
     * be taken away could not be replaced, and is no worse than it was.
     */
    private fun recheckOld() {
        scope.launch(Dispatchers.IO) {
            var looked = 0
            var bad = 0
            for (was in catalogue.entries()) {
                if (was.checked >= Media3AudioCheck.VERSION) continue
                reach.first { !it.offline }
                if (released) return@launch
                if (catalogue.entry(was.song.id)?.file != was.file) continue
                looked++
                val fault = faultIn(was)
                if (released) return@launch
                if (fault == null) {
                    catalogue.passed(was, Media3AudioCheck.VERSION)
                } else {
                    bad++
                    demote(was.song.id, was.file, was.modified, wordsFor(fault))
                }
                // (Gently: something may be playing.)
                delay(RECHECK_PAUSE_MILLIS)
            }
            if (looked > 0) note("downloads: $looked marked whole by an earlier version were read through to the end; $bad were not whole, and are fetched again")
        }
    }

    /** The song as the server's library has it now, for a download whose size or length isn't
     *  what was expected (see [SongFetch]). Null if the server has no such song. */
    private fun asTheServerHasIt(id: String): Song? = try {
        kotlinx.coroutines.runBlocking { api.song(id) }.takeIf { it.id == id }
    } catch (e: org.medianexus.music.net.ApiError) {
        // It is there and says no (the song has gone): nothing newer to go by. Anything else
        // is the server not being reachable to ask.
        if (e is org.medianexus.music.net.ApiError.Server && !e.signInRefused) null else throw IOException("The server couldn't be asked", java.net.ConnectException())
    }

    /** A failure that trying again won't help: passed to Media3 as final. */
    private class Final(cause: DownloadFailure) : RuntimeException(cause.reason, cause)

    /** Told when a download couldn't reach the server (see [SongDownloader]). */
    @Volatile var reachedFailed: () -> Unit = {}

    // Last in the class, and it must stay so: when the engine is made on the main thread this
    // runs at once, inside the constructor, and everything declared above must already have
    // its starting value. (Placed before the fields it uses, it found "not yet open" false,
    // and let Media3 start downloads before the folder had been swept.)
    init {
        onMain {
            if (released) return@onMain
            val index = DefaultDownloadIndex(database, accountKey)
            manager = DownloadManager(context, index, Factory()).apply {
                maxParallelDownloads = AT_ONCE
                minRetryCount = RETRIES
                // Media3 is asked for no network of its own: it would wait for one Android
                // has "validated" as reaching the internet, which a server on a home Wi-Fi
                // with no internet beyond it never is, and it reads "metered" in a way that
                // can miss a change on the same network. Whether downloads may run now is
                // decided here (see [gate]).
                requirements = Requirements(0)
                addListener(Listener())
            }
            follow()
        }
    }

    companion object {
        /** Songs fetched at a time. */
        const val AT_ONCE = 2

        /** Tries at a download that keeps failing to reach the server, after the first. Media3
         *  counts afresh whenever more of it has arrived. */
        const val RETRIES = 5

        /** Room left on the phone after a batch of downloads, so they never fill it to the
         *  brim (Windows' `SPARE`). */
        const val SPARE = 500L * 1024 * 1024

        private const val TICK_MILLIS = 500L

        /** Between one old download read through again and the next. */
        private const val RECHECK_PAUSE_MILLIS = 50L

        /** While songs arrive, the folder is added up again every this many ticks. */
        private const val MEASURE_EVERY_TICKS = 10

        /** How many times a download that fails its checks is fetched, in all, before it is
         *  listed as failed. */
        const val VERIFY_TRIES = 3

        /** "340 MB", "12.4 GB" (Windows' `size_label`). */
        fun sizeLabel(bytes: Long): String {
            val megabytes = bytes / (1024.0 * 1024.0)
            return if (megabytes >= 1024.0) "%.1f GB".format(megabytes / 1024.0) else "%.0f MB".format(megabytes)
        }
    }
}

/**
 * The fetches under way into each downloads folder, across every engine there has been for
 * it in this run of the app: so that a new engine for a folder can wait for an old one's
 * fetches to finish unwinding before it touches the same files (see `Downloads`).
 */
internal object InFlight {
    private val counts = HashMap<String, Int>()
    private val lock = Object()

    fun enter(dir: File) = synchronized(lock) {
        counts[dir.path] = (counts[dir.path] ?: 0) + 1
    }

    /** How many fetches are under way into `dir` now. */
    fun count(dir: File): Int = synchronized(lock) { counts[dir.path] ?: 0 }

    fun leave(dir: File) = synchronized(lock) {
        val left = (counts[dir.path] ?: 1) - 1
        if (left <= 0) counts.remove(dir.path) else counts[dir.path] = left
        lock.notifyAll()
    }

    /** Waits until nothing is being fetched into `dir`. Not for ever: a fetch that never
     *  lets go (it is interrupted, and should within moments) is not waited for past
     *  [MOST_MILLIS]. Returns whether the folder came free. */
    fun awaitNone(dir: File): Boolean = synchronized(lock) {
        val until = System.nanoTime() + MOST_MILLIS * 1_000_000
        while ((counts[dir.path] ?: 0) > 0) {
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) return@synchronized false
            lock.wait(left)
        }
        true
    }

    private const val MOST_MILLIS = 30_000L
}
