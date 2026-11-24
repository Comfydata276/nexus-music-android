package org.medianexus.music.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * What a file turned out to be when it was read as audio: its beginning only
 * ([AudioCheck.begins]), or from its first byte to its last ([AudioCheck.inspect]).
 */
data class Inspected(
    /** The container it is in (null: none the player's reader recognises). */
    val container: String?,
    /** Whether there is sound in it. */
    val audio: Boolean,
    /** How long the container itself says it runs (microseconds; -1 if it doesn't say). For
     *  an MP3 with no header this is only its size at its first frame's bit rate. */
    val durationUs: Long,
    /** How long the sound in it really runs: where its last sample ends, every sample having
     *  been read to the end of the file (microseconds). -1: not walked (a stand-in), and
     *  [durationUs] is all there is. */
    val ranUs: Long = -1,
    /** Why the walk stopped before a clean end; null if it reached one. */
    val broken: Broken? = null,
    /** Whether this phone has a decoder for what is in it. */
    val decodable: Boolean = true,
    /** Whether the phone answered that ([decodable]) at all: false when the question could
     *  not be put (then [decodable] is false for this download, and nothing is remembered). */
    val decodersAnswered: Boolean = true,
    /** The file ends part-way through a sample, in a format whose reader lets that pass (an
     *  MP3's last frame): no error, and the sound before it is whole, but a stream that
     *  stopped there was stopped. */
    val partial: Boolean = false,
    /** An MP3 whose reader stopped looking for the next frame: Media3's searches so far
     *  past the last one, finds none, and takes that for the end of the song, with no
     *  error. Either the rest is not sound (a large tag after it), or the sound that follows
     *  will never be played. */
    val gaveUp: Boolean = false,
) {
    /** How long it runs, as near as is known: by its samples if they were walked. */
    val runsUs: Long get() = if (ranUs >= 0) ranUs else durationUs
}

/** How a file failed to be read through to its end. */
enum class Broken {
    /** It stops in the middle of something: part of it is missing. */
    ENDS_EARLY,

    /** The player's own reader gives up on it part-way (or a checksum inside it is wrong):
     *  its bytes are damaged. */
    DAMAGED,
}

/** Looks at a file's bytes and says what it is. Never goes by its name or what the server
 *  called it. `stop` is asked as it goes: once it says yes, the look is abandoned with a
 *  [Cancelled]. */
fun interface AudioCheck {
    /** Reads the file through, every sample, to its end. */
    @Throws(IOException::class)
    fun inspect(file: File, suffix: String, stop: () -> Boolean): Inspected

    @Throws(IOException::class)
    fun inspect(file: File, suffix: String): Inspected = inspect(file, suffix) { false }

    /**
     * Looks only at how the file begins: whether the player's reader recognises it, whether
     * there is sound in it, and whether the phone can decode that sound. For a file already
     * known to be whole. (A stand-in that only reads through says the same of the beginning.)
     */
    @Throws(IOException::class)
    fun begins(file: File, suffix: String): Inspected = inspect(file, suffix)
}

/**
 * The two looks a download gets before it is marked whole (see [SongFetch]), both by the
 * same Media3 extractors the player will read it with, tried in the order the player tries
 * them, so "it is audio" means what the player will find and not what a label says.
 *
 * **Its beginning** ([begins]), for a file whose length already says it is the server's
 * file, whole: the player's readers are tried on it as the player tries them on a new
 * stream, and it is read only as far as the format of its sound (a FLAC's first block, an
 * MP3's first frame, an MP4's header wherever in the file that is). That catches what a
 * length cannot: something that is not a song at all, and sound this phone can't decode.
 *
 * **All of it** ([inspect]), for a file nothing else vouches for (a conversion, which comes
 * with no length; a library that gives the song no size): **every sample, to the end of the
 * file** (not decoded: read and let go), so that "it runs this long" is where its last
 * sample ends, not what its header claims.
 *
 * - **An error or an early end anywhere is a failure**: the extractor refusing something
 *   part-way ([Broken.DAMAGED]), or the file ending inside a sample ([Broken.ENDS_EARLY]).
 *   The first versions stopped once they had a track and a length, so a file with good
 *   headers and nothing after them passed. Media3's MP3 reader reports neither for a
 *   stretch that is not MP3: it looks 128 KiB for the next frame and then *ends the song*
 *   there, silently; that is seen here by where it stopped ([Inspected.gaveUp]).
 * - **A FLAC's frames are checked against their own checksums** (the CRC-16 that ends every
 *   frame): a FLAC's reader finds frames by their headers and never looks inside, so bytes
 *   changed in the middle of the sound went unseen. (Android's decoder plays on past such a
 *   frame, with whatever it makes of it: seen on the Android 16 emulator, a FLAC with
 *   sixty-four bytes changed inside a frame played its full 26 seconds with no error. So
 *   nothing at playback says so either; reading it through is the only place it is caught.)
 *   No other format here carries a checksum that can be checked without decoding.
 * - **Whether the phone can decode it** is the player's own question, asked the player's
 *   way ([playerDecodes]): is there a decoder that takes this very format, its profile, its
 *   channels and its sample rate; not merely one for the kind of sound. An ALAC in an `.m4a`
 *   is an MP4 the extractor reads perfectly and nothing on the phone plays. **A question
 *   that could not be put is a no**: the song is then downloaded as the server converts it,
 *   which plays, rather than marked on a guess.
 *
 * **Bounded**: one file at a time across the whole app ([one]); abandoned when asked
 * (`stop`); no embedded picture is decoded, and an MP3's tag is stepped over rather than
 * read (Media3 reads a whole ID3 tag into memory before looking at it: twenty megabytes of
 * cover art, twice); the samples themselves go through a small buffer. What it cannot avoid
 * is what the player itself needs: an MP4's sample tables, which Media3 holds in memory
 * whole. What that comes to was measured (docs/TESTING.md, "Done means it plays to the end").
 */
@OptIn(UnstableApi::class)
class Media3AudioCheck(
    /** Whether the phone can decode a track of this format. */
    private val decodes: (Format) -> Boolean,
) : AudioCheck {
    override fun inspect(file: File, suffix: String, stop: () -> Boolean): Inspected = oneAtATime(stop) { walk(file, suffix, stop, whole = true) }

    override fun begins(file: File, suffix: String): Inspected = oneAtATime({ false }) { walk(file, suffix, { false }, whole = false) }

    /** One at a time: what a look holds in memory (an MP4's tables) is then never held
     *  twice, however many downloads finish together. */
    private fun <T> oneAtATime(stop: () -> Boolean, look: () -> T): T {
        while (!one.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) if (stop()) throw Cancelled()
        try {
            return look()
        } finally {
            one.unlock()
        }
    }

    /** `whole`: every sample to the end of the file; otherwise only as far as the format of
     *  its sound. */
    private fun walk(file: File, suffix: String, stop: () -> Boolean, whole: Boolean): Inspected {
        val length = file.length()
        RandomAccessFile(file, "r").use { raf ->
            val window = Window(raf, length)
            // Past any ID3 tag at the start (see the class): the sound begins after it.
            val start = pastId3(window, length)
            // The kind the file is meant to be is tried first, the rest after: as the player
            // does from the address.
            val extractors = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setDisableArtworkMetadata(true)
                .createExtractors(Uri.parse("file:///song.${suffix.ifEmpty { "bin" }}"), emptyMap())
            window.seek(start)
            var input = DefaultExtractorInput(window, start, length)
            var chosen: Extractor? = null
            for (extractor in extractors) {
                try {
                    if (extractor.sniff(input)) {
                        chosen = extractor
                        break
                    }
                } catch (_: EOFException) {
                    // Too short for this one to tell: not it.
                } finally {
                    input.resetPeekPosition()
                }
            }
            val extractor = chosen ?: return Inspected(null, false, -1)
            // (Sniffing may have read ahead: back to where the sound starts.)
            window.seek(start)
            input = DefaultExtractorInput(window, start, length)
            val container = containerOf(extractor.underlyingImplementation)
            val seen = Seen(flac = container == "flac") { input.position }
            var broken: Broken? = null
            try {
                extractor.init(seen)
                val holder = PositionHolder()
                var seeks = 0
                var stuck = 0
                var at = input.position
                while (true) {
                    if (stop()) throw Cancelled()
                    // Only its beginning was asked about: that is known once the reader has
                    // said what sound is in it, or has handed over a first sample without
                    // having said so (a track's format comes before its samples: there is
                    // no sound in it).
                    if (!whole && (seen.audio != null || seen.sampled)) break
                    val result = extractor.read(input, holder)
                    if (result == Extractor.RESULT_END_OF_INPUT) break
                    if (result == Extractor.RESULT_SEEK) {
                        // (Bounded: a file that sends the extractor round in circles is not
                        // read for ever.)
                        if (++seeks > MOST_SEEKS) {
                            broken = Broken.DAMAGED
                            break
                        }
                        window.seek(holder.position)
                        input = DefaultExtractorInput(window, holder.position, length)
                        at = -1
                    }
                    if (seen.damaged) {
                        broken = Broken.DAMAGED
                        break
                    }
                    // Nor one it makes no headway in.
                    if (input.position == at) {
                        if (++stuck > MOST_IDLE_STEPS) {
                            broken = Broken.DAMAGED
                            break
                        }
                    } else {
                        stuck = 0
                        at = input.position
                    }
                }
            } catch (e: Cancelled) {
                throw e
            } catch (_: EOFException) {
                // It ended inside something.
                broken = Broken.ENDS_EARLY
            } catch (_: ParserException) {
                broken = Broken.DAMAGED
            } catch (e: IOException) {
                // The phone's own storage wouldn't give the file up: not a word on the file.
                throw e
            } catch (_: RuntimeException) {
                // An extractor led out of its depth by what it read (an index out of range,
                // a size that makes no sense): the player would fall the same way.
                broken = Broken.DAMAGED
            } catch (_: OutOfMemoryError) {
                // What it would take to read is more than the app has: nor can the player.
                broken = Broken.DAMAGED
            } finally {
                runCatching { extractor.release() }
            }
            // A FLAC's last frame that does not come to a right checksum: a tag put after
            // the sound is read as part of it, and so is damage. Told apart by looking.
            if (whole && broken == null && seen.flacTailUnverified() && !flacEndsInATag(seen)) broken = Broken.DAMAGED
            val said = seen.map?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 } ?: seen.durationUs
            val audio = seen.audio
            // The phone's answer: yes, no, or (null) the question could not be put.
            val decodes = if (audio == null) true else runCatching { decodes(audio) }.getOrNull()
            return Inspected(
                container = container,
                audio = audio != null,
                durationUs = if (said == C.TIME_UNSET || said <= 0) -1 else said,
                ranUs = if (whole && broken == null) seen.endUs() else -1,
                broken = broken,
                // (A question that could not be put is a no, for now: see [playerDecodes].)
                decodable = decodes == true,
                decodersAnswered = decodes != null,
                partial = seen.partial,
                gaveUp = whole && broken == null && container == "mp3" && seen.lastEnd >= 0 && input.position - seen.lastEnd >= MP3_SEARCH_BYTES,
            )
        }
    }

    /** Where the file's sound begins, past any ID3v2 tags at its start. Media3's extractors
     *  read such a tag whole into memory before they look at it; stepped over here, a tag
     *  with a large picture in it costs nothing. (What it says of gapless playback is not
     *  this check's concern.) */
    private fun pastId3(window: Window, length: Long): Long {
        var at = 0L
        val head = ByteArray(10)
        while (at + head.size < length) {
            window.seek(at)
            if (!window.readFully(head)) break
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) break
            if (head[3] == 0xFF.toByte() || head[4] == 0xFF.toByte()) break
            if ((6..9).any { head[it].toInt() and 0x80 != 0 }) break
            val size = (head[6].toInt() shl 21) or (head[7].toInt() shl 14) or (head[8].toInt() shl 7) or head[9].toInt()
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            val next = at + 10 + size + footer
            if (next >= length) break
            at = next
        }
        return at
    }

    /**
     * Whether a FLAC's end, which did not come to a right checksum, is a whole frame with a
     * tag after it (ID3v1, APE, Lyrics3 and ID3v2 are all written after the sound by one
     * program or another, and the reader takes whatever follows the last frame header for
     * that frame). It is, if the checksum comes to nothing part-way through what was read
     * since the last right one, and what follows from there is a tag by its own signature,
     * or only padding. Anything else is a last frame with wrong bytes in it, which used to
     * be let through as "a tag".
     */
    private fun flacEndsInATag(seen: Seen): Boolean {
        val tail = seen.tail.toByteArray()
        var crc = 0
        for (i in tail.indices) {
            crc = crc16(crc, tail, i, 1)
            // (A frame is at least a header and a checksum.)
            if (crc != 0 || i < 6) continue
            if (tagAt(tail, i + 1)) return true
            if (seen.tailBytes == tail.size.toLong() && (i + 1 until tail.size).all { tail[it] == 0.toByte() }) return true
        }
        return false
    }

    private fun tagAt(bytes: ByteArray, at: Int): Boolean = TAGS.any { tag -> at + tag.size <= bytes.size && tag.indices.all { bytes[at + it] == tag[it] } }

    private fun containerOf(extractor: Extractor): String? = when (extractor) {
        is androidx.media3.extractor.flac.FlacExtractor -> "flac"
        is androidx.media3.extractor.mp3.Mp3Extractor -> "mp3"
        is androidx.media3.extractor.mp4.Mp4Extractor, is androidx.media3.extractor.mp4.FragmentedMp4Extractor -> "mp4"
        is androidx.media3.extractor.ogg.OggExtractor -> "ogg"
        is androidx.media3.extractor.wav.WavExtractor -> "wav"
        is androidx.media3.extractor.mkv.MatroskaExtractor -> "matroska"
        is androidx.media3.extractor.ts.AdtsExtractor -> "adts"
        // Something else Media3 reads. Whether it is a song is whether there is sound in it
        // (a picture has none), not which reader it took.
        else -> "other"
    }

    /**
     * The file, read through a buffer of its own (a walk reads every byte: one system call
     * per MP3 frame would be a quarter of a million of them for a two-hour song).
     */
    private class Window(private val raf: RandomAccessFile, private val length: Long) : DataReader {
        private val buffer = ByteArray(64 * 1024)
        private var from = 0L
        private var held = 0
        private var at = 0L

        fun seek(position: Long) {
            at = position
        }

        override fun read(target: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (at >= length) return C.RESULT_END_OF_INPUT
            if (at < from || at >= from + held) {
                raf.seek(at)
                val got = raf.read(buffer, 0, buffer.size)
                if (got <= 0) return C.RESULT_END_OF_INPUT
                from = at
                held = got
            }
            val give = minOf(size.toLong(), from + held - at).toInt()
            System.arraycopy(buffer, (at - from).toInt(), target, offset, give)
            at += give
            return give
        }

        /** Fills `target` from where it is; false if the file ends first. */
        fun readFully(target: ByteArray): Boolean {
            var got = 0
            while (got < target.size) {
                val more = read(target, got, target.size - got)
                if (more == C.RESULT_END_OF_INPUT) return false
                got += more
            }
            return true
        }
    }

    /** What the extractor said of the file, and every sample it handed over. */
    private class Seen(private val flac: Boolean, private val position: () -> Long) : ExtractorOutput {
        /** Where in the file the last sample of sound ended (-1: there has been none). */
        var lastEnd = -1L

        /** A FLAC's bytes since its last frame that came to a right checksum (the first
         *  megabyte of them: where a frame ends and a tag begins is within a frame's length),
         *  and how many there have been. */
        val tail = java.io.ByteArrayOutputStream()
        var tailBytes = 0L

        private fun keep(bytes: ByteArray, offset: Int, length: Int) {
            crc = crc16(crc, bytes, offset, length)
            tailBytes += length
            val room = MOST_TAIL - tail.size()
            if (room > 0) tail.write(bytes, offset, minOf(length, room))
        }

        /** The reader has handed over a sample, of any track. */
        var sampled = false

        var map: SeekMap? = null
        var durationUs = C.TIME_UNSET

        /** The sound in it: the first audio track's format. */
        var audio: Format? = null

        /** A frame's checksum was wrong, and so were those after it: not a frame the reader
         *  had cut in two (see [frameEnded]). */
        var damaged = false

        /** The file ended with part of a sample read (see [Inspected.partial]). */
        var partial = false

        private var lastUs = C.TIME_UNSET
        private var beforeUs = C.TIME_UNSET
        private var crc = 0
        private var unverified = 0
        private val scratch = ByteArray(16 * 1024)

        /** Where the last sample ends: its time, and as long again as the one before it
         *  took (a sample says when it starts, not how long it is). 0 with no samples. */
        fun endUs(): Long = when {
            lastUs == C.TIME_UNSET -> 0
            beforeUs == C.TIME_UNSET -> lastUs
            else -> lastUs + (lastUs - beforeUs)
        }

        /** A FLAC whose last frame (or frames) never came to a right checksum. */
        fun flacTailUnverified(): Boolean = flac && unverified > 0

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private var sound = false

            /** Bytes of the sample now being handed over. */
            private var pending = 0

            override fun format(format: Format) {
                if (MimeTypes.isAudio(format.sampleMimeType)) {
                    sound = true
                    if (audio == null) audio = format
                }
            }

            override fun durationUs(durationUs: Long) {
                if (durationUs != C.TIME_UNSET) this@Seen.durationUs = durationUs
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                // Read and let go: nothing is decoded.
                val got = input.read(scratch, 0, minOf(length, scratch.size))
                if (got == C.RESULT_END_OF_INPUT) {
                    if (!allowEndOfInput) throw EOFException()
                    if (sound && pending > 0) partial = true
                    return C.RESULT_END_OF_INPUT
                }
                pending += got
                if (flac && sound) keep(scratch, 0, got)
                return got
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                if (flac && sound) keep(data.data, data.position, length)
                data.skipBytes(length)
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                pending = 0
                sampled = true
                if (!sound) return
                lastEnd = position()
                if (lastUs == C.TIME_UNSET || timeUs > lastUs) {
                    beforeUs = lastUs
                    lastUs = timeUs
                }
                if (flac) frameEnded()
            }
        }

        /**
         * A FLAC frame, as the extractor cut it, has been read. A frame ends with the CRC-16
         * of everything before it, so the CRC of a whole frame, its own two bytes included,
         * is nothing. The extractor finds where a frame ends by looking for the next
         * header, and now and then finds something that only looks like one inside a frame:
         * then this "frame" is the first part of one, and the count carries on into the next,
         * coming to nothing at the true end. A count that has not come to nothing over
         * several frames running is damage.
         */
        private fun frameEnded() {
            if (crc == 0) {
                unverified = 0
                tail.reset()
                tailBytes = 0
            } else if (++unverified > MOST_UNVERIFIED) {
                damaged = true
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            map = seekMap
        }
    }

    companion object {
        /** The check as the app uses it on this phone. */
        fun forPhone(context: android.content.Context): Media3AudioCheck {
            val app = context.applicationContext
            return Media3AudioCheck { format -> playerDecodes(app, format) }
        }

        /** One walk at a time, across every account's downloads. */
        private val one = java.util.concurrent.locks.ReentrantLock()

        /** How far Media3's MP3 reader looks for the next frame before it takes the song to
         *  have ended (`Mp3Extractor.MAX_SEARCH_BYTES`, read at 1.11.1). */
        private const val MP3_SEARCH_BYTES = 128 * 1024

        private const val MOST_SEEKS = 200_000
        private const val MOST_IDLE_STEPS = 10_000

        /** How many FLAC "frames" running may fail their checksum before it is damage and
         *  not the reader having cut a frame at a false header (which makes two or three). */
        private const val MOST_UNVERIFIED = 4

        /** How much of a FLAC's end that has not come to a right checksum is kept to look
         *  through (see [flacEndsInATag]). */
        private const val MOST_TAIL = 1024 * 1024

        /** How the tags written after a FLAC's sound begin: ID3v1, APE, Lyrics3, ID3v2. */
        private val TAGS = listOf("TAG", "APETAGEX", "LYRICSBEGIN", "ID3").map { it.toByteArray(Charsets.ISO_8859_1) }

        /**
         * Whether the container's own word on how long it runs is exact (a count of samples
         * the file states) and so can be held against what its samples come to. An MP3's or
         * a bare AAC stream's may be worked out from its size.
         */
        fun statesItsLength(container: String): Boolean = container != "mp3" && container != "adts"

        /**
         * **Whether the player can play a track of exactly this format on this phone**, asked
         * as the player's own audio renderer asks it (`MediaCodecAudioRenderer.supportsFormat`
         * at Media3 1.11.1): the decoders for the kind of sound, in the player's order, and
         * whether any of them takes this format (`MediaCodecInfo.isFormatSupported`: the
         * codec's profile, the sample rate, the number of channels). Plain PCM needs no
         * decoder. **Throws if the system can't be asked**, which the check takes for a no for
         * that download only ([Inspected.decodersAnswered]): the song is then downloaded as
         * the server converts it, which plays, and not marked on a guess, nor remembered as a
         * file this phone can't play. (It used to ask only whether some decoder for the kind
         * existed, and took a question that failed for yes.)
         */
        fun playerDecodes(context: android.content.Context, format: Format): Boolean {
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) return true
            if (format.sampleMimeType == null) return false
            return MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false).any { it.isFormatSupported(context, format) }
        }

        /** FLAC's CRC-16 (polynomial 0x8005, from nothing, most significant bit first). */
        internal fun crc16(start: Int, bytes: ByteArray, offset: Int, length: Int): Int {
            var crc = start
            for (i in offset until offset + length) crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor bytes[i].toInt()) and 0xFF]) and 0xFFFF
            return crc
        }

        private val CRC16 = IntArray(256) { index ->
            var value = index shl 8
            repeat(8) { value = if (value and 0x8000 != 0) (value shl 1) xor 0x8005 else value shl 1 }
            value and 0xFFFF
        }
    }
}
