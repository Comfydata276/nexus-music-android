package org.medianexus.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
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
import androidx.media3.test.utils.TestExoPlayerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.library.Availability
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A song keeps playing from the copy it started on, on the real ExoPlayer with Media3's
 * progressive source and MP3 extractor reading through the app's data source.
 *
 * There are two copies of the song here, at different bit rates: the server's stream (128
 * kbps) and a downloaded file (320 kbps). Every frame of each carries the time in the song it
 * belongs to, and a renderer standing where the decoder does reads it back beside the time
 * the player gives the frame. Media3 turns a seek into a byte in the copy it has been
 * reading; that byte in the other copy is a different place in the song, which is what would
 * be heard, and is what these tests look for.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedCopyTest {
    @get:Rule val folder = TemporaryFolder()

    private val seconds = 120
    private val tagBytes = 572

    private fun tag(size: Int): ByteArray {
        val body = size - 10
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            (body shr 21 and 0x7f).toByte(), (body shr 14 and 0x7f).toByte(), (body shr 7 and 0x7f).toByte(), (body and 0x7f).toByte()) + ByteArray(body) { 0x20 }
    }

    /** The song at `kbps`: a tag, then constant bit rate frames (MPEG-1 layer 3 at 48 kHz: 24
     *  ms and 3 bytes a kbps each), each carrying the time in the song at which it starts. */
    private fun copyAt(kbps: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(tagBytes))
        val index = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320).indexOf(kbps)
        var ms = 0
        while (ms < seconds * 1000) {
            out.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), (index shl 4 or 0x04).toByte(), 0x44))
            out.write(byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            out.write(ByteArray(3 * kbps - 8) { 0x55 })
            ms += 24
        }
        return out.toByteArray()
    }

    private val stream = copyAt(128)
    private val asked = CopyOnWriteArrayList<Long>()

    /** The server, sending the 128 kbps file as it is: its length, and byte ranges honoured. */
    private inner class Server : BaseDataSource(true), HttpDataSource {
        private var at = 0
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val from = dataSpec.httpRequestHeaders["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: dataSpec.position
            asked += from
            at = from.toInt()
            headers = mapOf("Accept-Ranges" to listOf("bytes"), "Content-Type" to listOf("audio/mpeg")) +
                if (from > 0) mapOf("Content-Range" to listOf("bytes $from-${stream.size - 1}/${stream.size}")) else emptyMap()
            return (stream.size - at).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= stream.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, stream.size - at, 4_096)
            stream.copyInto(buffer, offset, at, at + count)
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

    /** What the player handed on to be heard: the time it gave the frame, and the time in the
     *  song the frame says it is, both in milliseconds. */
    private data class Heard(val playerMs: Long, val songMs: Int)

    private val heard = CopyOnWriteArrayList<Heard>()

    private inner class Listener : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private val formats = FormatHolder()
        private var ended = false
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
                        if (data.remaining() >= 8 && data.get(0) == 0xff.toByte() && data.get(3) == 0x44.toByte()) {
                            waiting = Heard((buffer.timeUs - streamOffsetUs) / 1000, data.getInt(4))
                        }
                    }
                    else -> return
                }
            }
        }
    }

    /** The song's downloaded copy, when there is one: what `resolve` finds at this moment. */
    @Volatile private var downloaded: File? = null
    private val opened = CopyOnWriteArrayList<SongCopy>()

    private val leases = object : SongLeases {
        override fun generation() = 1L
        override fun lease(generation: Long) = object : SongLease {
            override val cache = null
            override val offline = false
            override val conversions = Conversions()
            override fun resolve(songId: String): SongWhere {
                // The file as it is (an MP3 within the limit), streamed; or its download.
                val form = StreamForm(0, "mp3")
                return SongWhere(form, cacheKey(songId, form), 0, downloaded?.takeIf { it.isFile }, 320) { "http://server.invalid/rest/stream?id=$songId" }
            }
            override fun opened(songId: String, copy: SongCopy) {
                opened += copy
            }
            override fun release() {}
        }
    }

    private var player: ExoPlayer? = null

    private fun start(): ExoPlayer {
        val sources = SongDataSource.Factory(leases, DataSource.Factory { Server() }, pause = {})
        val made = TestExoPlayerBuilder(RuntimeEnvironment.getApplication())
            .setRenderers(Listener())
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources, songExtractors()).setLoadErrorHandlingPolicy(RideOut()))
            .build()
        player = made
        made.setMediaItem(MediaItem.Builder().setMediaId("one").setUri(songUri("one")).build())
        made.prepare()
        return made
    }

    @After
    fun stop() {
        player?.release()
    }

    private fun download(): File = File(folder.root, "one.mp3").also { it.writeBytes(copyAt(320)) }

    /** Every frame heard from `from` on is the frame for the time the player gave it. */
    private fun assertHeardRightly(from: Int) {
        val frames = heard.toList().drop(from)
        assertTrue("nothing was heard", frames.isNotEmpty())
        val worst = frames.maxBy { kotlin.math.abs(it.playerMs - it.songMs) }
        assertTrue("at ${worst.playerMs} ms on the clock, the frame for ${worst.songMs} ms was heard", kotlin.math.abs(worst.playerMs - worst.songMs) <= 100)
    }

    @Test
    fun aSeekAfterItsDownloadArrivesStillReadsTheCopyItStartedOn() {
        val exo = start()
        exo.play()
        advance(exo).untilPosition(0, 5_000)
        assertEquals(listOf<SongCopy>(SongCopy.Stream(StreamForm(0, "mp3"))), opened.toList())
        // The song's download finishes while it plays: from now on, the song resolves to it.
        downloaded = download()
        // A seek, past what has been read ahead. Media3 asks for the byte where second 100 is
        // in the stream it has been reading; in the 320 kbps file that byte is second 40.
        val before = heard.size
        exo.seekTo(100_000)
        advance(exo).untilPosition(0, 105_000)
        assertNull(exo.playerError)
        assertTrue("nothing after the seek", heard.size > before)
        assertTrue("it went on from ${heard[before].songMs} ms", heard[before].songMs in 99_000..101_000)
        assertHeardRightly(before)
        // It was the server that was asked for that byte: the download was never opened.
        assertTrue("it asked for byte ${asked.last()}", asked.last() in (tagBytes + 99L * 16_000)..(tagBytes + 100L * 16_000))
        assertEquals(1, opened.size)
    }

    @Test
    fun aDownloadRemovedWhileItPlaysIsMadeReadyAgainFromItsTimeNotItsByte() {
        downloaded = download()
        val exo = start()
        exo.play()
        advance(exo).untilPosition(0, 5_000)
        assertTrue(opened.single() is SongCopy.Download)
        assertEquals(320, opened.single().kbps)
        assertTrue(asked.isEmpty())
        // Removed from downloads while it plays.
        downloaded!!.delete()
        downloaded = null
        // A seek, past what has been read ahead: the byte Media3 asks for is where second 100
        // is in the 320 kbps file, which in the stream is past its end. Nothing is opened
        // there: the read says its copy has gone, and is not tried again as a lost connection
        // is.
        val before = heard.size
        exo.seekTo(100_000)
        advance(exo).untilPlayerError()
        val failure = exo.playerError!!.cause as StreamFailure
        assertTrue(failure.moved)
        assertTrue("the stream was asked for a byte of the download: $asked", asked.isEmpty())
        // What the service does with that (see PlaybackService.onPlayerError): made ready
        // again where it is. That is a seek by time, on a fresh read of whatever is best now.
        exo.prepare()
        advance(exo).untilPosition(0, 105_000)
        assertNull(exo.playerError)
        assertTrue("it went on from ${heard[before].songMs} ms", heard[before].songMs in 99_000..101_000)
        assertHeardRightly(before)
        assertEquals(SongCopy.Stream(StreamForm(0, "mp3")), opened.last())
    }

    @Test
    fun aDownloadReplacedWhileItPlaysIsNotReadOnAtTheOldFilesByte() {
        downloaded = download()
        val exo = start()
        exo.play()
        advance(exo).untilPosition(0, 5_000)
        // Downloaded again at another quality: a file at the same name, and not the same file.
        downloaded!!.writeBytes(copyAt(160))
        downloaded!!.setLastModified(downloaded!!.lastModified() + 5_000)
        exo.seekTo(100_000)
        advance(exo).untilPlayerError()
        assertTrue((exo.playerError!!.cause as StreamFailure).moved)
    }

    // ---- What a failure is laid at -----------------------------------------------------------

    @Test
    fun whatIsSetAsideIsTheFileAndADecoderOrOutputErrorBlamesNothing() {
        val dir = folder.newFolder("downloads")
        val catalogue = Catalogue(dir)
        fun put(modified: Long): File {
            val file = File(dir, "s1.flac").also { it.writeBytes(ByteArray(10)) }
            catalogue.add(Downloaded(Song(id = "s1", title = "One"), "s1.flac", 10, at = modified / 1000, suffix = "flac", modified = modified))
            return file
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val availability = Availability(catalogue, KeptIndex(File(folder.root, "kept.json"), scope, kotlinx.coroutines.Dispatchers.Unconfined), cap = { 0 }, keeping = { false })
        val file = put(1_000_000)
        assertTrue(availability.isDownloaded("s1"))
        // That file couldn't be parsed: set aside, once.
        assertTrue(availability.setAside("s1", file, 1_000_000))
        assertFalse(availability.setAside("s1", file, 1_000_000))
        assertFalse(availability.isDownloaded("s1"))
        assertTrue(availability.isSetAside("s1"))
        // Downloaded again: another file, with a fresh chance.
        catalogue.remove(listOf("s1"))
        put(2_000_000)
        assertTrue(availability.isDownloaded("s1"))
        // And a failure of the file that was there before is not laid at the one there now.
        assertFalse(availability.setAside("s1", file, 1_000_000))
        assertTrue(availability.isDownloaded("s1"))

        // Which failures are the file's: it couldn't be read or parsed, or holds what the
        // phone has no way to play.
        assertTrue(Recovery.laidAtTheFile(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, false))
        assertTrue(Recovery.laidAtTheFile(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, local = true))
        assertTrue(Recovery.laidAtTheFile(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, false))
        // A decoder that fell over or couldn't be had, the sound output, the network: not.
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )) assertFalse("code $code", Recovery.laidAtTheFile(code, false))
    }

    @Test
    fun theFormatLineIsOfTheCopyBeingPlayed() {
        val flac = Song(suffix = "flac", samplingRate = 48_000, bitDepth = 24, bitRate = 2_100)
        // Still playing the stream it began on at 160 kbps, though the original has since
        // been downloaded; and the other way about.
        assertEquals(FormatLine("MP3 · 160 kbps", false), formatLineOf(flac, 160, "Live stream"))
        assertEquals(FormatLine("FLAC · 48 kHz · 24-bit", true), formatLineOf(flac, 0, "Live stream"))
        assertEquals(FormatLine("Live stream", false), formatLineOf(Song(radioUrl = "http://radio"), 0, "Live stream"))
    }
}
