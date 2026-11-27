package org.medianexus.music.playback

import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.NetworkWatch
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.KeptSongs
import org.medianexus.music.store.Settings
import org.medianexus.music.store.WholeMark
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Streaming on mobile data at a limit of its own, through the player's own data source and
 * Media3's real cache, with the address the app's API client makes: what is asked of the
 * server on each network, what is kept under which name, what a song playing does when the
 * network changes, what fetching ahead does, and two songs at different qualities played one
 * after the other on the real ExoPlayer.
 */
@RunWith(RobolectricTestRunner::class)
class MobileStreamTest {
    private lateinit var dir: File
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var kept: KeptSongs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var index: KeptIndex

    private val settings = MutableStateFlow(Settings(bitrate = 0, mobileBitrate = 128))
    private val network = MutableStateFlow(WIFI)
    private val quality = StreamQuality(settings, network)
    private val api = Subsonic("http://server.invalid", "ada", "sesame", OkHttpClient(), OfflineSwitch(false))

    /** The songs the queue knows, by id: a FLAC the phone plays as it is, of 10 seconds. */
    private val songs = mutableMapOf(
        "one" to Song(id = "one", suffix = "flac", bitRate = 900, duration = 10),
        "two" to Song(id = "two", suffix = "flac", bitRate = 900, duration = 10),
    )

    /** What the server has: the file as it is (a length, byte ranges honoured), and its
     *  conversion at each rate asked for (a fresh one each time, no length: MP3 frames, as
     *  the reader reads its start to know where a second is), told apart byte for byte. */
    private fun file(id: String) = ByteArray(300_000) { (it * 7 + id.hashCode()).toByte() }
    private fun conversion(id: String, kbps: Int) = frames(if (id == "one") 1 else 2, kbps, 10)

    /** Every request the server had: the song, the rate (0: the file), and where from. */
    private data class Asked(val id: String, val kbps: Int, val from: Long, val second: Int)
    private val asked = CopyOnWriteArrayList<Asked>()
    private val opened = CopyOnWriteArrayList<Pair<String, SongCopy>>()

    /** Holds the server's answer part-way, once, until let go (for fetching ahead). */
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var held: CountDownLatch? = null

    private inner class Server : BaseDataSource(true), HttpDataSource {
        private var bytes = ByteArray(0)
        private var at = 0
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun open(dataSpec: DataSpec): Long {
            val url = Uri.parse(dataSpec.uri.toString())
            uri = dataSpec.uri
            val id = url.getQueryParameter("id")!!
            val kbps = url.getQueryParameter("maxBitRate")?.toInt() ?: 0
            val from = dataSpec.httpRequestHeaders["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: dataSpec.position
            asked += Asked(id, kbps, from, url.getQueryParameter("timeOffset")?.toInt() ?: 0)
            if (kbps > 0) {
                bytes = conversion(id, kbps)
                at = 0
                headers = mapOf("Content-Type" to listOf("audio/mpeg"))
                return C.LENGTH_UNSET.toLong()
            }
            bytes = file(id)
            at = from.toInt()
            headers = mapOf("Accept-Ranges" to listOf("bytes"), "Content-Type" to listOf("audio/flac")) +
                if (from > 0) mapOf("Content-Range" to listOf("bytes $from-${bytes.size - 1}/${bytes.size}")) else emptyMap()
            return (bytes.size - at).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            if (at >= 40_000) hold?.let { gate ->
                hold = null
                held?.countDown()
                gate.await(30, TimeUnit.SECONDS)
            }
            val count = minOf(length, bytes.size - at, 8_192)
            bytes.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() {}
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
        override fun getResponseCode(): Int = 200
    }

    private val conversions = Conversions()

    /** The account's lease, as the playback service gives one: `resolve` is the service's
     *  own decision ([StreamQuality.whereNow]), with what is kept whole as the index has it. */
    private inner class Leases : SongLeases {
        override fun generation() = 1L
        override fun lease(generation: Long): SongLease {
            val cache: Cache? = kept.acquire()
            return object : SongLease {
                override val cache = cache
                override val offline = false
                override val conversions = this@MobileStreamTest.conversions
                override fun resolve(songId: String): SongWhere {
                    val song = songs[songId]
                    val suffix = song?.suffix.orEmpty()
                    return quality.whereNow(songId, song, suffix.isEmpty() || suffix in NATIVE_SUFFIXES, index::isWhole, null, 0) { kbps, offset -> api.streamUrl(songId, kbps, offset) }
                }
                override fun opened(songId: String, copy: SongCopy) {
                    opened += songId to copy
                }
                override fun closed(key: String) {
                    val cache = cache ?: return
                    val whole = if (WholeMark.isConversion(key)) WholeMark.length(cache, key) >= 0 else WholeMark.cached(cache, key) >= 0
                    if (whole) index.kept(key, songs[key.removePrefix("song:").substringBeforeLast(':')]!!) else index.gone(key)
                }
                override fun dropped(key: String) = index.gone(key)
                override fun release() {
                    if (cache != null) kept.release()
                }
            }
        }
    }

    private val sources = SongDataSource.Factory(Leases(), DataSource.Factory { Server() }, pause = {})

    @Before
    fun start() {
        dir = Files.createTempDirectory("kept").toFile()
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        kept = KeptSongs(dir, database) { 1L shl 30 }
        index = KeptIndex(File(dir.parentFile, "${dir.name}.json"), scope, Dispatchers.Unconfined)
    }

    @After
    fun stop() {
        player?.release()
        scope.cancel()
        kept.close()
        database.close()
        dir.deleteRecursively()
    }

    private fun open(id: String, at: Long = 0, source: DataSource = sources.createDataSource()): DataSource {
        source.open(DataSpec.Builder().setUri(songUri(id)).setPosition(at).setFlags(PLAYER_FLAGS).build())
        return source
    }

    private fun read(source: DataSource, most: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (out.size() < most) {
            val got = source.read(buffer, 0, minOf(buffer.size, most - out.size()))
            if (got == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, got)
        }
        return out.toByteArray()
    }

    private fun play(id: String): ByteArray {
        val source = open(id)
        try {
            return read(source)
        } finally {
            source.close()
        }
    }

    /** What is kept under `key`, read from the kept songs alone (the server never asked). */
    private fun keptUnder(key: String): ByteArray {
        val cache = kept.acquire()!!
        try {
            // (With nothing upstream, a byte that isn't kept is a failure, not a request.)
            val only = CacheDataSource(cache, null)
            val out = ByteArrayOutputStream()
            val length = cache.getCachedBytes(key, 0, Long.MAX_VALUE)
            if (length <= 0) return ByteArray(0)
            only.open(DataSpec.Builder().setUri("nexus://kept").setKey(key).setPosition(0).setLength(length).build())
            val buffer = ByteArray(16_384)
            while (true) {
                val got = only.read(buffer, 0, buffer.size)
                if (got == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, got)
            }
            only.close()
            return out.toByteArray()
        } finally {
            kept.release()
        }
    }

    @Test
    fun eachNetworkAsksForItsOwnLimitAndEachFormIsKeptUnderItsOwnName() {
        // The Wi-Fi: the file as it is. Part of it is read, and stays kept (a file's part does).
        val partWay = open("one")
        assertArrayEquals(file("one").copyOf(100_000), read(partWay, 100_000))
        partWay.close()
        assertEquals(listOf(Asked("one", 0, 0, 0)), asked.toList())
        assertEquals(100_000, keptUnder("song:one:0").size)
        // Mobile data: the conversion at mobile data's limit, asked for as such, kept under its
        // own name. Not one byte of the file's part comes into it.
        network.value = MOBILE
        assertArrayEquals(conversion("one", 128), play("one"))
        assertEquals(Asked("one", 128, 0, 0), asked.last())
        assertArrayEquals(conversion("one", 128), keptUnder("song:one:128"))
        assertTrue(index.isWhole("song:one:128"))
        // And not one byte of the conversion comes into the file's.
        assertArrayEquals(file("one").copyOf(100_000), keptUnder("song:one:0"))
        assertFalse(index.isWhole("song:one:0"))
        assertEquals(listOf<SongCopy>(SongCopy.Stream(StreamForm(0, "flac")), SongCopy.Stream(StreamForm(128, "mp3"))), opened.map { it.second })
    }

    @Test
    fun aSongOpenedOnOneNetworkKeepsItsFormWhenTheNetworkChanges() {
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        val source = open("one")
        assertEquals(40_000, read(source, 40_000).size)
        // The phone moves to mobile data mid-song. Media3 opens the song's data source again
        // (the connection dropped, or a seek), at a byte of the stream it has been reading:
        // it is the same form, from the same second, and nothing is opened at 128.
        network.value = MOBILE
        source.close()
        open("one", 80_000, source)
        read(source, 10_000)
        source.close()
        assertEquals(listOf(320, 320), asked.map { it.kbps })
        assertTrue("asked from second ${asked.last().second}", asked.last().second > 0)
        assertEquals(listOf("one" to SongCopy.Stream(StreamForm(320, "mp3"))), opened.toList())
        // The next song is opened on mobile data, and takes its limit.
        assertArrayEquals(conversion("two", 128), play("two"))
        assertEquals(Asked("two", 128, 0, 0), asked.last())
    }

    @Test
    fun whatWasFetchedAheadOnTheOtherNetworkPlaysAsItWasFetched() {
        val ahead = FetchAhead(sources)
        try {
            // Fetched ahead on the Wi-Fi, as it is.
            ahead.want(listOf("one"))
            waitFor { index.isWhole("song:one:0") }
            // Played on mobile data: from the phone, as fetched, the server not asked again.
            network.value = MOBILE
            assertArrayEquals(file("one"), play("one"))
            assertEquals(listOf(Asked("one", 0, 0, 0)), asked.toList())
            assertEquals(SongCopy.Stream(StreamForm(0, "flac")), opened.last().second)
            // What the format line says of it is what is sounding: the file as it is.
            val flac = Song(suffix = "flac", samplingRate = 44_100, bitDepth = 16)
            assertEquals(FormatLine("FLAC · 44.1 kHz · 16-bit", true), formatLineOf(flac, opened.last().second.kbps, "Live stream"))
            // And the other way about: fetched ahead on mobile data, played on the Wi-Fi.
            ahead.want(listOf("two"))
            waitFor { index.isWhole("song:two:128") }
            network.value = WIFI
            assertArrayEquals(conversion("two", 128), play("two"))
            assertEquals(listOf(Asked("one", 0, 0, 0), Asked("two", 128, 0, 0)), asked.toList())
            assertEquals(FormatLine("MP3 · 128 kbps", false), formatLineOf(flac, opened.last().second.kbps, "Live stream"))
        } finally {
            ahead.release()
        }
    }

    @Test
    fun fetchingAheadLetsGoOfASongWhenTheNetworkChangesItsLimitAndFetchesItAgainInTheNewForm() {
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        val ahead = FetchAhead(sources)
        try {
            val gate = CountDownLatch(1)
            val reached = CountDownLatch(1)
            held = reached
            hold = gate
            ahead.want(listOf("one"))
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            // Part-way through the Wi-Fi's 320, the phone is on mobile data: what the service
            // does when the limit moves with the network.
            network.value = MOBILE
            ahead.again()
            gate.countDown()
            waitFor { index.isWhole("song:one:128") }
            assertEquals(listOf(320, 128), asked.map { it.kbps })
            assertFalse(index.isWhole("song:one:320"))
            assertArrayEquals(conversion("one", 128), keptUnder("song:one:128"))
        } finally {
            ahead.release()
        }
    }

    @Test
    fun aVpnOverMobileDataIsMobileData() {
        fun caps(vararg transports: Int, unmetered: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also { made ->
            shadowOf(made).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            for (t in transports) shadowOf(made).addTransportType(t)
            if (unmetered) shadowOf(made).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        // Android gives a VPN the meteredness of the networks it goes out by.
        assertTrue(StreamQuality.mobile(NetworkWatch.of(caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_CELLULAR, unmetered = false))))
        assertTrue(StreamQuality.mobile(NetworkWatch.of(caps(NetworkCapabilities.TRANSPORT_CELLULAR, unmetered = false))))
        assertFalse(StreamQuality.mobile(NetworkWatch.of(caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI, unmetered = true))))
        assertFalse(StreamQuality.mobile(NetworkWatch.of(caps(NetworkCapabilities.TRANSPORT_WIFI, unmetered = true))))
    }

    // ---- Two songs, one after the other, at different qualities, on the real player --------

    /** Song `id` as MP3 frames at `kbps` (MPEG-1 layer 3 at 48 kHz: 24 ms and 3 bytes a kbps
     *  each), each carrying the song it is of and the time in it at which it starts. */
    private fun frames(id: Int, kbps: Int, seconds: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val index = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320).indexOf(kbps)
        var ms = 0
        while (ms < seconds * 1000) {
            out.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), (index shl 4 or 0x04).toByte(), 0x44))
            out.write(byteArrayOf(id.toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            out.write(ByteArray(3 * kbps - 8) { 0x55 })
            ms += 24
        }
        return out.toByteArray()
    }

    /** What the renderer was handed: the song, the time in it the frame says, and the time
     *  the player put it at (microseconds on the renderer's own clock, which runs on across
     *  songs). */
    private data class Heard(val song: Int, val songMs: Int, val atUs: Long)
    private val heard = CopyOnWriteArrayList<Heard>()

    private inner class Listener : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private val formats = FormatHolder()
        private var ended = false
        private var waiting: Heard? = null

        override fun getName() = "Listener"
        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(if (format.sampleMimeType == MimeTypes.AUDIO_MPEG) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun isReady() = waiting != null || isSourceReady
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            ended = false
            waiting = null
        }

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                waiting?.let { frame ->
                    if (frame.atUs > positionUs + 50_000) return
                    heard += frame
                    waiting = null
                }
                buffer.clear()
                when (readSource(formats, buffer, 0)) {
                    C.RESULT_FORMAT_READ -> continue
                    C.RESULT_BUFFER_READ -> {
                        if (buffer.isEndOfStream) {
                            ended = true
                            return
                        }
                        buffer.flip()
                        val data = buffer.data!!
                        if (data.remaining() >= 8 && data.get(0) == 0xff.toByte() && data.get(3) == 0x44.toByte()) {
                            waiting = Heard(data.get(4).toInt(), data.getInt(4) and 0xffffff, buffer.timeUs)
                        }
                    }
                    else -> return
                }
            }
        }
    }

    private var player: ExoPlayer? = null

    @Test
    fun twoSongsAtDifferentQualitiesFollowOneAnotherWithNoGap() {
        // The first, an MP3 at 320 sent as it is (the Wi-Fi has no limit), is opened on the
        // Wi-Fi; the phone then moves to mobile data, and the second (a FLAC) is opened there:
        // the server's conversion at 128, of no stated length.
        songs["three"] = Song(id = "three", suffix = "mp3", bitRate = 320, duration = 6)
        songs["four"] = Song(id = "four", suffix = "flac", bitRate = 900, duration = 6)
        val three = frames(3, 320, 6)
        val four = frames(4, 128, 6)
        val upstream = DataSource.Factory {
            object : BaseDataSource(true), HttpDataSource {
                private var bytes = ByteArray(0)
                private var at = 0
                private var uri: Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    uri = dataSpec.uri
                    val url = Uri.parse(dataSpec.uri.toString())
                    val kbps = url.getQueryParameter("maxBitRate")?.toInt() ?: 0
                    asked += Asked(url.getQueryParameter("id")!!, kbps, dataSpec.position, 0)
                    bytes = if (url.getQueryParameter("id") == "three") three else four
                    at = dataSpec.position.toInt()
                    return if (kbps > 0) C.LENGTH_UNSET.toLong() else (bytes.size - at).toLong()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (at >= bytes.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, bytes.size - at, 4_096)
                    bytes.copyInto(buffer, offset, at, at + count)
                    at += count
                    return count
                }
                override fun getUri(): Uri? = uri
                override fun getResponseHeaders(): Map<String, List<String>> =
                    if (uri?.getQueryParameter("maxBitRate") == null) mapOf("Accept-Ranges" to listOf("bytes"), "Content-Type" to listOf("audio/mpeg")) else mapOf("Content-Type" to listOf("audio/mpeg"))
                override fun close() {}
                override fun setRequestProperty(name: String, value: String) {}
                override fun clearRequestProperty(name: String) {}
                override fun clearAllRequestProperties() {}
                override fun getResponseCode(): Int = 200
            }
        }
        val factory = SongDataSource.Factory(Leases(), upstream, pause = {})
        val exo = madeUpClockPlayer(RuntimeEnvironment.getApplication(), DefaultMediaSourceFactory(factory, songExtractors()).setLoadErrorHandlingPolicy(RideOut()), Listener())
        player = exo
        val moved = mutableListOf<Int>()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                moved += reason
            }
        })
        exo.setMediaItem(MediaItem.Builder().setMediaId("three").setUri(songUri("three")).build())
        exo.prepare()
        exo.play()
        advance(exo).untilPosition(0, 1_000)
        network.value = MOBILE
        exo.addMediaItem(MediaItem.Builder().setMediaId("four").setUri(songUri("four")).build())
        advance(exo).untilPosition(1, 3_000)
        assertNull(exo.playerError)
        assertEquals(1, moved.count { it == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO })
        // Each was opened once, in the form for the network it was opened on.
        assertEquals(listOf("three" to SongCopy.Stream(StreamForm(0, "mp3")), "four" to SongCopy.Stream(StreamForm(128, "mp3"))), opened.toList())
        assertEquals(listOf(0, 128), asked.map { it.kbps })
        // The handover: the first song's every frame, then the second's from its start, the
        // second's first frame where the first's last one ended (24 ms on).
        val first = heard.filter { it.song == 3 }
        val second = heard.filter { it.song == 4 }
        assertEquals((6000 + 23) / 24, first.size)
        assertEquals(0, second.first().songMs)
        assertEquals(first.last().atUs + 24_000, second.first().atUs)
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!what()) {
            assertTrue("waited 30 s", System.currentTimeMillis() < until)
            Thread.sleep(20)
        }
    }

    private companion object {
        const val PLAYER_FLAGS = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION
        val WIFI = NetworkNow(connected = true, unmetered = true)
        val MOBILE = NetworkNow(connected = true, unmetered = false)
    }
}
