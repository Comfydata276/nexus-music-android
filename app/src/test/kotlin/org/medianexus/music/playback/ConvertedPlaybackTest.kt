package org.medianexus.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A converted song played by the real thing: ExoPlayer, Media3's progressive media source and
 * its MP3 extractor, reading through the app's data source and retry policy from a stand-in
 * for Navidrome that streams conversions the way the real one was seen to (no length and no
 * ranges for a fresh one, `timeOffset` exact, a tag before the audio, the rate it chooses).
 *
 * Each frame the stand-in sends carries the time in the song it belongs to. A renderer in
 * place of the decoder reads that back from every frame the player gives it, beside the time
 * the player says it is: so what is asserted is what would be heard, and when.
 */
@RunWith(RobolectricTestRunner::class)
class ConvertedPlaybackTest {
    // ---- The stand-in server ------------------------------------------------------------------

    /** How long the song really is, in seconds; and what the library says. */
    private var seconds = 60
    private var catalogueSeconds = 60
    /** The rate asked for, and the rate the server converts at whatever is asked (0: as asked). */
    private var askedKbps = 128
    private var givenKbps = 0
    private val tagBytes = 572
    /** The connection drops after this many bytes of the first answer (-1: it doesn't). */
    private var dropsAfter = -1
    /** The song is already an MP3 within the limit: the server sends the file as it is, with
     *  its length, honouring byte ranges and ignoring any time to start from. */
    private var sendsTheFile = false
    private val asked = CopyOnWriteArrayList<String>()
    /** The first answer's reading stops here (a byte), on the loading thread, until opened. */
    private var holdAt = -1
    private val held = java.util.concurrent.CompletableFuture<Unit>()
    private val open = java.util.concurrent.CountDownLatch(1)

    private fun tag(size: Int): ByteArray {
        val body = size - 10
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            (body shr 21 and 0x7f).toByte(), (body shr 14 and 0x7f).toByte(), (body shr 7 and 0x7f).toByte(), (body and 0x7f).toByte()) + ByteArray(body) { 0x20 }
    }

    /** A conversion at `kbps` started `offset` seconds in: the tag, then constant bit rate
     *  frames (MPEG-1 layer 3 at 48 kHz: 24 ms and 3 bytes a kbps each), each carrying after
     *  its header the time in the song at which it starts, in milliseconds. */
    private fun conversion(kbps: Int, offset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(tagBytes))
        val index = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320).indexOf(kbps)
        var ms = offset * 1000
        while (ms < seconds * 1000) {
            out.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), (index shl 4 or 0x04).toByte(), 0x44))
            out.write(byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            out.write(ByteArray(3 * kbps - 8) { 0x55 })
            ms += 24
        }
        return out.toByteArray()
    }

    private inner class Server : BaseDataSource(true), HttpDataSource {
        private var bytes = ByteArray(0)
        private var at = 0
        private var failAt = -1
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val offset = dataSpec.uri.getQueryParameter("timeOffset")?.toInt() ?: 0
            val rate = givenKbps.takeIf { it > 0 } ?: dataSpec.uri.getQueryParameter("maxBitRate")!!.toInt()
            val first = asked.isEmpty()
            // A byte further in is asked for as a server is asked: by a Range header.
            val from = dataSpec.httpRequestHeaders["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: dataSpec.position
            asked += "timeOffset=$offset" + if (from > 0) " bytes=$from-" else ""
            if (sendsTheFile) {
                bytes = conversion(rate, 0)
                at = from.toInt()
                headers = mapOf("Accept-Ranges" to listOf("bytes"), "Content-Type" to listOf("audio/mpeg")) +
                    if (from > 0) mapOf("Content-Range" to listOf("bytes $from-${bytes.size - 1}/${bytes.size}")) else emptyMap()
                return (bytes.size - at).toLong()
            }
            // Fresh: from its own start whatever byte was asked for, with no length.
            check(from == 0L) { "a byte range was asked of a server that ignores them: everything before byte $from would be read and thrown away" }
            bytes = conversion(rate, offset)
            at = 0
            failAt = if (first) dropsAfter else -1
            headers = mapOf("Accept-Ranges" to listOf("none"), "Content-Type" to listOf("audio/mpeg"))
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (holdAt in 0..at) {
                holdAt = -1
                held.complete(Unit)
                open.await()
            }
            if (failAt in 0..at) throw HttpDataSource.HttpDataSourceException(IOException("Connection reset"), DataSpec(uri!!), 2000, HttpDataSource.HttpDataSourceException.TYPE_READ)
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            var count = minOf(length, bytes.size - at, 4_096)
            if (failAt >= 0) count = minOf(count, failAt - at)
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

    // ---- The player ---------------------------------------------------------------------------

    /** What the player handed on to be heard: the time it gave the frame, and the time in the
     *  song the frame says it is, both in milliseconds. */
    private data class Heard(val playerMs: Long, val songMs: Int)

    private val heard = CopyOnWriteArrayList<Heard>()

    /** Stands where the decoder does: takes each frame as its time comes, and notes it. */
    private inner class Listener : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private val formats = FormatHolder()
        private var ended = false

        /** A frame read from the player whose time has not come yet, and that time. */
        private var waiting: Heard? = null
        private var waitingUs = 0L

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
                    // A frame is heard when its time has come, as a decoder is fed.
                    if (waitingUs > positionUs + 50_000) return
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
                        waitingUs = buffer.timeUs
                        // Only whole frames of the stand-in's making carry a time.
                        if (data.remaining() >= 8 && data.get(0) == 0xff.toByte() && data.get(3) == 0x44.toByte()) {
                            waiting = Heard((buffer.timeUs - streamOffsetUs) / 1000, data.getInt(4))
                        }
                    }
                    else -> return
                }
            }
        }
    }

    private val leases = object : SongLeases {
        override fun generation() = 1L
        override fun lease(generation: Long) = object : SongLease {
            override val cache = null
            override val offline = false
            override val conversions = this@ConvertedPlaybackTest.conversions
            override fun resolve(songId: String): SongWhere {
                val form = StreamForm(askedKbps, "mp3")
                return SongWhere(form, cacheKey(songId, form), leastBytes(catalogueSeconds, askedKbps, 0)) { offset ->
                    "http://server.invalid/rest/stream?id=$songId&format=mp3&maxBitRate=$askedKbps" + if (offset > 0) "&timeOffset=$offset" else ""
                }
            }
            override fun release() {}
        }
    }

    private val conversions = Conversions()
    private var player: ExoPlayer? = null
    private val clock = androidx.media3.test.utils.FakeClock(/* isAutoAdvancing = */ true)

    /** The player as the service builds its own: the app's data source, the app's way of
     *  reading MP3, the app's retry policy (without the waiting). */
    private fun start(): ExoPlayer {
        val sources = SongDataSource.Factory(leases, DataSource.Factory { Server() }, pause = {})
        val made = madeUpClockPlayer(RuntimeEnvironment.getApplication(), DefaultMediaSourceFactory(sources, songExtractors()).setLoadErrorHandlingPolicy(RideOut()), Listener(), clock = clock)
        player = made
        made.setMediaItem(MediaItem.Builder().setMediaId("one").setUri(songUri("one")).build())
        made.prepare()
        return made
    }

    @After
    fun stop() {
        player?.release()
    }

    /** The frames heard since `from` in the list, checked: each is the frame for the time the
     *  player gave it, to within `within` ms; and the song never goes back on itself. */
    private fun assertHeardRightly(from: Int = 0, within: Int = 1_100) {
        val frames = heard.toList().drop(from)
        assertTrue("nothing was heard", frames.isNotEmpty())
        for ((before, after) in frames.zipWithNext()) {
            assertTrue("the song went back: ${before.songMs} ms, then ${after.songMs} ms (at ${after.playerMs} ms on the clock)", after.songMs > before.songMs)
        }
        val worst = frames.maxBy { kotlin.math.abs(it.playerMs - it.songMs) }
        assertTrue("at ${worst.playerMs} ms on the clock, the frame for ${worst.songMs} ms was heard", kotlin.math.abs(worst.playerMs - worst.songMs) <= within)
    }

    // ---- What must hold -------------------------------------------------------------------------

    @Test
    fun aConversionPlaysFromItsFirstFrameToItsLast() {
        val exo = start()
        exo.play()
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull(exo.playerError)
        assertEquals(0, heard.first().songMs)
        assertTrue("ended at ${heard.last().songMs} ms", heard.last().songMs in 59_950..59_999)
        assertHeardRightly(within = 50)
        assertEquals(listOf("timeOffset=0"), asked.toList())
    }

    @Test
    fun aDropInTheMiddleCarriesOnFromWhereItWas() {
        // The line goes dead twenty seconds' worth into the stream.
        dropsAfter = tagBytes + 20 * 16_000 + 1_234
        val exo = start()
        exo.play()
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull(exo.playerError)
        // Media3, left to itself, takes a stream of unknown length for a broadcast and starts
        // a failed one again from its beginning: the song began again part-way through.
        assertEquals(0, heard.first().songMs)
        assertTrue("ended at ${heard.last().songMs} ms", heard.last().songMs in 59_950..59_999)
        assertHeardRightly()
        assertTrue("heard some of it twice, or not at all: ${heard.size} frames of 2,500", heard.size in 2_440..2_500)
        // Asked for again from the time it had reached, not from the start, and not by byte.
        assertEquals(listOf("timeOffset=0", "timeOffset=20"), asked.toList())
    }

    /** A song too long for the player to hold whole, so that a seek far along it has to be
     *  asked of the server. */
    private fun fiveMinutes() {
        seconds = 300
        catalogueSeconds = 300
    }

    @Test
    fun aSeekLandsWithinASecondAndThePositionShownIsRight() {
        fiveMinutes()
        val exo = start()
        exo.play()
        advance(exo).untilPositionAtLeast(2_000)
        val before = heard.size
        exo.seekTo(241_300)
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull(exo.playerError)
        val after = heard.toList().drop(before).filter { it.playerMs >= 241_000 }
        assertTrue("landed at ${after.first().songMs} ms", after.first().songMs in 240_300..242_300)
        assertHeardRightly(from = heard.indexOf(after.first()))
        assertTrue(heard.last().songMs in 299_950..299_999)
        // By time, from the whole second before; never by byte.
        assertEquals(listOf("timeOffset=0", "timeOffset=241"), asked.toList())
    }

    @Test
    fun aLoadThatTheMachineIsSlowToRunIsNotTakenForAStuckPlayer() {
        // The loading thread is held two seconds into the song, as a busy machine can leave it
        // without the processor, while the playback thread runs eleven minutes of the player's
        // made-up clock asking whether it is ready. (Media3's stuck-buffering detector, at its
        // ten minutes, failed the player here: ConvertedPlaybackTest once failed that way in a
        // release check, at its first `untilPositionAtLeast(2_000)`.)
        fiveMinutes()
        holdAt = tagBytes + 3 * askedKbps * (2_000 / 24)
        val exo = start()
        exo.play()
        androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil({ held.isDone }, NOT_FOR_EVER_MILLIS, androidx.media3.common.util.Clock.DEFAULT)
        val from = clock.elapsedRealtime()
        androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil(
            { clock.elapsedRealtime() - from > 11 * 60_000 || exo.playerError != null },
            NOT_FOR_EVER_MILLIS, androidx.media3.common.util.Clock.DEFAULT,
        )
        assertNull("taken for stuck", exo.playerError)
        open.countDown()
        advance(exo).untilPositionAtLeast(3_000)
        assertNull(exo.playerError)
        assertHeardRightly(from = 0)
    }

    @Test
    fun aSeekIntoTheLastSecondEndsTheSongAsAnyOther() {
        fiveMinutes()
        val exo = start()
        exo.play()
        advance(exo).untilPositionAtLeast(2_000)
        exo.seekTo(299_700)
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull("it failed instead of ending", exo.playerError)
        assertTrue("the last of the song wasn't played: ${heard.last().songMs} ms", heard.last().songMs in 299_950..299_999)
        assertEquals(listOf("timeOffset=0", "timeOffset=299"), asked.toList())
        // The very end: nothing is left to play, and that is an end too.
        heard.clear()
        asked.clear()
        exo.release()
        val again = start()
        again.play()
        advance(again).untilPositionAtLeast(1_000)
        again.seekTo(299_990)
        advance(again).untilState(Player.STATE_ENDED)
        assertNull("it failed instead of ending", again.playerError)
        assertEquals(2, asked.size)
    }

    @Test
    fun aSeekPastTheRealEndEndsTheSongToo() {
        // The library says the song is 330 seconds; what the server sends runs for 300. A seek
        // to 5:20 on the bar is past the last of it, and the server answers with a tag and
        // no audio at all.
        fiveMinutes()
        catalogueSeconds = 330
        val exo = start()
        exo.play()
        advance(exo).untilPositionAtLeast(2_000)
        exo.seekTo(320_000)
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull("it failed instead of ending", exo.playerError)
        // (From the whole second before the frame the player asked for.)
        assertEquals("timeOffset=0", asked.first())
        assertTrue(asked.toList().toString(), asked.size == 2 && asked.last() in setOf("timeOffset=319", "timeOffset=320"))
    }

    @Test
    fun aLowerRateThanAskedForStillSeeksAndEndsRightly() {
        // Asked for 320 kbps of a file that is only 160: the server sends 160.
        fiveMinutes()
        askedKbps = 320
        givenKbps = 160
        val exo = start()
        exo.play()
        advance(exo).untilPositionAtLeast(2_000)
        val before = heard.size
        exo.seekTo(150_000)
        advance(exo).untilPositionAtLeast(153_000)
        val landed = heard.toList().drop(before).first { it.playerMs >= 149_500 }
        assertTrue("landed at ${landed.songMs} ms", landed.songMs in 149_000..151_000)
        // And into its last second, which at the rate asked for would be half-way through.
        exo.seekTo(299_600)
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull("it failed instead of ending", exo.playerError)
        assertTrue(heard.last().songMs in 299_950..299_999)
        assertHeardRightly(from = heard.indexOf(landed))
        assertEquals(listOf("timeOffset=0", "timeOffset=150", "timeOffset=299"), asked.toList())
    }

    @Test
    fun anMp3TheServerSendsAsItIsSeeksToTheRightPlace() {
        // Asked for MP3 at 320 of a file that is an MP3 at 128 already: the server sends the
        // file, whatever time it is asked to start from.
        fiveMinutes()
        askedKbps = 320
        givenKbps = 128
        sendsTheFile = true
        val exo = start()
        exo.play()
        advance(exo).untilPositionAtLeast(2_000)
        val before = heard.size
        exo.seekTo(245_000)
        advance(exo).untilState(Player.STATE_ENDED)
        assertNull(exo.playerError)
        val landed = heard.toList().drop(before).first { it.playerMs >= 244_500 }
        // It began again from the start of the song here, with 4:05 on the clock.
        assertTrue("at ${landed.playerMs} ms on the clock, the frame for ${landed.songMs} ms was heard", landed.songMs in 244_900..245_100)
        assertHeardRightly(from = heard.indexOf(landed), within = 100)
        assertTrue(heard.last().songMs in 299_950..299_999)
        assertTrue(asked.toList().toString(), asked.size == 2 && asked.last().startsWith("timeOffset=0 bytes="))
    }
}
