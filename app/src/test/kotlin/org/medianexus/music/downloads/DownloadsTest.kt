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
        catalogue = Catalogue(folder.newFolder("downloads"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request
                val id = request.url.queryParameter("id").orEmpty()
                val bytes = files[id]
                if (bytes == null || id in missing) return MockResponse.Builder().code(404).setHeader("Content-Type", "application/json").body("{}").build()
                val from = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val builder = MockResponse.Builder().setHeader("Content-Type", "audio/flac").setHeader("Accept-Ranges", "bytes")
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

    private fun downloads(): Downloads {
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient())
        val covers = CoverStore(folder.newFolder(), scope, Dispatchers.IO, fetch = { _, _ -> throw ApiError.Network("none") })
        return Downloads(context, catalogue, api, OkHttpClient(), database, "acct", settings, reach, covers, { busy, wifi -> kept += busy to wifi }, scope, spare = { spare })
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
        assertFalse(File(catalogue.dir, Catalogue.SONGS_FILE).readText().contains("s2"))
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
    }
}
