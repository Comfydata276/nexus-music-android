package org.medianexus.music.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame

/**
 * What to trim from a decoded MP3 so that one song runs into the next with nothing lost and
 * nothing added.
 *
 * An MP3 says, in its LAME header, how many samples of padding its encoder put before and
 * after the music (576 and 1008 in the test library's files). A decoder adds a delay of its
 * own, 529 samples: what it gives back starts 529 samples late and, unless flushed, stops 529
 * short. So the music is found by trimming 529 more than the header says from the start and
 * 529 fewer from the end. Media3 1.11.1 does exactly that sum for files from LAME and ffmpeg
 * (`XingFrame.parse` adds 529 to the delay and takes 529 from the padding), which is right
 * for a decoder that hands back what it decodes as it comes.
 *
 * Android's own MP3 decoder doesn't. It drops its first 529 samples itself and adds 529 of
 * silence at the end of each stream, "so that the rest of the pipeline can assume zero
 * delay". With both corrections made, the first 529 samples of every song (12 ms) are cut
 * off and 529 samples of silence are left at its end: measured on the emulator, sample for
 * sample, against ffmpeg's decode of the same files. For that decoder the header's own
 * numbers are the right ones, so Media3's sum is undone.
 *
 * Which decoders make the correction themselves can only be known by name. The two below
 * are the platform's software decoder, under its current and its older name; it is part of
 * Android itself, the same on every phone, and what a phone uses unless its maker ships one
 * of their own for MP3. A maker's decoder is left with Media3's numbers: it may be right
 * with them or wrong, and only a recording of that phone's output can say.
 *
 * Which files Media3 made its sum for is known, not guessed. It makes it only for a header
 * written by LAME or ffmpeg (an encoder name starting "LAME", "Lavf" or "Lavc"); a header
 * from any other encoder is passed on as it is. The final numbers alone can't tell the two
 * apart (another encoder's file can perfectly well have a delay of 1105), and when the
 * header's padding is under 529 Media3 has cut it off at nothing, and the true figure is gone
 * from them. So the file's own header is read as the song is opened ([Mp3HeaderReader]) and
 * carried with the song's format: the correction is made only for a header of that family
 * whose numbers are the ones Media3 started from, and what is put in their place is the
 * header's own figures, exactly. In any doubt, nothing is changed: a few milliseconds of
 * silence at a join is better than a few milliseconds of music cut off.
 */
object Mp3Trim {
    /** The delay of an MP3 decoder, in samples (the 528 of its filter bank, and one). */
    const val DECODER_DELAY = 529

    /** Whether the decoder with this name takes its own delay off and pads its end. */
    fun compensates(decoder: String?): Boolean = decoder == "c2.android.mp3.decoder" || decoder == "OMX.google.mp3.decoder"

    /** Whether Media3 adds the decoder's delay to the numbers of a header written by this
     *  encoder (`XingFrame.usesLameGaplessEncoding` in Media3 1.11.1: the same three names). */
    fun adjustedByMedia3(encoder: String): Boolean = encoder.startsWith("LAME") || encoder.startsWith("Lavf") || encoder.startsWith("Lavc")

    /**
     * The samples to trim from the start and the end, or null to leave Media3's as they are.
     * `delay` and `padding` are what Media3 worked out (-1 when the file doesn't say);
     * `header` is the file's own LAME header, if it has one; `fromComment` says Media3 took
     * its numbers from an iTunes gapless comment instead, which it doesn't adjust.
     */
    fun correct(delay: Int, padding: Int, decoder: String?, fromComment: Boolean, header: LameHeader?): Pair<Int, Int>? {
        if (!compensates(decoder) || fromComment || header == null) return null
        // Another encoder's header: Media3 left its numbers alone, and so are they here.
        if (!adjustedByMedia3(header.encoder)) return null
        // Media3's numbers must be this header's, with its sum done: otherwise they came from
        // somewhere this doesn't know about, and it is not for this to say what they mean.
        if (delay != header.delay + DECODER_DELAY || padding != maxOf(0, header.padding - DECODER_DELAY)) return null
        return header.delay to header.padding
    }
}

/**
 * What an MP3's own header (the "Xing" or "Info" frame LAME and others put first) says about
 * itself: the name of the encoder that wrote it, and the samples of padding it put before
 * and after the music, as written. Carried in the format's metadata from where the file is
 * read ([Mp3HeaderReader]) to where the trim is decided ([GaplessAudioRenderer]).
 */
@OptIn(UnstableApi::class)
data class LameHeader(val encoder: String, val delay: Int, val padding: Int) : androidx.media3.common.Metadata.Entry

/**
 * Media3's MP3 extractor, with the file's own LAME header read beside it. Media3 reads that
 * header too, but hands on only the numbers it has already adjusted (or not: it depends on
 * the encoder's name, which it doesn't hand on). Everything else is the extractor's.
 */
@OptIn(UnstableApi::class)
class Mp3HeaderReader(private val mp3: androidx.media3.extractor.Extractor) : androidx.media3.extractor.Extractor {
    private var header: LameHeader? = null
    private var looked = false

    override fun sniff(input: androidx.media3.extractor.ExtractorInput): Boolean = mp3.sniff(input)

    override fun init(output: androidx.media3.extractor.ExtractorOutput) = mp3.init(
        object : androidx.media3.extractor.ExtractorOutput by output {
            override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput {
                val track = output.track(id, type)
                return object : androidx.media3.extractor.TrackOutput by track {
                    override fun format(format: Format) {
                        val found = header
                        if (found == null) {
                            track.format(format)
                        } else {
                            val with = format.metadata?.copyWithAppendedEntries(found) ?: androidx.media3.common.Metadata(found)
                            track.format(format.buildUpon().setMetadata(with).build())
                        }
                    }
                }
            }
        },
    )

    override fun read(input: androidx.media3.extractor.ExtractorInput, seekPosition: androidx.media3.extractor.PositionHolder): Int {
        // At the first read, wherever the input then is: Media3 tries each kind of file on a
        // new stream first (`sniff`), and its MP3 extractor leaves the input past the ID3 tag
        // when it has recognised one, at the first frame. (A period is always read from its
        // start first, so the first read is at one or the other.)
        if (!looked) {
            looked = true
            header = lameHeader(input)
        }
        return mp3.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) = mp3.seek(position, timeUs)

    override fun release() = mp3.release()

    override fun getUnderlyingImplementation(): androidx.media3.extractor.Extractor = mp3.underlyingImplementation

    companion object {
        /** A tag longer than this before the first frame is not looked past. */
        private const val MOST_TAG_BYTES = 32L * 1024 * 1024

        /**
         * Reads the LAME header from the start of an MP3 (from byte 0, or from its first
         * frame), without taking anything from the input: past any ID3v2 tags, the first
         * frame, and in it the "Xing" or "Info" frame with LAME's extension (as Media3's
         * `XingFrame.parse` reads it). Null if there is none, or the file isn't long enough
         * to hold one.
         */
        fun lameHeader(input: androidx.media3.extractor.ExtractorInput): LameHeader? = try {
            input.resetPeekPosition()
            val ten = ByteArray(10)
            var skipped = 0L
            while (true) {
                if (!input.peekFully(ten, 0, 10, true)) return null
                if (ten[0] != 'I'.code.toByte() || ten[1] != 'D'.code.toByte() || ten[2] != '3'.code.toByte()) break
                val size = (6..9).fold(0) { total, i -> (total shl 7) or (ten[i].toInt() and 0x7f) } + if (ten[5].toInt() and 0x10 != 0) 10 else 0
                skipped += 10L + size
                if (skipped > MOST_TAG_BYTES) return null
                input.advancePeekPosition(size)
            }
            val headerData = (ten[0].toInt() and 0xff shl 24) or (ten[1].toInt() and 0xff shl 16) or (ten[2].toInt() and 0xff shl 8) or (ten[3].toInt() and 0xff)
            val frameHeader = androidx.media3.extractor.MpegAudioUtil.Header()
            if (!frameHeader.setForHeaderData(headerData)) return null
            val frame = ByteArray(frameHeader.frameSize)
            ten.copyInto(frame, 0, 0, minOf(10, frame.size))
            if (frame.size > 10 && !input.peekFully(frame, 10, frame.size - 10, true)) return null
            parse(frameHeader, frame)
        } catch (_: java.io.EOFException) {
            null
        } finally {
            input.resetPeekPosition()
        }

        /** The LAME header in an MP3's first frame, if it is one. */
        internal fun parse(frameHeader: androidx.media3.extractor.MpegAudioUtil.Header, frame: ByteArray): LameHeader? {
            // Where the "Xing" frame sits depends on the kind of MPEG audio and on mono or not.
            val base = if (frameHeader.version and 1 != 0) (if (frameHeader.channels != 1) 36 else 21) else (if (frameHeader.channels != 1) 21 else 13)
            if (frame.size < base + 8) return null
            val name = String(frame, base, 4, Charsets.ISO_8859_1)
            if (name != "Xing" && name != "Info") return null
            val flags = frame[base + 7].toInt()
            var at = base + 8
            if (flags and 0x01 != 0) at += 4 // how many frames
            if (flags and 0x02 != 0) at += 4 // how many bytes
            if (flags and 0x04 != 0) at += 100 // the seek table
            if (flags and 0x08 != 0) at += 4 // quality
            // LAME's extension: the encoder's name (9), two bytes, ReplayGain (8), two bytes,
            // then the delay and the padding in twelve bits each.
            if (frame.size < at + 9 + 2 + 8 + 2 + 3) return null
            val encoder = String(frame, at, 9, Charsets.ISO_8859_1)
            at += 9 + 2 + 8 + 2
            val packed = (frame[at].toInt() and 0xff shl 16) or (frame[at + 1].toInt() and 0xff shl 8) or (frame[at + 2].toInt() and 0xff)
            return LameHeader(encoder, packed shr 12, packed and 0xfff)
        }
    }
}

/**
 * The audio renderer, with the trim for MP3 put right for the decoder in use (see
 * [Mp3Trim]), and the sink told which song the sound it is given belongs to. Everything else
 * is Media3's.
 *
 * **Which song.** Media3's renderer tells the sink the period it is *reading* when the
 * decoder's output changes format. A decoder gives its output back some frames after its
 * input, and the renderer reads ahead; so when a short song's sound begins to come out, the
 * renderer may already be reading the next song, and the sink was told that one: the short
 * song took the next one's gain (CompressedJoinTest). So each format is marked, as it is
 * read, with the period it was read from ([ReadFrom], in the format's custom data, which
 * Media3 carries to the sink's format), and the sink is told that period instead.
 */
@OptIn(UnstableApi::class)
class GaplessAudioRenderer(
    context: Context,
    codecs: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    decoderFallback: Boolean,
    handler: Handler?,
    listener: AudioRendererEventListener?,
    sink: AudioSink,
    /** Told, once for each decoder met, which one plays MP3 and what is trimmed for it. */
    private val note: (String) -> Unit = {},
) : MediaCodecAudioRenderer(context, codecs, selector, decoderFallback, handler, listener, PeriodOfTheSound(sink)) {
    private var said: String? = null

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        formatHolder.format?.let { formatHolder.format = it.buildUpon().setCustomData(ReadFrom(mediaPeriodId)).build() }
        return super.onInputFormatChanged(formatHolder)
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        var given = format
        if (format.sampleMimeType == MimeTypes.AUDIO_MPEG) {
            val decoder = codecInfo?.name
            Mp3Trim.correct(format.encoderDelay, format.encoderPadding, decoder, fromComment(format), lameHeader(format))?.let { (delay, padding) ->
                given = format.buildUpon().setEncoderDelay(delay).setEncoderPadding(padding).build()
            }
            // Once for each decoder, in every build: which one a phone uses for MP3 is the one
            // thing about gapless playback that can't be known from here.
            if (decoder != said) {
                said = decoder
                val what = "MP3 is decoded by $decoder; trimming ${given.encoderDelay} and ${given.encoderPadding} samples" +
                    if (given !== format) " (the file's own header: this decoder allows for its own delay)" else " (as Media3 has them)"
                android.util.Log.i("NexusPlayback", what)
                note(what)
            }
        }
        super.onOutputFormatChanged(given, mediaFormat)
    }

    /** Whether the file carries an iTunes gapless comment, which is where Media3 takes the
     *  numbers from when there is one. */
    private fun fromComment(format: Format): Boolean {
        val metadata = format.metadata ?: return false
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            if (entry is CommentFrame && entry.description == GAPLESS_COMMENT) return true
            if (entry is InternalFrame && entry.description == GAPLESS_COMMENT) return true
        }
        return false
    }

    /** The file's own LAME header, read beside Media3's extractor (see [Mp3HeaderReader]). */
    private fun lameHeader(format: Format): LameHeader? {
        val metadata = format.metadata ?: return null
        for (i in 0 until metadata.length()) (metadata[i] as? LameHeader)?.let { return it }
        return null
    }

    private companion object {
        const val GAPLESS_COMMENT = "iTunSMPB"
    }
}

/** The period a format was read from (see [GaplessAudioRenderer]). */
@OptIn(UnstableApi::class)
class ReadFrom(val period: MediaSource.MediaPeriodId?)

/** The sink, told the period the sound it is configured for was read from. */
@OptIn(UnstableApi::class)
private class PeriodOfTheSound(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val from = audioSinkConfig.format.customData as? ReadFrom
        super.configure(if (from == null) audioSinkConfig else audioSinkConfig.buildUpon().setMediaPeriodId(from.period).build())
    }
}
