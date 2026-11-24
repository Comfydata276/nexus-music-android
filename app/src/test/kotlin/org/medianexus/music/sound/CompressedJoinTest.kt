package org.medianexus.music.sound

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.playback.GaplessAudioRenderer
import org.medianexus.music.playback.QueuePlayer
import org.medianexus.music.store.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Rule 5 of the fixes, the reviewer's most wanted test: the real ExoPlayer, the app's audio
 * renderer and Media3's sink, two padded compressed songs, the first of them short. A real
 * decoder gives back its output some frames after the input it came from; here a decoder of
 * the test's own holds four frames back (each keeps its own time), so the first song's
 * sound only begins to come out after the renderer has read that song to its end and gone on
 * to the second. Each decoded frame carries which song it is, and every frame that reaches the
 * output is checked against the gain of the song it belongs to.
 */
@RunWith(RobolectricTestRunner::class)
class CompressedJoinTest {
    @get:Rule val folder = TemporaryFolder()

    // A codec for audio/mpeg the renderer can find; the decoding itself is the test's own.
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withCodecs(listOf(ShadowMediaCodecConfig.CODEC_INFO_MPEG), emptyList())

    private val samplesPerFrame = 1152

    /** An MP3 whose audio frames each say, at byte 200, which song they are: a LAME header
     *  (576 samples of delay, 1008 of padding), then `frames` frames of 417 bytes. */
    private fun mp3(mark: Int, frames: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val header = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x44)
        val info = ByteArray(417)
        header.copyInto(info)
        var at = 36
        "Info".toByteArray().copyInto(info, at); at += 4
        byteArrayOf(0, 0, 0, 0x0f).copyInto(info, at); at += 4
        byteArrayOf(0, 0, 0, frames.toByte()).copyInto(info, at); at += 4
        val bytes = 417 * (frames + 1)
        byteArrayOf(0, (bytes shr 16).toByte(), (bytes shr 8).toByte(), bytes.toByte()).copyInto(info, at); at += 4
        for (i in 0 until 100) info[at + i] = (i * 255 / 100).toByte()
        at += 100
        at += 4
        "LAME3.100".toByteArray(Charsets.ISO_8859_1).copyInto(info, at); at += 9
        at += 2 + 8 + 2
        val packed = (576 shl 12) or 1008
        info[at] = (packed shr 16).toByte(); info[at + 1] = (packed shr 8).toByte(); info[at + 2] = packed.toByte()
        out.write(info)
        repeat(frames) {
            val frame = ByteArray(417)
            header.copyInto(frame)
            frame[200] = mark.toByte()
            out.write(frame)
        }
        return out.toByteArray()
    }

    /** What each song's frames decode to: the song's mark times this. */
    private val level = 4000

    /**
     * A decoder that gives back each frame four frames after it was given it, with that
     * frame's own time, as 1,152 stereo samples of the frame's mark times [level].
     */
    private inner class LateDecoder : MediaCodecAdapter {
        private val inputs = Array(8) { ByteBuffer.allocate(4096) }
        private val freeInputs = ArrayDeque((0 until 8).toList())
        private val outputs = Array(8) { ByteBuffer.allocate(samplesPerFrame * 4 * 8).order(ByteOrder.LITTLE_ENDIAN) }
        private val freeOutputs = ArrayDeque((0 until 8).toList())
        private val held = ArrayDeque<Pair<Int, Long>>()
        private val ready = ArrayDeque<Pair<Int, Long>>()
        private var announced = false
        private var ending = false
        private var lastTime = 0L

        override fun dequeueInputBufferIndex(): Int = freeInputs.removeFirstOrNull() ?: MediaCodec.INFO_TRY_AGAIN_LATER

        override fun getInputBuffer(index: Int): ByteBuffer = inputs[index].also { it.clear() }

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if (size > 0) {
                held.addLast(inputs[index].get(offset + 200).toInt() to presentationTimeUs)
                lastTime = presentationTimeUs
                if (held.size > LATENCY) ready.addLast(held.removeFirst())
            }
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                while (held.isNotEmpty()) ready.addLast(held.removeFirst())
                ending = true
            }
            freeInputs.addLast(index)
        }

        override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
            if (!announced) {
                announced = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            val next = ready.firstOrNull()
            if (next == null) {
                if (ending) {
                    val index = freeOutputs.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
                    ending = false
                    outputs[index].clear()
                    outputs[index].limit(0)
                    bufferInfo.set(0, 0, lastTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return index
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            val index = freeOutputs.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            ready.removeFirst()
            val out = outputs[index]
            out.clear()
            repeat(samplesPerFrame * 2) { out.putShort((next.first * level).toShort()) }
            out.flip()
            bufferInfo.set(0, out.limit(), next.second, 0)
            return index
        }

        override fun getOutputFormat(): MediaFormat = MediaFormat.createAudioFormat(MimeTypes.AUDIO_RAW, 44_100, 2)

        override fun getOutputBuffer(index: Int): ByteBuffer = outputs[index]

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            freeOutputs.addLast(index)
        }

        override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) = releaseOutputBuffer(index, false)

        override fun flush() {
            held.clear()
            ready.clear()
            ending = false
            freeInputs.clear(); freeInputs.addAll(0 until 8)
            freeOutputs.clear(); freeOutputs.addAll(0 until 8)
        }

        override fun queueSecureInputBuffer(index: Int, offset: Int, info: androidx.media3.decoder.CryptoInfo, presentationTimeUs: Long, flags: Int) =
            queueInputBuffer(index, offset, 0, presentationTimeUs, flags)

        override fun release() {}
        override fun setOnFrameRenderedListener(listener: MediaCodecAdapter.OnFrameRenderedListener, handler: Handler) {}
        override fun setOutputSurface(surface: Surface) {}
        override fun detachOutputSurface() {}
        override fun setParameters(params: Bundle) {}
        override fun setVideoScalingMode(scalingMode: Int) {}
        override fun needsReconfiguration(): Boolean = false
        override fun getMetrics(): PersistableBundle = PersistableBundle()
        override fun subscribeToVendorParameters(names: List<String>) {}
        override fun unsubscribeFromVendorParameters(names: List<String>) {}
    }

    @Test
    fun a_short_songs_sound_takes_its_own_gain_though_the_renderer_has_moved_on_to_the_next() {
        val processor = SoundProcessor().also { it.sound = Sound.of(Settings(normalize = true, levelMode = 1), false) }
        val tap = ArrayList<Short>()
        val sink = DefaultAudioSink.Builder(RuntimeEnvironment.getApplication())
            .setAudioProcessors(arrayOf(processor, TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
                override fun handleBuffer(buffer: ByteBuffer) {
                    val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    while (b.remaining() >= 4) {
                        tap += b.short
                        b.short
                    }
                }
            })))
            .build()
        val factory = MediaCodecAdapter.Factory { LateDecoder() }
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf<Renderer>(GaplessAudioRenderer(RuntimeEnvironment.getApplication(), factory, MediaCodecSelector.DEFAULT, false, handler, audio, sink))
        }
        // Normal: -6 dB of tag is -2 dB, -12 dB is -8 dB.
        val songs = listOf(
            Song(id = "a", title = "a", replayGain = ReplayGain(trackGain = -6f, trackPeak = 1f)),
            Song(id = "b", title = "b", replayGain = ReplayGain(trackGain = -12f, trackPeak = 1f)),
        )
        val files = listOf(mp3(mark = 1, frames = 3), mp3(mark = 3, frames = 20)).mapIndexed { i, bytes -> folder.newFile("$i.mp3").apply { writeBytes(bytes) } }
        val transitions = ArrayList<Int>()
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), renderers).setClock(FakeClock(true)).build()
        try {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions += player.currentMediaItemIndex
                }
            })
            player.setMediaItems(songs.mapIndexed { i, s -> MediaItem.Builder().setMediaId(s.id).setUri(Uri.fromFile(files[i])).setTag(QueuePlayer.SongTag(s, i + 1L)).build() })
            player.prepare()
            player.play()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
        } finally {
            player.release()
        }
        val a = 1 * level * 10.0.pow(-2.0 / 20)
        val b = 3 * level * 10.0.pow(-8.0 / 20)
        // Media3 trims 1,105 samples from each song's start and 479 from its end.
        val realA = 3 * samplesPerFrame - 1105 - 479
        val realB = 20 * samplesPerFrame - 1105 - 479
        var at = 0
        while (at < tap.size && abs(tap[at] - a) <= 1) at++
        assertEquals("the first song's frames, each at its own gain (the first after them: ${tap.getOrNull(at)})", realA, at)
        var bs = 0
        while (at < tap.size && abs(tap[at] - b) <= 1) { at++; bs++ }
        assertTrue("then the second song's, each at its own gain ($bs of $realB; next: ${tap.getOrNull(at)})", bs >= realB)
        assertEquals("the item moved on once, to the second song", listOf(0, 1), transitions)
    }

    private companion object {
        const val LATENCY = 4
    }
}
