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

    /** Requests the engine cancelled, by the path they were for. */
    private val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val coverCancelled = CountDownLatch(1)

    /** The unmetered network the system has given, as the test says it comes and goes. */
    private inner class Routes : UnmeteredRoutes {
        override val now = MutableStateFlow<BoundRoute?>(null)
        override fun want(on: Boolean) = Unit

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
                "ping" in request.path -> GatedServer.Answer(200, mapOf("Content-Type" to "application/json"), OK.toByteArray())
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
        made.forEach { it.release() }
        idle()
        scope.cancel()
        server.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, ok: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
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

    private fun song(cover: String = "") = Song(id = "s1", title = "One", suffix = "flac", size = bytes.size.toLong(), duration = 10, coverArt = cover)

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
        idle()
        assertTrue("the cover's request was not cancelled", coverCancelled.await(30, TimeUnit.SECONDS))
        assertFalse(File(catalogue.dir, "covers").walkTopDown().any { it.isFile && it.length() > 0 })
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

    private companion object {
        const val MONDAY = "Mon, 23 Nov 2026 07:57:08 GMT"
        const val OK = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"}}"""
    }
}
