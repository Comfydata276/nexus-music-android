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
import kotlinx.coroutines.flow.update
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

    /** A download that needs a fresh copy (see [Downloaded.stale]) is not Done: it is shown
     *  as on its way again, or with why its fresh copy couldn't be had. */
    fun stateOf(id: String): SongState {
        val entry = downloaded[id]
        if (entry != null && !entry.stale) return SongState.Done
        return activeById[id]?.state ?: failedById[id]?.let { SongState.Failed(it.reason) }
            ?: if (entry != null) SongState.Failed(NEEDS_A_FRESH_COPY) else SongState.None
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

    companion object {
        /** A download that failed when it was played, with nothing fetching it again now. */
        const val NEEDS_A_FRESH_COPY = "It wouldn't play, and needs downloading again."
    }
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
    private val check: AudioCheck = Media3AudioCheck.forPhone(context),
    /** Where the catalogue is written from the main thread's side, and the start-up sweep of
     *  the folder runs: one thing at a time, in the order asked, never on the main thread.
     *  (A test's own, to hold it.) */
    private val disk: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /** The unmetered network transfers are bound to while Wi-Fi only is on (see
     *  [org.medianexus.music.net.UnmeteredNetwork]). Given none (a test of something else),
     *  the default route while the system reports it unmetered. */
    unmeteredRoutes: org.medianexus.music.net.UnmeteredRoutes? = null,
) {
    private val unmetered: org.medianexus.music.net.UnmeteredRoutes =
        unmeteredRoutes ?: org.medianexus.music.net.DefaultRouteWhileUnmetered(network, http, scope)

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

    /** Told when Media3's manager next has nothing under way (see [removeAll]). */
    private val whenIdle = ArrayList<kotlinx.coroutines.CompletableDeferred<Unit>>()

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
        // Wi-Fi only switched on or off, and the unmetered network coming, going, turning
        // metered or giving way to another: at once, and first of all every request under
        // way by a way that may no longer be used is cancelled (see [stopStrays]).
        scope.launch(Dispatchers.Main.immediate) {
            settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect {
                stopStrays()
                gate()
            }
        }
        scope.launch(Dispatchers.Main.immediate) { network.collect { gate() } }
        scope.launch(Dispatchers.Main.immediate) {
            unmetered.now.collect {
                stopStrays()
                gate()
            }
        }
        scope.launch(Dispatchers.Main.immediate) { reach.map { it.offline }.distinctUntilChanged().collect { gate() } }
    }

    /**
     * Whether downloads may run now, decided here and nowhere else, at every change of
     * anything it rests on: not offline by hand; a network to use, **on which our own server
     * answers** (what is under way stops and is picked up again later, rather than failing
     * for want of a server); and not stopped by Android. What is under way is stopped at
     * once when it turns to no.
     *
     * **The network to use, and where the server is asked.** With Wi-Fi only off: any, as
     * the system reports the phone's network (never whether Android has validated it as
     * reaching the internet), and whether the server answers is what the app as a whole
     * knows of it (`reach`, asked on the phone's own route, which is the one these transfers
     * use). **With Wi-Fi only on: an unmetered network the system has given the app for the
     * purpose** ([unmetered]), which every transfer is bound to (see `SongDownloader`), **and
     * whether the server answers is asked on that network** ([askOn]): the phone's own route
     * can be mobile data while the Wi-Fi is up, and what the server says there says nothing
     * of the Wi-Fi. (It used to go by the phone's own route in both cases: downloads waited
     * for a server that the Wi-Fi reached perfectly well, or were thrown, song after song,
     * at a Wi-Fi the server wasn't on.)
     */
    private fun gate() {
        if (!::manager.isInitialized || released) return
        val route = if (settings.value.downloadWifiOnly) unmetered.now.value else null
        if (route !== askedOn) askOn(route)
        if (opening || holdsFor() != Held.NOTHING) manager.pauseDownloads() else manager.resumeDownloads()
        publish()
    }

    /** Why downloads may not run now ([Held.NOTHING]: they may). */
    private fun holdsFor(): Held {
        val net = network.value
        val wifiOnly = settings.value.downloadWifiOnly
        val route = if (wifiOnly) unmetered.now.value else null
        return when {
            reach.value.manual -> Held.OFFLINE
            wifiOnly && route == null -> Held.WIFI
            // (Not yet answered on that network is not a yes.)
            wifiOnly && !(askedOn === route && answersThere == true) -> Held.SERVER
            !wifiOnly && reach.value.unreachable -> Held.SERVER
            !wifiOnly && !net.connected -> Held.NETWORK
            stopped -> Held.ANDROID
            else -> Held.NOTHING
        }
    }

    /** The unmetered network the server is being asked on (or was), and what it said there
     *  (null: it has not answered yet). */
    private var askedOn: org.medianexus.music.net.BoundRoute? = null
    private var answersThere: Boolean? = null
    private var askingThere: Job? = null

    /**
     * Asks our own server whether it is there, **on the unmetered network transfers are bound
     * to** (`route`; null: there is none, and nothing is asked). Until it has said yes there,
     * nothing is transferred on that network; while it says no it is asked again, less often
     * the longer it has been (as the app's own watch on the server does), for as long as that
     * network is the one in use. This is what a Wi-Fi with a sign-in page, or one the server
     * isn't on, comes to: one small question now and then, and no song thrown at it.
     */
    private fun askOn(route: org.medianexus.music.net.BoundRoute?) {
        askingThere?.cancel()
        askedOn = route
        answersThere = null
        if (route == null) return
        askingThere = scope.launch(Dispatchers.Main.immediate) {
            val began = System.nanoTime()
            while (true) {
                val there = try {
                    api.over(route.client).ping()
                    true
                } catch (e: org.medianexus.music.net.ApiError) {
                    // (A sign-in it won't take is still the server answering: what follows
                    // from that is the app's own watch's to find, on its own route.)
                    e.signInRefused
                }
                if (askedOn !== route || released) return@launch
                if (answersThere != there) {
                    answersThere = there
                    if (!there) note("downloads: the server didn't answer on the unmetered network; asked again there in a while (Wi-Fi only: nothing else is used)")
                    gate()
                }
                if (there) return@launch
                delay(org.medianexus.music.ServerWatch.againAfter((System.nanoTime() - began) / 1_000_000))
            }
        }
    }

    /** A transfer bound to `route` couldn't reach the server on it: asked again there before
     *  anything more is sent that way. */
    private fun lostOn(route: org.medianexus.music.net.BoundRoute) = onMain {
        if (released || askedOn !== route || answersThere != true) return@onMain
        askOn(route)
        gate()
    }

    /** A request under way, the way it went out (the unmetered network it is bound to; null:
     *  the phone's own route, or the job's), and how to cancel it. */
    private class Out(val route: org.medianexus.music.net.BoundRoute?, val stop: () -> Unit)

    private val out: MutableSet<Out> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether a request that went out by `route` may still take bytes: bound to the
     *  unmetered network, while that very route is still the one the system gives (the same
     *  network gone and come back, or turned metered and back, is another route); unbound,
     *  while Wi-Fi only is off. */
    private fun mayStill(route: org.medianexus.music.net.BoundRoute?): Boolean =
        if (route != null) unmetered.now.value === route else !settings.value.downloadWifiOnly

    /**
     * **Every request under way by a way that may no longer be used is cancelled, now**: a
     * song's or a cover's, on the thread the change is heard on. A transfer used to find out
     * at its next read, which on a link that has stalled is no time soon, and a cover's was
     * never told at all.
     */
    private fun stopStrays() {
        for (request in out) if (!mayStill(request.route)) request.stop()
    }

    /** Runs `request` as one going out by `route`, cancelled by `stop` the moment that way
     *  may no longer be used (also if that is already so). */
    private inline fun <T> going(route: org.medianexus.music.net.BoundRoute?, noinline stop: () -> Unit, request: () -> T): T {
        val under = Out(route, stop)
        out += under
        try {
            if (!mayStill(route)) stop()
            return request()
        } finally {
            out -= under
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

    /** The network Android gave the job (see [useNetwork]), to tell when it is not the one
     *  transfers are bound to. */
    @Volatile private var jobNetwork: android.net.Network? = null
    private var differingSaid: Pair<Any, Any>? = null

    /**
     * **With Wi-Fi only on, the unmetered network the app asked for governs, not the job's.**
     * Transfers are bound to the network `requestNetwork` gave ([unmetered]): the app is told
     * the instant that one goes or turns metered, and holds it only while something waits.
     * The network Android gave the job is Android's condition for running the job, nothing
     * more; when it goes Android stops the job, and the transfers with it. The two are
     * usually the same network. When they are not (two unmetered networks up at once), that
     * is written down, once for each pair, so that it is never so without our knowing.
     */
    private fun noteIfTheJobIsElsewhere(bound: org.medianexus.music.net.BoundRoute) {
        val job = jobNetwork ?: return
        if (job == bound.network) return
        val pair: Pair<Any, Any> = job to bound.network
        if (differingSaid == pair) return
        differingSaid = pair
        note("downloads: Android gave the job one unmetered network and the app another; transfers are bound to the app's, which is the one it is told about as it changes")
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
        jobNetwork = given
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
        // (A download that needs a fresh copy is fetched like one that isn't there: what is
        // on the phone stays until the new one has taken its place.)
        val fresh = proper.filter { (catalogue.entry(it.id)?.stale ?: true) && it.id !in asks }
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
            // (As the server converts it, if this phone is known not to play the file itself.)
            val form = savedForm(song, quality).let { if (!it.converted && catalogue.convertsFor(song)) convertedForm() else it }
            val ask = Ask(song.copy(queued = false), form.bitrate, form.suffix)
            asks[song.id] = ask
            val request = DownloadRequest.Builder(song.id, Uri.parse(songUri(song.id)))
                .setData(json.encodeToString(Ask.serializer(), ask).toByteArray())
                .build()
            manager.addDownload(request)
        }
        publish()
        // Covers for the lists, kept where the system won't clear them.
        list?.coverArt?.takeIf { it.isNotEmpty() }?.let { scope.launch { pinCover(it) } }
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
     * or Liked songs downloaded whole: `key` is its list's, `shown` the songs the page
     * shows, which are the ones that have arrived), by its button there. **The action covers
     * every member of the group, arrived or not** ([membersOf]): removed, it is all removed
     * (what has arrived is deleted, what is on its way is stopped); with `cancelOnly`, which
     * is what the button offers while what it shows is itself on its way, everything on its
     * way is stopped and what has arrived stays. The same path as a list's own button, so
     * the same rule about who else wants each song.
     *
     * (The page used to pass the songs it showed, and those were all that was acted on: an
     * album removed with one of its ten songs arrived lost that one, and the other nine
     * went on arriving, for a list that had just been forgotten.)
     */
    suspend fun removeGroup(key: String, shown: List<String>, cancelOnly: Boolean = false): Toggled = asking.withLock {
        ready.await()
        onDisk {}
        val members = membersOf(key, shown)
        if (cancelOnly) withdraw(key, members.filter { it in asks }, files = false) else withdraw(key, members, files = true)
    }

    /**
     * Every song of the group `key`, arrived or not: a list downloaded whole is its songs as
     * recorded; an album that is no such list is every song of that album that is
     * downloaded or on its way; and whatever the page showed of it besides.
     */
    private fun membersOf(key: String, shown: List<String>): List<String> {
        val out = LinkedHashSet<String>(shown)
        catalogue.collection(key)?.let { out += it.songs }
        if (key.startsWith("album:")) {
            val album = key.removePrefix("album:")
            if (album.isNotEmpty()) {
                catalogue.state.value.songs.values.filter { it.song.albumId == album }.mapTo(out) { it.song.id }
                asks.values.filter { it.song.albumId == album }.mapTo(out) { it.song.id }
            }
        }
        return out.toList()
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
     * **The wish is withdrawn on the disk before anything else is done, and if that can't be
     * written nothing else is done**: no file is deleted, nothing on its way is stopped, and
     * the answer is [Toggled.Failed], which the person is told. (It was withdrawn in memory
     * whatever became of the write: "Removed" was said, and the list was back, fetching its
     * songs again, the next time the app started.)
     *
     * Decided on the disk's own thread, in turn with every other change, under [asking].
     */
    private suspend fun withdraw(key: String, ids: List<String>, files: Boolean): Toggled {
        val others = onDisk {
            val gone = if (catalogue.collection(key) != null) forgetList(key) else catalogue.unsingle(ids)
            if (gone) catalogue.wantedBesides(key) else null
        } ?: return Toggled.Failed
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

        /** Nothing was done: the phone would not record that it is no longer wanted. */
        data object Failed : Toggled
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
        // (The wish first, on the disk: only then are they stopped. See [withdraw]. In its
        // turn with every other asking, so that what is asked next finds it done.)
        scope.launch(Dispatchers.Main.immediate) {
            asking.withLock {
                ready.await()
                if (onDisk { unwanted(asked) }) stop(asked)
            }
        }
    }

    /** These songs are no longer wanted, by themselves or by any list downloaded whole that
     *  holds one of them: written, or (false) not, and then nothing is to be done to them.
     *  (Off the main thread.) */
    private fun unwanted(ids: Set<String>): Boolean {
        for (list in catalogue.state.value.collections) if (list.songs.any { it in ids } && !forgetList(list.key)) return false
        return catalogue.unsingle(ids)
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
        val failed = catalogue.state.value.failed.values.filter { !onlyNetwork || it.network }.map { it.song }
        // (And, asked for by hand, the downloads that need a fresh copy and have none coming.)
        val stale = if (onlyNetwork) emptyList() else catalogue.state.value.songs.values.filter { it.stale }.map { it.song }
        val songs = (failed + stale).distinctBy { it.id }
        if (songs.isEmpty()) return null
        return download(songs)
    }

    /**
     * Deletes these songs' downloaded copies, by hand, and stops any still on their way. A
     * list downloaded whole that one of them belongs to is no longer kept whole (its other
     * songs stay downloaded): otherwise the next look at it would fetch the song straight
     * back. (Removing a *list* is [toggle]'s, and changes no other list.)
     */
    fun remove(ids: Collection<String>) = onMain {
        val asked = ids.toSet()
        scope.launch(Dispatchers.Main.immediate) {
            asking.withLock {
                ready.await()
                if (onDisk { unwanted(asked) }) drop(asked)
            }
        }
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

    /**
     * Everything downloaded goes, everything on its way is stopped, every list downloaded
     * whole is forgotten, **and everything else in the downloads' folder goes with them**:
     * parts of downloads cut short, their records, the covers kept with the downloads,
     * anything left lying (`Catalogue.clear`). What the person is told the downloads take
     * is what this gives back. Returns whether it could be done.
     *
     * No download is accepted while it is under way, and it waits for Media3 to finish
     * letting go of what it was fetching first (a fetch being stopped can still be writing).
     */
    suspend fun removeAll(): Boolean = asking.withLock {
        ready.await()
        onDisk {}
        if (!::manager.isInitialized || released) return@withLock false
        // No cover is kept from here on, the requests for covers under way are cancelled,
        // and the ones already being written are waited for: a cover still arriving used to
        // land in the folder after it had been emptied.
        clearing = true
        try {
            for (request in out) request.stop()
            for (download in manager.currentDownloads) manager.removeDownload(download.request.id)
            for (id in asks.keys.toList()) manager.removeDownload(id)
            asks.clear()
            batch.clear()
            publish()
            if (!manager.isIdle) {
                val idle = kotlinx.coroutines.CompletableDeferred<Unit>()
                whenIdle += idle
                kotlinx.coroutines.withTimeoutOrNull(LET_GO_MILLIS) { idle.await() }
                whenIdle -= idle
            }
            kotlinx.coroutines.withTimeoutOrNull(LET_GO_MILLIS) { pinning.first { it == 0 } }
            // (Whether everything went is asked of the folder afterwards: see Catalogue.clear.)
            val done = onDisk { catalogue.clear() }
            demoted.clear()
            publish()
            measure()
            done
        } finally {
            clearing = false
        }
    }

    /** "Remove all" is under way: nothing is kept with the downloads meanwhile. */
    @Volatile private var clearing = false

    /** Covers being kept with the downloads at this moment (see [pinCover]). */
    private val pinning = MutableStateFlow(0)

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

    /**
     * Keeps a cover with the downloads. With Wi-Fi only on, a cover that isn't on the phone
     * yet is fetched **by the unmetered network, as the songs are**, and not at all if there
     * is none just now (it is asked for again as later songs of the album arrive): the
     * covers of a hundred downloaded songs were two megabytes over mobile data, by the
     * phone's default route, with every song of them held to the Wi-Fi.
     */
    private suspend fun pinCover(id: String) {
        // (Counted before it is asked whether a "Remove all" is under way, so that one which
        // begins now either sees this and waits for it, or is seen here.)
        pinning.update { it + 1 }
        try {
            if (!clearing) pin(id)
        } finally {
            pinning.update { it - 1 }
        }
    }

    private suspend fun pin(id: String) {
        covers.pin(id) {
            if (clearing) return@pin null
            val wifiOnly = settings.value.downloadWifiOnly
            val bound = if (wifiOnly) unmetered.now.value ?: return@pin null else null
            // (No cover is asked of a server known to be out of reach.)
            if (!wifiOnly && reach.value.offline) return@pin null
            withContext(Dispatchers.IO) {
                val call = (bound?.client ?: route).newCall(okhttp3.Request.Builder().url(api.coverUrl(id, CoverStore.SIZE)).build())
                try {
                    // Cancelled, as a song's transfer is, the moment its way out may no
                    // longer be used.
                    going(bound, call::cancel) {
                        call.execute().use { answer ->
                            val type = answer.header("Content-Type").orEmpty().lowercase()
                            if (!answer.isSuccessful || !type.startsWith("image/")) return@use null
                            val source = answer.body.source()
                            if (source.request(MOST_COVER_BYTES + 1)) null else source.buffer.readByteArray()
                        }
                    }
                } catch (_: IOException) {
                    null
                }
            }
        }
    }

    /** A list is no longer downloaded whole: forgotten, on the disk (false: it couldn't be
     *  written, and the list is still wanted), and its own cover (kept with the downloads
     *  when it was asked for) let go if nothing downloaded still uses it. (Off the main
     *  thread.) */
    private fun forgetList(key: String): Boolean {
        val cover = catalogue.collection(key)?.coverArt
        if (!catalogue.forget(key)) return false
        if (!cover.isNullOrEmpty()) unpin(listOf(cover))
        return true
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
                    }
                }
            }
            // Downloads whose files were found, at sign-in, to be missing or not the length
            // that was recorded: not downloads any more, and fetched again.
            // (Queued, or, if there is no room to queue them, listed as failed for want of
            // room: never dropped while this says they are being fetched.)
            val again = catalogue.wanting.map { it.song }.filter { !catalogue.has(it.id) }
            if (again.isNotEmpty()) note("downloads: ${again.size} marked whole were not (their files are missing or not the length recorded)")
            // And the downloads still waiting for a fresh copy, with none coming and none
            // already tried and failed (those wait to be asked for by hand).
            val snapshot = catalogue.state.value
            val stale = snapshot.songs.values.filter { it.stale && it.song.id !in asks && it.song.id !in snapshot.failed }.map { it.song }
            if (again.isNotEmpty() || stale.isNotEmpty()) scope.launch(Dispatchers.Main.immediate) { refetch(again + stale) }
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val id = download.request.id
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    asks.remove(id)
                    catalogue.entry(id)?.song?.coverArt?.takeIf { it.isNotEmpty() }?.let { cover ->
                        // Have the artwork kept too, for when the server can't be reached.
                        scope.launch { pinCover(cover) }
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
            whenIdle.toList().forEach { it.complete(Unit) }
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
            batchDone = batch.count { snapshot.songs[it]?.stale == false },
            batchFailed = batch.count { it in snapshot.failed },
            settled = initialized,
            occupied = occupied,
        )
        now.value = next
        // Offline by hand, nothing is to be done until it is switched off.
        keeper(active.isNotEmpty() && !reach.value.manual, settings.value.downloadWifiOnly)
        // An unmetered network is asked of the system only while something waits for one.
        unmetered.want(active.isNotEmpty() && settings.value.downloadWifiOnly && !reach.value.manual && !released)
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

    private fun stopEverything() {
        for (request in out) request.stop()
    }

    /** The catalogue changed (a song arrived, was removed): published. */
    fun refresh() = onMain { publish() }

    /** The account has gone: its downloads stop here (they carry on when it is back). */
    fun release() = onMain {
        released = true
        askingThere?.cancel()
        stopEverything()
        unmetered.want(false)
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
        /** The attempt under way, and whether Media3 has called this download off. */
        @Volatile private var fetch: SongFetch? = null
        private val called = java.util.concurrent.CountDownLatch(1)
        private val cancelled get() = called.count == 0L

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

        /**
         * The way one attempt goes out, decided **for each attempt, as it begins** (a
         * downloader that had been running kept the client it was made with, whatever had
         * changed since).
         *
         * With Wi-Fi only on: the unmetered network the system has given, and nothing else.
         * The attempt is bound to it, and stops when it is no longer that network (gone, or
         * turned metered), or Wi-Fi only was switched on after an unbound attempt began.
         * With Wi-Fi only off: the phone's own route, or the network Android gave the job.
         */
        private fun attempt(): SongFetch {
            val wifiOnly = settings.value.downloadWifiOnly
            val bound = if (wifiOnly) unmetered.now.value ?: throw RouteGone() else null
            if (bound != null) noteIfTheJobIsElsewhere(bound)
            val client = bound?.client ?: route
            return SongFetch(
                client, catalogue, check,
                // (The one other thing a download may ask of the server, by the same way.)
                refresh = { id -> asTheServerHasIt(client, id) },
                mayRun = { mayStill(bound) },
                // Half of what is always kept spare: the estimate made when this was asked
                // for may be out, and other things fill a phone too.
                room = { catalogue.dir.usableSpace >= spare() / 2 },
            ).also { it.bound = bound }
        }

        private fun fetchUntilSettled(ask: Ask, asked: SavedForm, progressListener: Downloader.ProgressListener?) {
            var tries = 0
            // A file this phone was found to have no decoder for is not fetched as it is
            // again: the server's conversion, as streaming asks for one it can't play.
            var form = if (!asked.converted && catalogue.convertsFor(ask.song)) convertedForm() else asked
            // What the last fetch brought, if it was a song with something wrong with it.
            var before: Flawed? = null
            while (true) {
                var now: SongFetch? = null
                try {
                    if (cancelled) throw Cancelled()
                    now = attempt()
                    fetch = now
                    if (cancelled) throw Cancelled()
                    val entry = going(now.bound as? org.medianexus.music.net.BoundRoute, now::routeGone) {
                        now.fetch(ask.song, form, api.streamUrl(ask.song.id, form.bitrate), before) { got, total ->
                            progressListener?.onProgress(total, got, if (total > 0) 100f * got / total else C.PERCENTAGE_UNSET.toFloat())
                        }
                    }
                    // Kept knowing something is wrong with it: the same bytes came twice.
                    if (entry.flaw.isNotEmpty() && before != null) {
                        note("downloads: one came twice the same, with the same flaw (${wordsFor(before.fault)}): the server's own copy has it. It is kept as it is, and marked downloaded")
                    }
                    break
                } catch (e: Cancelled) {
                    // Stopped by Media3 (cancelled, the network went, offline): not a failure.
                    // (Only then: Media3 takes an interruption it didn't ask for as success.)
                    throw InterruptedException("Stopped")
                } catch (e: RouteGone) {
                    // The network this was bound to may no longer be used, or there is none
                    // to bind to. If the system has already given another (one unmetered
                    // network taking over from another), it is carried on with by that one,
                    // now. If not, [gate] has seen the same and is stopping everything, by
                    // Media3: waited for, so this ends as "stopped", which it is. If that
                    // doesn't come (the network was back at once), it is one more try.
                    main.post { gate() }
                    if (!cancelled && settings.value.downloadWifiOnly && unmetered.now.value.let { it != null && it !== now?.bound }) continue
                    if (called.await(STOP_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) throw InterruptedException("Stopped")
                    throw DownloadFailure("Waiting for Wi-Fi.", network = true)
                } catch (e: DownloadFailure) {
                    // The server can't be reached: Media3 tries again a few times, and meanwhile
                    // the server is asked whether it is there at all (which pauses everything if
                    // it isn't).
                    if (e.network) {
                        val bound = now?.bound as? org.medianexus.music.net.BoundRoute
                        if (bound != null) {
                            // Bound to the unmetered network, and it didn't get there. Wi-Fi
                            // only: no other network is tried. The server is asked again on
                            // that one before anything more is sent by it (see [lostOn]).
                            lostOn(bound)
                        } else if (route !== http) {
                            // On the network the job was given, and it didn't get there: the
                            // next try goes by the phone's own route instead (each attempt
                            // takes the route as it then is).
                            route = http
                            note("downloads: the network Android gave the job didn't reach the server; the phone's own route is used")
                        }
                        // (The app's own watch on the server is told only of its own route.)
                        if (bound == null) reachedFailed()
                        throw e
                    }
                    // The file as it is arrived whole, and nothing on this phone decodes
                    // what is in it (an ALAC in an .m4a, say): never marked as a download.
                    // The server's conversion is downloaded in its place.
                    if (e.undecodable && !form.converted) {
                        catalogue.mustConvert(ask.song)
                        form = convertedForm()
                        before = null
                        note("downloads: the phone can't decode one as it is; the server's conversion is downloaded instead")
                        continue
                    }
                    // What arrived was not the song, whole: fetched afresh, a few times, by
                    // itself (the server's next answer may be right). Anything else won't
                    // come right by asking again at once: final.
                    if (e.again && ++tries < VERIFY_TRIES) {
                        before = e.flawed
                        note("downloads: one didn't pass its checks, and is fetched again")
                        continue
                    }
                    if (e.reason == SongFetch.NO_ROOM) note("downloads: one was stopped: the phone is nearly out of space")
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

        override fun cancel() {
            called.countDown()
            fetch?.cancel()
        }

        override fun remove() {
            // Removed or cancelled: whatever had arrived of it goes. (Not a copy already
            // downloaded: a download is unmarked and deleted only by [drop], which is not
            // what cancelling the fetch of a fresh copy of one asks for.)
            val stem = SongFetch.stem(id)
            catalogue.dir.listFiles()?.forEach { if (SongFetch.stemOfPart(it.name) == stem) it.delete() }
        }
    }

    /** The form a song the phone can't play as it is is downloaded in (see [savedForm]). */
    private fun convertedForm(): SavedForm = settings.value.downloadQuality.let { SavedForm(if (it == 0) 320 else it, "mp3") }

    private fun wordsFor(fault: Fault): String = when (fault) {
        Fault.NO_DECODER -> "it holds what this phone can't decode"
        Fault.CUT_SHORT -> "it stops before the song's end"
        Fault.WRONG_LENGTH -> "it does not run as long as the library says the song does"
        Fault.DAMAGED -> "it can't be read through to its end"
        Fault.NOT_A_SONG -> "it is not a song"
    }

    // ---- Downloads that turn out not to play --------------------------------------------------

    /** Songs fetched again in this run of the app because their downloads didn't play: one
     *  whose fresh copy fails too is not fetched a third time. */
    private val demoted = HashSet<String>()

    /**
     * A downloaded file failed when it was played, for a reason that is the file's own (it
     * couldn't be read, or parsed, or holds what this phone can't decode): **it needs a fresh
     * copy**, and is fetched again as soon as the server can be reached. **Nothing is deleted
     * meanwhile.** The copy that failed stays a download, on the phone, played with no server
     * as far as it plays, and shown as on its way again; the new copy takes its place only
     * once it has arrived and been verified. (It used to be unmarked and deleted on the spot,
     * so a song that stumbled once in a dead spot was gone until there was a signal again.)
     *
     * Only that file: `file` and `modified` are the name and modification time of the copy
     * that failed (0: whatever the entry has), and a download that has been replaced since is
     * left alone. `beyondThePhone`: what failed was the phone's decoding of the file as it
     * is; that is remembered, on the disk, and the fresh copy is the server's conversion.
     * And only once in a run of the app: a fresh copy that fails too is listed as failed,
     * with the reason, not fetched over and over.
     *
     * Returns once that is on the disk: whether the download now stands as needing a fresh
     * copy (so that the player may turn to the server's).
     */
    suspend fun demote(id: String, file: String, modified: Long, why: String, beyondThePhone: Boolean = false): Boolean = withContext(Dispatchers.Main.immediate) {
        ready.await()
        var already = false
        val entry = onDisk {
            val was = catalogue.entry(id)?.takeIf { it.file == file && (modified == 0L || it.modified == 0L || it.modified == modified) } ?: return@onDisk null
            already = was.stale
            if (beyondThePhone && was.bitrate == 0) catalogue.mustConvert(was.song)
            catalogue.stale(was)
        } ?: return@withContext false
        // (That copy failing again, with its fresh one still to come: nothing more to do.)
        if (already) return@withContext true
        note("downloads: one marked whole $why: it is kept, and a fresh copy is fetched to take its place")
        if (!demoted.add(id)) {
            later { catalogue.failed(Failed(entry.song, "It was downloaded again and still wouldn't play.")) }
            return@withContext true
        }
        refetch(listOf(entry.song))
        true
    }

    /**
     * The player found a download's file not to be the length that was recorded when it was
     * verified, and did not open it: **the catalogue agrees, at once**, as it would at the
     * start of the next run. It is no longer a download (unmarked, what is left of it gone),
     * and is fetched again. Asked again on the disk's own thread: a file that is being
     * replaced by its fresh copy at that instant is the right length by the time it is asked.
     */
    fun notWhole(id: String) {
        scope.launch(Dispatchers.Main.immediate) {
            ready.await()
            val was = onDisk { catalogue.dropIfNotWhole(id) } ?: return@launch
            if (::manager.isInitialized && !released) manager.removeDownload(id)
            note("downloads: one marked whole was not (its file is not the length recorded): it is no longer a download")
            refetch(listOf(was.song))
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
            note("downloads: ${songs.size} to be fetched again could not be queued: not enough room on the phone. They are listed as failed")
            later { for (song in songs) if (catalogue.entry(song.id)?.stale != false) catalogue.failed(Failed(song, "Not enough room on the phone to download it again.")) }
        } else {
            note("downloads: ${songs.size} are fetched again")
        }
    }

    /** The song as the server's library has it now, for a download whose size or length isn't
     *  what was expected (see [SongFetch]). Null if the server has no such song. */
    private fun asTheServerHasIt(by: OkHttpClient, id: String): Song? = try {
        kotlinx.coroutines.runBlocking { (if (by === http) api else api.over(by)).song(id) }.takeIf { it.id == id }
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

        /** A cover larger than this is not one. */
        private const val MOST_COVER_BYTES = 16L * 1024 * 1024

        /** How long Remove all waits for Media3 to let go of what it was fetching. */
        private const val LET_GO_MILLIS = 10_000L

        /** How long a transfer whose network has gone waits to be stopped by Media3 (which
         *  [gate] is seeing to) before it gives the try up as failed. */
        private const val STOP_WAIT_MILLIS = 5_000L

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
