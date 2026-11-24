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
 * What "Done" requires (offline hardening 3, item 2): on Media3's real download manager and
 * extractors, real audio (made by ffmpeg, in `resources/audio`), and the system's own list
 * of decoders as Robolectric stands in for it.
 *
 * The rule: a file as it is, of the length the server stated and the size the library gives,
 * is the server's file, complete, and only its beginning is looked at; a conversion, or a
 * file the library gives no size for, is read through to its end. The second round read
 * everything through (and again after it played, and once more for old downloads), which
 * cost minutes on a first download of lossless albums and refused good files; and it deleted
 * a download that failed when played before a new copy had arrived.
 */
@RunWith(RobolectricTestRunner::class)
class WhatDoneRequiresTest {
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

    /** The phone's check, counting each look at a file's beginning and each reading through. */
    private val begun = java.util.concurrent.atomic.AtomicInteger()
    private val readThrough = java.util.concurrent.atomic.AtomicInteger()
    private val counting: AudioCheck by lazy {
        val real = phoneCheck()
        object : AudioCheck {
            override fun inspect(file: File, suffix: String, stop: () -> Boolean): Inspected = real.inspect(file, suffix, stop).also { readThrough.incrementAndGet() }
            override fun begins(file: File, suffix: String): Inspected = real.begins(file, suffix).also { begun.incrementAndGet() }
        }
    }

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
    private fun appStarted(check: AudioCheck = counting): Pair<Catalogue, Downloads> {
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

    private fun streams(id: String) = asked.count { "stream" in it.path && it.query("id") == id }

    private fun settled(engine: Downloads, catalogue: Catalogue, id: String) =
        until("$id settled") { !engine.state.value.busy && (catalogue.has(id) || engine.state.value.failed.any { it.song.id == id }) }

    // ---- The file as it is, of the size stated and the size the library gives ----------------

    @Test
    fun anOriginalOfTheStatedSizeAndTheLibrarysIsDoneOnALookAtItsBeginning() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        // Its beginning was looked at, once. It was not read through.
        assertEquals(1, begun.get())
        assertEquals(0, readThrough.get())
        assertEquals(1, streams("s1"))
        // Nor is it when the app is next started, or ever again.
        appStopped(engine)
        val (again, later) = appStarted()
        repeat(30) { idle() }
        run { later.download(emptyList()) }
        assertTrue(again.has("s1"))
        assertEquals(1, begun.get())
        assertEquals(0, readThrough.get())
    }

    @Test
    fun whatWasRefusedWronglyNowDownloads() {
        val (catalogue, engine) = appStarted()
        // A large tag after the sound (more than Media3's MP3 reader will search past), on a
        // song the library gives no running time for: the reader stops looking at the tag,
        // which was taken for sound that would never be played ("What arrived is damaged").
        val tagged = twenty + ByteArray(160 * 1024) { (it * 31 + 7).toByte() }
        originals["tagged"] = tagged
        // A file in another container than its name says: an MP3 the library calls .m4a. The
        // player reads a file by what it is ("What the server sent isn't a song…").
        originals["named"] = twenty
        val songs = listOf(
            Song(id = "tagged", title = "Tagged", suffix = "mp3", size = tagged.size.toLong(), duration = 0, bitRate = 128),
            Song(id = "named", title = "Named", suffix = "m4a", size = twenty.size.toLong(), duration = 20, bitRate = 128),
        ).onEach { library[it.id] = it }
        run { engine.download(songs) }
        until("settled") { !engine.state.value.busy && songs.all { catalogue.has(it.id) || engine.state.value.failed.any { f -> f.song.id == it.id } } }
        assertTrue("${engine.state.value.failed}", engine.state.value.failed.isEmpty())
        assertArrayEquals(tagged, catalogue.fileOf("tagged")!!.readBytes())
        assertArrayEquals(twenty, catalogue.fileOf("named")!!.readBytes())
        assertEquals(0, readThrough.get())
    }

    @Test
    fun aFileOfTheRightSizeWithDamageInsideItsSoundIsTheServersFile() {
        // What the second round refused, and this one takes on purpose: the size the library
        // gives and the length the server stated say it is the server's file, complete.
        // Bytes wrong inside a frame are then the server's own, and the player plays past them.
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", hurt()))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        assertEquals("", catalogue.entry("s1")!!.flaw)
    }

    @Test
    fun anErrorPageSavedAsASongIsStillRefused() {
        val (catalogue, engine) = appStarted()
        // Sent as audio, and even the size the library gives the song: not a song.
        val page = "<html><body><h1>502 Bad Gateway</h1></body></html>".repeat(300).toByteArray()
        run { engine.download(listOf(flac("s1", page))) }
        settled(engine, catalogue, "s1")
        assertFalse(catalogue.has("s1"))
        assertEquals("What the server sent isn't a song the phone can play.", engine.state.value.failed.single().reason)
        // (The same file would come again: asked for once.)
        assertEquals(1, streams("s1"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".flac") || it.name.endsWith(".part") })
        // And one that is not the size the library gives is not that song.
        originals["s2"] = page
        run { engine.download(listOf(Song(id = "s2", title = "Two", suffix = "flac", size = five.size.toLong(), duration = 5).also { library["s2"] = it })) }
        settled(engine, catalogue, "s2")
        assertFalse(catalogue.has("s2"))
    }

    // ---- A conversion, or a file the library gives no size for: read through ------------------

    @Test
    fun aConversionIsReadThroughAndOneCutShortIsStillRefused() {
        val (catalogue, engine) = appStarted()
        // The library gives the song no length; the conversion stops inside a frame, at
        // another place each time (the journey's doing), and the server ends the answer as
        // if that were all.
        val cuts = java.util.concurrent.atomic.AtomicInteger()
        val song = Song(id = "s2", title = "Two", suffix = "ape", size = 900_000, duration = 0, bitRate = 16)
        server.handler = { request ->
            if ("stream" in request.path) GatedServer.conversion(twenty.copyOf(twenty.size * 6 / 10 + 37 + 4_001 * cuts.incrementAndGet()))
            else GatedServer.Answer(404, mapOf("Content-Type" to "application/json"), "{}".toByteArray())
        }
        run { engine.download(listOf(song)) }
        settled(engine, catalogue, "s2")
        assertFalse(catalogue.has("s2"))
        assertEquals("The download was cut short.", engine.state.value.failed.single().reason)
        assertEquals(Downloads.VERIFY_TRIES, cuts.get())
        assertEquals(Downloads.VERIFY_TRIES, readThrough.get())
        // Whole, it is taken: read to a clean end is the proof, with no length to hold it to.
        server.handler = { request -> if ("stream" in request.path) GatedServer.conversion(twenty) else GatedServer.Answer(404, emptyMap(), ByteArray(0)) }
        run { engine.download(listOf(song)) }
        until("downloaded") { catalogue.has("s2") && !engine.state.value.busy }
        assertEquals("", catalogue.entry("s2")!!.flaw)
    }

    @Test
    fun anOriginalWithNoSizeFromTheLibraryIsReadThrough() {
        val (catalogue, engine) = appStarted()
        unsized = setOf("s1")
        run { engine.download(listOf(flac("s1", five, size = 0))) }
        settled(engine, catalogue, "s1")
        assertTrue("${engine.state.value.failed}", catalogue.has("s1"))
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        assertEquals(1, readThrough.get())
        assertEquals(0, begun.get())
    }

    @Test
    fun theSameBytesTwiceFailingTheSameWayAreTheServersOwnFlaw() {
        val (catalogue, engine) = appStarted()
        // The library gives the song no size, so it is read through; a frame inside it does
        // not match its own checksum. Fetched again, the very same bytes come.
        unsized = setOf("s1")
        val flawed = hurt()
        run { engine.download(listOf(flac("s1", flawed, size = 0))) }
        settled(engine, catalogue, "s1")
        // It is the server's copy that has the flaw: kept, marked downloaded, and said.
        assertTrue("${engine.state.value.failed}", catalogue.has("s1"))
        assertArrayEquals(flawed, catalogue.fileOf("s1")!!.readBytes())
        assertEquals("What arrived is damaged: it can't be read through to its end.", catalogue.entry("s1")!!.flaw)
        assertEquals(2, streams("s1"))
        assertEquals(1, notes.count { "the server's own copy has it" in it })
        assertEquals(SongState.Done, engine.state.value.stateOf("s1"))
        // And it is still so when the app is next started.
        appStopped(engine)
        assertEquals("What arrived is damaged: it can't be read through to its end.", appStarted().first.entry("s1")!!.flaw)
    }

    @Test
    fun aDownloadThatFailsDifferentlyEachTimeIsRefused() {
        val (catalogue, engine) = appStarted()
        // Damaged somewhere else each time: the journey's doing, not the server's copy.
        val tries = java.util.concurrent.atomic.AtomicInteger()
        val song = Song(id = "s1", title = "One", suffix = "flac", size = 0, duration = 5).also { library["s1"] = it }
        server.handler = { request ->
            when {
                "getSong" in request.path -> GatedServer.song("s1", "flac", 0, 5)
                else -> {
                    val at = five.size / 3 + 5_000 * tries.incrementAndGet()
                    GatedServer.Answer(200, mapOf("Content-Type" to "audio/flac"), five.copyOf().also { for (i in at until at + 64) it[i] = (it[i] + 1).toByte() }, chunked = true)
                }
            }
        }
        run { engine.download(listOf(song)) }
        settled(engine, catalogue, "s1")
        assertFalse(catalogue.has("s1"))
        assertEquals(Downloads.VERIFY_TRIES, tries.get())
        assertEquals("What arrived is damaged: it can't be read through to its end.", engine.state.value.failed.single().reason)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".flac") || it.name.endsWith(".part") })
    }

    // ---- What the phone can decode --------------------------------------------------------------

    @Test
    fun aFileThePhoneCannotDecodeIsDownloadedConvertedAndNeverAsItIsAgain() {
        val (catalogue, engine) = appStarted()
        // An ALAC in an .m4a: the right size, a container the phone reads, three seconds of
        // sound in it, and nothing on the phone that decodes it.
        originals["s4"] = alac
        conversions["s4"] = twenty
        val song = Song(id = "s4", title = "Four", suffix = "m4a", size = alac.size.toLong(), duration = 20, bitRate = 16)
        run { engine.download(listOf(song)) }
        settled(engine, catalogue, "s4")
        assertTrue("${engine.state.value.failed}", catalogue.has("s4"))
        // What is marked Done is the server's conversion, which the phone plays.
        val entry = catalogue.entry("s4")!!
        assertEquals("mp3", entry.suffix)
        assertEquals(320, entry.bitrate)
        assertArrayEquals(twenty, File(dir, entry.file).readBytes())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".m4a") || it.name.endsWith(".part") })
        // The file as it is was asked for once, and never again for this song: not after it
        // is removed and downloaded again, and not after the app is started again.
        fun raw() = asked.count { "stream" in it.path && it.query("format") == "raw" }
        assertEquals(1, raw())
        engine.remove(listOf("s4"))
        until("removed") { !catalogue.has("s4") }
        appStopped(engine)
        val (again, later) = appStarted()
        assertTrue(again.convertsFor(song))
        run { later.download(listOf(song)) }
        until("again") { again.has("s4") && !later.state.value.busy }
        assertEquals("mp3", again.entry("s4")!!.suffix)
        assertEquals(1, raw())
    }

    @Test
    fun whenThePhoneCannotBeAskedTheFileIsConvertedNotMarkedOnAGuess() {
        // The question itself fails (the system's list of decoders can't be read): that is
        // not a yes. (It used to be: the file was marked Done unasked.)
        val (catalogue, engine) = appStarted(Media3AudioCheck { format -> if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_FLAC) throw IllegalStateException("the decoders could not be listed") else true })
        conversions["s1"] = twenty
        run { engine.download(listOf(flac("s1", five, seconds = 20))) }
        settled(engine, catalogue, "s1")
        assertTrue("${engine.state.value.failed}", catalogue.has("s1"))
        assertEquals("mp3", catalogue.entry("s1")!!.suffix)
    }

    // ---- A download that fails when it is played ----------------------------------------------

    @Test
    fun aDownloadThatFailsWhenPlayedIsKeptUntilItsFreshCopyHasTakenItsPlace() {
        val (catalogue, engine) = appStarted()
        val song = flac("s1", five)
        run { engine.toggle(DownloadedList("playlist:p1", "Mix", songs = listOf("s1")), listOf(song)) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        // The server goes out of reach, and the file fails when played (the player met an
        // error that is the file's).
        reach.value = Reach(unreachable = true)
        idle()
        assertTrue(run { engine.demote("s1", was.file, was.modified, "wouldn't play") })
        // **Nothing is deleted.** It is still a download and still on the phone, to be
        // played with no server; it is marked as needing a fresh copy, on the disk, and is
        // shown as on its way again, waiting for the server.
        assertTrue(catalogue.has("s1"))
        assertTrue(catalogue.entry("s1")!!.stale)
        assertArrayEquals(five, File(dir, was.file).readBytes())
        assertTrue(Catalogue(dir).entry("s1")!!.stale)
        until("waiting") { engine.state.value.active.any { it.song.id == "s1" } }
        assertEquals(SongState.Waiting, engine.state.value.stateOf("s1"))
        assertEquals(Held.SERVER, engine.state.value.held)
        assertEquals(listOf("playlist:p1"), engine.state.value.collections.map { it.key })
        // It failing again where it lies, meanwhile, changes nothing.
        assertTrue(run { engine.demote("s1", was.file, was.modified, "wouldn't play") })
        val before = streams("s1")
        repeat(20) { idle() }
        assertEquals(before, streams("s1"))
        assertTrue(engine.state.value.failed.isEmpty())
        // The server is back: fetched again with nobody asking, and the new copy takes the
        // old one's place.
        reach.value = Reach()
        until("replaced") { catalogue.entry("s1")?.stale == false && !engine.state.value.busy }
        assertEquals(before + 1, streams("s1"))
        assertEquals(SongState.Done, engine.state.value.stateOf("s1"))
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        // The fresh copy failing too is not fetched over and over: it is listed as failed,
        // with the reason, and is still on the phone.
        val fresh = catalogue.entry("s1")!!
        assertTrue(run { engine.demote("s1", fresh.file, fresh.modified, "wouldn't play") })
        until("listed as failed") { engine.state.value.failed.any { it.song.id == "s1" } }
        assertFalse(engine.state.value.busy)
        assertEquals("It was downloaded again and still wouldn't play.", engine.state.value.failed.single().reason)
        assertTrue(catalogue.has("s1") && File(dir, fresh.file).isFile)
        assertEquals(before + 1, streams("s1"))
    }

    @Test
    fun cancellingTheFetchOfAFreshCopyLeavesTheCopyThatIsThere() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five)), own = true) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        reach.value = Reach(unreachable = true)
        idle()
        run { engine.demote("s1", was.file, was.modified, "wouldn't play") }
        until("waiting") { engine.state.value.active.any { it.song.id == "s1" } }
        engine.cancelAll()
        until("cancelled") { !engine.state.value.busy }
        run { engine.download(emptyList()) }
        // (Media3's "remove" for the fetch used to delete whatever the catalogue had.)
        assertTrue(catalogue.has("s1"))
        assertArrayEquals(five, File(dir, was.file).readBytes())
        assertTrue(engine.state.value.stateOf("s1") is SongState.Failed)
    }

    @Test
    fun onlyTheFileThatFailedNeedsAFreshCopy() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five))) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        // The failure was of another file under that id (one since replaced).
        assertFalse(run { engine.demote("s1", was.file, was.modified + 5_000, "wouldn't play") })
        assertFalse(run { engine.demote("s1", "other.mp3", was.modified, "wouldn't play") })
        run { engine.download(emptyList()) }
        assertFalse(catalogue.entry("s1")!!.stale)
        assertEquals(1, streams("s1"))
    }

    @Test
    fun aDownloadThePlayerFindsBeyondThePhoneIsFetchedConvertedAndNeverAsItIsAgain() {
        val (catalogue, engine) = appStarted()
        conversions["s1"] = twenty
        val song = flac("s1", five, seconds = 20)
        run { engine.download(listOf(song)) }
        until("downloaded") { catalogue.has("s1") && !engine.state.value.busy }
        val was = catalogue.entry("s1")!!
        assertEquals(0, was.bitrate)
        fun raw() = asked.count { "stream" in it.path && it.query("id") == "s1" && it.query("format") == "raw" }
        assertEquals(1, raw())
        // Played, the phone's decoder turns out not to take it (more channels, a higher rate
        // or a profile it doesn't have): the player says the format exceeds what it can do.
        assertTrue(run { engine.demote("s1", was.file, was.modified, "wouldn't play", beyondThePhone = true) })
        // The same file again would fail the same way: what is fetched is the conversion,
        // which takes the original's place (and the original's file goes only then).
        until("converted") { catalogue.entry("s1")?.let { !it.stale && it.bitrate == 320 } == true && !engine.state.value.busy }
        assertArrayEquals(twenty, catalogue.fileOf("s1")!!.readBytes())
        assertFalse(File(dir, was.file).exists())
        assertEquals(1, raw())
        // Remembered on the disk: removed, the app started again, downloaded again, it is
        // the conversion that is asked for.
        engine.remove(listOf("s1"))
        until("removed") { !catalogue.has("s1") }
        appStopped(engine)
        val (again, later) = appStarted()
        run { later.download(listOf(song)) }
        until("again") { again.has("s1") && !later.state.value.busy }
        assertEquals(320, again.entry("s1")!!.bitrate)
        assertEquals(1, raw())
    }

    @Test
    fun aFileNoLongerTheLengthRecordedIsNoDownloadInTheRunAsAtTheStartOfOne() {
        val (catalogue, engine) = appStarted()
        run { engine.download(listOf(flac("s1", five), flac("s2", five))) }
        until("downloaded") { catalogue.has("s1") && catalogue.has("s2") && !engine.state.value.busy }
        // Something has cut one of the files short where it lies. The player finds it so as
        // it opens the song, does not open it, and says so.
        val file = catalogue.fileOf("s1")!!
        file.writeBytes(five.copyOf(five.size / 2))
        assertEquals(null, catalogue.wholeFileOf("s1"))
        engine.notWhole("s1")
        // The catalogue agrees at once (it used to go on saying "downloaded" until the app
        // was next started): unmarked, and fetched again.
        until("unmarked or fetched again") { streams("s1") == 2 }
        until("downloaded again") { catalogue.wholeFileOf("s1") != null && !engine.state.value.busy }
        assertArrayEquals(five, catalogue.fileOf("s1")!!.readBytes())
        assertEquals(1, notes.count { "its file is not the length recorded" in it })
        // Said of a file that is the length recorded, nothing happens.
        engine.notWhole("s2")
        run { engine.download(emptyList()) }
        assertEquals(1, streams("s2"))
        assertTrue(catalogue.has("s2"))
    }

    // ---- What is no longer done ---------------------------------------------------------------

    @Test
    fun nothingDownloadedIsReadAgainAtTheStartOrEver() {
        Catalogue(dir).let { old ->
            // As an earlier build left them: a whole file, and one with damage inside a frame.
            for ((id, bytes) in listOf("good" to five, "hurt" to hurt())) {
                val name = "$id.flac"
                val file = File(dir, name).also { it.writeBytes(bytes) }
                old.add(Downloaded(flac(id, bytes), name, bytes.size.toLong(), 1_764_000_000, "flac", 0, file.lastModified()))
            }
        }
        val (catalogue, engine) = appStarted()
        repeat(50) { idle() }
        run { engine.download(emptyList()) }
        assertEquals(setOf("good", "hurt"), catalogue.state.value.ids)
        assertEquals(0, begun.get() + readThrough.get())
        assertTrue(asked.none { "stream" in it.path })
        assertEquals(SongState.Done, engine.state.value.stateOf("hurt"))
    }
}
