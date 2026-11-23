package org.medianexus.music.playback

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The reader behind the player's data source, against a stand-in for Navidrome that streams
 * conversions the way the real one was seen to (see docs/DESIGN.md): no length and no ranges
 * for a fresh one, `timeOffset` exact, the rate it chooses rather than the rate asked for,
 * and a tag before the audio.
 */
class SongReaderTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val asked = CopyOnWriteArrayList<String>()

    // ---- The stand-in server ------------------------------------------------------------------

    /** How long the song really is, in seconds. */
    private var seconds = 60
    /** The rate the server converts at, whatever is asked for (0: as asked). */
    private var givenKbps = 0
    private var tagBytes = 572
    /** The server has the whole conversion in its own cache: it says how long it is and
     *  honours ranges. */
    private var warm = false
    /** The server ends a conversion cleanly after this many bytes (-1: at its real end). */
    private var endsAfter = -1L
    /** The connection drops part-way through a conversion asked for from its start. */
    private var drops = false
    /** It drops part-way through whatever is asked for, every time. */
    private var alwaysDrops = false
    /** The song is already an MP3 within the limit: asked for "MP3 at the limit", the server
     *  sends the file as it is, with its length, whatever time it was asked to start from. */
    private var sendsTheFile = false
    /** The waits the reader asked for before asking for a stream again. */
    private val waits = mutableListOf<Long>()

    /** An ID3v2 tag of this many bytes in all. */
    private fun tag(size: Int): ByteArray {
        val body = size - 10
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            (body shr 21 and 0x7f).toByte(), (body shr 14 and 0x7f).toByte(), (body shr 7 and 0x7f).toByte(), (body and 0x7f).toByte()) + ByteArray(body) { 0x20 }
    }

    /**
     * A conversion at `kbps`, started `offset` seconds in: the tag, then constant bit rate
     * frames (MPEG-1 layer 3 at 48 kHz, so each is 24 ms and 3 bytes a kbps) that each carry,
     * after a real frame header, the time in the song at which they start, in milliseconds.
     */
    private fun conversion(kbps: Int, offset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(tagBytes))
        val index = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320).indexOf(kbps)
        val frame = 3 * kbps
        var ms = offset * 1000
        while (ms < seconds * 1000) {
            out.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), (index shl 4 or 0x04).toByte(), 0x44))
            out.write(byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            out.write(ByteArray(frame - 8) { 0x55 })
            ms += 24
        }
        return out.toByteArray()
    }

    /** The file as it is on the server: any bytes will do. */
    private val original = ByteArray(300_000) { (it * 31 + it / 251).toByte() }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val range = request.headers["Range"]
                asked += url.query.orEmpty() + (range?.let { " [$it]" } ?: "")
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0
                if (url.queryParameter("format") == "raw") {
                    val body = Buffer().write(original, from.toInt(), original.size - from.toInt())
                    return MockResponse.Builder().code(if (range != null) 206 else 200).setHeader("Accept-Ranges", "bytes").body(body).build()
                }
                val rate = givenKbps.takeIf { it > 0 } ?: url.queryParameter("maxBitRate")!!.toInt()
                val offset = url.queryParameter("timeOffset")?.toInt() ?: 0
                var bytes = conversion(rate, if (sendsTheFile) 0 else offset)
                if ((offset == 0 && warm) || sendsTheFile) {
                    val body = Buffer().write(bytes, from.toInt(), bytes.size - from.toInt())
                    return MockResponse.Builder().code(if (range != null) 206 else 200).setHeader("Accept-Ranges", "bytes").body(body).build()
                }
                // Fresh: from its own start whatever range was asked for, in chunks, with no
                // length.
                if (endsAfter >= 0) bytes = bytes.copyOf(minOf(endsAfter, bytes.size.toLong()).toInt())
                val response = MockResponse.Builder().setHeader("Accept-Ranges", "none")
                if ((offset == 0 && drops) || alwaysDrops) {
                    // Some of it is sent, and the line goes dead.
                    return response.chunkedBody(Buffer().write(bytes), 8_192).onResponseBody(SocketEffect.CloseSocket()).build()
                }
                return response.chunkedBody(Buffer().write(bytes), 8_192).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    // ---- The stand-in for Media3's data sources -------------------------------------------------

    /** The kept songs: what the cache data source does, in a map. It keeps what goes by,
     *  from the start, and knows a copy's length once it has been read to its end. */
    private inner class Streams(private val keeps: Boolean = true) : SongStreams {
        inner class Kept(val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), var length: Long = -1, var marked: Boolean = false)
        val kept = HashMap<String, Kept>()
        /** A kept copy that fails after this many bytes are read from it (-1: never). */
        var keptFailsAfter = -1L
        /** Bytes read from an answer that began at the song's start when a byte further in
         *  was asked for: read only to be thrown away. */
        var discarded = 0L

        override fun server(url: String, position: Long): Opened {
            val request = Request.Builder().url(url)
            if (position > 0) request.header("Range", "bytes=$position-")
            val response = http.newCall(request.build()).execute()
            // A server that ignores the range answers from the song's start. Nothing is read
            // here to make up for that (the real data source is kept from doing so too: see
            // RangedAnswerTest); whatever the reader then reads of it was read for nothing.
            val honoured = position == 0L || response.code == 206
            val stream = response.body.byteStream()
            return Opened(
                object : ByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int) = stream.read(buffer, offset, length).also { if (!honoured && it > 0) discarded += it }
                    override fun close() = response.close()
                },
                response.header("Content-Length")?.toLongOrNull() ?: -1,
                response.header("Accept-Ranges") == "bytes",
                honoured,
            )
        }

        override fun kept(url: String, key: String, position: Long): Opened? {
            if (!keeps) return null
            val entry = kept.getOrPut(key) { Kept() }
            val have = entry.bytes.size().toLong()
            if (entry.length >= 0 && have == entry.length) return keptOnly(key, position)
            // What is kept so far, then the rest from the server, kept as it goes by.
            assertTrue("this stand-in only keeps from the start", position <= have)
            val upstream = server(url, have)
            // A length the server gives is noted straight away, as the real cache notes it.
            if (upstream.length >= 0) entry.length = have + upstream.length
            var at = position
            return Opened(
                object : ByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val held = entry.bytes.toByteArray()
                        if (at < held.size) {
                            val n = minOf(length.toLong(), held.size - at).toInt()
                            held.copyInto(buffer, offset, at.toInt(), at.toInt() + n)
                            at += n
                            return n
                        }
                        val got = upstream.source.read(buffer, offset, length)
                        if (got < 0) {
                            entry.length = entry.bytes.size().toLong()
                            return -1
                        }
                        entry.bytes.write(buffer, offset, got)
                        at += got
                        return got
                    }
                    override fun close() = upstream.source.close()
                },
                if (upstream.length >= 0) upstream.length + have - position else -1,
                upstream.ranged,
            )
        }

        override fun keptOnly(key: String, position: Long): Opened {
            val entry = kept[key] ?: throw IOException("nothing kept")
            val held = entry.bytes.toByteArray()
            var at = position
            var given = 0L
            return Opened(
                object : ByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (keptFailsAfter in 0..given) throw IOException("a kept file has been cleared away")
                        if (at >= held.size) return -1
                        var n = minOf(length.toLong(), held.size - at).toInt()
                        if (keptFailsAfter >= 0) n = minOf(n.toLong(), keptFailsAfter - given).toInt()
                        held.copyInto(buffer, offset, at.toInt(), at.toInt() + n)
                        at += n
                        given += n
                        return n
                    }
                    override fun close() {}
                },
                held.size - position,
            )
        }

        /** Run by whoever next looks for a whole copy, once it has looked and before it has
         *  its answer: where a test lets another read get on. */
        var whenLookedAt: (() -> Unit)? = null

        override fun wholeLength(key: String, converted: Boolean): Long {
            val length = kept[key]?.takeIf { it.length >= 0 && it.bytes.size().toLong() == it.length && (it.marked || !converted) }?.length ?: -1
            whenLookedAt?.also { whenLookedAt = null }?.invoke()
            return length
        }

        override fun keep(key: String, length: Long): Boolean {
            val entry = kept[key]?.takeIf { it.length == length && it.bytes.size().toLong() == length } ?: return false
            entry.marked = true
            return true
        }

        override fun drop(key: String) {
            kept.remove(key)
        }
    }

    private val heads = ConversionHeads()

    /** A song converted to MP3 at `kbps` (as asked; the server may give another rate). */
    private fun converted(kbps: Int = 128, catalogueSeconds: Int = seconds, fileKbps: Int = 0) = SongWhere(
        StreamForm(kbps, "mp3"), "song:s1:$kbps", leastBytes(catalogueSeconds, kbps, fileKbps),
    ) { offset -> server.url("/rest/stream?id=s1&format=mp3&maxBitRate=$kbps" + if (offset > 0) "&timeOffset=$offset" else "").toString() }

    private fun asItIs() = SongWhere(StreamForm(0, "flac"), "song:s1:0") { server.url("/rest/stream?id=s1&format=raw").toString() }

    /** Reads up to `most` bytes (all of it by default) from where the reader is. */
    private fun SongReader.take(most: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(7_000)
        while (out.size() < most) {
            val got = read(buffer, 0, minOf(buffer.size, most - out.size()))
            if (got < 0) break
            out.write(buffer, 0, got)
        }
        return out.toByteArray()
    }

    /** The time, in milliseconds, that the first whole frame in these bytes starts at: where
     *  the player, which looks for the next frame after a seek, begins to play. */
    private fun firstFrameMs(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0xfb.toByte() && bytes[i + 3] == 0x44.toByte()) {
                return (bytes[i + 4].toInt() and 0xff shl 24) or (bytes[i + 5].toInt() and 0xff shl 16) or (bytes[i + 6].toInt() and 0xff shl 8) or (bytes[i + 7].toInt() and 0xff)
            }
        }
        fail("no frame in ${bytes.size} bytes")
        return -1
    }

    /** The byte the player asks for when it seeks to `ms`: worked out, as Media3's constant
     *  bit rate seeker does, from where the audio starts and the rate of the first frame. */
    private fun byteFor(ms: Int, kbps: Int): Long {
        val frame = 3L * kbps
        return tagBytes + (ms.toLong() * kbps * 125 / 1000) / frame * frame
    }

    // ---- From the start ---------------------------------------------------------------------------

    @Test
    fun aConversionIsPlayedToTheServersEndWhateverTheCatalogueSays() {
        // The catalogue says 40 seconds; the conversion runs for 60. A length worked out
        // from the catalogue used to be enforced as the end, and the last 20 seconds cut.
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted(catalogueSeconds = 40)
        assertEquals("no length is promised", -1L, reader.open(where, 0))
        val all = reader.take()
        reader.close()
        assertArrayEquals(conversion(128, 0), all)
        assertEquals(59_976, firstFrameMs(all.copyOfRange(all.size - 384, all.size)))
        // It was read from its first byte to a clean end, so it is kept, whole.
        assertEquals(all.size.toLong(), streams.wholeLength(where.key))
        // And what it said about itself was noted: the audio starts after the tag, at 16,000
        // bytes a second.
        assertEquals(ConversionHead(572, 16_000), heads[where.key])
    }

    @Test
    fun aKeptConversionIsReadFromThePhoneAndTheServerIsNotAsked() {
        val streams = Streams()
        val where = converted()
        SongReader(streams, heads, pause = { waits += it }).apply { open(where, 0); take(); close() }
        val before = asked.size
        val again = SongReader(streams, heads, pause = { waits += it })
        // Now its length is known, from any place in it.
        assertEquals(conversion(128, 0).size.toLong(), again.open(where, 0))
        assertArrayEquals(conversion(128, 0), again.take())
        again.close()
        val place = byteFor(30_000, 128)
        assertEquals(conversion(128, 0).size - place, again.open(where, place))
        assertEquals(30_000, firstFrameMs(again.take(2_000)))
        again.close()
        assertEquals("nothing more was asked of the server", before, asked.size)
    }

    // ---- Anywhere but the start -------------------------------------------------------------------

    @Test
    fun aSeekAsksForTheSecondBeforeAndLandsOnThePlaceAsked() {
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        // The player reads the start (which is where it learns the rate), then seeks.
        reader.open(where, 0)
        reader.take(4_000)
        reader.close()
        for (ms in listOf(37_300, 1_500, 59_000, 400)) {
            asked.clear()
            val place = byteFor(ms, 128)
            assertEquals(-1L, reader.open(where, place))
            val landed = firstFrameMs(reader.take(2_000))
            reader.close()
            // Within a frame or two of where the player thinks that byte is; never the best
            // part of a second out, let alone several.
            val expected = ((place - tagBytes) / 16).toInt()
            assertTrue("asked for $ms ms (byte $place, which is $expected ms), landed on $landed ms", landed in expected - 30..expected + 30)
            // By time, never by a byte range, which this server answers from the very start.
            assertEquals(1, asked.size)
            assertFalse(asked.single(), "Range" in asked.single() || "bytes=" in asked.single())
            // From the whole second before that place (or, inside the first second, from
            // the start).
            val second = (place - tagBytes) / 16_000
            if (second > 0) assertTrue(asked.single(), asked.single().endsWith("timeOffset=$second")) else assertFalse(asked.single(), "timeOffset" in asked.single())
        }
    }

    @Test
    fun theRateAndTheTagAreReadFromTheStreamNotAssumed() {
        // Asked for 320 kbps of a file that is 161: the server sends 160. And the tag is
        // long (lyrics): 40,000 bytes is two seconds at this rate. Worked out from the rate
        // asked for and from byte 0, a seek to 40 seconds asked the server for second 20.
        givenKbps = 160
        tagBytes = 40_000
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted(kbps = 320, fileKbps = 161)
        reader.open(where, 0)
        // The tag goes by in pieces before the first frame does.
        reader.take(45_000)
        reader.close()
        assertEquals(ConversionHead(40_000, 20_000), heads[where.key])
        asked.clear()
        val place = byteFor(40_000, 160)
        reader.open(where, place)
        val landed = firstFrameMs(reader.take(2_000))
        reader.close()
        assertTrue("landed on $landed ms", landed in 39_950..40_030)
        // (The byte for 40 seconds is the start of the frame that holds it, just before.)
        assertTrue(asked.single(), asked.single().endsWith("timeOffset=39"))
    }

    @Test
    fun aSeekBeforeAnythingIsKnownOfTheConversionStillArrivesSomewhereSensible() {
        // Nothing read from the start first (it doesn't happen with the player, which reads
        // the start to find out what it is playing): the rate asked for is the best guess.
        val reader = SongReader(Streams(), ConversionHeads(), pause = { waits += it })
        reader.open(converted(), byteFor(20_000, 128))
        val landed = firstFrameMs(reader.take(2_000))
        reader.close()
        assertTrue("landed on $landed ms", landed in 19_900..20_100)
    }

    // ---- What is kept -----------------------------------------------------------------------------

    @Test
    fun partOfAConversionIsNeverKeptAndNeverPiecedTogether() {
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        // The start is read, and then the player moves on (a seek, the next song).
        reader.open(where, 0)
        reader.take(100_000)
        reader.close()
        assertEquals(-1L, streams.wholeLength(where.key))
        assertNull("what was read of it is forgotten", streams.kept[where.key])
        // Played again from the start: asked for whole, from the start. One kept byte used
        // to send the rest to the server as a range, which it answers from the beginning.
        asked.clear()
        reader.open(where, 0)
        assertArrayEquals(conversion(128, 0), reader.take())
        reader.close()
        assertEquals(1, asked.size)
        assertFalse(asked.single(), "bytes=" in asked.single() || "timeOffset" in asked.single())
        assertEquals(conversion(128, 0).size.toLong(), streams.wholeLength(where.key))
        // A conversion started part-way is not the whole song and is not kept either.
        val other = converted(kbps = 96)
        reader.open(other, byteFor(30_000, 96))
        reader.take()
        reader.close()
        assertEquals(-1L, streams.wholeLength(other.key))
    }

    @Test
    fun aConversionThatEndsEarlyButCleanlyIsNotTakenForTheWholeSong() {
        // The server (or something between) ends it a third of the way through, properly
        // closed. It is the end as far as this play goes; it must not be the song from now on.
        endsAfter = conversion(128, 0).size / 3L
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        reader.open(where, 0)
        assertEquals(endsAfter, reader.take().size.toLong())
        reader.close()
        assertEquals(-1L, streams.wholeLength(where.key))
        assertNull(streams.kept[where.key])
        // The same check Windows applies: four fifths of what the song must come to, at the
        // lower of the rate asked for and the file's own.
        assertEquals(60L * 128 * 125 * 4 / 5, where.least)
        assertEquals(300L * 161 * 125 * 4 / 5, leastBytes(300, 320, 161))
        assertEquals(300L * 128 * 125 * 4 / 5, leastBytes(300, 128, 161))
        assertEquals(300L * 320 * 125 * 4 / 5, leastBytes(300, 320, 0))
        // Next time the server sends all of it, and that is kept.
        endsAfter = -1
        reader.open(where, 0)
        reader.take()
        reader.close()
        assertEquals(conversion(128, 0).size.toLong(), streams.wholeLength(where.key))
    }

    /** The time each frame in these bytes starts at, in order. */
    private fun frames(bytes: ByteArray): List<Int> {
        val times = ArrayList<Int>()
        var i = 0
        while (i < bytes.size - 8) {
            if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0xfb.toByte() && bytes[i + 3] == 0x44.toByte()) {
                times += (bytes[i + 4].toInt() and 0xff shl 24) or (bytes[i + 5].toInt() and 0xff shl 16) or (bytes[i + 6].toInt() and 0xff shl 8) or (bytes[i + 7].toInt() and 0xff)
                i += 8
            } else {
                i++
            }
        }
        return times
    }

    @Test
    fun aDroppedConnectionCarriesOnFromWhereItGotToWithinTheSameRead() {
        // The line goes dead part-way through. The player is told nothing: left to itself it
        // takes a stream of unknown length for a broadcast and starts a failed one again from
        // its beginning. The reader asks for the song again from the time it had reached, and
        // the bytes carry on.
        drops = true
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        reader.open(where, 0)
        val all = reader.take()
        reader.close()
        val times = frames(all)
        // From the first frame to the real end of the song, never going back.
        assertEquals(0, times.first())
        assertTrue("ended at ${times.last()} ms", times.last() in 59_970..59_999)
        assertTrue("the song went back on itself", times.zipWithNext().all { (a, b) -> b > a })
        // Carried on within a second's worth of frames of where it stopped (and not a frame
        // of it repeated: what was already given is thrown away up to the place).
        val gaps = times.zipWithNext().map { (a, b) -> b - a }
        assertTrue("a gap of ${gaps.max()} ms", gaps.max() <= 48)
        // Asked for once from the start and once from a time, after the first wait.
        assertEquals(2, asked.size)
        assertTrue(asked[1], "timeOffset=" in asked[1] && "bytes=" !in asked[1])
        assertEquals(listOf(1_000L), waits)
        // What was kept of the first part is not a copy of the song, and isn't kept.
        assertNull(streams.kept[where.key])
        assertEquals(-1L, streams.wholeLength(where.key))
    }

    @Test
    fun aStreamThatKeepsFailingIsGivenUpAsFinalAfterTheUsualWaits() {
        alwaysDrops = true
        val reader = SongReader(Streams(), heads, pause = { waits += it })
        reader.open(converted(), 0)
        try {
            reader.take()
            fail("read to an end through a connection that never stays up")
        } catch (e: GaveUp) {
            // The player doesn't begin a round of its own after this one.
        }
        reader.close()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
    }

    @Test
    fun aRefusalIsNotAskedForAgain() {
        drops = true
        val refusing = object : SongStreams by Streams() {
            override fun mayPass(error: IOException) = false
        }
        val reader = SongReader(refusing, heads, pause = { waits += it })
        reader.open(converted(), 0)
        try {
            reader.take()
            fail("carried on after a failure that can't pass")
        } catch (e: IOException) {
            assertFalse(e is GaveUp)
        }
        reader.close()
        assertTrue(waits.isEmpty())
        assertEquals(1, asked.size)
    }

    @Test
    fun anMp3TheServerSendsAsItIsIsAskedForByByteNotByTime() {
        // Asked for MP3 at 320 kbps, of a file that is already an MP3 under that: Navidrome
        // converts nothing. It sends the file, says how long it is and that it takes byte
        // ranges, and takes no notice of a time to start from. Asked for "from second 45",
        // it sent the whole file again, and the song began again at 0:45 on the clock.
        sendsTheFile = true
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted(kbps = 320, fileKbps = 128)
        givenKbps = 128
        val whole = conversion(128, 0).size.toLong()
        assertEquals(whole, reader.open(where, 0))
        reader.take(4_000)
        reader.close()
        assertTrue(heads[where.key]!!.ranged)
        asked.clear()
        val place = byteFor(45_000, 128)
        assertEquals("told how much is left, as of a file", whole - place, reader.open(where, place))
        val rest = reader.take()
        reader.close()
        assertEquals(45_000, firstFrameMs(rest))
        assertTrue(asked.single(), "bytes=$place-" in asked.single() && "timeOffset" !in asked.single())
        assertTrue(frames(rest).last() in 59_970..59_999)
    }

    @Test
    fun aKeptCopyThatGivesOutPartWayCarriesOnFromTheServer() {
        val streams = Streams()
        val where = converted()
        SongReader(streams, heads, pause = { waits += it }).apply { open(where, 0); take(); close() }
        // The phone clears part of it away while it is being played.
        streams.keptFailsAfter = 300_000
        asked.clear()
        val reader = SongReader(streams, heads, pause = { waits += it })
        reader.open(where, 0)
        val all = reader.take()
        reader.close()
        // Not an error, and not the end: the rest came from the server, from the right time.
        assertTrue(firstFrameMs(all.copyOfRange(all.size - 384, all.size)) in 59_970..59_999)
        assertTrue(asked.single(), "timeOffset=${(300_000 - tagBytes) / 16_000}" in asked.single())
        val due = ((300_000 - tagBytes) / 16)
        assertTrue(firstFrameMs(all.copyOfRange(300_000, 302_000)) in due - 30..due + 30)
        // And the broken copy is forgotten.
        assertNull(streams.kept[where.key])
    }

    @Test
    fun aConversionTheServerHasWholeIsOpenedByByteAndKeptWhenReadThrough() {
        // The server says how long it is and that it takes byte ranges: it has the whole
        // conversion in its own cache. Its start has said so by the time anywhere else is
        // asked for, so a place in it is asked for as a byte, which is exact.
        warm = true
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        assertEquals(conversion(128, 0).size.toLong(), reader.open(where, 0))
        reader.take(4_000)
        reader.close()
        asked.clear()
        reader.open(where, byteFor(45_000, 128))
        assertEquals(45_000, firstFrameMs(reader.take(2_000)))
        reader.close()
        assertTrue(asked.single(), "bytes=${byteFor(45_000, 128)}-" in asked.single() && "timeOffset" !in asked.single())
        // Told the length, the player reads exactly that many bytes and closes: it never
        // reads once more to be told "that's the end". That is still a read to the end.
        val length = reader.open(where, 0)
        assertEquals(length.toInt(), reader.take(length.toInt()).size)
        reader.close()
        assertEquals(conversion(128, 0).size.toLong(), streams.wholeLength(where.key))
        // But stopping short of the length it was told is not.
        streams.drop(where.key)
        reader.open(where, 0)
        reader.take(length.toInt() - 1)
        reader.close()
        assertEquals(-1L, streams.wholeLength(where.key))
    }

    @Test
    fun withNoKeptSongsAConversionIsReadStraightFromTheServer() {
        val streams = Streams(keeps = false)
        val reader = SongReader(streams, heads, pause = { waits += it })
        reader.open(converted(), 0)
        assertArrayEquals(conversion(128, 0), reader.take())
        reader.close()
        assertEquals(-1L, streams.wholeLength(converted().key))
    }

    // ---- Offline mode -----------------------------------------------------------------------------

    @Test
    fun inOfflineModeNothingIsAskedOfTheServer() {
        // Offline mode stopped the pages being fetched and left the player fetching songs.
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        for (where in listOf(converted(), asItIs())) {
            asked.clear()
            try {
                reader.open(where, 0, offline = true)
                fail("a song that isn't kept was opened in offline mode")
            } catch (_: NotKeptOffline) {
            }
            reader.close()
            assertEquals("the server was asked for ${where.key}", 0, asked.size)
            // Played through once while online, it is kept whole...
            reader.open(where, 0)
            val all = reader.take()
            reader.close()
            asked.clear()
            // ...and then plays offline, from the start or anywhere in it, from the phone.
            assertEquals(all.size.toLong(), reader.open(where, 0, offline = true))
            assertArrayEquals(all, reader.take())
            reader.close()
            assertEquals(all.size - 50_000L, reader.open(where, 50_000, offline = true))
            assertArrayEquals(all.copyOfRange(50_000, all.size), reader.take())
            reader.close()
            assertEquals(0, asked.size)
        }
        // Part of a song is not the song.
        val part = converted(kbps = 96)
        reader.open(part, 0)
        reader.take(30_000)
        reader.close()
        try {
            reader.open(part, 0, offline = true)
            fail("part of a song was played as the song")
        } catch (_: NotKeptOffline) {
        }
    }

    // ---- A file as it is ---------------------------------------------------------------------------

    @Test
    fun aFileAsItIsIsReadFromAnyByteByRange() {
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        assertEquals(original.size.toLong(), reader.open(asItIs(), 0))
        assertArrayEquals(original, reader.take())
        reader.close()
        // Kept, and read again from the phone.
        asked.clear()
        reader.open(asItIs(), 123_456)
        assertArrayEquals(original.copyOfRange(123_456, original.size), reader.take())
        reader.close()
        assertEquals(0, asked.size)
        // With nothing kept, a place in it is a range, which the server honours for a file.
        val direct = SongReader(Streams(keeps = false), heads, pause = { waits += it })
        assertEquals(original.size - 250_000L, direct.open(asItIs(), 250_000))
        assertArrayEquals(original.copyOfRange(250_000, original.size), direct.take())
        direct.close()
        assertTrue(asked.single(), "bytes=250000-" in asked.single())
    }

    // ---- Reading the head -------------------------------------------------------------------------

    private fun sniff(bytes: ByteArray, piece: Int): ConversionHead? {
        val sniffer = HeadSniffer()
        var at = 0
        while (at < bytes.size) {
            val n = minOf(piece, bytes.size - at)
            sniffer.feed(bytes, at, n)
            at += n
        }
        return sniffer.head
    }

    @Test
    fun theHeadIsReadHoweverTheBytesArrive() {
        val stream = conversion(128, 0).copyOf(8_000)
        for (piece in listOf(1, 3, 7, 10, 573, 8_000)) assertEquals("in pieces of $piece", ConversionHead(572, 16_000), sniff(stream, piece))
        // No tag at all.
        val bare = stream.copyOfRange(572, 4_000)
        assertEquals(ConversionHead(0, 16_000), sniff(bare, 5))
        // Two tags, one after the other, and a few stray bytes before the first frame.
        val twice = tag(300) + tag(1_000) + byteArrayOf(0, 0, 0x11) + bare
        assertEquals(ConversionHead(1_303, 16_000), sniff(twice, 9))
        // A tag with a footer is ten bytes longer than it says.
        val footed = tag(200).also { it[5] = 0x10 } + ByteArray(10) + bare
        assertEquals(ConversionHead(210, 16_000), sniff(footed, 64))
        // The rate is the frame's own: 160 kbps is 20,000 bytes a second, 64 is 8,000.
        assertEquals(20_000, sniff(conversion(160, 0).copyOf(4_000), 100)!!.bytesPerSecond)
        assertEquals(8_000, sniff(conversion(64, 0).copyOf(4_000), 100)!!.bytesPerSecond)
        // Not MP3 at all: nothing is made up.
        assertNull(sniff(ByteArray(40_000) { 0x41 }, 1_000))
        assertNotNull(sniff(stream, 4_096))
    }

    @Test
    fun aPlaceInBytesIsAWholeSecondAndWhatIsLeftOver() {
        val head = ConversionHead(572, 16_000)
        assertEquals(ConversionHead.Start(0, 0), head.startFor(0))
        assertEquals(ConversionHead.Start(0, 0), head.startFor(572))
        assertEquals(ConversionHead.Start(0, 15_999), head.startFor(572 + 15_999))
        assertEquals(ConversionHead.Start(1, 0), head.startFor(572 + 16_000))
        assertEquals(ConversionHead.Start(37, 4_800), head.startFor(572 + 37 * 16_000 + 4_800))
    }

    @Test
    fun offlineModeTurnedOnPartWayCarriesOnOnlyWithWhatIsKeptWhole() {
        var offline = false
        // A song being converted and fetched: it stops there.
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        reader.open(where, 0) { offline }
        assertEquals(30_000, reader.take(30_000).size)
        offline = true
        try {
            reader.take()
            fail("went on fetching in offline mode")
        } catch (_: NotKeptOffline) {
        }
        reader.close()
        assertEquals(1, asked.size)
        assertEquals(-1L, streams.wholeLength(where.key))
        // A file as it is, being read through the kept songs, every byte of which is kept:
        // it carries on from the phone's copy, at the place it had reached.
        offline = false
        val file = asItIs()
        SongReader(streams, heads, pause = { waits += it }).apply { open(file, 0); take(); close() }
        assertEquals(original.size.toLong(), streams.wholeLength(file.key, converted = false))
        val again = SongReader(streams, heads, pause = { waits += it })
        again.open(file, 0) { offline }
        val first = again.take(100_000)
        offline = true
        val rest = again.take()
        again.close()
        assertArrayEquals(original, first + rest)
    }

    @Test
    fun anMp3AlreadyWithinTheLimitIsAskedForAsTheFileItIs() {
        // Navidrome converts nothing when the file is already an MP3 at or under the rate
        // asked for: it sends the file. So it is asked for as the file, and read as one.
        assertEquals(StreamForm(0, "mp3"), streamForm(native = true, cap = 320, suffix = "mp3", fileKbps = 128))
        assertEquals(StreamForm(0, "mp3"), streamForm(native = true, cap = 128, suffix = "mp3", fileKbps = 128))
        // Over the limit, it is converted down; so is any other kind of file.
        assertEquals(StreamForm(128, "mp3"), streamForm(native = true, cap = 128, suffix = "mp3", fileKbps = 192))
        assertEquals(StreamForm(128, "mp3"), streamForm(native = true, cap = 128, suffix = "flac", fileKbps = 96))
        // A rate the library doesn't know: asked for converted, and found out from the answer
        // (see ConversionHead.ranged).
        assertEquals(StreamForm(128, "mp3"), streamForm(native = true, cap = 128, suffix = "mp3", fileKbps = 0))
        // No limit: the file; a file the phone can't play: converted, as before.
        assertEquals(StreamForm(0, "mp3"), streamForm(native = true, cap = 0, suffix = "mp3", fileKbps = 320))
        assertEquals(StreamForm(320, "mp3"), streamForm(native = false, cap = 0, suffix = "ape", fileKbps = 900))
        assertEquals(StreamForm(128, "mp3"), streamForm(native = false, cap = 128, suffix = "mp3", fileKbps = 96))
    }

    // ---- Round three --------------------------------------------------------------------------

    @Test
    fun aConversionTheServerNoLongerHasWholeIsNotAskedForByByteOnTheStrengthOfLastTime() {
        // The server had the whole conversion in its own cache: its start said so, with a
        // length and "takes byte ranges".
        warm = true
        val streams = Streams()
        val reader = SongReader(streams, heads, pause = { waits += it })
        val where = converted()
        reader.open(where, 0)
        reader.take(4_000)
        reader.close()
        assertTrue(heads[where.key]!!.ranged)
        // Since then it has dropped that copy (Navidrome's cache of conversions is a cache):
        // it converts afresh, from the start, whatever byte is asked for.
        warm = false
        asked.clear()
        val place = byteFor(45_000, 128)
        reader.open(where, place)
        val got = reader.take(2_000)
        reader.close()
        // What it remembered was asked for by byte; the answer was not a range; and the
        // answer is what decides. Nothing of that answer is read (it is the whole song from
        // its start: reading it up to the place is what must never happen), and the place
        // is asked for by time instead.
        assertEquals(45_000, firstFrameMs(got))
        assertEquals("read and thrown away from an answer that ignored the range", 0L, streams.discarded)
        assertEquals(2, asked.size)
        assertTrue(asked[0], "bytes=$place-" in asked[0])
        assertTrue(asked[1], "timeOffset=" in asked[1] && "bytes=" !in asked[1])
        // And it is not taken on trust again.
        assertFalse(heads[where.key]!!.ranged)
    }

    @Test
    fun aLinkThatDeliversALittleAndStallsIsGivenUpNotTriedForEver() {
        // Every time the song is asked for, a little arrives and the line goes dead. Each
        // little counted as progress and started the waits again from the shortest, so the
        // reader went round for as long as the song had bytes left, and the player was never
        // told anything was wrong.
        seconds = 3_600
        alwaysDrops = true
        var now = 0L
        val reader = SongReader(Streams(), heads, pause = { waits += it; now += it }, clock = { now })
        reader.open(converted(), 0)
        try {
            reader.take()
            fail("read to an end through a connection that never stays up")
        } catch (_: GaveUp) {
        }
        reader.close()
        // Four more tries, further apart each time, and then it is the player's to wait for
        // the server.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
        assertEquals(5, asked.size)
    }

    @Test
    fun offlineModeTurnedOnWhileWaitingToAskAgainAsksNothingMore() {
        drops = true
        var offline = false
        val streams = Streams()
        // The line drops; and during the wait before the song is asked for again, offline
        // mode is switched on.
        val reader = SongReader(streams, heads, pause = { offline = true })
        reader.open(converted(), 0) { offline }
        try {
            reader.take()
            fail("went on in offline mode")
        } catch (_: NotKeptOffline) {
        }
        reader.close()
        // It used to ask the server once more first, and only then notice.
        assertEquals("asked of the server in offline mode: $asked", 1, asked.size)
    }

    @Test
    fun aSecondReadCannotThrowAwayTheCopyTheFirstHasJustFinished() {
        val streams = Streams()
        val writers = ConversionWriters()
        val where = converted()
        val whole = conversion(128, 0)
        // One read is keeping the conversion as it plays, and is part-way through.
        val first = SongReader(streams, heads, writers, pause = { waits += it })
        first.open(where, 0)
        val began = first.take(30_000)
        // A second read of the same song opens (the player fetching ahead, or a seek back to
        // its start). It looks for a whole copy: there is none yet. And in the moment after
        // it has looked, the first read comes to its end and closes, its copy whole.
        var rest = ByteArray(0)
        val finishing = Thread {
            rest = first.take()
            first.close()
        }
        streams.whenLookedAt = {
            finishing.start()
            // Until it has finished, or can get no further because the second read's look
            // is not over (which is the point).
            while (finishing.isAlive && finishing.state != Thread.State.BLOCKED) Thread.yield()
        }
        val second = SongReader(streams, heads, writers, pause = { waits += it })
        second.open(where, 0)
        // It reads the song's start and is closed, as a read is when the player moves on.
        val heard = second.take(20_000)
        second.close()
        finishing.join()
        assertArrayEquals(whole, began + rest)
        assertArrayEquals(whole.copyOf(20_000), heard)
        // Looking and claiming were two steps: having seen no whole copy, the second read
        // went on to claim the right to keep one, found it free (the first had just let it
        // go), and began by clearing away "whatever is kept, which is not whole": the copy
        // the first read had that moment finished. What it then kept itself was only the
        // start of the song, and went when it closed: nothing was left kept at all.
        assertEquals("the finished copy was thrown away", whole.size.toLong(), streams.wholeLength(where.key))
    }
}
