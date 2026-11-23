package org.medianexus.music.playback

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Bytes being read from somewhere: the server, or the kept songs. */
interface ByteSource {
    /** Up to `length` bytes into `buffer`; -1 at the end. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/** Something opened: its bytes, and how many there are from here on (-1 if not known). */
class Opened(val source: ByteSource, val length: Long)

/**
 * Where a [SongReader] gets its bytes. In the app this is Media3's HTTP and cache data
 * sources; in tests, a stand-in server and a map.
 */
interface SongStreams {
    /** Straight from the server at `url`, from byte `position` (asked for as a range, which
     *  the server honours for a file as it is). */
    @Throws(IOException::class)
    fun server(url: String, position: Long): Opened

    /** Through the kept songs under `key`: what is kept is read from the phone, the rest is
     *  fetched from `url` and kept as it goes by. Null if there are no kept songs to use. */
    @Throws(IOException::class)
    fun kept(url: String, key: String, position: Long): Opened?

    /** From the kept songs alone, never the server. Throws if a byte that's needed isn't
     *  kept. */
    @Throws(IOException::class)
    fun keptOnly(key: String, position: Long): Opened

    /** How long the kept copy under `key` is, if it is whole (every byte from the first to
     *  a known end); -1 if there is none, or only part of one. */
    fun wholeLength(key: String): Long

    /** Forgets whatever is kept under `key`. */
    fun drop(key: String)
}

/**
 * What the start of a conversion says about itself: where its audio begins (after the tag
 * the converter writes first) and how many bytes of it there are to a second. The player
 * works out "the byte for two minutes in" from these same two numbers, read from the same
 * bytes, so a place in bytes can be turned back into the same place in time.
 *
 * Both have to come from the stream. The rate asked for is not always the rate given (asked
 * for 320 kbps of a 161 kbps file, Navidrome sends 160), and the tag is hundreds of bytes,
 * or tens of thousands when it carries lyrics.
 */
data class ConversionHead(val audioStart: Long, val bytesPerSecond: Int) {
    /** Where to start a conversion so as to arrive at byte `position` of the whole one. */
    fun startFor(position: Long): Start {
        val into = (position - audioStart).coerceAtLeast(0)
        val seconds = (into / bytesPerSecond).toInt()
        return Start(seconds, into - seconds.toLong() * bytesPerSecond)
    }

    /** From this whole second, throwing away this many bytes of audio after its tag. */
    data class Start(val seconds: Int, val skip: Long)
}

/** What conversions have said about themselves (see [ConversionHead]), by kept-song key:
 *  learnt when one is read from its start, used when it is opened further in. */
class ConversionHeads(private val most: Int = 64) {
    private val heads = ConcurrentHashMap<String, ConversionHead>()

    operator fun get(key: String): ConversionHead? = heads[key]

    operator fun set(key: String, head: ConversionHead) {
        // Only a few are ever needed (the songs playing and about to); when it has grown
        // past that, start again rather than keep count of which was used last.
        if (heads.size >= most && !heads.containsKey(key)) heads.clear()
        heads[key] = head
    }
}

/**
 * Reads the start of an MP3 stream as it goes by and works out its [ConversionHead]: skips
 * any ID3v2 tags, then reads the bit rate from the first audio frame's header. It is shown
 * every byte from the first, in order, and holds on to none of them.
 */
class HeadSniffer {
    /** Set once the head has been read; stays null if the stream doesn't look like MP3. */
    var head: ConversionHead? = null
        private set
    private var done = false
    private var at = 0L
    /** Where the thing being looked at (a tag's header, or a frame's) starts. */
    private var from = 0L
    private val window = ByteArray(10)
    private var have = 0
    private var skipTo = 0L

    fun feed(buffer: ByteArray, offset: Int, length: Int) {
        if (done) return
        var i = offset
        val end = offset + length
        while (i < end && !done) {
            if (at < skipTo) {
                // Inside a tag: nothing in it matters.
                val jump = minOf((skipTo - at), (end - i).toLong()).toInt()
                at += jump
                i += jump
                continue
            }
            if (have == 0) from = at
            window[have++] = buffer[i++]
            at++
            if (have == 4 && !isTagStart()) {
                // Not a tag: these should be the first bytes of audio.
                frame()
                continue
            }
            if (have == 10) {
                // A whole tag header: skip the tag, and look again after it (there can be
                // more than one).
                val size = (6..9).fold(0L) { total, b -> (total shl 7) or (window[b].toLong() and 0x7f) }
                val footer = if (window[5].toInt() and 0x10 != 0) 10 else 0
                skipTo = from + 10 + size + footer
                have = 0
            }
        }
    }

    private fun isTagStart() = window[0] == 'I'.code.toByte() && window[1] == 'D'.code.toByte() && window[2] == '3'.code.toByte()

    /** Four bytes that should be a frame header. If they aren't, slide along by one: a
     *  little rubbish before the first frame is allowed, as the player allows it. */
    private fun frame() {
        val rate = bitrateOf(window)
        if (rate > 0) {
            head = ConversionHead(from, rate * 125)
            done = true
            return
        }
        if (at - skipTo > MOST_RUBBISH) {
            done = true
            return
        }
        window.copyInto(window, 0, 1, 4)
        have = 3
        from += 1
    }

    companion object {
        private const val MOST_RUBBISH = 16 * 1024

        private val MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        /** The bit rate, in kbps, that a layer 3 frame header gives; 0 if these four bytes
         *  aren't one. */
        fun bitrateOf(header: ByteArray): Int {
            val b0 = header[0].toInt() and 0xff
            val b1 = header[1].toInt() and 0xff
            val b2 = header[2].toInt() and 0xff
            if (b0 != 0xff || b1 and 0xe0 != 0xe0) return 0
            val version = (b1 shr 3) and 3
            val layer = (b1 shr 1) and 3
            val sampleRate = (b2 shr 2) and 3
            if (version == 1 || layer != 1 || sampleRate == 3) return 0
            return (if (version == 3) MPEG1 else MPEG2)[(b2 shr 4) and 0xf]
        }
    }
}

/**
 * Reads one song for the player, from wherever its bytes are. This is the logic of the
 * player's data source, kept free of Android so it can be tested against a stand-in server.
 *
 * A file as it is on the server can be read from any byte: the server honours ranges and
 * says how long it is, so it goes through the kept songs like anything else.
 *
 * A song the server converts is different. Navidrome streams a fresh conversion with no
 * length and ignores ranges, answering any request from the start (once it has a whole
 * conversion in its own cache it does honour them, but there is no knowing that before
 * asking). So:
 *
 * - **From the start**, it is read from the server's start to the server's end, whatever
 *   that turns out to be: no length is promised or enforced, so nothing is cut short.
 * - **Anywhere else** (a seek, or carrying on after the connection dropped), it is asked for
 *   from the whole second before with `timeOffset`, and the bytes up to the exact place are
 *   thrown away. A byte range is never asked of the server, so a long way in never means
 *   reading and discarding everything before it. Which second and how many bytes come from
 *   what the conversion said about itself when it was read from its start
 *   ([ConversionHead]), so the place arrived at is the place the player asked for.
 * - **It is kept only whole**: read from the first byte to a clean end, and at least as long
 *   as Windows requires of one (`least`, from `least_bytes` in src/playback.rs: four fifths
 *   of what the song's length comes to at the lower of the rate asked for and the file's
 *   own). Anything less is forgotten the moment the read closes. A kept copy is used only
 *   if it is whole, and then nothing is asked of the server at all; part of one is never
 *   pieced together with the server's bytes.
 */
class SongReader(private val streams: SongStreams, private val heads: ConversionHeads) {
    private var where: SongWhere? = null
    private var source: ByteSource? = null
    /** The place in the whole stream of the next byte to be given. */
    private var position = 0L
    /** Bytes still to throw away before that place. */
    private var toSkip = 0L
    /** Whether the stream's own tag is still to be passed over (a conversion started
     *  part-way has one of its own before its audio). */
    private var tagToSkip = false
    /** Reading a conversion from its start, by way of the kept songs: it is kept if this
     *  read reaches a clean end and is long enough. */
    private var keeping = false
    private var fromKept = false
    private var ended = false
    /** The place the stream ends at, when the server said how long it is; -1 when it didn't. */
    private var endsAt = -1L
    private var sniffer: HeadSniffer? = null

    /** Opens the song at byte `at`. Returns how many bytes there are from there, or -1 if
     *  that isn't known (a conversion: it ends when the server says so). */
    @Throws(IOException::class)
    fun open(where: SongWhere, at: Long): Long {
        this.where = where
        position = at
        ended = false
        keeping = false
        fromKept = false
        toSkip = 0
        tagToSkip = false
        sniffer = null
        endsAt = -1
        if (!where.form.converted) {
            val opened = streams.kept(where.stream(0), where.key, at) ?: streams.server(where.stream(0), at)
            source = opened.source
            return opened.length
        }
        val whole = streams.wholeLength(where.key)
        if (whole >= 0) {
            try {
                val opened = streams.keptOnly(where.key, at)
                source = opened.source
                fromKept = true
                if (at == 0L) sniffer = HeadSniffer()
                return (whole - at).coerceAtLeast(0)
            } catch (_: IOException) {
                // It looked whole and isn't (the phone cleared part of it away): forget it.
                streams.drop(where.key)
            }
        }
        return fresh(where, at)
    }

    /** Opens a conversion from the server: from its start, or from a time. */
    private fun fresh(where: SongWhere, at: Long): Long {
        fromKept = false
        if (at == 0L) {
            // Whatever is kept is not whole (or it would have been used): start it again.
            streams.drop(where.key)
            val kept = streams.kept(where.stream(0), where.key, 0)
            keeping = kept != null
            val opened = kept ?: streams.server(where.stream(0), 0)
            source = opened.source
            sniffer = HeadSniffer()
            // Whatever length the server gives is only passed on; none is made up. When it
            // gives one, the player reads that many bytes and stops, without ever being
            // told "that's the end": so the end is noticed here, by count.
            if (opened.length >= 0) endsAt = opened.length
            return opened.length
        }
        keeping = false
        val head = heads[where.key] ?: ConversionHead(0, where.form.bitrate * 125)
        val start = head.startFor(at)
        if (start.seconds == 0) {
            // Inside the first second: the whole conversion again, up to the place.
            source = streams.server(where.stream(0), 0).source
            toSkip = at
            tagToSkip = false
        } else {
            source = streams.server(where.stream(start.seconds), 0).source
            toSkip = start.skip
            tagToSkip = true
        }
        return -1
    }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val where = where ?: throw IOException("Not open")
        if (ended) return -1
        if (length == 0) return 0
        if (tagToSkip) {
            tagToSkip = false
            toSkip += tagLength(source ?: throw IOException("Not open"))
        }
        while (toSkip > 0) {
            val scratch = ByteArray(minOf(toSkip, 16_384L).toInt())
            val got = (source ?: throw IOException("Not open")).read(scratch, 0, scratch.size)
            if (got < 0) return finish(where)
            toSkip -= got
        }
        val got = try {
            (source ?: throw IOException("Not open")).read(buffer, offset, length)
        } catch (e: IOException) {
            if (!fromKept) throw e
            // The kept copy gave out part-way (the phone cleared some of it): forget it, and
            // carry on from the server at the place reached.
            runCatching { source?.close() }
            source = null
            streams.drop(where.key)
            fresh(where, position)
            return read(buffer, offset, length)
        }
        if (got < 0) return finish(where)
        sniffer?.let { sniffing ->
            sniffing.feed(buffer, offset, got)
            sniffing.head?.let { heads[where.key] = it; sniffer = null }
        }
        position += got
        if (endsAt in 0..position) finish(where)
        return got
    }

    /** The source has ended. */
    private fun finish(where: SongWhere): Int {
        ended = true
        // A conversion kept as it was read: only if it is as long as a whole one must be.
        // A server (or something between) that ends one early ends it cleanly, and that
        // must not be taken for the whole song ever after.
        if (keeping && position < where.least) streams.drop(where.key)
        return -1
    }

    fun close() {
        val where = where
        try {
            source?.close()
        } finally {
            // Closed before its end (a seek, the next song, a dropped connection): what was
            // kept of it is only part, and isn't kept.
            if (where != null && keeping && !ended) streams.drop(where.key)
            source = null
            this.where = null
            keeping = false
            fromKept = false
            toSkip = 0
            tagToSkip = false
            sniffer = null
        }
    }

    /** Reads the first ten bytes of a stream. If they head an ID3v2 tag, returns how much of
     *  the tag is left to pass over; if not, they were audio, and count towards what is being
     *  thrown away (so the answer is minus ten, or fewer if the stream was shorter). */
    private fun tagLength(source: ByteSource): Long {
        val head = ByteArray(10)
        var have = 0
        while (have < head.size) {
            val got = source.read(head, have, head.size - have)
            if (got < 0) break
            have += got
        }
        if (have < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return -have.toLong()
        val size = (6..9).fold(0L) { total, i -> (total shl 7) or (head[i].toLong() and 0x7f) }
        return size + if (head[5].toInt() and 0x10 != 0) 10 else 0
    }
}
