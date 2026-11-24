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
 * "Done" means it plays to the end (offline hardening 2, item 2): on Media3's real download
 * manager and extractors, real audio (made by ffmpeg, in `resources/audio`), and the system's
 * own list of decoders as Robolectric stands in for it.
 *
 * Every test here fails on the code as it was reviewed (version 0.2.0): the check stopped
 * once it had a track and a length, so a file with good headers and damaged sound was marked
 * Done, as was a conversion that stopped part-way when the library gave the song no length,
 * and a file nothing on the phone decodes; an original with no size from the library and no
 * length from the server was refused; a download that failed when played was only passed
 * over until the app was next started, and went on being shown as downloaded; and nothing
 * marked by an earlier build was ever looked at again.
 */
@RunWith(RobolectricTestRunner::class)
class PlaysToTheEndTest {
    @get:Rule val folder = TemporaryFolder()

    /** A phone's decoders: none for ALAC. */
    @get:Rule val decoders = phoneDecoders()
    private val server = GatedServer()
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = MutableStateFlow(Settings(downloadWifiOnly = false))
    private val reach = MutableStateFlow(Reach())
    private val made = mutableListOf<Downloads>()
    private val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val five = GatedServer.fixture("five-seconds.flac")
    private val twenty = GatedServer.fixture("twenty-seconds.mp3")
    private val alac = GatedServer.fixture("three-seconds-alac.m4a")

    /** What the server has under each song id: the file as it is, and its conversion. */
    private val originals = HashMap<String, ByteArray>()
    private val conversions = HashMap<String, ByteArray>()

    /** The songs as the server's library has them (`getSong`). */
    private val library = java.util.concurrent.ConcurrentHashMap<String, Song>()

    /** Sent with no length (as a server that streams a file through something does). */
    @Volatile private var unsized = setOf<String>()

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        database = StandaloneDatabaseProvider(context)
        dir = folder.newFolder("downloads")
        server.handler = { request ->
            val id = request.query("id").orEmpty()
            val converted = request.query("format") == "mp3"
            val bytes = if (converted) conversions[id] else originals[id]
            when {
                // The library's own word on a song, asked when a download disagrees with it.
                "getSong" in request.path && library.containsKey(id) -> library[id]!!.let { GatedServer.song(id, it.suffix, it.size, it.duration) }
                "stream" !in request.path || bytes == null -> GatedServer.Answer(404, mapOf("Content-Type" to "application/json"), "{}".toByteArray())
                converted -> GatedServer.conversion(bytes)
                id in unsized -> GatedServer.Answer(200, mapOf("Content-Type" to "audio/flac"), bytes, chunked = true)
                else -> GatedServer.file(request, bytes, "Mon, 23 Nov 2026 07:57:08 GMT")
            }
        }
    }

    @After
    fun stop() {
        made.forEach { it.release() }
        idle()
        scope.cancel()
        server.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** What the server has been asked so far (a copy: it is added to from its own threads). */
    private val asked: List<GatedServer.Asked> get() = synchronized(server.asked) { server.asked.toList() }

    private fun until(what: String, ok: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            idle()
            if (ok()) return
            if (System.nanoTime() > deadline) fail("never: $what (${made.lastOrNull()?.state?.value}; $notes)")
            Thread.yield()
        }
    }

    private fun <T> run(block: suspend () -> T): T {
        val result = scope.async { block() }
        until("an answer") { result.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return result.getCompleted()
    }

    /** The app started on the downloads folder as it is: the real check unless a test gives
     *  its own. */
    private fun appStarted(check: AudioCheck = Media3AudioCheck.system): Pair<Catalogue, Downloads> {
        val catalogue = Catalogue(dir)
        val api = Subsonic(server.url("/"), "tester", "pass", OkHttpClient())
        val covers = CoverStore(folder.newFolder(), scope, Dispatchers.IO, fetch = { _, _ -> throw ApiError.Network("none") })
        val downloads = Downloads(context, catalogue, api, OkHttpClient(), database, "acct", settings, reach, covers, { _, _ -> }, scope, spare = { 0 }, note = { notes += it }, check = check)
        made += downloads
        idle()
        until("ready") { downloads.state.value.settled }
        return catalogue to downloads
    }

    private fun appStopped(downloads: Downloads) {
        downloads.release()
        made.remove(downloads)
        idle()
    }

    private fun flac(id: String, bytes: ByteArray, seconds: Int = 5, size: Long = bytes.size.toLong()): Song {
        originals[id] = bytes
        return Song(id = id, title = id.uppercase(), suffix = "flac", size = size, duration = seconds).also { library[id] = it }
    }

    /** The five-second FLAC with bytes changed inside one frame's sound: same headers, same
     *  length. */
    private fun hurt(): ByteArray = five.copyOf().also { for (i in it.size / 2 until it.size / 2 + 64) it[i] = (it[i] + 1).toByte() }

    // ---- At download ------------------------------------------------------------------------

    @Test
    fun aFileWithGoodHeadersAndDamagedSoundIsNeverMarkedDone() {
        val (catalogue, engine) = appStarted()
        // The length the library gives, the length the server states, the container, the
        // running time its header claims: all right. The sound is not.
        val song = flac("s1", hurt())
        run { engine.download(listOf(song)) }
        until("it failed") { engine.state.value.failed.isNotEmpty() && !engine.state.value.busy }
        assertFalse(catalogue.has("s1"))
        assertEquals("What arrived is damaged: it can't be read through to its end.", engine.state.value.failed.single().reason)
        // Fetched afresh three times in all, in case it was the journey that did it.
        assertEquals(Downloads.VERIFY_TRIES, asked.count { "stream" in it.path })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".flac") || it.name.endsWith(".part") })
        // The whole file, then, is a download.
        originals["s1"] = five
        run { engine.download(listOf(song)) }
        until("downloaded") { catalogue.has("s1") }
        assertEquals(Media3AudioCheck.VERSION, catalogue.entry("s1")!!.checked)
    }

    @Test
    fun aConversionThatStoppedPartWayIsRefusedEvenWhenTheLibraryHasNoLengthForIt() {
        val (catalogue, engine) = appStarted()
        // The library gives it no length; the conversion stops inside a frame, and the
        // server ends the answer as if that were all.
        conversions["s2"] = twenty.copyOf(twenty.size * 6 / 10 + 37)
        val song = Song(id = "s2", title = "Two", suffix = "ape", size = 900_000, duration = 0, bitRate = 16)
        run { engine.download(listOf(song)) }
        until("it failed") { engine.state.value.failed.isNotEmpty() && !engine.state.value.busy }
        assertFalse(catalogue.has("s2"))
        assertEquals("The download was cut short.", engine.state.value.failed.single().reason)
        // Whole, it is taken: read to a clean end is the proof, with no length to hold it to.
        conversions["s2"] = twenty
        run { engine.download(listOf(song)) }
        until("downloaded") { catalogue.has("s2") }
    }

    @Test
    fun anOriginalWithNoSizeFromTheLibraryAndNoLengthFromTheServerIsTakenOnBeingReadThrough() {
        val (catalogue, engine) = appStarted()
        unsized = setOf("s1")
        // (It was refused: "The server sent a different file from the one in its library.")
        val song = flac("s1", five, size = 0)
        run { engine.download(listOf(song)) }
        until("settled") { !engine.state.value.busy && (catalogue.has("s1") || engine.state.value.failed.isNotEmpty()) }
        assertTrue("${engine.state.value.failed}", catalogue.has("s1"))
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        // But not one that stops before its own header says it does.
        originals["s3"] = five.copyOf(five.size * 4 / 5)
        unsized = setOf("s1", "s3")
        run { engine.download(listOf(Song(id = "s3", title = "Three", suffix = "flac", size = 0, duration = 5).also { library["s3"] = it })) }
        until("it failed") { engine.state.value.failed.isNotEmpty() && !engine.state.value.busy }
        assertFalse(catalogue.has("s3"))
    }

    @Test
    fun aFileThePhoneHasNoDecoderForIsDownloadedConvertedInstead() {
        val (catalogue, engine) = appStarted()
        // An ALAC in an .m4a: the right size, a container the phone reads, three seconds of
        // sound in it, and nothing on the phone that decodes it.
        originals["s4"] = alac
        conversions["s4"] = twenty
        val song = Song(id = "s4", title = "Four", suffix = "m4a", size = alac.size.toLong(), duration = 20, bitRate = 16)
        run { engine.download(listOf(song)) }
        until("settled") { !engine.state.value.busy && (catalogue.has("s4") || engine.state.value.failed.isNotEmpty()) }
        assertTrue("${engine.state.value.failed}", catalogue.has("s4"))
        // What is marked Done is the server's conversion, which the phone plays.
        val entry = catalogue.entry("s4")!!
        assertEquals("mp3", entry.suffix)
        assertEquals(320, entry.bitrate)
        assertArrayEquals(twenty, File(dir, entry.file).readBytes())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".m4a") || it.name.endsWith(".part") })
        // The file as it is was asked for once, and never again for this song.
        assertEquals(1, asked.count { "stream" in it.path && it.query("format") == "raw" })
        engine.remove(listOf("s4"))
        until("removed") { !catalogue.has("s4") }
        run { engine.download(listOf(song)) }
        until("again") { catalogue.has("s4") }
        assertEquals(1, asked.count { "stream" in it.path && it.query("format") == "raw" })
    }

    // ---- Afterwards -------------------------------------------------------------------------

    @Test
    fun aDownloadThatFailsWhenPlayedStopsBeingADownloadAndIsFetchedAgain() {
        val (catalogue, engine) = appStarted()
        val song = flac("s1", five)
        run { engine.toggle(DownloadedList("playlist:p1", "Mix", songs = listOf("s1")), listOf(song)) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        // The server goes out of reach, and the file turns out not to play (the player met
        // an error that is the file's).
        reach.value = Reach(unreachable = true)
        idle()
        engine.demote("s1", was.file, was.modified, "wouldn't play")
        until("unmarked") { !catalogue.has("s1") }
        // It is no longer shown as downloaded: it is on its way again, waiting for the server.
        until("waiting") { engine.state.value.active.any { it.song.id == "s1" } }
        assertEquals(SongState.Waiting, engine.state.value.stateOf("s1"))
        assertEquals(Held.SERVER, engine.state.value.held)
        assertFalse(File(dir, was.file).exists())
        // The list it belongs to is still downloaded whole: it wants the song back.
        assertEquals(listOf("playlist:p1"), engine.state.value.collections.map { it.key })
        val before = asked.count { "stream" in it.path }
        repeat(20) { idle() }
        assertEquals(before, asked.count { "stream" in it.path })
        // The server is back: fetched again, with nobody asking.
        reach.value = Reach()
        until("downloaded again") { catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        // The fresh copy failing the same way is not fetched over and over: it is listed as
        // failed, with the reason.
        val fresh = catalogue.entry("s1")!!
        engine.demote("s1", fresh.file, fresh.modified, "wouldn't play")
        until("listed as failed") { engine.state.value.failed.any { it.song.id == "s1" } }
        assertFalse(catalogue.has("s1"))
        assertFalse(engine.state.value.busy)
        assertEquals("It was downloaded again and still wouldn't play.", engine.state.value.failed.single().reason)
    }

    @Test
    fun onlyTheFileThatFailedIsDemoted() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        // The failure was of another file under that id (one since replaced).
        engine.demote("s1", was.file, was.modified + 5_000, "wouldn't play")
        engine.demote("s1", "other.mp3", was.modified, "wouldn't play")
        repeat(30) { idle() }
        run { engine.download(emptyList()) }
        assertTrue(catalogue.has("s1"))
    }

    @Test
    fun aDownloadTheDecoderStoppedOnIsReadThroughAndDemotedIfItIsTheFile() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five), flac("s2", five))) }
        until("downloaded") { catalogue.has("s1") && catalogue.has("s2") && !engine.state.value.busy }
        // One of the files is damaged where it lies (same length, bytes changed in a frame).
        catalogue.fileOf("s1")!!.writeBytes(hurt())
        val forTheGoodOne = asked.count { it.query("id") == "s2" }
        // The decoder stopped on each: neither is blamed for that alone; each is read through.
        engine.verifyNow("s1")
        engine.verifyNow("s2")
        until("the damaged one fetched again") { asked.count { it.query("id") == "s1" } == 2 && catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        // The good one was left alone.
        assertEquals(forTheGoodOne, asked.count { it.query("id") == "s2" })
        assertTrue(catalogue.has("s2"))
    }

    // ---- What an earlier build marked ---------------------------------------------------------

    /** A download as version 0.2.0 marked it: in place, in the catalogue, never read through. */
    private fun markedByAnEarlierBuild(catalogue: Catalogue, song: Song, bytes: ByteArray, suffix: String = song.suffix, bitrate: Int = 0) {
        val name = "${song.id}.$suffix"
        val file = File(dir, name).also { it.writeBytes(bytes) }
        catalogue.add(Downloaded(song, name, bytes.size.toLong(), 1_764_000_000, suffix, bitrate, file.lastModified()))
    }

    @Test
    fun whatAnEarlierBuildMarkedDoneIsReadThroughOnceAndWhatIsNotWholeIsFetchedAgain() {
        Catalogue(dir).let { old ->
            // A whole file; one with good headers and damaged sound; a conversion that had
            // stopped at three fifths of a song the library gave no length for; and a file
            // the phone has no decoder for.
            markedByAnEarlierBuild(old, flac("good", five), five)
            markedByAnEarlierBuild(old, flac("hurt", five), hurt())
            conversions["short"] = twenty
            markedByAnEarlierBuild(old, Song(id = "short", title = "Short", suffix = "ape", size = 900_000, duration = 0, bitRate = 16), twenty.copyOf(twenty.size * 6 / 10 + 37), "mp3", 160)
            originals["alac"] = alac
            conversions["alac"] = twenty
            markedByAnEarlierBuild(old, Song(id = "alac", title = "Alac", suffix = "m4a", size = alac.size.toLong(), duration = 20), alac)
            assertEquals(0, old.entry("good")!!.checked)
        }
        // The app is updated and started. Each is read through, in the background.
        val looks = java.util.concurrent.atomic.AtomicInteger()
        val counting = AudioCheck { file, suffix, stop -> looks.incrementAndGet(); Media3AudioCheck.system.inspect(file, suffix, stop) }
        val (catalogue, engine) = appStarted(counting)
        until("all four whole") {
            !engine.state.value.busy && listOf("good", "hurt", "short", "alac").all { catalogue.has(it) && catalogue.entries().first { e -> e.song.id == it }.checked == Media3AudioCheck.VERSION }
        }
        until("said") { notes.any { it.startsWith("downloads: 4 marked whole by an earlier version were read through to the end; 3 were not whole") } }
        assertTrue(engine.state.value.failed.isEmpty())
        // The whole one was left where it was; the others are the server's files now.
        assertEquals(0, asked.count { it.query("id") == "good" })
        assertArrayEquals(five, catalogue.fileOf("hurt")!!.readBytes())
        assertArrayEquals(twenty, catalogue.fileOf("short")!!.readBytes())
        assertEquals("mp3", catalogue.entries().first { it.song.id == "alac" }.suffix)
        // Started again: nothing is read through a second time.
        appStopped(engine)
        val seen = looks.get()
        val (again, _) = appStarted(counting)
        repeat(50) { idle() }
        Thread.sleep(200)
        idle()
        assertEquals(seen, looks.get())
        assertEquals(setOf("good", "hurt", "short", "alac"), again.state.value.ids)
    }

    @Test
    fun whatAnEarlierBuildMarkedIsLeftAsItIsWhileTheServerCannotBeAsked() {
        Catalogue(dir).let { old -> markedByAnEarlierBuild(old, flac("hurt", five), hurt()) }
        // In a dead spot: taking it away now could not be made good, and it is no worse
        // than it was.
        reach.value = Reach(unreachable = true)
        val (catalogue, engine) = appStarted()
        repeat(50) { idle() }
        Thread.sleep(200)
        idle()
        assertTrue(catalogue.has("hurt"))
        reach.value = Reach()
        until("fetched again") { asked.any { it.query("id") == "hurt" } && catalogue.has("hurt") && !engine.state.value.busy }
        assertArrayEquals(five, catalogue.fileOf("hurt")!!.readBytes())
    }
}
