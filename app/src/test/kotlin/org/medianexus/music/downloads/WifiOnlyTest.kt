package org.medianexus.music.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.Reach
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.BoundRoute
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.net.UnmeteredRoutes
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * "Download on Wi-Fi only", for the whole life of a transfer (offline hardening 3, item 3):
 * Media3's download manager under the app's own engine, and a server whose answers stop
 * where the test says (`GatedServer`), so that a transfer can be caught with its connection
 * open and **nothing arriving on it**. That is the case the second round left: a transfer
 * was asked "may you still run?" before each read, so one that was not reading (a stalled
 * link) was not told, and a cover's was never asked at all.
 *
 * Nothing here waits on time: every client has a read time-out of ten minutes, so that
 * nothing but the engine cancelling a request can end it while the server holds it.
 */
@RunWith(RobolectricTestRunner::class)
class WifiOnlyTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = GatedServer()
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var catalogue: Catalogue
    private lateinit var covers: CoverStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = MutableStateFlow(Settings(downloadWifiOnly = true))
    private val reach = MutableStateFlow(Reach())
    private val network = MutableStateFlow(NetworkNow(connected = true, unmetered = false))
    private val routes = Routes()
    private val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val made = mutableListOf<Downloads>()

    private val bytes = ByteArray(1_200_000) { (it * 7 + 3).toByte() }
    private val picture = ByteArray(40_000) { (it * 5).toByte() }

    /** Where the server holds its answer to the first request for the song, and for the cover. */
    private val songGate = GatedServer.Gate()
    private val coverGate = GatedServer.Gate()

    /** Where the server holds its answer to "are you there?" by the network `pingHeldVia`,
     *  and to the library's word on the song (`getSong`). */
    private val pingGate = GatedServer.Gate()
    @Volatile private var pingHeldVia: String? = null
    /** The library's word on the song (`getSong`) is held before anything of it is sent. */
    private val songWordHeld = CountDownLatch(1)

    /** Requests the engine cancelled, by the path they were for, and on which thread. */
    private val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cancelledOn: MutableList<Pair<String, Thread>> = Collections.synchronizedList(mutableListOf())
    private val coverCancelled = CountDownLatch(1)

    /** The unmetered network the system has given, as the test says it comes and goes. */
    private inner class Routes : UnmeteredRoutes {
        override val now = MutableStateFlow<BoundRoute?>(null)

        /** Whether an unmetered network is wanted, each time that changes. */
        val wanted: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        override fun want(on: Boolean) {
            if (wanted.lastOrNull() != on) wanted += on
        }

        /** A route by the network `network`; every request made by it says `via`. */
        fun route(network: String, via: String = network) = BoundRoute(network, client(via))
    }

    private fun client(via: String?): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.MINUTES)
        .addInterceptor { chain -> chain.proceed(if (via == null) chain.request() else chain.request().newBuilder().header("X-Via", via).build()) }
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                val path = call.request().url.encodedPath.substringAfterLast('/')
                cancelled += path
                cancelledOn += path to Thread.currentThread()
                if ("getCoverArt" in path) coverCancelled.countDown()
            }
        })
        .build()

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        database = StandaloneDatabaseProvider(context)
        catalogue = Catalogue(folder.newFolder("downloads"))
        covers = CoverStore(folder.newFolder("covers"), scope, Dispatchers.IO, fetch = { _, _ -> throw ApiError.Network("none") }, pinned = File(catalogue.dir, "covers"))
        var songAsked = 0
        server.handler = { request ->
            when {
                "ping" in request.path -> GatedServer.Answer(200, mapOf("Content-Type" to "application/json"), OK.toByteArray(), holdAfter = if (pingHeldVia != null && request.headers["x-via"] == pingHeldVia) 0 else -1, gate = pingGate)
                "getSong" in request.path -> {
                    songWordHeld.await(HELD_MINUTES, TimeUnit.MINUTES)
                    GatedServer.song("s1", "flac", bytes.size.toLong(), 10)
                }
                // The first request for the song is held with 200 KB sent; later ones are not.
                "stream" in request.path -> synchronized(this) { songAsked++ }.let { GatedServer.file(request, bytes, MONDAY, hold = if (songAsked == 1) 200_000 to songGate else null) }
                "getCoverArt" in request.path -> GatedServer.Answer(200, mapOf("Content-Type" to "image/jpeg"), picture, holdAfter = 1_000, gate = coverGate)
                else -> GatedServer.Answer(404, mapOf("Content-Type" to "application/json"), "{}".toByteArray())
            }
        }
    }

    @After
    fun stop() {
        songGate.dropHere()
        coverGate.dropHere()
        pingGate.dropHere()
        songWordHeld.countDown()
        made.forEach { it.release() }
        idle()
        scope.cancel()
        server.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, ok: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(WAIT_MINUTES)
        while (true) {
            idle()
            if (ok()) return
            if (System.nanoTime() > deadline) fail("never: $what (${made.lastOrNull()?.state?.value}; cancelled $cancelled; $notes)")
            Thread.yield()
        }
    }

    private fun <T> run(block: suspend () -> T): T {
        val result = scope.async { block() }
        until("an answer") { result.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return result.getCompleted()
    }

    private fun engine(): Downloads {
        val api = Subsonic(server.url("/"), "tester", "pass", client(null))
        val check = AudioCheck { _, _, _ -> Inspected("flac", true, -1) }
        return Downloads(context, catalogue, api, client(null), database, "acct", settings, reach, covers, { _, _ -> }, scope, spare = { 0 }, note = { notes += it }, network = network, check = check, unmeteredRoutes = routes)
            .also { made += it }
            .also { idle() }
            .also { engine -> until("ready") { engine.state.value.settled } }
    }

    private fun song(cover: String = "", size: Long = bytes.size.toLong()) = Song(id = "s1", title = "One", suffix = "flac", size = size, duration = 10, coverArt = cover)

    /** By which way each request for the song came (null: the phone's own route). */
    private fun via(): List<String?> = synchronized(server.asked) { server.asked.filter { "stream" in it.path }.map { it.headers["x-via"] } }

    private val part get() = File(catalogue.dir, SongFetch.partName("s1.flac"))

    /** A download under way by `first`, held by the server with 200 KB arrived and its
     *  connection open: nothing more is coming, and nothing is being read. */
    private fun stalledOn(first: BoundRoute?): Downloads {
        routes.now.value = first
        val engine = engine()
        run { engine.download(listOf(song())) }
        until("the song asked for") { via().isNotEmpty() }
        songGate.awaitReached()
        until("what was sent has arrived") { part.length() >= 190_000 }
        return engine
    }

    @Test
    fun aStalledTransferIsCancelledTheInstantAnotherNetworkTakesOverNotAtItsNextRead() {
        val engine = stalledOn(routes.route("wifi"))
        // The system gives another unmetered network in the Wi-Fi's place (never "none" in
        // between). The transfer on the Wi-Fi is not reading: nothing is arriving on it.
        routes.now.value = routes.route("home")
        // Its request is cancelled there and then, and the rest is asked for by the new
        // network, while the server is still holding the first answer. (Asked only before a
        // read, it sat on its connection until that connection next gave it something.)
        until("downloaded by the other network") { catalogue.has("s1") && !engine.state.value.busy }
        assertTrue("stream" in cancelled.joinToString())
        assertArrayEquals(bytes, catalogue.fileOf("s1")!!.readBytes())
        assertEquals("wifi", via().first())
        assertTrue("it came by ${via()}", via().drop(1).let { it.isNotEmpty() && it.all { v -> v == "home" } })
        // What had arrived was carried on from, not fetched again.
        assertTrue(synchronized(server.asked) { server.asked.any { "stream" in it.path && (it.from ?: 0) > 0 } })
        assertTrue(engine.state.value.failed.isEmpty())
    }

    @Test
    fun theSameNetworkGoneAndBackIsNotTheRouteATransferBeganOn() {
        val engine = stalledOn(routes.route("wifi", via = "wifi, before"))
        // The Wi-Fi turns metered and unmetered again (or drops and comes back) in a moment:
        // the same network by name, and another route. A transfer that asks only "is it
        // still that network?" carries on down its old connection.
        routes.now.value = routes.route("wifi", via = "wifi, after")
        until("downloaded by the route as it now is") { catalogue.has("s1") && !engine.state.value.busy }
        assertEquals("wifi, before", via().first())
        assertTrue("it came by ${via()}", via().drop(1).let { it.isNotEmpty() && it.all { v -> v == "wifi, after" } })
        assertArrayEquals(bytes, catalogue.fileOf("s1")!!.readBytes())
    }

    @Test
    fun aStalledTransferIsCancelledWhenItsNetworkGoesAndWaits() {
        val engine = stalledOn(routes.route("wifi"))
        routes.now.value = null
        until("its request cancelled") { "stream" in cancelled.joinToString() && InFlight.count(catalogue.dir) == 0 }
        until("waiting for Wi-Fi") { engine.state.value.held == Held.WIFI && engine.state.value.active.none { it.state is SongState.Fetching } }
        assertTrue(engine.state.value.failed.isEmpty())
        assertEquals(listOf<String?>("wifi"), via())
        assertFalse(catalogue.has("s1"))
    }

    @Test
    fun wifiOnlySwitchedOnCancelsAStalledTransferThatWasGoingByThePhonesOwnRoute() {
        settings.value = Settings(downloadWifiOnly = false)
        val engine = stalledOn(null)
        assertEquals(listOf<String?>(null), via())
        // Wi-Fi only is switched on, and there is no Wi-Fi.
        settings.value = Settings(downloadWifiOnly = true)
        until("its request cancelled") { "stream" in cancelled.joinToString() && InFlight.count(catalogue.dir) == 0 }
        until("waiting for Wi-Fi") { engine.state.value.held == Held.WIFI }
        assertEquals(listOf<String?>(null), via())
        // Wi-Fi comes: the rest arrives by it.
        routes.now.value = routes.route("wifi")
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertTrue("it came by ${via()}", via().drop(1).let { it.isNotEmpty() && it.all { v -> v == "wifi" } })
    }

    @Test
    fun aCoverOnItsWayIsCancelledWhenItsNetworkGoes() {
        // The song arrives by the Wi-Fi (nothing holds it this time), and its cover is asked
        // for by the Wi-Fi after it: held by the server with a kilobyte sent.
        songGate.letThrough()
        routes.now.value = routes.route("wifi")
        val engine = engine()
        run { engine.download(listOf(song(cover = "c1"))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        until("the cover asked for") { synchronized(server.asked) { server.asked.any { "getCoverArt" in it.path } } }
        coverGate.awaitReached()
        assertEquals("wifi", synchronized(server.asked) { server.asked.first { "getCoverArt" in it.path }.headers["x-via"] })
        // The Wi-Fi goes. The cover's request is cancelled with it. (It was never told: it
        // went on to its end on whatever that connection had become.)
        routes.now.value = null
        until("the cover's request cancelled") { coverCancelled.count == 0L }
        assertFalse(File(catalogue.dir, "covers").walkTopDown().any { it.isFile && it.length() > 0 })
    }

    @Test
    fun removeAllCannotBeUndoneByACoverStillArriving() {
        // (Offline hardening 3, item 4.) A song is downloaded, and its cover is on its way
        // to be kept beside it: held by the server with a kilobyte sent.
        songGate.letThrough()
        routes.now.value = routes.route("wifi")
        val engine = engine()
        run { engine.download(listOf(song(cover = "c1"))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        until("the cover asked for") { synchronized(server.asked) { server.asked.any { "getCoverArt" in it.path } } }
        coverGate.awaitReached()
        // "Remove all", now. It says everything has gone, and it has: the cover's request is
        // cancelled, and nothing lands in the folder afterwards. (The cover used to arrive
        // after the folder had been emptied, and stay there.)
        assertTrue(run { engine.removeAll() })
        until("the cover's request cancelled") { coverCancelled.count == 0L }
        coverGate.letThrough()
        run { engine.download(emptyList()) }
        repeat(30) { idle() }
        assertTrue(catalogue.state.value.ids.isEmpty())
        val left = catalogue.dir.walkTopDown().filter { it.isFile && it.length() > 0 }.map { it.name }.toList()
        assertTrue("left in the folder: $left", left.all { it == Catalogue.SONGS_FILE })
    }

    @Test
    fun theJobOnAnotherNetworkThanTheTransfersIsWrittenDown() {
        songGate.letThrough()
        val wifi = org.robolectric.shadows.ShadowNetwork.newInstance(7)
        val other = org.robolectric.shadows.ShadowNetwork.newInstance(9)
        routes.now.value = BoundRoute(wifi, client("wifi"))
        val engine = engine()
        // Android gave the job another unmetered network than the one the app was given.
        engine.useNetwork(other)
        run { engine.download(listOf(song())) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        // The app's own governs (the transfer went by it), and it is said, once.
        assertEquals(listOf<String?>("wifi"), via())
        assertEquals(1, notes.count { "Android gave the job one unmetered network and the app another" in it })
        // The same network for both: nothing to say.
        engine.useNetwork(wifi)
        engine.remove(listOf("s1"))
        until("removed") { !catalogue.has("s1") }
        run { engine.download(listOf(song())) }
        until("again") { catalogue.has("s1") && !engine.state.value.busy }
        assertEquals(1, notes.count { "Android gave the job one unmetered network and the app another" in it })
    }

    // ---- Offline loose ends, item 6 ------------------------------------------------------------

    @Test
    fun nothingGoesByANetworkThatHasJustComeBeforeTheServerHasAnsweredOnIt() {
        songGate.letThrough()
        coverGate.letThrough()
        routes.now.value = routes.route("wifi")
        val engine = engine()
        // Another unmetered network takes over, and the server has not yet answered on it.
        pingHeldVia = "home"
        routes.now.value = routes.route("home")
        until("asked there") { synchronized(server.asked) { server.asked.any { "ping" in it.path && it.headers["x-via"] == "home" } } }
        pingGate.awaitReached()
        // An album is asked for meanwhile: its song waits for the server, and so does its
        // cover. (The cover went by the new network at once, before the server had said
        // anything there; a song's worker that was carrying on did the same.)
        run { engine.download(listOf(song()), DownloadedList("album:al-1", "Album", coverArt = "c1", songs = listOf("s1"))) }
        until("waiting for the server") { engine.state.value.held == Held.SERVER }
        // (The one look here for something that must not happen: two seconds of the main
        // thread running, in which the old code's cover request arrived within milliseconds.)
        val looked = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < looked) {
            idle()
            assertTrue("sent before the server answered there", synchronized(server.asked) { server.asked.none { "getCoverArt" in it.path || "stream" in it.path } })
            Thread.yield()
        }
        // It answers: the song and the cover come by it.
        pingGate.letThrough()
        until("downloaded, and the cover kept") { catalogue.has("s1") && !engine.state.value.busy && File(catalogue.dir, "covers").walkTopDown().any { it.isFile && it.length() > 0 } }
        assertEquals(listOf<String?>("home"), via())
        assertEquals(listOf("home"), synchronized(server.asked) { server.asked.filter { "getCoverArt" in it.path }.map { it.headers["x-via"] } })
    }

    @Test
    fun askingTheServerForTheSongAgainIsCancelledWithTheNetworkThereAndThen() {
        songGate.letThrough()
        routes.now.value = routes.route("wifi")
        val engine = engine()
        // The library gives the song another size than the file the server sends: the server
        // is asked for the song as it has it now, and holds that answer.
        run { engine.download(listOf(song(size = bytes.size + 1L))) }
        until("the song asked for again") { synchronized(server.asked) { server.asked.any { "getSong" in it.path } } }
        // The Wi-Fi goes. That request is cancelled with it, on the thread that hears so, as
        // the transfer's is. (It was left to Media3 stopping the download, later, from its
        // own thread; and only if it did.)
        routes.now.value = null
        until("its request cancelled") { "getSong" in cancelled.joinToString() && InFlight.count(catalogue.dir) == 0 }
        assertEquals(listOf(Looper.getMainLooper().thread), cancelledOn.filter { "getSong" in it.first }.map { it.second })
        until("waiting for Wi-Fi") { engine.state.value.held == Held.WIFI }
        assertTrue(engine.state.value.failed.isEmpty())
        assertFalse(catalogue.has("s1"))
    }

    @Test
    fun theUnmeteredNetworkIsKeptUntilTheLastSongsCoverHasCome() {
        songGate.letThrough()
        routes.now.value = routes.route("wifi")
        val engine = engine()
        run { engine.download(listOf(song(cover = "c1"))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        until("the cover asked for") { synchronized(server.asked) { server.asked.any { "getCoverArt" in it.path } } }
        coverGate.awaitReached()
        // The last song has arrived, and its cover is still on its way by the Wi-Fi: the
        // network is still wanted. (It was let go here.)
        repeat(10) { idle() }
        assertEquals(true, routes.wanted.last())
        coverGate.letThrough()
        until("the cover kept") { File(catalogue.dir, "covers").walkTopDown().any { it.isFile && it.length() > 0 } }
        until("let go") { routes.wanted.last() == false }
    }

    private companion object {
        const val MONDAY = "Mon, 23 Nov 2026 07:57:08 GMT"

        /** How long another thread is given to do what a test waits for before it is taken
         *  never to happen: nothing here is ordered by time, so this only ends a test that
         *  has already failed. */
        const val WAIT_MINUTES = 2L

        /** How long the library's word on the song is held when a test holds it (each test
         *  lets it go as it ends). */
        const val HELD_MINUTES = 10L
        const val OK = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"}}"""
    }
}
