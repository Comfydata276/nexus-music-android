package org.medianexus.music.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A place in a conversion asked for by byte, of a server that turns out not to honour the
 * byte: with the player's real data source and Media3's real HTTP data source under it, over
 * a real connection, because the fault is in what Media3's does. Told the place, and
 * answered from the start, it reads the answer up to the place and throws it away, inside
 * `open`; so what counts here is how much of each answer was actually taken off the wire.
 */
@RunWith(RobolectricTestRunner::class)
class RangedAnswerTest {
    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()

    /** Bytes of answer bodies read off the connection, for each answer in turn. */
    private val taken = CopyOnWriteArrayList<AtomicLong>()

    /** The server holds the whole conversion: a length, and byte ranges honoured. */
    private var holdsItWhole = true
    private val seconds = 240
    private val tagBytes = 572

    private fun conversion(offset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val body = tagBytes - 10
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            (body shr 21 and 0x7f).toByte(), (body shr 14 and 0x7f).toByte(), (body shr 7 and 0x7f).toByte(), (body and 0x7f).toByte()) + ByteArray(body) { 0x20 })
        var ms = offset * 1000
        // 128 kbps, MPEG-1 layer 3 at 48 kHz: 384 bytes and 24 ms a frame, each carrying the
        // time in the song at which it starts.
        while (ms < seconds * 1000) {
            out.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x94.toByte(), 0x44))
            out.write(byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            out.write(ByteArray(384 - 8) { 0x55 })
            ms += 24
        }
        return out.toByteArray()
    }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                asked += request.url.query.orEmpty() + (range?.let { " [$it]" } ?: "")
                val offset = request.url.queryParameter("timeOffset")?.toInt() ?: 0
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val bytes = conversion(offset)
                if (holdsItWhole && offset == 0) {
                    val answer = MockResponse.Builder().code(if (range != null) 206 else 200).setHeader("Accept-Ranges", "bytes")
                    if (range != null) answer.setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                    return answer.body(Buffer().write(bytes, from, bytes.size - from)).build()
                }
                // A fresh conversion: from its start (or the time asked for), whatever byte
                // was asked for, with no length.
                return MockResponse.Builder().setHeader("Accept-Ranges", "none").chunkedBody(Buffer().write(bytes), 8_192).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    /** OkHttp, counting what is read of each answer's body. */
    private val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val answer = chain.proceed(chain.request())
        val count = AtomicLong().also { taken += it }
        val body = answer.body
        val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) count.addAndGet(it) }
        }
        answer.newBuilder().body(counted.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }.build()

    private val form = StreamForm(128, "mp3")
    private val conversions = Conversions()

    private val leases = object : SongLeases {
        override fun generation() = 1L

        override fun lease(generation: Long) = object : SongLease {
            override val cache: Cache? = null
            override val offline = false
            override val conversions = this@RangedAnswerTest.conversions
            override fun resolve(songId: String) = SongWhere(form, cacheKey(songId, form)) { offset ->
                server.url("/rest/stream?id=$songId&format=mp3&maxBitRate=128" + if (offset > 0) "&timeOffset=$offset" else "").toString()
            }
            override fun release() {}
        }
    }

    private val sources = SongDataSource.Factory(leases, OkHttpDataSource.Factory(http))

    private fun read(at: Long, most: Int): ByteArray {
        val source: DataSource = sources.createDataSource()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec.Builder().setUri(songUri("one")).setPosition(at).build())
            val buffer = ByteArray(8_192)
            while (out.size() < most) {
                val got = source.read(buffer, 0, minOf(buffer.size, most - out.size()))
                if (got == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, got)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    private fun firstFrameMs(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0xfb.toByte() && bytes[i + 3] == 0x44.toByte()) {
                return (bytes[i + 4].toInt() and 0xff shl 24) or (bytes[i + 5].toInt() and 0xff shl 16) or (bytes[i + 6].toInt() and 0xff shl 8) or (bytes[i + 7].toInt() and 0xff)
            }
        }
        return -1
    }

    /** The byte of the whole conversion at which the frame for `ms` starts. */
    private fun byteFor(ms: Int) = tagBytes + ms / 24 * 384L

    @Test
    fun aRangeTheServerHonoursIsReadFromThatByte() {
        read(0, 4_000)
        val place = byteFor(180_000)
        asked.clear()
        assertEquals(180_000, firstFrameMs(read(place, 2_000)))
        assertTrue(asked.single(), "[bytes=$place-]" in asked.single())
    }

    @Test
    fun anAnswerThatIgnoredTheRangeIsNotReadUpToThePlace() {
        // Its start said it takes byte ranges. Then the server let its whole copy go.
        read(0, 4_000)
        assertTrue(conversions.heads[cacheKey("one", form)]!!.ranged)
        holdsItWhole = false
        asked.clear()
        taken.clear()
        // Three minutes in: 2.9 MB from the start.
        val place = byteFor(180_000)
        val got = read(place, 2_000)
        // Asked for by byte, as remembered; answered with the song from its start. That
        // answer used to be read through to the place inside Media3's `open` (2.9 MB fetched
        // and thrown away, and then the song played from its start at 3:00 on the clock,
        // the bytes being a different conversion's).
        assertTrue(asked[0], "[bytes=$place-]" in asked[0])
        assertTrue("${taken[0].get()} bytes of the answer that ignored the range were read", taken[0].get() < 100_000)
        // Asked for by time instead, and arrived at the place.
        assertEquals(2, asked.size)
        assertTrue(asked[1], "timeOffset=" in asked[1] && "bytes=" !in asked[1])
        assertTrue("landed at ${firstFrameMs(got)} ms", firstFrameMs(got) in 179_976..180_024)
        assertFalse(conversions.heads[cacheKey("one", form)]!!.ranged)
    }
}
