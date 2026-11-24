package org.medianexus.music.sound

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.playback.QueuePlayer
import org.medianexus.music.store.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The sound processor in Media3's own sink (`DefaultAudioSink`), and in the real ExoPlayer,
 * with a tap after it (Media3's `TeeAudioProcessor`, as the debug builds' capture) recording
 * every sample that leaves the chain. The sink is driven the way Media3's audio renderer
 * drives it: configured for each song with the timeline and the song's period, then fed.
 */
@RunWith(RobolectricTestRunner::class)
class SoundProcessorTest {
    @get:Rule val folder = TemporaryFolder()

    private val rate = 44_100

    /** Every 16-bit sample the tap saw, in order. */
    private class Tap : TeeAudioProcessor.AudioBufferSink {
        val samples = ArrayList<Short>()
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
        override fun handleBuffer(buffer: ByteBuffer) {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 2) samples += b.short
        }
    }

    private fun song(id: String, album: String = "", track: Int = 0, gain: ReplayGain? = null) =
        Song(id = id, title = id, albumId = album, track = track, discNumber = 1, replayGain = gain)

    private fun item(song: Song, uid: Long) = MediaItem.Builder().setMediaId(song.id).setUri("nexus://song/${song.id}").setTag(QueuePlayer.SongTag(song, uid)).build()

    private fun timelineOf(vararg songs: Song): Timeline = FakeTimeline(
        *songs.mapIndexed { i, s -> FakeTimeline.TimelineWindowDefinition.Builder().setUid("w$i").setMediaItem(item(s, i + 1L)).build() }.toTypedArray(),
    )

    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(rate).build()

    /** The sink as the service builds it, with the tap after the processor. */
    private class Rig(val processor: SoundProcessor) {
        val tap = Tap()
        val sink: AudioSink = SoundSink(
            DefaultAudioSink.Builder(RuntimeEnvironment.getApplication()).setAudioProcessors(arrayOf(processor, TeeAudioProcessor(tap))).build(),
            processor,
        )
        var pts = 0L
        init {
            sink.play()
        }
    }

    private fun Rig.begin(timeline: Timeline, index: Int, format: Format = pcm) {
        sink.configure(
            AudioSink.AudioSinkConfig.Builder(format)
                .setTimeline(timeline)
                .setMediaPeriodId(MediaSource.MediaPeriodId(timeline.getUidOfPeriod(index)))
                .build(),
        )
    }

    /** Feeds stereo frames (left and right the same unless given) in buffers of 1,000. */
    private fun Rig.feed(left: ShortArray, right: ShortArray = left) {
        var at = 0
        while (at < left.size) {
            val n = minOf(1000, left.size - at)
            val buffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in at until at + n) buffer.putShort(left[i]).putShort(right[i])
            buffer.flip()
            var tries = 0
            while (!sink.handleBuffer(buffer, pts, 1)) check(++tries < 10_000) { "the sink never took the buffer" }
            pts += n * 1_000_000L / rate
            at += n
        }
    }

    private fun Rig.end() {
        var tries = 0
        sink.playToEndOfStream()
        while (!sink.isEnded && ++tries < 100) sink.playToEndOfStream()
    }

    private fun Rig.left(): ShortArray = ShortArray(tap.samples.size / 2) { tap.samples[it * 2] }

    private fun steady(frames: Int, value: Short) = ShortArray(frames) { value }

    private fun sine(frames: Int, from: Int = 0, hz: Double = 440.0, level: Double = 0.5) =
        ShortArray(frames) { (sin(2 * PI * hz * (from + it) / rate) * level * 32767).roundToInt().toShort() }

    private fun levelled(settings: Settings = Settings(normalize = true, levelMode = 1)) = SoundProcessor().also { it.sound = Sound.of(settings, false) }

    @Test
    fun with_everything_off_it_is_left_out_and_the_sound_is_untouched() {
        val processor = SoundProcessor() // Sound.OFF
        val rig = Rig(processor)
        val a = song("a", gain = ReplayGain(trackGain = -9f, trackPeak = 1f))
        val b = song("b", gain = ReplayGain(trackGain = -3f, trackPeak = 1f))
        val timeline = timelineOf(a, b)
        val first = sine(5000)
        val second = sine(7000, from = 5000)
        rig.begin(timeline, 0)
        rig.feed(first)
        rig.begin(timeline, 1)
        rig.feed(second)
        rig.end()
        assertFalse(processor.isActive())
        assertEquals("never in the chain", 0L, processor.framesProcessed)
        assertTrue("every sample as it went in", (first + second).contentEquals(rig.left()))
    }

    @Test
    fun a_new_songs_gain_begins_on_its_first_sample() {
        val processor = levelled()
        val rig = Rig(processor)
        // Normal: -6 dB of tag + 4 = -2 dB; -12 + 4 = -8 dB.
        val a = song("a", gain = ReplayGain(trackGain = -6f, trackPeak = 1f))
        val b = song("b", gain = ReplayGain(trackGain = -12f, trackPeak = 1f))
        val timeline = timelineOf(a, b)
        rig.begin(timeline, 0)
        rig.feed(steady(4410, 10_000))
        rig.begin(timeline, 1)
        rig.feed(steady(4410, 10_000))
        rig.end()
        val out = rig.left()
        assertEquals(8820, out.size)
        val ga = (10_000 * 10.0.pow(-2.0 / 20)).roundToInt().toShort()
        val gb = (10_000 * 10.0.pow(-8.0 / 20)).roundToInt().toShort()
        for (i in 0 until 4410) assertEquals("sample $i of the first song", ga.toFloat(), out[i].toFloat(), 1f)
        for (i in 4410 until 8820) assertEquals("sample $i, the second song", gb.toFloat(), out[i].toFloat(), 1f)
    }

    @Test
    fun an_album_played_in_order_is_sample_continuous_across_the_join() {
        // Album mode by Auto: both songs have the album's value, and the equaliser is on too,
        // so its filters must carry straight across.
        val settings = Settings(normalize = true, levelMode = 0, equalizer = true, eqGains = listOf(6f, 4f, 0f, 0f, 0f, 0f))
        val tags = { track: Float -> ReplayGain(trackGain = track, albumGain = -7f, trackPeak = 0.9f, albumPeak = 0.95f) }
        val one = song("one", album = "x", track = 1, gain = tags(-4f))
        val two = song("two", album = "x", track = 2, gain = tags(-10f))
        val joined = sine(30_000, hz = 220.0)
        val split = Rig(SoundProcessor().also { it.sound = Sound.of(settings, false) })
        val timeline = timelineOf(one, two)
        split.begin(timeline, 0)
        split.feed(joined.copyOfRange(0, 13_337))
        split.begin(timeline, 1)
        split.feed(joined.copyOfRange(13_337, 30_000))
        split.end()
        // The same samples as one song, from the first: what the album sounds like mastered.
        val whole = Rig(SoundProcessor().also { it.sound = Sound.of(settings, false) })
        val alone = timelineOf(one.copy(id = "whole"), song("next", album = "x", track = 2, gain = tags(-10f)))
        whole.begin(alone, 0)
        whole.feed(joined)
        whole.end()
        assertTrue("no step at the join", whole.left().contentEquals(split.left()))
        // And it was levelled by the album's value, not left alone.
        val expected = 10f.pow((-7f + 4f) / 20f)
        val peakOut = split.left().maxOf { abs(it.toInt()) }
        assertTrue("turned down about 3 dB (and the bass raised): $peakOut", peakOut < 0.5 * 32767 * expected * 2.2)
    }

    @Test
    fun shuffled_the_same_two_songs_each_take_their_own_value_at_the_join() {
        val one = song("one", album = "x", track = 1, gain = ReplayGain(-4f, -7f, 1f, 1f))
        val two = song("two", album = "x", track = 2, gain = ReplayGain(-10f, -7f, 1f, 1f))
        val rig = Rig(SoundProcessor().also { it.sound = Sound.of(Settings(normalize = true), true) })
        val timeline = timelineOf(one, two)
        rig.begin(timeline, 0)
        rig.feed(steady(1000, 10_000))
        rig.begin(timeline, 1)
        rig.feed(steady(1000, 10_000))
        rig.end()
        val out = rig.left()
        assertEquals((10_000 * 10.0.pow(0.0 / 20)).toFloat(), out[999].toFloat(), 1f)
        assertEquals((10_000 * 10.0.pow(-6.0 / 20)).toFloat(), out[1000].toFloat(), 1f)
    }

    @Test
    fun switched_on_and_off_mid_song_nothing_is_lost_or_repeated_and_nothing_jumps() {
        val processor = SoundProcessor()
        val rig = Rig(processor)
        val a = song("a", gain = ReplayGain(trackGain = -10f, trackPeak = 1f))
        val timeline = timelineOf(a)
        val input = sine(44_100, hz = 300.0)
        rig.begin(timeline, 0)
        rig.feed(input.copyOfRange(0, 10_000))
        // Levelling on: the processor joins the chain at the next buffer and ramps down.
        processor.sound = Sound.of(Settings(normalize = true), false)
        rig.feed(input.copyOfRange(10_000, 30_000))
        // And off again: it ramps back up, then leaves.
        processor.sound = Sound.OFF
        rig.feed(input.copyOfRange(30_000, 44_100))
        rig.end()
        val out = rig.left()
        assertEquals("every frame once", input.size, out.size)
        for (i in 0 until 10_000) assertEquals("untouched before: $i", input[i], out[i])
        // Settled at -6 dB well inside the second part.
        val g = 10f.pow(-6f / 20f)
        for (i in 20_000 until 30_000) assertEquals(input[i] * g, out[i].toFloat(), 1f)
        // Untouched again once the ramp back has run (50 ms), and out of the chain.
        for (i in 33_000 until 44_100) assertEquals("untouched after: $i", input[i], out[i])
        assertFalse(processor.isActive())
        // No click: from one sample to the next it never moves more than the sine itself can.
        val most = (2 * PI * 300 / rate * 0.5 * 32767).toInt() + 10
        for (i in 1 until out.size) assertTrue("step at $i", abs(out[i] - out[i - 1]) <= most)
    }

    /** Switched off while paused, between songs: the next song never goes through it. */
    @Test
    fun switched_off_between_songs_the_next_song_is_left_out_from_its_first_sample() {
        val processor = levelled()
        val rig = Rig(processor)
        val timeline = timelineOf(song("a", gain = ReplayGain(trackGain = -10f, trackPeak = 1f)), song("b", gain = ReplayGain(trackGain = -3f, trackPeak = 1f)))
        rig.begin(timeline, 0)
        rig.feed(steady(3000, 10_000))
        processor.sound = Sound.OFF
        val before = processor.framesProcessed
        val second = sine(3000)
        rig.begin(timeline, 1)
        rig.feed(second)
        rig.end()
        assertEquals("not one frame of the second song through it", before, processor.framesProcessed)
        assertFalse(processor.isActive())
        assertTrue(second.contentEquals(rig.left().copyOfRange(3000, 6000)))
    }

    @Test
    fun after_a_seek_the_first_sample_is_the_first_one_fed_at_the_songs_own_level() {
        val processor = levelled()
        val rig = Rig(processor)
        val timeline = timelineOf(song("a", gain = ReplayGain(trackGain = -10f, trackPeak = 1f)))
        rig.begin(timeline, 0)
        rig.feed(steady(3000, 10_000))
        rig.sink.flush() // a seek: the renderer flushes the sink and feeds from the new place
        rig.tap.samples.clear()
        rig.pts = 5_000_000
        rig.sink.play()
        rig.feed(steady(3000, 20_000))
        rig.end()
        val out = rig.left()
        assertEquals("nothing from before the seek, nothing twice", 3000, out.size)
        assertEquals(20_000 * 10f.pow(-6f / 20f), out[0].toFloat(), 1f)
        assertEquals(20_000 * 10f.pow(-6f / 20f), out[2999].toFloat(), 1f)
    }

    @Test
    fun mono_mixes_the_channels_from_a_songs_first_sample() {
        val processor = SoundProcessor().also { it.sound = Sound.of(Settings(mono = true), false) }
        val rig = Rig(processor)
        val timeline = timelineOf(song("a"))
        rig.begin(timeline, 0)
        rig.feed(steady(3000, 8000), steady(3000, 0))
        rig.end()
        // A song that begins with mono on has it from its first sample: left and right are
        // each the mix of the two.
        val both = rig.tap.samples
        assertEquals(6000, both.size)
        for (i in both.indices) assertEquals("sample $i", 4000f, both[i].toFloat(), 1f)
    }

    // ---- In the real player ------------------------------------------------------------------

    private fun wav(left: ShortArray): ByteArray {
        val data = left.size * 4
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        head.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        head.put("data".toByteArray()).putInt(data)
        out.write(head.array())
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        for (s in left) body.putShort(s).putShort(s)
        out.write(body.array())
        return out.toByteArray()
    }

    /**
     * ExoPlayer itself, playing two files one after the other with Media3's own audio renderer:
     * the join is where the player puts it, and the second song's gain begins exactly there.
     */
    @Test
    fun in_the_player_the_gain_changes_exactly_at_the_join() {
        val processor = levelled()
        val tap = Tap()
        val sink = SoundSink(
            DefaultAudioSink.Builder(RuntimeEnvironment.getApplication()).setAudioProcessors(arrayOf(processor, TeeAudioProcessor(tap))).build(),
            processor,
        )
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf<Renderer>(MediaCodecAudioRenderer(RuntimeEnvironment.getApplication(), MediaCodecSelector.DEFAULT, handler, audio, sink))
        }
        val frames = 22_050
        val files = listOf("a", "b").map { name -> folder.newFile("$name.wav").apply { writeBytes(wav(steady(frames, 10_000))) } }
        val songs = listOf(song("a", gain = ReplayGain(trackGain = -6f, trackPeak = 1f)), song("b", gain = ReplayGain(trackGain = -12f, trackPeak = 1f)))
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), renderers).setClock(FakeClock(true)).build()
        try {
            player.setMediaItems(songs.mapIndexed { i, s -> MediaItem.Builder().setMediaId(s.id).setUri(Uri.fromFile(files[i])).setTag(QueuePlayer.SongTag(s, i + 1L)).build() })
            player.prepare()
            player.play()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
        } finally {
            player.release()
        }
        val out = ShortArray(tap.samples.size / 2) { tap.samples[it * 2] }
        assertEquals(2 * frames, out.size)
        val ga = 10_000 * 10f.pow(-2f / 20f)
        val gb = 10_000 * 10f.pow(-8f / 20f)
        assertEquals("the last sample of the first song", ga, out[frames - 1].toFloat(), 1f)
        assertEquals("the first sample of the second", gb, out[frames].toFloat(), 1f)
        for (i in 0 until frames) assertEquals(ga, out[i].toFloat(), 1f)
        for (i in frames until 2 * frames) assertEquals(gb, out[i].toFloat(), 1f)
    }
}
