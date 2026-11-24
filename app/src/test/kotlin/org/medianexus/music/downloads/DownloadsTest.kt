package org.medianexus.music.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
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
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * The downloads on Media3's real download manager (its queue, its database, its tasks and
 * the way it stops them), with our own downloader, against a stand-in server. What is
 * waited for here is another thread's work (a download arriving): the test lets the main
 * thread run until it is so; the order of what happens is the test's to decide.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var catalogue: Catalogue
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = MutableStateFlow(Settings(downloadWifiOnly = false))
    private val reach = MutableStateFlow(Reach())
    private val asked: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val kept = mutableListOf<Pair<Boolean, Boolean>>()
    private var spare = 0L

    /** The songs the server has, by id; `slow`: sent a little at a time. */
    private val files = HashMap<String, ByteArray>()
    @Volatile private var slow = false
    @Volatile private var missing = setOf<String>()

    private val made = mutableListOf<Downloads>()

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        connected()
        database = StandaloneDatabaseProvider(context)
        catalogue = Catalogue(folder.newFolder("downloads"), note = { notes += it to Thread.currentThread() })
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request
                val id = request.url.queryParameter("id").orEmpty()
                val bytes = files[id]
                if (bytes == null || id in missing) return MockResponse.Builder().code(404).setHeader("Content-Type", "application/json").body("{}").build()
                val from = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val builder = MockResponse.Builder().setHeader("Content-Type", "audio/flac").setHeader("Accept-Ranges", "bytes").setHeader("Last-Modified", "Mon, 23 Nov 2026 07:57:08 GMT").setHeader("Date", GatedServer.now())
                if (slow) builder.throttleBody(16 * 1024, 40, TimeUnit.MILLISECONDS)
                return if (from > 0) {
                    builder.code(206).setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}").body(Buffer().write(bytes, from, bytes.size - from)).build()
                } else {
                    builder.body(Buffer().write(bytes)).build()
                }
            }
        }
        server.start()
    }

    /** The phone is on a network that Media3 counts as there (validated, on Wi-Fi). */
    private fun connected() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
    }

    @After
    fun stop() {
        made.forEach { it.release() }
        idle()
        scope.cancel()
        server.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the main thread run until `ok`, as another thread's work finishes. */
    private fun until(what: String, ok: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            idle()
            if (ok()) return
            if (System.nanoTime() > deadline) fail("never: $what (${made.lastOrNull()?.state?.value})")
            Thread.yield()
        }
    }

    /** Where the catalogue is written and the start-up sweep runs: one thing at a time, at
     *  once unless a test holds it. */
    private val serial = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "disk").apply { isDaemon = true } }
    private val held = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    @Volatile private var holding = false
    private val disk = object : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (holding) held += block else serial.execute(block)
        }
    }

    /** What the catalogue had to say, and on which thread it was doing what it said. */
    private val notes: MutableList<Pair<String, Thread>> = Collections.synchronizedList(mutableListOf())

    /** The phone's network as the downloads are told of it (a test changes it as Android
     *  would say it changed). */
    private var network: kotlinx.coroutines.flow.MutableStateFlow<org.medianexus.music.net.NetworkNow> = MutableStateFlow(org.medianexus.music.net.NetworkNow())

    /** The unmetered network the system has given, as a test says it comes and goes (null:
     *  the engine's own stand-in, the default route while it is unmetered). */
    private var routes: FakeRoutes? = null

    private class FakeRoutes : org.medianexus.music.net.UnmeteredRoutes {
        override val now = MutableStateFlow<org.medianexus.music.net.BoundRoute?>(null)
        val wanted = mutableListOf<Boolean>()
        override fun want(on: Boolean) {
            if (wanted.lastOrNull() != on) wanted += on
        }

        /** A network: every request made on it says so. */
        fun network(name: String) = org.medianexus.music.net.BoundRoute(
            name,
            OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Via", name).build()) }.build(),
        )
    }

    /** By which network each request for a song came (null: the phone's default route). */
    private fun via(id: String): List<String?> = synchronized(asked) { asked.filter { it.url.queryParameter("id") == id }.map { it.headers["X-Via"] } }

    /** What the engines had to say for Diagnostics. */
    private val engineNotes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What says a file is the song (see above): a test may make it slow to answer. */
    private var check = AudioCheck { _, _, _ -> Inspected("flac", true, -1) }

    private fun downloads(): Downloads {
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient())
        val covers = CoverStore(folder.newFolder(), scope, Dispatchers.IO, fetch = { _, _ -> throw ApiError.Network("none") })
        return Downloads(context, catalogue, api, OkHttpClient(), database, "acct", settings, reach, covers, { busy, wifi -> kept += busy to wifi }, scope, spare = { spare },
            // The songs here are not audio (what is and isn't a song is WholeDownloadTest's).
            check = check, disk = disk, network = network, note = { engineNotes += it }, unmeteredRoutes = routes)
            .also { made += it }
            .also { idle() }
    }

    private fun song(id: String, size: Int = 200_000): Song {
        files[id] = ByteArray(size) { (it * 7 + id.hashCode()).toByte() }
        return Song(id = id, title = id.uppercase(), album = "Album", albumId = "al-1", suffix = "flac", size = size.toLong(), duration = 10, track = id.last().digitToInt())
    }

    private fun <T> run(block: suspend () -> T): T {
        val result = scope.async { block() }
        until("an answer") { result.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return result.getCompleted()
    }

    @Test
    fun anAlbumIsDownloadedWholeAndStaysDownloaded() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val songs = listOf(song("s1"), song("s2"), song("s3"))
        val list = DownloadedList("album:al-1", "Album", "Someone", songs = songs.map { it.id })
        val asked = run { engine.download(songs, list) }
        assertTrue(asked is Asked.Started && asked.count == 3)
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        for (song in songs) assertArrayEquals(files[song.id], catalogue.fileOf(song.id)!!.readBytes())
        val state = engine.state.value
        assertFalse(state.busy)
        assertEquals(3, state.batchDone)
        assertEquals(listOf("album:al-1"), state.collections.map { it.key })
        // The keeper was told there was work, and then that there is none.
        assertTrue(kept.contains(true to false))
        assertEquals(false to false, kept.last())
        // Asked again: nothing new.
        assertEquals(Asked.AlreadyDownloaded, run { engine.download(songs) })
    }

    @Test
    fun aDownloadMedia3StopsIsNeverTakenForFinished() {
        // Media3 takes a downloader that ends by being interrupted, without having been
        // stopped by Media3 itself, as having finished. Stopped part-way here (offline mode),
        // the song must still be on its way, not downloaded.
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val one = song("s1", size = 400_000)
        run { engine.download(listOf(one)) }
        until("arriving") { engine.state.value.active.any { (it.state as? SongState.Fetching)?.fraction?.let { f -> f > 0.05f } == true } }
        reach.value = Reach(manual = true)
        until("held") { engine.state.value.held == Held.OFFLINE && engine.state.value.active.all { it.state == SongState.Waiting } }
        assertFalse(catalogue.has("s1"))
        assertEquals(Held.OFFLINE, engine.state.value.held)
        // On again: it carries on from where it stopped, by a range, and arrives whole.
        slow = false
        reach.value = Reach()
        until("arrived") { catalogue.has("s1") }
        assertTrue(asked.any { (it.headers["Range"] ?: "").startsWith("bytes=") && it.headers["Range"] != "bytes=0-" })
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
    }

    @Test
    fun theAppStoppedPartWayCarriesOnWhenItIsBack() {
        slow = true
        val first = downloads()
        until("ready") { first.state.value.settled }
        run { first.download(listOf(song("s1", size = 400_000), song("s2"))) }
        until("arriving") { first.state.value.active.any { it.state is SongState.Fetching } }
        // The app is stopped (its manager goes with it, part-way).
        first.release()
        made.remove(first)
        idle()
        assertFalse(catalogue.has("s1"))
        slow = false
        // Started again: what was waiting is read from Media3's list and carries on.
        val again = downloads()
        until("both") { catalogue.has("s1") && catalogue.has("s2") && !again.state.value.busy }
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
        assertFalse(again.state.value.busy)
    }

    @Test
    fun aFailureIsSaidInWordsAndRetriedOnRequest() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val gone = song("s1")
        missing = setOf("s1")
        run { engine.download(listOf(gone, song("s2"))) }
        until("settled") { engine.state.value.failed.isNotEmpty() && catalogue.has("s2") && !engine.state.value.busy }
        val failed = engine.state.value.failed.single()
        assertEquals("s1", failed.song.id)
        assertEquals("The server refused it (HTTP 404).", failed.reason)
        // Refused is final: asked once, not five times.
        assertEquals(1, asked.count { it.url.queryParameter("id") == "s1" })
        assertEquals(SongState.Failed("The server refused it (HTTP 404)."), engine.state.value.stateOf("s1"))
        // Kept through a restart of the app, with its reason.
        assertEquals("The server refused it (HTTP 404).", Catalogue(catalogue.dir).state.value.failed["s1"]?.reason)
        missing = emptySet()
        run { engine.retryFailed() }
        until("retried") { catalogue.has("s1") }
        assertTrue(engine.state.value.failed.isEmpty())
    }

    @Test
    fun nothingIsStartedThatWontFit() {
        spare = Long.MAX_VALUE / 4
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val asked = run { engine.download(listOf(song("s1"))) }
        assertTrue(asked is Asked.NoRoom)
        assertEquals(200_000L, (asked as Asked.NoRoom).needed)
        idle()
        assertFalse(engine.state.value.busy)
        assertTrue(this.asked.isEmpty())
    }

    @Test
    fun cancelledAndRemovedSongsLeaveNothingBehind() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 400_000))) }
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        engine.cancel(listOf("s1"))
        until("gone") { !engine.state.value.busy }
        until("its part cleared") { catalogue.dir.listFiles()!!.none { it.name.endsWith(".part") } }
        assertFalse(catalogue.has("s1"))
        slow = false
        run { engine.download(listOf(song("s2"))) }
        until("s2") { catalogue.has("s2") }
        val file = catalogue.fileOf("s2")!!
        engine.remove(listOf("s2"))
        until("removed") { !catalogue.has("s2") }
        assertFalse(file.exists())
        assertFalse(Catalogue(catalogue.dir).has("s2"))
    }

    @Test
    fun aDownloadedListFollowsItsPage() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val first = listOf(song("s1"), song("s2"))
        run { engine.download(first, DownloadedList("playlist:p1", "Mix", songs = first.map { it.id })) }
        until("two") { engine.state.value.downloaded.size == 2 }
        // The playlist gained a song, and its order changed, on the server.
        val now = listOf(song("s3"), song("s2"), song("s1"))
        run { engine.follow(DownloadedList("playlist:p1", "Mix", songs = emptyList()), now) }
        until("three") { engine.state.value.downloaded.containsKey("s3") }
        assertEquals(listOf("s3", "s2", "s1"), catalogue.collection("playlist:p1")!!.songs)
        assertEquals(listOf("s3", "s2", "s1"), DownloadedViews.playlist(engine.state.value, "playlist:p1")!!.songs.map { it.id })
        // One of its songs removed by hand: the list is no longer downloaded whole, so the
        // next look at it doesn't fetch the song straight back.
        engine.remove(listOf("s2"))
        until("forgotten") { catalogue.collection("playlist:p1") == null }
        run { engine.follow(DownloadedList("playlist:p1", "Mix"), now) }
        idle()
        assertFalse(engine.state.value.busy)
        assertFalse(catalogue.has("s2"))
        assertTrue(catalogue.has("s1") && catalogue.has("s3"))
    }

    @Test
    fun noWorkIsAcceptedUntilTheStartUpSweepIsOver() {
        // The sweep is held: it runs when the test says.
        holding = true
        // What an earlier run left: a file that is nobody's, and old.
        val stray = File(catalogue.dir, "stray.flac").also { it.writeBytes(ByteArray(9)); it.setLastModified(catalogue.opened - 60_000) }
        val engine = downloads()
        until("read") { engine.state.value.settled }
        val asking = scope.async { engine.download(listOf(song("s1"))) }
        // Media3's manager is up, and still nothing is taken on: the folder hasn't been swept.
        repeat(50) { idle() }
        assertFalse(asking.isCompleted)
        assertTrue(asked.isEmpty())
        assertTrue(stray.exists())
        // The sweep runs.
        holding = false
        while (true) serial.execute(held.poll() ?: break)
        until("asked") { asking.isCompleted }
        assertFalse(stray.exists())
        until("downloaded") { catalogue.has("s1") }
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
    }

    // ---- Lists that share songs (offline review 4) -----------------------------------------

    private fun list(key: String, songs: List<Song>) = DownloadedList(key, key, songs = songs.map { it.id })

    @Test
    fun removingOneOfTwoListsThatShareASongLeavesItForTheOther() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2, s3) = listOf(song("s1"), song("s2"), song("s3"))
        run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        run { engine.toggle(list("playlist:p2", listOf(s2, s3)), listOf(s2, s3)) }
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        // The first playlist's button again: it is removed.
        assertEquals(Downloads.Toggled.Removed, run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) })
        until("s1 gone") { !catalogue.has("s1") }
        repeat(20) { idle() }
        // The song the other playlist holds stays, and that playlist is still downloaded whole.
        assertTrue(catalogue.has("s2") && catalogue.has("s3"))
        assertTrue(catalogue.fileOf("s2")!!.isFile)
        assertEquals(listOf("playlist:p2"), engine.state.value.collections.map { it.key })
        assertEquals(listOf("s2", "s3"), DownloadedViews.playlist(engine.state.value, "playlist:p2")!!.songs.map { it.id })
        // And after the app is started again.
        assertEquals(setOf("s2", "s3"), Catalogue(catalogue.dir).state.value.ids)
        // A list that was never asked for itself, every song of which another download
        // holds: its button can remove nothing, and says so rather than "Removed".
        assertEquals(Downloads.Toggled.HeldElsewhere, run { engine.toggle(list("playlist:p3", listOf(s2)), listOf(s2)) })
        assertTrue(catalogue.has("s2"))
        assertEquals(listOf("playlist:p2"), engine.state.value.collections.map { it.key })
    }

    @Test
    fun cancellingAListLeavesWhatAnotherListIsWaitingFor() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2, s3) = listOf(song("s1", size = 400_000), song("s2", size = 400_000), song("s3", size = 400_000))
        run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        run { engine.toggle(list("playlist:p2", listOf(s2, s3)), listOf(s2, s3)) }
        // With all of it still on its way, the first playlist's button: it is cancelled.
        assertEquals(Downloads.Toggled.Cancelled, run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) })
        slow = false
        until("the other list whole") { catalogue.has("s2") && catalogue.has("s3") && !engine.state.value.busy }
        assertFalse(catalogue.has("s1"))
        assertEquals(listOf("playlist:p2"), engine.state.value.collections.map { it.key })
        // What the other list wanted was not stopped and started again for it.
        assertEquals(1, asked.count { it.url.queryParameter("id") == "s2" })
    }

    @Test
    fun aSongDownloadedByItselfOutlivesAListItIsIn() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2) = listOf(song("s1"), song("s2"))
        run { engine.download(listOf(s1), own = true) }
        run { engine.toggle(list("album:al-1", listOf(s1, s2)), listOf(s1, s2)) }
        until("both") { engine.state.value.downloaded.size == 2 && !engine.state.value.busy }
        assertEquals(Downloads.Toggled.Removed, run { engine.toggle(list("album:al-1", listOf(s1, s2)), listOf(s1, s2)) })
        until("s2 gone") { !catalogue.has("s2") }
        repeat(20) { idle() }
        assertTrue(catalogue.has("s1"))
        // Until it is itself removed, by hand.
        engine.remove(listOf("s1"))
        until("s1 gone") { !catalogue.has("s1") }
        assertTrue(Catalogue(catalogue.dir).state.value.singles.isEmpty())
        // With nothing left downloaded, what the downloads are said to take is what is
        // really left in their folder (their own small lists), not what was there before.
        val left = catalogue.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        until("measured again") { engine.state.value.occupied == catalogue.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        assertTrue("$left bytes are left", left < 10_000)
    }

    @Test
    fun twoAlbumsAskedForTogetherCannotBothPassACheckThatOnlyOneFits() {
        // Nothing arrives meanwhile, and there is room for one of them: each needs 300 MB,
        // and all but 400 MB of what is free must be left spare.
        slow = true
        val each = 300L * 1024 * 1024
        spare = catalogue.dir.usableSpace - 400L * 1024 * 1024
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val one = listOf(song("a1").copy(size = each))
        val two = listOf(song("b1").copy(size = each))
        val first = scope.async { engine.toggle(list("album:a", one), one) }
        val second = scope.async { engine.toggle(list("album:b", two), two) }
        until("both answered") { first.isCompleted && second.isCompleted }
        @Suppress("OPT_IN_USAGE")
        val answers = listOf(first.getCompleted(), second.getCompleted()).map { (it as Downloads.Toggled.Asked).asked }
        assertEquals(1, answers.count { it is Asked.Started })
        // The other is told what is needed in all, with what is already on its way.
        assertEquals(2 * each, (answers.single { it is Asked.NoRoom } as Asked.NoRoom).needed)
        engine.cancelAll()
    }

    @Test
    fun askingAgainForWhatIsOnItsWayDoesNotStartItAgain() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val one = song("s1", size = 400_000)
        // Asked for twice in the same breath (two taps; a page's list and its top-up), before
        // Media3 has so much as said the first is queued.
        val answers = run { listOf(engine.download(listOf(one)), engine.download(listOf(one))) }
        assertTrue(answers[0] is Asked.Started)
        assertEquals(Asked.AlreadyDownloaded, answers[1])
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        // And again while it arrives.
        assertEquals(Asked.AlreadyDownloaded, run { engine.download(listOf(one)) })
        slow = false
        until("arrived") { catalogue.has("s1") && !engine.state.value.busy }
        // It was fetched once, from its start: never stopped and begun again.
        assertEquals(listOf<String?>(null), asked.filter { it.url.queryParameter("id") == "s1" }.map { it.headers["Range"] })
    }

    @Test
    fun aFailureIsWrittenOffTheMainThreadAndAPhoneThatCantWriteCrashesNothing() {
        // The list of failures can't be written (the phone is full; here, something is in
        // its way).
        File(File(catalogue.dir, Catalogue.FAILED_FILE), "in-the-way").apply { parentFile.mkdirs(); writeText("x") }
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val gone = song("s1")
        missing = setOf("s1")
        run { engine.download(listOf(gone)) }
        // Said to the person all the same, from memory...
        until("failed") { engine.state.value.failed.isNotEmpty() && !engine.state.value.busy }
        assertEquals("The server refused it (HTTP 404).", engine.state.value.failed.single().reason)
        // ...and the write that couldn't be made was tried somewhere other than the main thread.
        until("said") { notes.any { "failed.json couldn't be written" in it.first } }
        assertTrue(notes.filter { "couldn't be written" in it.first }.none { it.second === Looper.getMainLooper().thread })
    }

    // ---- When they may run (offline review 6) -----------------------------------------------

    @Test
    fun onAWifiWithNoInternetBeyondItDownloadsStillRun() {
        // A home Wi-Fi with a music server on it and nothing beyond: Android never marks it
        // as validated. It is unmetered, and it is a network; that is all that is asked of it.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        settings.value = Settings(downloadWifiOnly = true)
        // (As the app reads it from the system.)
        network = MutableStateFlow(org.medianexus.music.net.NetworkWatch(context).state.value)
        assertEquals(org.medianexus.music.net.NetworkNow(connected = true, unmetered = true), network.value)
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1"))) }
        until("downloaded") { catalogue.has("s1") }
    }

    @Test
    fun theSameNetworkTurningMeteredStopsWifiOnlyDownloadsAtOnce() {
        slow = true
        settings.value = Settings(downloadWifiOnly = true)
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 400_000))) }
        until("arriving") { engine.state.value.active.any { (it.state as? SongState.Fetching)?.fraction?.let { f -> f > 0.05f } == true } }
        // The network is the same one, and now counts as metered (a hotspot recognised as
        // one; the person marking it so).
        network.value = org.medianexus.music.net.NetworkNow(connected = true, unmetered = false)
        until("held for Wi-Fi") { engine.state.value.held == Held.WIFI && engine.state.value.active.all { it.state == SongState.Waiting } }
        assertFalse(catalogue.has("s1"))
        // No network at all, Wi-Fi only on: still waiting for Wi-Fi. Off: for any connection.
        network.value = org.medianexus.music.net.NetworkNow(connected = false, unmetered = false)
        until("still Wi-Fi") { engine.state.value.held == Held.WIFI }
        settings.value = Settings(downloadWifiOnly = false)
        until("any connection") { engine.state.value.held == Held.NETWORK }
        // Mobile data, and Wi-Fi only is off: it carries on, from where it stopped.
        slow = false
        network.value = org.medianexus.music.net.NetworkNow(connected = true, unmetered = false)
        until("arrived") { catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
    }

    @Test
    fun stoppedByAndroidTheTransfersStopUntilItCarriesThemOnAgain() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 400_000))) }
        until("arriving") { engine.state.value.active.any { (it.state as? SongState.Fetching)?.fraction?.let { f -> f > 0.05f } == true } }
        // Android stops the job (its time is up; the network it was given went).
        engine.stoppedByAndroid(true)
        until("paused") { engine.state.value.held == Held.ANDROID && engine.state.value.active.all { it.state == SongState.Waiting } }
        // Nothing more arrives: the transfer itself has stopped, not only the watching of it.
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        until("its fetch has let go") { made.last().state.value.active.none { it.state is SongState.Fetching } && part.length() > 0 }
        val had = part.length()
        repeat(200) { idle() }
        assertEquals(had, part.length())
        assertFalse(catalogue.has("s1"))
        // The job runs again.
        slow = false
        engine.stoppedByAndroid(false)
        until("arrived") { catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
    }

    // ---- Smaller things (offline review 9) ----------------------------------------------------

    @Test
    fun aNewEngineWaitsForTheLastOnesFetchesToLetGoOfTheFolder() {
        // The first engine's fetch has everything and is checking it; it will be slow to
        // notice it has been stopped (as a thread in the middle of a write to disk is).
        val checking = java.util.concurrent.CountDownLatch(1)
        val letGo = java.util.concurrent.CountDownLatch(1)
        check = AudioCheck { _, _, _ ->
            checking.countDown()
            while (true) {
                try {
                    letGo.await()
                    break
                } catch (_: InterruptedException) {
                    // Not yet.
                }
            }
            Inspected("flac", true, -1)
        }
        val first = downloads()
        until("ready") { first.state.value.settled }
        run { first.download(listOf(song("s1"))) }
        assertTrue("the first engine's fetch never got as far as its check", checking.await(120, TimeUnit.SECONDS))
        assertEquals(1, InFlight.count(catalogue.dir))
        // Signed out and straight back in to the same account: the old engine is released
        // (Media3 interrupts its tasks and doesn't wait for them), and a new one made on the
        // same folder and the same list of downloads.
        first.release()
        made.remove(first)
        idle()
        check = AudioCheck { _, _, _ -> Inspected("flac", true, -1) }
        val second = downloads()
        until("read") { second.state.value.settled }
        // It has the song to fetch, and doesn't begin: the old fetch still has the part file.
        repeat(200) { idle() }
        assertEquals("the new engine asked the server for it while the old fetch still had its files", 1, asked.count { it.url.queryParameter("id") == "s1" })
        assertTrue(
            "the new engine is fetching, with ${InFlight.count(catalogue.dir)} of the old one's fetches still in the folder (notes: $engineNotes)",
            second.state.value.active.none { it.state is SongState.Fetching },
        )
        // The old one lets go.
        letGo.countDown()
        until("downloaded by the new one") { catalogue.has("s1") && !second.state.value.busy }
        assertArrayEquals(files["s1"], catalogue.wholeFileOf("s1")!!.readBytes())
    }

    @Test
    fun theRoomDownloadsTakeCountsEverythingInTheirFolder() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        // What isn't a marked song and takes room all the same: a part of something on its
        // way, a cover kept with the downloads, something left lying.
        File(catalogue.dir, SongFetch.partName("zz.flac")).writeBytes(ByteArray(5_000))
        File(File(catalogue.dir, "covers").also { it.mkdirs() }, "al-1.640").writeBytes(ByteArray(7_000))
        File(catalogue.dir, "stray.bin").writeBytes(ByteArray(11_000))
        run { engine.download(listOf(song("s1"))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val all = catalogue.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        until("measured") { engine.state.value.occupied == all }
        // More than the song alone: that is what the person is told.
        assertTrue(all >= 200_000L + 23_000L)
        assertEquals(all, engine.state.value.bytes)
        assertEquals(200_000L, engine.state.value.downloaded.values.sumOf { it.bytes })
    }

    @Test
    fun whatMedia3WritesToTheSystemLogOfAFailureHasNoCauseInIt() {
        // A release build: the failure's own words and no more.
        org.medianexus.music.quietMedia3(debug = false)
        try {
            val failure = RuntimeException("The server refused it (HTTP 404).", java.io.IOException("unexpected end of stream on http://music.example.org/rest/stream?u=ada&t=0123"))
            val logged = androidx.media3.common.util.Log.getThrowableString(failure)!!
            assertEquals("The server refused it (HTTP 404).", logged)
            assertFalse(logged.contains("music.example.org"))
        } finally {
            org.medianexus.music.quietMedia3(debug = true)
        }
        // And a failure of our own making never carries the exception it came from.
        assertEquals(null, DownloadFailure("Can't reach the server.", network = true).cause)
    }

    // ---- Groups on the Downloads page, and Cancel all (offline hardening 2, item 1) ---------

    @Test
    fun removingAnAlbumFromTheDownloadsPageLeavesWhatAPlaylistStillNeeds() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2, s3) = listOf(song("s1"), song("s2"), song("s3"))
        // A playlist downloaded whole holds two songs of an album; a third song of the album
        // was downloaded by itself. The Downloads page shows the album with all three.
        run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        run { engine.download(listOf(s3), own = true) }
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        assertEquals(listOf("s1", "s2", "s3"), DownloadedViews.album(engine.state.value, "al-1")!!.songs.map { it.id })
        // The album's button on the Downloads page: Remove from downloads.
        assertEquals(Downloads.Toggled.Removed, run { engine.removeGroup("album:al-1", listOf("s1", "s2", "s3")) })
        until("s3 gone") { !catalogue.has("s3") }
        repeat(20) { idle() }
        // What the playlist needs is still there, files and all, and the playlist is still
        // downloaded whole: nothing of it was forgotten.
        assertTrue(catalogue.has("s1") && catalogue.has("s2"))
        assertTrue(catalogue.fileOf("s1")!!.isFile && catalogue.fileOf("s2")!!.isFile)
        assertEquals(listOf("playlist:p1"), engine.state.value.collections.map { it.key })
        assertEquals(setOf("s1", "s2"), Catalogue(catalogue.dir).state.value.ids)
        assertEquals(listOf("playlist:p1"), Catalogue(catalogue.dir).state.value.collections.map { it.key })
        // Asked again, there is nothing of the album's own to remove, and it says so.
        assertEquals(Downloads.Toggled.HeldElsewhere, run { engine.removeGroup("album:al-1", listOf("s1", "s2")) })
        assertTrue(catalogue.has("s1") && catalogue.has("s2"))
    }

    @Test
    fun removingAPlaylistFromTheDownloadsPageLeavesAnotherListWhole() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2, s3) = listOf(song("s1"), song("s2"), song("s3"))
        run { engine.toggle(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        run { engine.toggle(list("playlist:p2", listOf(s2, s3)), listOf(s2, s3)) }
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        assertEquals(Downloads.Toggled.Removed, run { engine.removeGroup("playlist:p1", listOf("s1", "s2")) })
        until("s1 gone") { !catalogue.has("s1") }
        repeat(20) { idle() }
        assertTrue(catalogue.has("s2") && catalogue.has("s3"))
        assertEquals(listOf("playlist:p2"), engine.state.value.collections.map { it.key })
    }

    @Test
    fun anAlbumOfSongsDownloadedByThemselvesCanBeRemovedAsAGroup() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2) = listOf(song("s1"), song("s2"))
        run { engine.download(listOf(s1, s2), own = true) }
        until("both") { engine.state.value.downloaded.size == 2 && !engine.state.value.busy }
        // No list holds them: the group is the songs' own downloads, and removing it removes
        // them (it must not answer "something else has them" for ever).
        assertEquals(Downloads.Toggled.Removed, run { engine.removeGroup("album:al-1", listOf("s1", "s2")) })
        until("gone") { engine.state.value.downloaded.isEmpty() }
        assertTrue(Catalogue(catalogue.dir).state.value.singles.isEmpty())
        // And so does the album's own button, on its page.
        run { engine.download(listOf(s1, s2), own = true) }
        until("both again") { engine.state.value.downloaded.size == 2 && !engine.state.value.busy }
        assertEquals(Downloads.Toggled.Removed, run { engine.toggle(list("album:al-1", listOf(s1, s2)), listOf(s1, s2)) })
        until("gone again") { engine.state.value.downloaded.isEmpty() }
    }

    @Test
    fun cancelAllIsNotUndoneByTheNextLookAtAList() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val songs = listOf(song("s1", size = 400_000), song("s2", size = 400_000), song("s3", size = 400_000))
        run { engine.toggle(list("playlist:p1", songs), songs) }
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        engine.cancelAll()
        until("stopped") { !engine.state.value.busy }
        slow = false
        // The playlist's page arrives from the server, and the server is found to be back:
        // both used to fetch every cancelled song again.
        run { engine.follow(list("playlist:p1", songs), songs) }
        run { engine.topUp { it to songs } }
        // (Something asked for afterwards has arrived: anything asked for before it would
        // have been asked of the server by now.)
        run { engine.download(listOf(song("s9"))) }
        until("the later one") { catalogue.has("s9") && !engine.state.value.busy }
        // No cancelled song was asked for a second time, and none arrived.
        for (song in songs) assertTrue(song.id, asked.count { it.url.queryParameter("id") == song.id } <= 1)
        assertEquals(setOf("s9"), catalogue.state.value.ids)
        // The wish to keep the list whole was withdrawn with the cancel, and stays withdrawn.
        assertTrue(engine.state.value.collections.isEmpty())
        assertTrue(Catalogue(catalogue.dir).state.value.collections.isEmpty())
    }

    @Test
    fun cancelAllLeavesWhatHadAlreadyArrived() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2) = listOf(song("s1"), song("s2", size = 400_000))
        run { engine.toggle(list("playlist:p1", listOf(s1)), listOf(s1)) }
        until("s1") { catalogue.has("s1") && !engine.state.value.busy }
        slow = true
        run { engine.follow(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        engine.cancelAll()
        until("stopped") { !engine.state.value.busy }
        repeat(20) { idle() }
        // The song that had arrived is still a download; only the one on its way went.
        assertTrue(catalogue.has("s1") && catalogue.fileOf("s1")!!.isFile)
        assertFalse(catalogue.has("s2"))
    }

    @Test
    fun aListForgottenWhileItsPageWasBeingLookedAtFetchesNothing() {
        // The page of a list downloaded whole has arrived and is being looked at (the write
        // of its new order is held), and at that moment everything is cancelled.
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val (s1, s2) = listOf(song("s1", size = 400_000), song("s2", size = 400_000))
        run { engine.toggle(list("playlist:p1", listOf(s1)), listOf(s1)) }
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        holding = true
        val following = scope.async { engine.follow(list("playlist:p1", listOf(s1, s2)), listOf(s1, s2)) }
        repeat(20) { idle() }
        engine.cancelAll()
        holding = false
        while (true) serial.execute(held.poll() ?: break)
        until("looked at") { following.isCompleted }
        // Something asked for afterwards has arrived: had the look at the page asked for the
        // list's songs, Media3 (which starts downloads in the order asked) would have asked
        // the server for them before it.
        slow = false
        run { engine.download(listOf(song("s9"))) }
        until("the later one") { catalogue.has("s9") && !engine.state.value.busy }
        assertFalse(catalogue.has("s2"))
        assertEquals(0, asked.count { it.url.queryParameter("id") == "s2" })
        assertEquals(null, catalogue.collection("playlist:p1"))
    }

    // ---- A repair with no room for it (offline hardening 2, item 3) -------------------------

    @Test
    fun aRepairThatCannotBeQueuedForWantOfRoomIsListedAsFailedNotDropped() {
        // Two downloads were marked whole; by the time the app is next started one file has
        // gone and the other is short.
        val first = downloads()
        until("ready") { first.state.value.settled }
        run { first.download(listOf(song("s1"), song("s2"))) }
        until("both") { first.state.value.downloaded.size == 2 && !first.state.value.busy }
        first.release()
        made.remove(first)
        idle()
        catalogue.fileOf("s1")!!.delete()
        catalogue.fileOf("s2")!!.let { it.writeBytes(it.readBytes().copyOf(1_000)) }
        // And the phone has no room to fetch them again.
        spare = Long.MAX_VALUE / 2
        catalogue = Catalogue(catalogue.dir)
        engineNotes.clear()
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        until("said") { engine.state.value.failed.size == 2 }
        // Neither is a download, neither is on its way, and both are shown as failed for
        // want of room: nothing says they are being fetched.
        assertTrue(engine.state.value.downloaded.isEmpty())
        assertFalse(engine.state.value.busy)
        assertEquals(setOf("Not enough room on the phone to download it again."), engine.state.value.failed.map { it.reason }.toSet())
        assertTrue("$engineNotes", engineNotes.any { "could not be queued: not enough room" in it })
        assertTrue("$engineNotes", engineNotes.none { it.endsWith("are fetched again") })
        // Room again, and Retry failed fetches them.
        spare = 0
        run { engine.retryFailed() }
        until("both again") { engine.state.value.downloaded.size == 2 && !engine.state.value.busy }
        assertTrue(engine.state.value.failed.isEmpty())
    }

    // ---- Wi-Fi only is a promise (offline hardening 2, item 5) -------------------------------

    private val mobileData = org.medianexus.music.net.NetworkNow(connected = true, unmetered = false)
    private val wifi = org.medianexus.music.net.NetworkNow(connected = true, unmetered = true)

    @Test
    fun withWifiOnlyEveryTransferGoesByTheUnmeteredNetworkWhateverTheDefaultRouteIs() {
        settings.value = Settings(downloadWifiOnly = true)
        // Wi-Fi and mobile data are both up, and the phone's default route is mobile data.
        network.value = mobileData
        val both = FakeRoutes().also { routes = it }
        both.now.value = both.network("wifi")
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 400_000), song("s2", size = 400_000), song("s3"))) }
        until("arriving") { engine.state.value.active.any { it.state is SongState.Fetching } }
        // The default route flips between them as they arrive.
        repeat(6) {
            network.value = if (it % 2 == 0) wifi else mobileData
            repeat(5) { idle() }
        }
        slow = false
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        // Every request for every song went by the Wi-Fi, and none by the default route.
        for (id in listOf("s1", "s2", "s3")) assertTrue("$id came by ${via(id)}", via(id).isNotEmpty() && via(id).all { it == "wifi" })
        // The network was asked for while there was something to fetch, and let go after.
        assertEquals(listOf(true, false), both.wanted.filterIndexed { i, on -> i == 0 || both.wanted[i - 1] != on }.takeLast(2))
    }

    @Test
    fun withWifiOnlyAndNoUnmeteredNetworkNothingIsTransferredWhateverTheDefaultRouteLooksLike() {
        settings.value = Settings(downloadWifiOnly = true)
        // The default route is reported as unmetered (as in the moment a switch is under
        // way), and the system has no unmetered network to give.
        network.value = wifi
        routes = FakeRoutes()
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1"))) }
        until("held") { engine.state.value.held == Held.WIFI }
        repeat(50) { idle() }
        Thread.sleep(200)
        idle()
        assertTrue(asked.isEmpty())
        assertEquals(SongState.Waiting, engine.state.value.stateOf("s1"))
        // One arrives: the download goes by it.
        routes!!.now.value = routes!!.network("wifi")
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertEquals(listOf<String?>("wifi"), via("s1"))
    }

    @Test
    fun aTransferInFlightStopsWhenItsNetworkGoesOrTurnsMeteredAndCarriesOnByTheNext() {
        settings.value = Settings(downloadWifiOnly = true)
        network.value = mobileData
        val both = FakeRoutes().also { routes = it }
        both.now.value = both.network("wifi")
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 2_400_000))) }
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        until("arriving") { part.length() > 100_000 }
        // The Wi-Fi goes (or the system says it has turned metered: the same word from it).
        // Mobile data is still there, and is the default route.
        both.now.value = null
        until("stopped") { engine.state.value.held == Held.WIFI && engine.state.value.active.none { it.state is SongState.Fetching } }
        until("its thread has let go") { InFlight.count(catalogue.dir) == 0 }
        val arrived = part.length()
        val requests = via("s1").size
        Thread.sleep(300)
        repeat(20) { idle() }
        // Nothing more arrives, by any route, and nothing has failed: it waits.
        assertEquals(arrived, part.length())
        assertEquals(requests, via("s1").size)
        assertTrue(engine.state.value.failed.isEmpty())
        assertTrue(arrived < 2_400_000)
        // Another unmetered network: carried on with, by that one alone.
        slow = false
        both.now.value = both.network("home")
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(files["s1"], catalogue.fileOf("s1")!!.readBytes())
        assertTrue("it came by ${via("s1")}", via("s1").take(requests).all { it == "wifi" } && via("s1").drop(requests).let { it.isNotEmpty() && it.all { v -> v == "home" } })
    }

    @Test
    fun wifiOnlySwitchedOnStopsATransferThatWasGoingByTheDefaultRoute() {
        // Wi-Fi only off, on mobile data: a download is under way by the default route.
        network.value = mobileData
        val both = FakeRoutes().also { routes = it }
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 2_400_000))) }
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        until("arriving") { part.length() > 100_000 }
        assertEquals(listOf<String?>(null), via("s1"))
        // Wi-Fi only is switched on, and there is no Wi-Fi.
        settings.value = Settings(downloadWifiOnly = true)
        until("stopped") { engine.state.value.held == Held.WIFI && engine.state.value.active.none { it.state is SongState.Fetching } }
        until("its thread has let go") { InFlight.count(catalogue.dir) == 0 }
        val arrived = part.length()
        Thread.sleep(300)
        repeat(20) { idle() }
        assertEquals(arrived, part.length())
        // Wi-Fi comes: the rest arrives by it.
        slow = false
        both.now.value = both.network("wifi")
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertTrue("it came by ${via("s1")}", via("s1").drop(1).let { it.isNotEmpty() && it.all { v -> v == "wifi" } })
    }

    @Test
    fun anUnmeteredNetworkThatDoesNotReachTheServerIsLeftAloneNotHammered() {
        settings.value = Settings(downloadWifiOnly = true)
        network.value = mobileData
        val both = FakeRoutes().also { routes = it }
        // A Wi-Fi that is up and on which the server can't be reached (a sign-in page, a
        // network the server isn't on). Mobile data would reach it; Wi-Fi only says not to.
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        both.now.value = org.medianexus.music.net.BoundRoute(
            "cafe",
            OkHttpClient.Builder().addInterceptor { attempts.incrementAndGet(); throw java.net.ConnectException("refused") }.build(),
        )
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download((1..8).map { song("s$it") }) }
        until("left alone") { engine.state.value.held == Held.WIFI && engineNotes.any { "the unmetered network didn't reach the server" in it } }
        until("its threads have let go") { InFlight.count(catalogue.dir) == 0 }
        val tried = attempts.get()
        repeat(100) { idle() }
        Thread.sleep(300)
        idle()
        // A try or two, and then none: not every song thrown at it until all have failed.
        assertTrue("it was tried $tried times", tried in 1..4)
        assertEquals(tried, attempts.get())
        assertTrue(engine.state.value.failed.isEmpty())
        assertEquals(8, engine.state.value.active.size)
        // Nothing went by the default route.
        assertTrue(asked.isEmpty())
        engine.cancelAll()
    }

    // ---- Room, as bytes arrive; and all of it given back (offline hardening 2, item 5) -------

    @Test
    fun aDownloadIsStoppedCleanlyBeforeThePhoneIsFull() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        run { engine.download(listOf(song("s1", size = 2_400_000), song("s2"), song("s3"))) }
        val part = File(catalogue.dir, SongFetch.partName("s1.flac"))
        until("arriving") { part.length() > 100_000 }
        // Something else fills the phone meanwhile: less than half the spare room is left.
        spare = Long.MAX_VALUE / 2
        until("all stopped") { engine.state.value.failed.size == 3 && !engine.state.value.busy }
        assertEquals(setOf("The phone is nearly out of space."), engine.state.value.failed.map { it.reason }.toSet())
        assertTrue(engine.state.value.downloaded.size <= 1)
        // What had arrived of it was given back.
        until("its part gone") { catalogue.dir.listFiles()!!.none { it.name.contains("s1") } }
        assertTrue("$engineNotes", engineNotes.any { "the phone is nearly out of space" in it })
        // Nothing was begun that there was no room for.
        assertEquals(0, via("s3").size)
        // Room again: Retry failed fetches them.
        spare = 0
        slow = false
        run { engine.retryFailed() }
        until("all three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
    }

    @Test
    fun removeAllGivesBackEverythingTheDownloadsOccupy() {
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val songs = listOf(song("s1"), song("s2"))
        run { engine.toggle(list("playlist:p1", songs), songs) }
        run { engine.download(listOf(song("s3")), own = true) }
        until("three") { engine.state.value.downloaded.size == 3 && !engine.state.value.busy }
        // One more on its way, part-way; a cover kept with the downloads; a part some
        // earlier download left; something left lying; a failure on the list.
        slow = true
        run { engine.download(listOf(song("s4", size = 2_400_000))) }
        until("arriving") { File(catalogue.dir, SongFetch.partName("s4.flac")).length() > 100_000 }
        File(catalogue.dir, "covers").mkdirs()
        File(catalogue.dir, "covers/al-1.640").writeBytes(ByteArray(23_000))
        File(catalogue.dir, SongFetch.partName("old.flac")).writeBytes(ByteArray(50_000))
        File(catalogue.dir, SongFetch.metaName("old.flac")).writeText("{}")
        File(catalogue.dir, "stray.bin").writeBytes(ByteArray(9_000))
        catalogue.failed(Failed(song("s5"), "The server refused it (HTTP 404)."))
        assertTrue(run { engine.removeAll() })
        // Nothing is left but the downloads' own record, which says there are none.
        val left = catalogue.dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("left: ${left.map { it.name to it.length() }}", left.all { it.name == Catalogue.SONGS_FILE || it.name == Catalogue.LOG_FILE })
        assertTrue(left.sumOf { it.length() } < 100)
        until("measured") { engine.state.value.occupied in 0..99 }
        val state = engine.state.value
        assertTrue(state.downloaded.isEmpty() && state.collections.isEmpty() && state.failed.isEmpty() && !state.busy)
        // And after the app is started again: nothing comes back, nothing is fetched.
        val again = Catalogue(catalogue.dir)
        assertTrue(again.state.value.ids.isEmpty() && again.state.value.collections.isEmpty() && again.wanting.isEmpty())
    }

    // ---- What the screens' grouping is made from (offline hardening 2, item 7) -----------------

    @Test
    fun aProgressTickHandsOnTheVeryListsOfWhatIsDownloaded() {
        slow = true
        val engine = downloads()
        until("ready") { engine.state.value.settled }
        val songs = listOf(song("s1"), song("s2", size = 2_400_000))
        run { engine.toggle(list("playlist:p1", songs), songs) }
        until("one downloaded, one arriving") { engine.state.value.downloaded.size == 1 && engine.state.value.active.any { it.state is SongState.Fetching } }
        val first = engine.state.value
        // As the song arrives the state is published again and again with how far it has got.
        engine.refresh()
        idle()
        until("further on") { engine.state.value !== first && engine.state.value.active != first.active }
        val later = engine.state.value
        // What is downloaded, and the lists, are the same objects: a page that makes its
        // grouping from them has nothing to make again.
        org.junit.Assert.assertSame(first.downloaded, later.downloaded)
        org.junit.Assert.assertSame(first.collections, later.collections)
        engine.cancelAll()
    }
}
