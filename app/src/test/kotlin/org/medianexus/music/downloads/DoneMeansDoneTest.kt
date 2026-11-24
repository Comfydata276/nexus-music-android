package org.medianexus.music.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertNull
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
import org.medianexus.music.playback.Conversions
import org.medianexus.music.playback.RideOut
import org.medianexus.music.playback.SongDataSource
import org.medianexus.music.playback.SongLease
import org.medianexus.music.playback.SongLeases
import org.medianexus.music.playback.SongWhere
import org.medianexus.music.playback.StreamForm
import org.medianexus.music.playback.cacheKey
import org.medianexus.music.playback.songExtractors
import org.medianexus.music.playback.songUri
import org.medianexus.music.store.CoverStore
import org.medianexus.music.store.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * The test the review of the offline engine asked for, end to end on the real things:
 * Media3's download manager and its database, our downloader, real audio, a server whose
 * answers stop where the test says, and then ExoPlayer with Media3's extractors reading what
 * was marked Done, through the app's own data source, with the network gone.
 *
 * An original is interrupted after its first bytes. The app is "restarted" against the same
 * files on disk, and meanwhile the server has a different file under the same song id. The
 * network is cut. The downloads are read cold, as the car's Downloads tab reads them, and
 * every song marked Done must play to its expected end.
 */
@RunWith(RobolectricTestRunner::class)
class DoneMeansDoneTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = GatedServer()
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = MutableStateFlow(Settings(downloadWifiOnly = false))
    private val reach = MutableStateFlow(Reach())
    private val made = mutableListOf<Downloads>()

    private val three = GatedServer.fixture("three-seconds.flac")
    private val five = GatedServer.fixture("five-seconds.flac")
    private val twenty = GatedServer.fixture("twenty-seconds.mp3")

    /** The file the server has under "s1" now, when it changed, and how long it runs. */
    @Volatile private var original = three
    @Volatile private var modified = "Mon, 23 Nov 2026 07:57:08 GMT"
    @Volatile private var seconds = 3
    @Volatile private var hold: Pair<Int, GatedServer.Gate>? = null

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        database = StandaloneDatabaseProvider(context)
        dir = folder.newFolder("downloads")
        server.handler = { request ->
            val id = request.query("id").orEmpty()
            when {
                "getSong" in request.path && id == "s1" -> GatedServer.song("s1", "flac", original.size.toLong(), seconds)
                "getSong" in request.path && id == "s2" -> GatedServer.song("s2", "ape", 900_000, 20)
                // The file as it is, as Navidrome serves one; held where the test says, once.
                "stream" in request.path && id == "s1" -> GatedServer.file(request, original, modified, hold = hold.also { hold = null })
                // A file the phone can't play, converted as it goes.
                "stream" in request.path && id == "s2" -> GatedServer.conversion(twenty)
                else -> GatedServer.Answer(404, mapOf("Content-Type" to "application/json"), "{}".toByteArray())
            }
        }
    }

    @After
    fun stop() {
        player?.release()
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

    /** The app started: the catalogue read from the disk, and the downloads on Media3's
     *  manager, which reads what was waiting from its database. */
    private fun appStarted(): Pair<Catalogue, Downloads> {
        val catalogue = Catalogue(dir)
        val api = Subsonic(server.url("/"), "tester", "pass", OkHttpClient())
        val covers = CoverStore(folder.newFolder(), scope, Dispatchers.IO, fetch = { _, _ -> throw ApiError.Network("none") })
        val downloads = Downloads(context, catalogue, api, OkHttpClient(), database, "acct", settings, reach, covers, { _, _ -> }, scope, spare = { 0 })
        made += downloads
        idle()
        return catalogue to downloads
    }

    private fun appStopped(downloads: Downloads) {
        downloads.release()
        made.remove(downloads)
        idle()
    }

    @Test
    fun everySongMarkedDoneDecodesToItsExpectedEnd() {
        val gate = GatedServer.Gate()
        hold = 8_192 to gate
        val (first, engine) = appStarted()
        until("ready") { engine.state.value.settled }
        // As the app's pages have them now: the original, and a file that must be converted.
        val s1 = Song(id = "s1", title = "One", suffix = "flac", size = three.size.toLong(), duration = 3)
        // (Its own rate is low, so the server converts it at that: Navidrome never converts up.)
        val s2 = Song(id = "s2", title = "Two", suffix = "ape", size = 900_000, duration = 20, bitRate = 16)
        val asked = scope.async { engine.download(listOf(s1, s2)) }
        until("asked") { asked.isCompleted }

        // The original is interrupted after its first bytes: the app stops.
        gate.awaitReached()
        val part = File(dir, SongFetch.partName("s1.flac"))
        until("its first bytes on the disk") { part.length() >= 8_192 }
        appStopped(engine)
        gate.dropHere()
        assertFalse(first.has("s1"))
        assertEquals(8_192L, part.length())

        // Meanwhile the server has a different file under the same song id.
        original = five
        modified = "Tue, 24 Nov 2026 09:00:00 GMT"
        seconds = 5

        // The app is started again, against the same files on disk.
        val (second, again) = appStarted()
        until("both downloaded") { second.has("s1") && second.has("s2") && again.state.value.settled && !again.state.value.busy }
        assertTrue(again.state.value.failed.isEmpty())

        // The network is cut.
        appStopped(again)
        server.close()

        // Cold, as the car's Downloads tab reads them: from the downloads' own record.
        val cold = Catalogue(dir)
        val songs = DownloadedViews.songsFor(DownloadsState(downloaded = cold.state.value.songs), DownloadedViews.SOURCE)!!
        assertEquals(setOf("s1", "s2"), songs.map { it.id }.toSet())
        assertArrayEquals("the download is not the file the server has", five, cold.wholeFileOf("s1")!!.readBytes())
        for (song in songs) {
            val heard = play(cold, song.id)
            val expected = song.duration * 1000L
            assertTrue("${song.id} is known to run $expected ms and its file says ${heard.durationMs} ms", SongFetch.runsAsLongAs(song, heard.durationMs * 1000))
            assertTrue("${song.id} decoded to ${heard.lastMs} ms of $expected ms", heard.lastMs >= expected - 600)
        }
    }

    @Test
    fun aDownloadThatFailsItsChecksIsFetchedAFewTimesAndThenListedWithItsReason() {
        // The right size, called FLAC, and no audio in it, every time it is asked for.
        original = ByteArray(three.size) { (it * 31 + 7).toByte() }
        val (catalogue, engine) = appStarted()
        until("ready") { engine.state.value.settled }
        val asked = scope.async { engine.download(listOf(Song(id = "s1", title = "One", suffix = "flac", size = three.size.toLong(), duration = 3))) }
        until("given up on") { asked.isCompleted && engine.state.value.failed.isNotEmpty() && !engine.state.value.busy }
        assertFalse(catalogue.has("s1"))
        assertEquals("What the server sent isn't a song the phone can play.", engine.state.value.failed.single().reason)
        assertEquals(Downloads.VERIFY_TRIES, server.asked.count { "stream" in it.path })
        // Nothing of it is left to be mistaken for a song.
        assertTrue(dir.listFiles()!!.none { it.name.contains("s1") })
    }

    @Test
    fun aDownloadFoundWantingWhenTheAppStartsIsFetchedAgain() {
        val (catalogue, engine) = appStarted()
        until("ready") { engine.state.value.settled }
        val asked = scope.async { engine.download(listOf(Song(id = "s1", title = "One", suffix = "flac", size = three.size.toLong(), duration = 3))) }
        until("downloaded") { asked.isCompleted && catalogue.has("s1") && !engine.state.value.busy }
        appStopped(engine)
        // Its file is not the length that was recorded when it was verified.
        val file = catalogue.fileOf("s1")!!
        file.writeBytes(three.copyOf(three.size - 512))
        val (second, again) = appStarted()
        // Not a download now, whatever the list says...
        assertFalse(second.has("s1"))
        // ...and fetched again without anyone asking.
        until("fetched again") { second.has("s1") && again.state.value.settled && !again.state.value.busy }
        assertArrayEquals(three, second.wholeFileOf("s1")!!.readBytes())
    }

    // ---- The player, with no network ------------------------------------------------------------

    private class Heard(val durationMs: Long, val lastMs: Long)

    private var player: androidx.media3.exoplayer.ExoPlayer? = null

    /** Stands where the decoder does: takes every frame the extractor finds, to the end. */
    private class Frames : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private val formats = FormatHolder()
        private var ended = false
        @Volatile var lastUs = -1L

        override fun getName() = "Frames"
        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(if (MimeTypes.isAudio(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun isReady() = isSourceReady
        override fun isEnded() = ended

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                buffer.clear()
                when (readSource(formats, buffer, 0)) {
                    C.RESULT_FORMAT_READ -> continue
                    C.RESULT_BUFFER_READ -> {
                        if (buffer.isEndOfStream) {
                            ended = true
                            return
                        }
                        lastUs = buffer.timeUs - streamOffsetUs
                    }
                    else -> return
                }
            }
        }
    }

    /** Plays one downloaded song as the service would with the server gone: its file from
     *  the catalogue, through the app's data source, and nothing else to turn to. */
    private fun play(catalogue: Catalogue, id: String): Heard {
        val leases = object : SongLeases {
            override fun generation() = 1L
            override fun lease(generation: Long) = object : SongLease {
                override val cache = null
                override val offline = true
                override val conversions = Conversions()
                override fun resolve(songId: String): SongWhere {
                    val form = StreamForm(0, "")
                    return SongWhere(form, cacheKey(songId, form), 0, catalogue.wholeFileOf(songId)) { throw IllegalStateException("the network is cut") }
                }
                override fun release() {}
            }
        }
        val noNetwork = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long = throw IOException("the network is cut")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("the network is cut")
                override fun getUri() = null
                override fun close() {}
            }
        }
        val frames = Frames()
        val exo = TestExoPlayerBuilder(context)
            .setRenderers(frames)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SongDataSource.Factory(leases, noNetwork, pause = {}), songExtractors()).setLoadErrorHandlingPolicy(RideOut()))
            .build()
        player?.release()
        player = exo
        exo.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(songUri(id)).build())
        exo.prepare()
        exo.play()
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull(exo.playerError)
        return Heard(exo.duration, frames.lastUs / 1000)
    }
}
