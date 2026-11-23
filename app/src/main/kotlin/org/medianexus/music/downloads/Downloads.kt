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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.medianexus.music.Reach
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
enum class Held { NOTHING, WIFI, NETWORK, OFFLINE, SERVER }

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

    val bytes: Long get() = downloaded.values.sumOf { it.bytes }
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
) {
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = MutableStateFlow(DownloadsState())

    /** Where the downloads stand, as it changes; the screens and the car follow this. */
    val state: StateFlow<DownloadsState> = now.asStateFlow()

    private lateinit var manager: DownloadManager
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var released = false

    /** Songs on their way, by id, as they were asked for. */
    private val songs = HashMap<String, Song>()
    /** The songs of this batch (Windows' `batch_total` counts them): what has become of
     *  each is read from the catalogue, not counted from Media3's events, which a song asked
     *  for again, or removed meanwhile, would throw out. */
    private val batch = LinkedHashSet<String>()
    private var ticking: Job? = null
    private var initialized = false

    init {
        onMain {
            if (released) return@onMain
            val index = DefaultDownloadIndex(database, accountKey)
            manager = DownloadManager(context, index, Factory()).apply {
                maxParallelDownloads = AT_ONCE
                minRetryCount = RETRIES
                requirements = requirementsFor(settings.value.downloadWifiOnly)
                addListener(Listener())
            }
            follow()
            ready.complete(Unit)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** The settings and the server: Wi-Fi only, and pausing while nothing can be asked of it. */
    private fun follow() {
        // A song entered in the catalogue (on a download's own thread), removed, or failed:
        // published at once, not when Media3 next says something.
        scope.launch(Dispatchers.Main.immediate) { catalogue.state.collect { publish() } }
        scope.launch(Dispatchers.Main.immediate) {
            settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                manager.requirements = requirementsFor(wifiOnly)
                publish()
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            reach.map { it.offline }.distinctUntilChanged().collect { offline ->
                // Offline, by hand or because the server has gone: what is under way stops
                // (and is picked up again later), rather than failing for want of a server.
                if (offline) manager.pauseDownloads() else manager.resumeDownloads()
                publish()
            }
        }
    }

    private fun requirementsFor(wifiOnly: Boolean) = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    // ---- Asking for downloads --------------------------------------------------------------

    /**
     * Downloads these songs. Ones already downloaded or on their way are passed over. Nothing
     * is started that would not fit, with half a gigabyte to spare (as Windows checks).
     * `list` is the album, playlist or Liked songs they are, downloaded whole: it stays
     * downloaded, and songs it gains are fetched too.
     */
    suspend fun download(wanted: List<Song>, list: DownloadedList? = null): Asked {
        ready.await()
        val quality = settings.value.downloadQuality
        val queued = now.value.active.mapTo(HashSet()) { it.song.id }
        val seen = HashSet<String>()
        val fresh = wanted.filter { it.radioUrl.isEmpty() && it.id.isNotEmpty() && !catalogue.has(it.id) && it.id !in queued && seen.add(it.id) }
        val needed = fresh.sumOf { expectedBytes(it, quality) }
        if (fresh.isNotEmpty()) {
            val free = withContext(Dispatchers.IO) { catalogue.dir.usableSpace }
            if (needed + spare() > free) return Asked.NoRoom(needed, free)
        }
        if (list != null) catalogue.keep(list.copy(at = list.at.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)))
        if (fresh.isEmpty()) return Asked.AlreadyDownloaded
        if (now.value.active.isEmpty()) {
            batch.clear()
        }
        fresh.mapTo(batch) { it.id }
        catalogue.clearFailures(fresh.map { it.id })
        for (song in fresh) {
            val form = savedForm(song, quality)
            val ask = Ask(song.copy(queued = false), form.bitrate, form.suffix)
            songs[song.id] = ask.song
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

    /**
     * The download button on an album, playlist or Liked songs (Windows' `toggle_page_download`):
     * cancels what is under way, downloads what is missing, or, if all of it is downloaded,
     * removes it.
     */
    suspend fun toggle(list: DownloadedList, songs: List<Song>): Toggled {
        ready.await()
        val active = now.value.active.mapTo(HashSet()) { it.song.id }
        val waiting = songs.filter { it.id in active }.map { it.id }
        return when {
            waiting.isNotEmpty() -> {
                catalogue.forget(list.key)
                cancel(waiting)
                Toggled.Cancelled
            }
            songs.isNotEmpty() && songs.all { catalogue.has(it.id) } -> {
                catalogue.forget(list.key)
                remove(songs.map { it.id })
                Toggled.Removed
            }
            else -> Toggled.Asked(download(songs, list.copy(songs = songs.map { it.id })))
        }
    }

    sealed interface Toggled {
        data object Cancelled : Toggled
        data object Removed : Toggled
        data class Asked(val asked: org.medianexus.music.downloads.Asked) : Toggled
    }

    /** Stops these songs' downloads, waiting or part-way. */
    fun cancel(ids: Collection<String>) = onMain {
        if (!::manager.isInitialized) return@onMain
        for (id in ids) {
            manager.removeDownload(id)
            songs.remove(id)
        }
        batch.removeAll(ids.filter { !catalogue.has(it) }.toSet())
        publish()
    }

    /** Stops everything on its way. */
    fun cancelAll() = cancel(now.value.active.map { it.song.id })

    /** Has another go at the songs that couldn't be downloaded. */
    suspend fun retryFailed(onlyNetwork: Boolean = false): Asked? {
        val failed = catalogue.state.value.failed.values.filter { !onlyNetwork || it.network }
        if (failed.isEmpty()) return null
        return download(failed.map { it.song })
    }

    /**
     * Deletes these songs' downloaded copies, and stops any still on their way. A list
     * downloaded whole that one of them belongs to is no longer kept whole (its other songs
     * stay downloaded): otherwise the next look at it would fetch the song straight back.
     */
    fun remove(ids: Collection<String>) = onMain {
        val asked = ids.toSet()
        for (list in catalogue.state.value.collections) if (list.songs.any { it in asked }) catalogue.forget(list.key)
        val gone = ids.mapNotNull { catalogue.entry(it)?.song?.coverArt }
        catalogue.remove(ids)
        if (::manager.isInitialized) for (id in ids) manager.removeDownload(id)
        ids.forEach { songs.remove(it) }
        unpin(gone)
        publish()
    }

    /** Everything downloaded goes, and every list downloaded whole is forgotten. */
    fun removeAll() {
        catalogue.state.value.collections.forEach { catalogue.forget(it.key) }
        val ids = catalogue.state.value.songs.keys + now.value.active.map { it.song.id }
        remove(ids.toList())
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
        val was = catalogue.collection(list.key) ?: return
        val next = list.copy(songs = songs.map { it.id }, at = was.at)
        if (next != was) catalogue.keep(next)
        val missing = songs.filter { !catalogue.has(it.id) }
        if (missing.isNotEmpty() && !reach.value.offline) download(missing)
    }

    // ---- Covers -----------------------------------------------------------------------------

    private fun unpin(coverIds: List<String>) {
        val still = HashSet<String>()
        catalogue.state.value.songs.values.mapTo(still) { it.song.coverArt }
        catalogue.state.value.collections.mapTo(still) { it.coverArt }
        val gone = coverIds.filter { it.isNotEmpty() && it !in still }.distinct()
        if (gone.isNotEmpty()) scope.launch(Dispatchers.IO) { gone.forEach { covers.unpin(it) } }
    }

    // ---- What Media3 says -------------------------------------------------------------------

    private inner class Listener : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            // What was waiting when the app last stopped: its songs, from what they were asked
            // for with. And what a download cut short left in the folder (part files with no
            // download waiting) is cleared away.
            for (download in downloadManager.currentDownloads) ask(download)?.let { songs[it.song.id] = it.song }
            if (batch.isEmpty()) batch += songs.keys
            val busy = songs.keys.toSet()
            scope.launch(Dispatchers.IO) { catalogue.sweep(busy) }
            initialized = true
            publish()
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val id = download.request.id
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    songs.remove(id)
                    catalogue.entry(id)?.song?.coverArt?.takeIf { it.isNotEmpty() }?.let { cover ->
                        // Have the artwork kept too, for when the server can't be reached.
                        scope.launch { covers.pin(cover) }
                    }
                }
                Download.STATE_FAILED -> {
                    val song = songs.remove(id) ?: ask(download)?.song
                    if (song != null) {
                        val failure = finalException?.let { generateSequence<Throwable>(it) { e -> e.cause }.firstNotNullOfOrNull { e -> e as? DownloadFailure } }
                        catalogue.failed(Failed(song, failure?.reason ?: "It couldn't be downloaded.", failure?.network ?: true))
                        note("downloads: one failed (${if (failure?.network == true) "the server out of reach" else "see the Downloads list"})")
                    }
                }
                else -> ask(download)?.let { songs[id] = it.song }
            }
            publish()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            songs.remove(download.request.id)
            publish()
        }

        override fun onIdle(downloadManager: DownloadManager) = publish()

        override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) = publish()

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
            val song = songs[download.request.id] ?: ask(download)?.song ?: return@mapNotNull null
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
        val held = when {
            active.isEmpty() -> Held.NOTHING
            reach.value.manual -> Held.OFFLINE
            reach.value.unreachable -> Held.SERVER
            manager.notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> Held.WIFI
            manager.notMetRequirements and Requirements.NETWORK != 0 -> Held.NETWORK
            else -> Held.NOTHING
        }
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
            while (true) {
                delay(TICK_MILLIS)
                publish()
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
        private val fetch = SongFetch(http, catalogue)

        override fun download(progressListener: Downloader.ProgressListener?) {
            val ask = ask ?: throw IllegalStateException("A download with nothing to say what it is")
            val form = SavedForm(ask.bitrate, ask.suffix)
            try {
                fetch.fetch(ask.song, form, api.streamUrl(ask.song.id, form.bitrate)) { got, total ->
                    progressListener?.onProgress(total, got, if (total > 0) 100f * got / total else C.PERCENTAGE_UNSET.toFloat())
                }
            } catch (e: Cancelled) {
                // Stopped by Media3 (cancelled, the network went, offline): not a failure.
                // (Only then: Media3 takes an interruption it didn't ask for as success.)
                throw InterruptedException("Stopped")
            } catch (e: DownloadFailure) {
                // The server can't be reached: Media3 tries again a few times, and meanwhile
                // the server is asked whether it is there at all (which pauses everything if
                // it isn't). Anything else won't come right by asking again at once: final.
                if (e.network) {
                    reachedFailed()
                    throw e
                }
                throw Final(e)
            }
            main.post { publish() }
        }

        override fun cancel() = fetch.cancel()

        override fun remove() {
            // Removed or cancelled: whatever arrived of it goes, and the download with it.
            catalogue.remove(listOf(id))
            val stem = SongFetch.stem(id)
            catalogue.dir.listFiles()?.forEach { if (SongFetch.stemOfPart(it.name) == stem) it.delete() }
        }
    }

    /** A failure that trying again won't help: passed to Media3 as final. */
    private class Final(cause: DownloadFailure) : RuntimeException(cause.reason, cause)

    /** Told when a download couldn't reach the server (see [SongDownloader]). */
    @Volatile var reachedFailed: () -> Unit = {}

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

        /** "340 MB", "12.4 GB" (Windows' `size_label`). */
        fun sizeLabel(bytes: Long): String {
            val megabytes = bytes / (1024.0 * 1024.0)
            return if (megabytes >= 1024.0) "%.1f GB".format(megabytes / 1024.0) else "%.0f MB".format(megabytes)
        }
    }
}
