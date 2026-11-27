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
import java.io.IOException
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
import org.junit.Assert.fail
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
    /** What Android says of the network this instant (`StreamQuality`'s second look): by
     *  default what its last callback said; a test of a race makes them differ. */
    @Volatile private var lookNow: () -> Boolean? = { network.value.takeIf { it.connected }?.unmetered }
    private val quality = StreamQuality(settings, network, unmeteredNow = { lookNow() })

    /** Offline mode. */
    @Volatile private var offline = false
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

    /** Holds the server's answer part-way (at [holdAt] bytes), once, until let go (for
     *  fetching ahead). */
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var held: CountDownLatch? = null
    @Volatile private var holdAt = 40_000

    /** Cancels what is in flight, as OkHttp's `Call.cancel` does: a read held ends in an
     *  error at once. (What fetching ahead's own client is told to do: see FetchAhead.) */
    @Volatile private var cancelled = false
    private fun cancelAll() {
        cancelled = true
        hold = null
        held = null
        gate?.countDown()
    }
    @Volatile private var gate: CountDownLatch? = null

    /** Requests the server turns away at open (the network gone under a bound request, or
     *  the server out of reach). */
    @Volatile private var refuse: (Asked) -> Boolean = { false }

    /** Called as each request is answered, before a byte of it is read (a test of a race
     *  changes the network here). */
    @Volatile private var answered: (Asked) -> Unit = {}

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
            val request = Asked(id, kbps, from, url.getQueryParameter("timeOffset")?.toInt() ?: 0)
            asked += request
            cancelled = false
            if (refuse(request)) throw IOException("No way to the server")
            answered(request)
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
            if (at >= holdAt) hold?.let { waiting ->
                hold = null
                gate = waiting
                held?.countDown()
                waiting.await(30, TimeUnit.SECONDS)
            }
            if (cancelled) throw IOException("Canceled")
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
                override val offline get() = this@MobileStreamTest.offline
                override val conversions = this@MobileStreamTest.conversions
                override fun resolve(songId: String): SongWhere = resolve(songId, null)
                override fun resolve(songId: String, entry: Long?): SongWhere {
                    val song = songs[songId]
                    val suffix = song?.suffix.orEmpty()
                    val withKept = cache != null
                    // (As the service does: an entry made ready again after "moved" takes the
                    // lower form.)
                    return quality.whereNow(songId, song, suffix.isEmpty() || suffix in NATIVE_SUFFIXES, { withKept && index.isWhole(it) }, null, 0, entry != null && entry in movedEntries) { kbps, offset -> api.streamUrl(songId, kbps, offset) }
                }
                override fun opened(songId: String, entry: Long?, copy: SongCopy) {
                    opened += songId to copy
                    told += Triple(songId, entry, copy)
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

    /** Entries made ready again after a read of them failed as "moved" (the service's). */
    private val movedEntries = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** What each open told: the song, the queue entry, the copy. */
    private val told = CopyOnWriteArrayList<Triple<String, Long?, SongCopy>>()

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

    private var entries = 1L

    /** What fetching ahead is told the form of a song is now, as the service tells it. */
    private fun formNow(id: String): StreamForm? = songs[id]?.let { song ->
        val suffix = song.suffix.lowercase()
        quality.formOf(suffix.isEmpty() || suffix in NATIVE_SUFFIXES, suffix, song.bitRate)
    }

    /** Opens the song as the player does for queue entry `entry` (null: as fetching ahead). */
    private fun open(id: String, at: Long = 0, source: DataSource = sources.createDataSource(), entry: Long? = entries++): DataSource {
        source.open(DataSpec.Builder().setUri(songUri(id)).setPosition(at).setFlags(PLAYER_FLAGS).setKey(entry?.let { SongDataSource.ENTRY + it }).build())
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
    fun r2_aReadThatCannotGoOnInItsFormWithoutExceedingTheLowerLimitFailsToBeMadeReadyAgain() {
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        val source = open("one", entry = 100)
        assertEquals(40_000, read(source, 40_000).size)
        // The phone moves to mobile data mid-song. Media3 opens the song's data source again
        // (the connection dropped, or a seek past what it holds): not at 320 over mobile data,
        // nor at 128 joined to the 320's bytes. The read fails as "moved", which the service
        // takes as "make the entry ready again from where it is" (see onPlayerError).
        network.value = MOBILE
        source.close()
        try {
            open("one", 80_000, source, entry = 100)
            read(source, 10_000)
            fail("the 320 went on over mobile data")
        } catch (e: StreamFailure) {
            assertTrue(e.moved)
        }
        runCatching { source.close() }
        assertEquals(listOf(Asked("one", 320, 0, 0)), asked.toList())
        // Made ready again: a new read, chosen afresh, at 128.
        val again = open("one", 80_000, entry = 100)
        read(again, 10_000)
        again.close()
        assertEquals(128, asked.last().kbps)
        // The next song is opened on mobile data, and takes its limit.
        assertArrayEquals(conversion("two", 128), play("two"))
        assertEquals(Asked("two", 128, 0, 0), asked.last())
    }

    @Test
    fun whatWasFetchedAheadOnTheOtherNetworkPlaysAsItWasFetched() {
        val ahead = FetchAhead(sources, ::formNow)
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
        val ahead = FetchAhead(sources, ::formNow)
        try {
            val gate = CountDownLatch(1)
            val reached = CountDownLatch(1)
            held = reached
            hold = gate
            ahead.want(listOf("one"))
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            // Part-way through the Wi-Fi's 320, the phone is on mobile data: what the service
            // does when the limit moves with the network.
            // Not continued at 320 over mobile data: let go between one read and the next,
            // and fetched again in mobile data's form.
            network.value = MOBILE
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

    // ---- The lead's rulings R1 to R4 (each test fails without its rule) ---------------------

    @Test
    fun r1_aLateCallbackIsTheLowerLimit() {
        // Android's last callback says Wi-Fi, but the network this instant is metered (the
        // default route has moved to mobile data and the callback hasn't come yet).
        lookNow = { false }
        assertArrayEquals(conversion("one", 128), play("one"))
        assertEquals(listOf(Asked("one", 128, 0, 0)), asked.toList())
        // The other way about: the callback says mobile data, the look says Wi-Fi. In doubt.
        asked.clear()
        network.value = MOBILE
        lookNow = { true }
        assertArrayEquals(conversion("two", 128), play("two"))
        assertEquals(listOf(Asked("two", 128, 0, 0)), asked.toList())
        // Both say Wi-Fi: the file as it is.
        asked.clear()
        network.value = WIFI
        songs["three"] = Song(id = "three", suffix = "flac", bitRate = 900, duration = 10)
        assertArrayEquals(file("three"), play("three"))
        assertEquals(listOf(Asked("three", 0, 0, 0)), asked.toList())
    }

    @Test
    fun r1_aHigherFormWhoseNetworkTurnsMeteredBeforeItsFirstByteIsLetGoAndChosenAgain() {
        // Chosen and asked for on the Wi-Fi; the route moves to mobile data as the answer
        // comes, before a byte of it has been handed on.
        answered = { request -> if (request.kbps == 0) { network.value = MOBILE; lookNow = { false } } }
        val source = sources.createDataSource()
        try {
            open("one", source = source, entry = 200)
            read(source, 1_000)
            fail("the file as it is was handed on over mobile data")
        } catch (e: StreamFailure) {
            assertTrue(e.moved)
        }
        runCatching { source.close() }
        // Made ready again (the service's recovery): chosen afresh, at mobile data's limit.
        answered = {}
        assertArrayEquals(conversion("one", 128), play("one"))
        assertEquals(listOf(0, 128), asked.map { it.kbps })
    }

    @Test
    fun fix1_aNetworkThatComesBackBeforeEachTryAndGoesBeforeEachFirstByteGivesOneReadyingAgain() {
        // Every request is answered just as the Wi-Fi goes (the route on mobile data before
        // the first byte arrives), and the Wi-Fi is back before the entry is made ready again.
        answered = { network.value = MOBILE; lookNow = { false } }
        var readied = 0
        var played: ByteArray? = null
        while (played == null) {
            check(readied < 5) { "made ready again $readied times" }
            network.value = WIFI
            lookNow = { true }
            val source = sources.createDataSource()
            try {
                open("one", source = source, entry = 9)
                played = read(source)
            } catch (e: StreamFailure) {
                assertTrue(e.moved)
                // What the service does with it (onPlayerError): the entry noted, and made
                // ready again.
                movedEntries += 9L
                readied++
            } finally {
                runCatching { source.close() }
            }
        }
        // Once: the lower form, which "moved" can't come of, played whatever the network did.
        assertEquals(1, readied)
        assertArrayEquals(conversion("one", 128), played)
        assertEquals(listOf(0, 128), asked.map { it.kbps })
    }

    @Test
    fun r1_aRouteThatChangesAfterTheFirstBytesIsAccepted() {
        val source = open("one", entry = 300)
        assertEquals(50_000, read(source, 50_000).size)
        network.value = MOBILE
        lookNow = { false }
        // The answer already coming goes on to its end, in its form.
        assertArrayEquals(file("one").copyOfRange(50_000, 300_000), read(source))
        source.close()
        assertEquals(listOf(Asked("one", 0, 0, 0)), asked.toList())
    }

    @Test
    fun r2_aCopyOfTheHigherFormClearedAwayIsNeverFetchedInThatFormOverMobileData() {
        // The FLAC fetched whole on the Wi-Fi...
        assertArrayEquals(file("one"), play("one"))
        assertTrue(index.isWhole("song:one:0"))
        // ...cleared away by the phone between the choice and the read: the index still says
        // whole (it hears of it later).
        val cache = kept.acquire()!!
        cache.removeResource("song:one:0")
        kept.release()
        asked.clear()
        network.value = MOBILE
        val source = sources.createDataSource()
        try {
            open("one", source = source)
            read(source, 1_000)
            fail("the FLAC was fetched over mobile data")
        } catch (e: StreamFailure) {
            assertTrue(e.moved)
        }
        runCatching { source.close() }
        assertEquals(emptyList<Asked>(), asked.toList())
        assertFalse(index.isWhole("song:one:0"))
        // Made ready again: mobile data's own form.
        assertArrayEquals(conversion("one", 128), play("one"))
        assertEquals(listOf(Asked("one", 128, 0, 0)), asked.toList())
    }

    @Test
    fun r2_withNoKeptSongsToReadTheIndexIsNotTakenAtItsWord() {
        assertArrayEquals(file("one"), play("one"))
        assertTrue(index.isWhole("song:one:0"))
        asked.clear()
        network.value = MOBILE
        // A lease with no kept songs (the account's folder still held by its turn before),
        // whose choice still believes the index: the read asks the server nothing in that form.
        val bare = object : SongLeases {
            override fun generation() = 1L
            override fun lease(generation: Long): SongLease {
                val inner = Leases().lease(generation)
                inner.release()
                return object : SongLease by inner {
                    override val cache: Cache? = null
                    override fun resolve(songId: String): SongWhere =
                        quality.whereNow(songId, songs[songId], true, index::isWhole, null, 0) { kbps, offset -> api.streamUrl(songId, kbps, offset) }
                    // (Defaults of the interface: `by` doesn't hand them on.)
                    override fun dropped(key: String) = index.gone(key)
                    // (With no kept songs there is nothing to judge whole when a read closes.)
                    override fun closed(key: String) {}
                    override fun release() {}
                }
            }
        }
        val factory = SongDataSource.Factory(bare, DataSource.Factory { Server() }, pause = {})
        val source = factory.createDataSource()
        try {
            open("one", source = source)
            read(source, 1_000)
            fail("the FLAC was fetched over mobile data")
        } catch (e: StreamFailure) {
            assertTrue(e.moved)
        }
        runCatching { source.close() }
        assertFalse("the index still says the FLAC is whole", index.isWhole("song:one:0"))
        val again = factory.createDataSource()
        open("one", source = again)
        assertArrayEquals(conversion("one", 128), read(again))
        again.close()
        assertEquals(listOf(Asked("one", 128, 0, 0)), asked.toList())
    }

    @Test
    fun r2_offlineAReadWhoseCopyHasGoneIsMadeReadyAgainOnAnotherKeptForm() {
        // Both forms kept whole: 320 fetched on the Wi-Fi, 128 on mobile data.
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        assertArrayEquals(conversion("one", 320), play("one"))
        network.value = MOBILE
        // (With the 320 kept whole, mobile data would play it as kept: the index is kept from
        // seeing it for a moment, so that the 128 is fetched too.)
        index.gone("song:one:320")
        assertArrayEquals(conversion("one", 128), play("one"))
        index.kept("song:one:320", songs.getValue("one"))
        assertTrue(index.isWhole("song:one:320") && index.isWhole("song:one:128"))
        // Offline; the 128 is cleared away after it was chosen.
        offline = true
        val source = sources.createDataSource()
        open("one", source = source)
        source.close()
        val cache = kept.acquire()!!
        cache.removeResource("song:one:128")
        kept.release()
        try {
            open("one", 50_000, source)
            read(source, 1_000)
            fail("a copy that has gone was read")
        } catch (e: StreamFailure) {
            assertTrue("$e", e.moved)
        }
        runCatching { source.close() }
        // Made ready again: the 320, from the phone.
        assertArrayEquals(conversion("one", 320), play("one"))
    }

    @Test
    fun r3_whatIsPlayingIsToldAsTheEntryIsOpenedByEntryAndFetchingAheadTellsNothing() {
        // The same FLAC twice in the queue: entry 1 on the Wi-Fi, as it is.
        val first = open("one", entry = 1)
        // Told as it opens (before a byte is read), as the released app told it.
        assertEquals(listOf(Triple("one", 1L as Long?, SongCopy.Stream(StreamForm(0, "flac")) as SongCopy)), told.toList())
        read(first, 50_000)
        network.value = MOBILE
        val second = open("one", entry = 2)
        val ahead = open("one", entry = null)
        listOf(first, second, ahead).forEach { it.close() }
        assertEquals(listOf(1L, 2L, null), told.map { it.second })
        assertEquals(listOf<SongCopy>(SongCopy.Stream(StreamForm(0, "flac")), SongCopy.Stream(StreamForm(128, "mp3")), SongCopy.Stream(StreamForm(128, "mp3"))), told.map { it.third })
    }

    @Test
    fun r4_aHigherFormIsNotRetriedOnMeteredAndAFailureBelongsToItsForm() {
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        val ahead = FetchAhead(sources, ::formNow)
        try {
            // Held part-way on the Wi-Fi; the connection then drops (the Wi-Fi gone), with
            // the phone on mobile data. The reader's own retry would ask for 320 again.
            val reached = CountDownLatch(1)
            held = reached
            hold = CountDownLatch(1)
            ahead.want(listOf("one"))
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            network.value = MOBILE
            cancelAll()
            // Never asked for at 320 over mobile data; fetched at 128.
            waitFor { index.isWhole("song:one:128") }
            assertEquals(listOf(320, 128), asked.map { it.kbps })
        } finally {
            ahead.release()
        }
    }

    @Test
    fun r4_aFailureInOneFormIsNotTriedAgainInItButIsInAnotherAndNothingSpins() {
        settings.value = Settings(bitrate = 320, mobileBitrate = 128)
        refuse = { it.kbps == 320 }
        val ahead = FetchAhead(sources, ::formNow)
        try {
            ahead.want(listOf("one"))
            waitFor { asked.isNotEmpty() }
            Thread.sleep(200)
            // Failed at 320 on the Wi-Fi. The service asks for it again (the queue looked at
            // again): not tried again in that form, and the worker doesn't set itself going,
            // again and again, for a list of nothing but what has failed.
            ahead.want(listOf("one"))
            Thread.sleep(300)
            val runs = ahead.runs
            Thread.sleep(300)
            assertEquals(runs, ahead.runs)
            assertTrue("the worker ran $runs times", runs <= 2)
            assertEquals(listOf(320), asked.map { it.kbps })
            // The phone moves to mobile data: tried again there, in mobile data's form.
            network.value = MOBILE
            ahead.want(listOf("one"))
            waitFor { index.isWhole("song:one:128") }
            assertEquals(listOf(320, 128), asked.map { it.kbps })
            // A song that failed in both forms is tried in neither again (a second failure
            // doesn't wipe out the first), however the network comes and goes.
            refuse = { it.id == "two" }
            ahead.want(listOf("two"))
            waitFor { asked.count { it.id == "two" } == 1 }
            Thread.sleep(200)
            network.value = WIFI
            ahead.want(listOf("two"))
            waitFor { asked.count { it.id == "two" } == 2 }
            Thread.sleep(200)
            network.value = MOBILE
            ahead.want(listOf("two"))
            Thread.sleep(300)
            network.value = WIFI
            ahead.want(listOf("two"))
            Thread.sleep(300)
            assertEquals(listOf(128, 320), asked.filter { it.id == "two" }.map { it.kbps })
        } finally {
            ahead.release()
        }
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
