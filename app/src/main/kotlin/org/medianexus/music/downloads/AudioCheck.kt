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
 * What a file turned out to be when it was read as audio, from its first byte to its last.
 */
data class Inspected(
    /** The container it is in (null: none the phone reads). */
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
    @Throws(IOException::class)
    fun inspect(file: File, suffix: String, stop: () -> Boolean): Inspected

    @Throws(IOException::class)
    fun inspect(file: File, suffix: String): Inspected = inspect(file, suffix) { false }
}

/**
 * The check a download must pass before it is marked whole (see [SongFetch]): the file is
 * read by the same Media3 extractors the player will read it with, **every sample of it, to
 * the end of the file** (not decoded: the samples are read and let go). So "it is audio"
 * means what the player will find, not what a label says; and "it runs this long" is where
 * its last sample ends, not what its header claims.
 *
 * - **An error or an early end anywhere is a failure**: the extractor refusing something
 *   part-way ([Broken.DAMAGED]), or the file ending inside a sample ([Broken.ENDS_EARLY]).
 *   The first versions stopped once they had a track and a length, so a file with good
 *   headers and nothing after them passed. Media3's MP3 reader reports neither for a
 *   stretch that is not MP3: it looks 128 KiB for the next frame and then *ends the song*
 *   there, silently; that is seen here by where it stopped ([Inspected.gaveUp]).
 * - **A FLAC's frames are checked against their own checksums** (the CRC-16 that ends every
 *   frame): a FLAC's reader finds frames by their headers and never looks inside, so bytes
 *   changed in the middle of the sound went unseen, and Android's decoder stops at such a
 *   frame. No other format here carries a checksum that can be checked without decoding.
 * - **Whether the phone has a decoder for it** is asked of the system, as the player's own
 *   renderer asks ([decodes]): an ALAC in an `.m4a` is an MP4 the extractor reads perfectly
 *   and nothing on the phone plays.
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
    /** Whether the phone has a decoder for a track of this format. */
    private val decodes: (Format) -> Boolean = ::systemDecodes,
) : AudioCheck {
    override fun inspect(file: File, suffix: String, stop: () -> Boolean): Inspected {
        // One at a time: what a walk holds in memory (an MP4's tables) is then never held
        // twice, however many downloads finish together.
        while (!one.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) if (stop()) throw Cancelled()
        try {
            return walk(file, suffix, stop)
        } finally {
            one.unlock()
        }
    }

    private fun walk(file: File, suffix: String, stop: () -> Boolean): Inspected {
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
            if (broken == null && seen.flacTailUnverified()) broken = Broken.DAMAGED
            val said = seen.map?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 } ?: seen.durationUs
            val audio = seen.audio
            return Inspected(
                container = container,
                audio = audio != null,
                durationUs = if (said == C.TIME_UNSET || said <= 0) -1 else said,
                ranUs = if (broken == null) seen.endUs() else -1,
                broken = broken,
                decodable = audio == null || decodes(audio),
                partial = seen.partial,
                gaveUp = broken == null && container == "mp3" && seen.lastEnd >= 0 && input.position - seen.lastEnd >= MP3_SEARCH_BYTES,
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

    private fun containerOf(extractor: Extractor): String? = when (extractor) {
        is androidx.media3.extractor.flac.FlacExtractor -> "flac"
        is androidx.media3.extractor.mp3.Mp3Extractor -> "mp3"
        is androidx.media3.extractor.mp4.Mp4Extractor, is androidx.media3.extractor.mp4.FragmentedMp4Extractor -> "mp4"
        is androidx.media3.extractor.ogg.OggExtractor -> "ogg"
        is androidx.media3.extractor.wav.WavExtractor -> "wav"
        is androidx.media3.extractor.mkv.MatroskaExtractor -> "matroska"
        is androidx.media3.extractor.ts.AdtsExtractor -> "adts"
        // Something Media3 reads that is no kind of song file here (a picture, a video
        // stream, a telephone recording).
        else -> null
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

        /** A FLAC whose last frames never came to a right checksum. One frame's worth is
         *  let pass: a tag some programs put after the sound is read as part of the last
         *  frame. */
        fun flacTailUnverified(): Boolean = flac && unverified > 1

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
                if (flac && sound) crc = crc16(crc, scratch, 0, got)
                return got
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                if (flac && sound) crc = crc16(crc, data.data, data.position, length)
                data.skipBytes(length)
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                pending = 0
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
        /** The check as the app uses it. */
        val system = Media3AudioCheck()

        /**
         * The version of this check, kept with each download it has passed
         * (`Downloaded.checked`): a download marked whole by a build whose check was less
         * (0: it stopped at the headers) is checked again, once, in the background.
         */
        const val VERSION = 1

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

        /** The containers a file of each kind may rightly be in. */
        fun fits(suffix: String, container: String): Boolean = when (suffix.lowercase()) {
            "mp3" -> container == "mp3"
            "flac" -> container == "flac"
            "m4a", "mp4", "aac" -> container == "mp4" || container == "adts"
            "ogg", "oga", "opus" -> container == "ogg"
            "wav" -> container == "wav"
            "mka", "webm" -> container == "matroska"
            // A kind not known by name: any of them.
            else -> true
        }

        /**
         * Whether the container's own word on how long it runs is exact (a count of samples
         * the file states) and so can be held against what its samples come to. An MP3's or
         * a bare AAC stream's may be worked out from its size.
         */
        fun statesItsLength(container: String): Boolean = container != "mp3" && container != "adts"

        /**
         * Whether the phone has a decoder for a track of this format, asked as the player's
         * audio renderer asks. Plain PCM needs none. If the system can't be asked, yes: a
         * question that couldn't be put is no reason to refuse a song.
         */
        fun systemDecodes(format: Format): Boolean {
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) return true
            return try {
                MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false).isNotEmpty()
            } catch (_: MediaCodecUtil.DecoderQueryException) {
                true
            }
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
