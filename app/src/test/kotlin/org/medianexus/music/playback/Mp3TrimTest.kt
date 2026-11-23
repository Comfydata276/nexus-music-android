package org.medianexus.music.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.PositionHolder
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is trimmed from an MP3 for a gapless join, decided from the file's own header. Each
 * file here is read by Media3's real MP3 extractor, as the player reads it, with the app's
 * reader of the LAME header beside it; the trim is then worked out from what the two said.
 */
@RunWith(RobolectricTestRunner::class)
class Mp3TrimTest {
    private val androids = "c2.android.mp3.decoder"

    /** An MP3: an optional ID3 tag, an optional first frame holding a "Xing"/"Info" header
     *  with LAME's extension (the encoder's name, the delay and the padding), then audio
     *  frames (MPEG-1 layer 3, 44.1 kHz, 128 kbps, stereo: 417 bytes each). */
    private fun mp3(encoder: String? = null, delay: Int = 0, padding: Int = 0, kind: String = "Info", tagBytes: Int = 0, comment: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        if (tagBytes > 0 || comment != null) {
            val body = ByteArrayOutputStream()
            if (comment != null) {
                // A COMM frame, as iTunes writes its gapless numbers.
                val text = byteArrayOf(0) + "eng".toByteArray() + "iTunSMPB".toByteArray() + byteArrayOf(0) + comment.toByteArray()
                body.write("COMM".toByteArray())
                body.write(byteArrayOf(0, 0, (text.size shr 7 and 0x7f).toByte(), (text.size and 0x7f).toByte(), 0, 0))
                body.write(text)
            }
            while (body.size() < tagBytes) body.write(0)
            val size = body.size()
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
                (size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte()))
            out.write(body.toByteArray())
        }
        val header = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x44)
        if (encoder != null) {
            val frame = ByteArray(417)
            header.copyInto(frame)
            var at = 36
            kind.toByteArray().copyInto(frame, at); at += 4
            byteArrayOf(0, 0, 0, 0x0f).copyInto(frame, at); at += 4 // frames, bytes, table, quality
            byteArrayOf(0, 0, 0, 40).copyInto(frame, at); at += 4
            byteArrayOf(0, 0, 0x41, 0x25).copyInto(frame, at); at += 4
            for (i in 0 until 100) frame[at + i] = (i * 255 / 100).toByte()
            at += 100
            at += 4
            encoder.padEnd(9).take(9).toByteArray(Charsets.ISO_8859_1).copyInto(frame, at); at += 9
            at += 2 + 8 + 2
            val packed = (delay shl 12) or padding
            frame[at] = (packed shr 16).toByte(); frame[at + 1] = (packed shr 8).toByte(); frame[at + 2] = packed.toByte()
            out.write(frame)
        }
        repeat(40) {
            out.write(header)
            out.write(ByteArray(413))
        }
        return out.toByteArray()
    }

    /**
     * The format the player is given for this file, read by the app's extractors the way
     * Media3 reads a new stream: each kind of file is tried on it in turn (`sniff`), and the
     * one that knows it then reads it. (The MP3 extractor's sniff leaves the input past the
     * ID3 tag, at the first frame: a reader that expected to start at byte 0 found nothing in
     * any file with a tag, which is every file in a real library.)
     */
    private fun read(file: ByteArray): Format {
        val input = FakeExtractorInput.Builder().setData(file).build()
        val extractor = songExtractors().createExtractors(android.net.Uri.parse("nexus://song/one"), mapOf("Content-Type" to listOf("audio/mpeg"))).first { candidate ->
            try {
                candidate.sniff(input)
            } catch (_: java.io.EOFException) {
                false
            } finally {
                input.resetPeekPosition()
            }
        }
        assertEquals(androidx.media3.extractor.mp3.Mp3Extractor::class.java, extractor.underlyingImplementation.javaClass)
        val output = FakeExtractorOutput()
        extractor.init(output)
        val holder = PositionHolder()
        var format: Format? = null
        while (format == null) {
            extractor.read(input, holder)
            format = output.trackOutputs.takeIf { it.size() > 0 }?.valueAt(0)?.lastFormat
        }
        assertEquals(MimeTypes.AUDIO_MPEG, format.sampleMimeType)
        return format
    }

    private fun header(format: Format): LameHeader? = format.metadata?.let { m -> (0 until m.length()).firstNotNullOfOrNull { m[it] as? LameHeader } }

    /** What the renderer makes of a file, for a decoder: the trim, or null to leave Media3's. */
    private fun trim(file: ByteArray, decoder: String? = androids, fromComment: Boolean = false): Pair<Int, Int>? {
        val format = read(file)
        return Mp3Trim.correct(format.encoderDelay, format.encoderPadding, decoder, fromComment, header(format))
    }

    @Test
    fun aLameFileIsTrimmedByItsOwnHeaderForADecoderThatAllowsForItsDelay() {
        // The test library's files say 576 before and 1008 after. Media3 hands on 1105 and
        // 479: right for a decoder that gives back what it decodes, 529 samples late.
        // Android's own takes those 529 off itself, and pads the end: with both, every song
        // lost its first 529 samples and gained 529 of silence.
        val file = mp3("LAME3.100", delay = 576, padding = 1008)
        assertEquals(1105 to 479, read(file).let { it.encoderDelay to it.encoderPadding })
        assertEquals(LameHeader("LAME3.100", 576, 1008), header(read(file)))
        assertEquals(576 to 1008, trim(file))
        assertEquals(576 to 1008, trim(file, decoder = "OMX.google.mp3.decoder"))
        // ffmpeg's two names, which Media3 treats the same.
        assertEquals(576 to 1008, trim(mp3("Lavf61.7.", delay = 576, padding = 1008)))
        assertEquals(1105 to 600, trim(mp3("Lavc61.19", delay = 1105, padding = 600, kind = "Xing")))
        // Behind a tag with a cover in it, the header is still found.
        assertEquals(576 to 1008, trim(mp3("LAME3.100", delay = 576, padding = 1008, tagBytes = 300_000)))
        // A phone maker's own decoder: not known to do the same, so left as Media3 has it.
        assertNull(trim(file, decoder = "OMX.SEC.mp3.dec"))
        assertNull(trim(file, decoder = null))
    }

    @Test
    fun aFileFromAnotherEncoderIsLeftAloneWhateverItsNumbers() {
        // An encoder that isn't LAME or ffmpeg, whose own delay happens to be 1105: exactly
        // what Media3 hands on for a LAME file with 576. Media3 added nothing here, and the
        // old rule, which went by the numbers, took 529 off all the same: the first twelve
        // milliseconds of padding left in, and twelve milliseconds of music cut from the end.
        val file = mp3("Gogo3.13 ", delay = 1105, padding = 479)
        assertEquals(1105 to 479, read(file).let { it.encoderDelay to it.encoderPadding })
        assertNull(trim(file))
        assertNull(trim(mp3("Fraunhofe", delay = 1600, padding = 900)))
        assertNull(trim(mp3("XING-ENC ", delay = 576, padding = 1008, kind = "Xing")))
    }

    @Test
    fun aFileWithNoHeaderAtAllIsLeftAlone() {
        val file = mp3()
        assertNull(header(read(file)))
        assertNull(trim(file))
        assertNull(trim(mp3(tagBytes = 2_000)))
    }

    @Test
    fun paddingMedia3CutOffAtNothingIsPutBackFromTheHeaderExactly() {
        // LAME wrote 300 samples of padding. Media3, taking 529 from it, has none left, and
        // its numbers no longer say what the file did. Adding 529 back to "nothing" would
        // have trimmed 229 samples of music; the header says 300, and 300 it is.
        val file = mp3("LAME3.99r", delay = 576, padding = 300)
        assertEquals(1105 to 0, read(file).let { it.encoderDelay to it.encoderPadding })
        assertEquals(576 to 300, trim(file))
        assertEquals(576 to 0, trim(mp3("LAME3.99r", delay = 576, padding = 0)))
    }

    @Test
    fun inAnyDoubtNothingIsChanged() {
        val lame = LameHeader("LAME3.100", 576, 1008)
        // Media3's numbers are not this header's with its sum done: they came from somewhere
        // else (a newer Media3 that does it differently, a comment this didn't see).
        assertNull(Mp3Trim.correct(576, 1008, androids, fromComment = false, header = lame))
        assertNull(Mp3Trim.correct(1105, 500, androids, fromComment = false, header = lame))
        assertNull(Mp3Trim.correct(-1, -1, androids, fromComment = false, header = lame))
        // An iTunes comment: Media3 takes its numbers from that, unadjusted.
        assertNull(Mp3Trim.correct(1105, 479, androids, fromComment = true, header = lame))
        // No header carried with the format (a stream not read through the app's extractors).
        assertNull(Mp3Trim.correct(1105, 479, androids, fromComment = false, header = null))
        assertEquals(576 to 1008, Mp3Trim.correct(1105, 479, androids, fromComment = false, header = lame))
    }

    @Test
    fun aFileTooShortToHoldAHeaderIsReadAsItIs() {
        // Cut off inside its first frame: nothing to find, and nothing thrown.
        val whole = mp3("LAME3.100", delay = 576, padding = 1008)
        val input = FakeExtractorInput.Builder().setData(whole.copyOf(60)).build()
        assertNull(Mp3HeaderReader.lameHeader(input))
        assertEquals(0, input.peekPosition)
    }
}
