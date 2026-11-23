package org.medianexus.music.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
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
 */
object Mp3Trim {
    /** The delay of an MP3 decoder, in samples (the 528 of its filter bank, and one). */
    const val DECODER_DELAY = 529

    /** Whether the decoder with this name takes its own delay off and pads its end. */
    fun compensates(decoder: String?): Boolean = decoder == "c2.android.mp3.decoder" || decoder == "OMX.google.mp3.decoder"

    /**
     * The samples to trim from the start and the end, given what Media3 worked out (`delay`,
     * `padding`: -1 when the file doesn't say), or null to leave Media3's as they are.
     * `fromComment` says the numbers came from an iTunes gapless comment rather than a LAME
     * header: Media3 doesn't adjust those, so there is nothing to undo.
     */
    fun correct(delay: Int, padding: Int, decoder: String?, fromComment: Boolean): Pair<Int, Int>? {
        if (!compensates(decoder) || fromComment) return null
        if (delay < DECODER_DELAY || padding < 0) return null
        return delay - DECODER_DELAY to padding + DECODER_DELAY
    }
}

/**
 * The audio renderer, with the trim for MP3 put right for the decoder in use (see
 * [Mp3Trim]). Everything else is Media3's.
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
) : MediaCodecAudioRenderer(context, codecs, selector, decoderFallback, handler, listener, sink) {
    private var said: String? = null

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        var given = format
        if (format.sampleMimeType == MimeTypes.AUDIO_MPEG) {
            val decoder = codecInfo?.name
            Mp3Trim.correct(format.encoderDelay, format.encoderPadding, decoder, fromComment(format))?.let { (delay, padding) ->
                given = format.buildUpon().setEncoderDelay(delay).setEncoderPadding(padding).build()
            }
            // Once for each decoder, in every build: which one a phone uses for MP3 is the one
            // thing about gapless playback that can't be known from here.
            if (decoder != said) {
                said = decoder
                android.util.Log.i("NexusPlayback", "MP3 is decoded by $decoder; trimming ${given.encoderDelay} and ${given.encoderPadding} samples" +
                    if (given !== format) " (the file's own numbers: this decoder allows for its own delay)" else " (as Media3 has them)")
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

    private companion object {
        const val GAPLESS_COMMENT = "iTunSMPB"
    }
}
