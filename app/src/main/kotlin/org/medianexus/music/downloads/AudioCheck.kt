package org.medianexus.music.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
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
 * What a file turned out to be when it was read as audio: the container it is in (null: none
 * the phone reads), whether there is sound in it, and how long the container itself says it
 * runs (microseconds; -1 if it doesn't say).
 */
data class Inspected(val container: String?, val audio: Boolean, val durationUs: Long)

/** Looks at a file's bytes and says what it is. Never goes by its name or what the server
 *  called it. */
fun interface AudioCheck {
    @Throws(IOException::class)
    fun inspect(file: File, suffix: String): Inspected
}

/**
 * The check a download must pass before it is marked whole (see [SongFetch]): the file is
 * read by the same Media3 extractors the player will read it with, as far as it takes to
 * know its container, that it has an audio track, and how long the container says it is. So
 * "it is audio" and "it runs this long" mean what the player will find, not what a label
 * says.
 *
 * How long: FLAC, MP4, WAV and Matroska state it; Ogg's is read from its last page; an MP3's
 * from its Xing or VBRI header, or, with neither (every conversion the server streams is so),
 * from its size and its constant bit rate, which for a file on disk is every frame it has.
 */
@OptIn(UnstableApi::class)
object Media3AudioCheck : AudioCheck {
    override fun inspect(file: File, suffix: String): Inspected {
        val length = file.length()
        RandomAccessFile(file, "r").use { raf ->
            val reader = DataReader { buffer, offset, size -> raf.read(buffer, offset, size).let { if (it < 0) C.RESULT_END_OF_INPUT else it } }
            // The kind the file is meant to be is tried first, the rest after: as the player
            // does from the address.
            val extractors = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .createExtractors(Uri.parse("file:///song.${suffix.ifEmpty { "bin" }}"), emptyMap())
            var input = DefaultExtractorInput(reader, 0, length)
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
            val container = containerOf(extractor.underlyingImplementation)
            val seen = Seen()
            try {
                extractor.init(seen)
                val holder = PositionHolder()
                // Until the tracks and the length are known, or the file ends. (Bounded: a
                // file that sends the extractor round in circles is not read for ever.)
                var steps = 0
                while (!(seen.ended && seen.map != null) && steps++ < MOST_STEPS) {
                    when (extractor.read(input, holder)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            raf.seek(holder.position)
                            input = DefaultExtractorInput(reader, holder.position, length)
                        }
                        else -> Unit
                    }
                }
            } catch (_: EOFException) {
                // It ended inside something: whatever was learnt before that stands.
            } finally {
                runCatching { extractor.release() }
            }
            val duration = seen.map?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 } ?: seen.durationUs
            return Inspected(container, seen.audio, if (duration == C.TIME_UNSET || duration <= 0) -1 else duration)
        }
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

    /** What the extractor said of the file. */
    private class Seen : ExtractorOutput {
        var ended = false
        var map: SeekMap? = null
        var audio = false
        var durationUs = C.TIME_UNSET

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                if (MimeTypes.isAudio(format.sampleMimeType)) audio = true
            }

            override fun durationUs(durationUs: Long) {
                if (durationUs != C.TIME_UNSET) this@Seen.durationUs = durationUs
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                // Read and let go: only what the file says of itself is wanted.
                val scratch = ByteArray(minOf(length, 8 * 1024))
                val got = input.read(scratch, 0, scratch.size)
                if (got == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw EOFException()
                }
                return got
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }

        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            map = seekMap
        }
    }

    private const val MOST_STEPS = 200_000
}
