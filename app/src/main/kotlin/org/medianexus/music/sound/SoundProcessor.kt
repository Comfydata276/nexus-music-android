package org.medianexus.music.sound

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.playback.QueuePlayer
import org.medianexus.music.store.Settings

/**
 * What the sound settings are, as the audio thread takes them: one value, made on the main
 * thread and never changed, swapped in whole (see [SoundProcessor.sound]). Windows' `Controls`
 * (src/audio/dsp.rs) and the levelling settings, with Windows' limits applied.
 */
class Sound private constructor(
    val levelling: Boolean,
    val volumeLevel: Int,
    val levelMode: Int,
    val untaggedDb: Float,
    private val gains: FloatArray,
    /** The preamp in use, in dB, 0 or below ([Settings.eqPreampNow]). */
    val preampDb: Float,
    val mono: Boolean,
    /** The queue plays shuffled (Auto levelling then goes by song). */
    val shuffled: Boolean,
    equaliser: Boolean,
) {
    /** A band's gain in dB, as set (whether or not the equaliser is on). */
    fun gain(band: Int): Float = gains[band]

    /** The equaliser is on and does something: a band off zero, or the preamp turned down.
     *  (Windows' `active`.) */
    val eqOn: Boolean = equaliser && (gains.any { abs(it) > 0.05f } || preampDb < -0.01f)

    /** Anything at all is to be done to the sound. With nothing (and nothing ramping), the
     *  processor hands every sample on as it came. */
    val wanted: Boolean = levelling || eqOn || mono

    companion object {
        val OFF = Sound(false, 1, 0, -6f, FloatArray(6), 0f, false, false, false)

        fun of(settings: Settings, shuffled: Boolean): Sound = Sound(
            levelling = settings.normalize,
            volumeLevel = settings.volumeLevel.coerceIn(0, 2),
            levelMode = settings.levelMode.coerceIn(0, 2),
            untaggedDb = settings.untaggedDb.coerceAtMost(0f),
            gains = FloatArray(6) { settings.eqGains.getOrElse(it) { 0f }.coerceIn(-Eq.RANGE, Eq.RANGE) },
            preampDb = settings.eqPreampNow().coerceIn(-Eq.RANGE, 0f),
            mono = settings.mono,
            shuffled = shuffled,
            equaliser = settings.equalizer,
        )
    }
}

/** Where a song's levelling values come from: the song's own, or, for one that came
 *  without them, the phone's copy of the library. Asked once a song, on the audio thread. */
fun interface SongTags {
    fun of(song: Song): ReplayGain?
}

/**
 * The one audio processor of ours: volume levelling, the equaliser and mono, in that order,
 * as Windows' chain has them (the gain in `feed`, then `Effects::process`). It sits in
 * Media3's sink after the trimming that makes joins gapless and before the debug builds'
 * tap (see `PlaybackService.renderers`).
 *
 * - **Always in the chain.** It is installed from the start and stays: with levelling, the
 *   equaliser and mono all off and nothing ramping, it hands on exactly the samples it was
 *   given (one copy of each buffer). A setting switched on or off takes effect at once, with
 *   its ramp; nothing is ever signalled to the sink. (It used to leave the chain when off,
 *   and to come back it had the sink drain mid-song, which let a gapless song's padding out
 *   of the trimmer and could move the player's clock.)
 * - **Which song, from Media3.** At every flush the sink says which period of the timeline
 *   comes next; the song is read from that period's media item (ours carry it), and its
 *   gain worked out then, with the songs either side of it for Auto. At a join between two
 *   songs the sink has drained the first through the chain before it flushes for the second,
 *   so the new gain begins on exactly the second song's first sample. Inside an album played
 *   in order both have the album's gain, and nothing changes at all.
 * - **No clicks.** A change while music plays is ramped over 50 ms (the gain, mono), and
 *   the equaliser's bands move to their new values in small steps.
 * - **No clipping.** Whenever a sample may have been pushed past where it was (a gain above
 *   1, or the equaliser running), it goes through Windows' soft limiter, whole, whatever is
 *   ramping: never faded in or out, never blended. Otherwise nothing can go over full scale,
 *   and nothing is rounded off (Normal and Quiet never round a peak, as on Windows).
 * - **Nothing made on the audio thread** while it runs: the settings arrive as one value
 *   swapped in ([Sound]), filters and buffers are made when the format changes, and a song is
 *   looked up once, at the flush that begins it.
 *
 * It takes 16-bit and float PCM, whatever the sample rate and channels, and hands back the
 * same: the sink gives it 16-bit (the player's float output is off), so nothing is converted
 * that would not have been.
 */
@OptIn(UnstableApi::class)
class SoundProcessor(private val tags: SongTags = SongTags { it.replayGain }) : BaseAudioProcessor() {
    /** The settings in force. Written on the main thread, read on the audio thread. */
    @Volatile
    var sound: Sound = Sound.OFF

    // ---- What the audio thread holds (only the playback thread touches these) ----

    private var rate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var rampFrames = 1f
    private var lastSound: Sound? = null

    /** Everything up to here was drained through to the end (the sink does that before a
     *  gapless join), so what comes next follows on from it. */
    private var drained = false

    /** The song being processed: its period, its levelling values, and whether it plays as
     *  part of its album (decided as it begins, as Windows decides when a song loads). */
    private var period: Any? = null
    private var hasSong = false
    private var songTags: ReplayGain? = null
    private var albumRun = false

    /** The gain for the song sounding, under the settings in force: it changes, as a step,
     *  where the song does. */
    private var songGain = 1f

    /** A change the person made, easing in: the gain heard is [songGain] times this, which
     *  moves to 1 over the ramp. A change of song doesn't touch it. */
    private var ease = 1f
    private var easeStep = 0f

    /** Nothing has sounded since a reset or a new format: the next start is in place at once. */
    private var fresh = true
    private var mono = 0f
    private var monoTarget = 0f

    private val filters = EqFilters()
    private val bands = FloatArray(6)
    private val bandTargets = FloatArray(6)
    private var preampDb = 0f
    private var preampTarget = 0f
    private var preamp = 1f
    private var eqRunning = false
    private var eqStepDb = 1f
    private var blockLeft = 0
    private var frame = FloatArray(2)

    private val window = Timeline.Window()
    private val periodHolder = Timeline.Period()

    /** Frames that have been through it: for tests, which show it was left out. */
    @Volatile
    var framesProcessed = 0L
        private set

    /** Time spent processing them, in nanoseconds: what it costs (debug builds log it). */
    @Volatile
    var busyNanos = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val ok = inputAudioFormat.channelCount > 0 && inputAudioFormat.sampleRate > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        return if (ok) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val s = sound
        val format = inputAudioFormat
        val formatChanged = format.sampleRate != rate || format.channelCount != channels || format.encoding != encoding
        if (formatChanged && isActive) {
            rate = format.sampleRate
            channels = format.channelCount
            encoding = format.encoding
            rampFrames = maxOf(1f, rate * RAMP_SECONDS)
            // The bands move a full swing (24 dB) in one ramp, a block at a time.
            eqStepDb = 2 * Eq.RANGE * BLOCK / rampFrames
            if (frame.size < channels) frame = FloatArray(channels)
            filters.format(rate, channels)
            fresh = true
        }
        // The settings the sound is under; a newer value is eased in at the next buffer.
        val settings = if (fresh) s else lastSound ?: s
        val joined = streamMetadata.periodUid != period || period == null
        if (joined) whichSong(streamMetadata, settings)
        // A new song's level is in place from its first sample, as a step; whatever the
        // person changed is still easing in, and carries on.
        songGain = songGainFor(settings)
        if (fresh) {
            arrive(settings)
            fresh = false
        }
        lastSound = settings
        // The equaliser remembers what has passed only across sound that really is one: the
        // sink drained everything before this flush (a gapless join). Any other flush (a seek,
        // another song chosen, a new queue, a reset) is a break, and nothing rings over it.
        if (!drained) filters.clear()
        drained = false
    }

    override fun onQueueEndOfStream() {
        drained = true
    }

    override fun onReset() {
        drained = false
        period = null
        hasSong = false
        songTags = null
        rate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
        lastSound = null
        fresh = true
        identity()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val began = System.nanoTime()
        val s = sound
        if (s !== lastSound) {
            lastSound = s
            retarget(s)
        }
        val float = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        if (settled()) {
            // Nothing to do: every sample handed on exactly as it came.
            val out = replaceOutputBuffer(frames * frameBytes)
            inputBuffer.limit(inputBuffer.position() + frames * frameBytes)
            out.put(inputBuffer)
            out.flip()
            framesProcessed += frames
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(frames * frameBytes)
        var at = input.position()
        for (f in 0 until frames) {
            if (--blockLeft <= 0) {
                blockLeft = BLOCK
                stepEq()
            }
            if (ease != 1f) ease = approach(ease, 1f, easeStep)
            val gain = songGain * ease
            if (mono != monoTarget) mono = approach(mono, monoTarget, 1f / rampFrames)
            var sum = 0f
            for (ch in 0 until channels) {
                var x = if (float) input.getFloat(at) else input.getShort(at) / 32768f
                at += bytesPerSample
                x *= gain
                if (eqRunning) x = filters.run(ch, x * preamp)
                // Whatever is ramping, anything that may have been pushed past where it was
                // goes through the limiter, whole: nothing leaves over full scale.
                if (gain > 1f || eqRunning) x = Eq.softClip(x)
                frame[ch] = x
                sum += x
            }
            if (mono > 0f && channels > 1) {
                val mixed = sum / channels
                for (ch in 0 until channels) frame[ch] += mono * (mixed - frame[ch])
            }
            for (ch in 0 until channels) {
                val x = frame[ch]
                if (float) {
                    out.putFloat(x)
                } else {
                    // Turned into a float and back by exact powers of two: untouched, a
                    // sample comes out exactly as it went in.
                    out.putShort((x * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.position() + frames * frameBytes)
        out.flip()
        framesProcessed += frames
        busyNanos += System.nanoTime() - began
    }

    // ---- Inside ---------------------------------------------------------------------------

    /** Reads the song a period stands for from the timeline the sink was given, with the songs
     *  either side of it in playing order (ExoPlayer's playlist is the queue in the order it
     *  plays). Something that isn't one of our songs (none, or a station) is left as it is. */
    private fun whichSong(meta: AudioProcessor.StreamMetadata, s: Sound) {
        val uid = meta.periodUid
        period = uid
        hasSong = false
        songTags = null
        albumRun = false
        val timeline = meta.timeline
        if (uid == null || timeline.isEmpty) return
        val index = timeline.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return
        val w = timeline.getPeriod(index, periodHolder).windowIndex
        val song = songAt(timeline, w) ?: return
        if (song.radioUrl.isNotEmpty()) return
        val before = if (w > 0) songAt(timeline, w - 1) else null
        val after = if (w < timeline.windowCount - 1) songAt(timeline, w + 1) else null
        hasSong = true
        songTags = tags.of(song)
        albumRun = Levelling.inAlbumOrder(song, before, after, s.shuffled)
    }

    private fun songAt(timeline: Timeline, index: Int): Song? =
        (timeline.getWindow(index, window).mediaItem.localConfiguration?.tag as? QueuePlayer.SongTag)?.song

    private fun songGainFor(s: Sound): Float = if (hasSong) Levelling.gain(s, songTags, albumRun) else 1f

    /** The person changed something: each part eases towards what `s` asks for, from where it
     *  is now (the gain heard doesn't jump). */
    private fun retarget(s: Sound) {
        val target = songGainFor(s)
        ease = songGain * ease / target
        easeStep = abs(ease - 1f) / rampFrames
        songGain = target
        monoTarget = if (s.mono) 1f else 0f
        for (band in 0 until 6) bandTargets[band] = if (s.eqOn) s.gain(band) else 0f
        preampTarget = if (s.eqOn) s.preampDb else 0f
    }

    /** Sound begins with nothing before it (after a reset, or in a new format): everything is
     *  as `s` asks from the first sample. */
    private fun arrive(s: Sound) {
        ease = 1f
        mono = if (s.mono) 1f else 0f
        monoTarget = mono
        preampTarget = if (s.eqOn) s.preampDb else 0f
        preampDb = preampTarget
        preamp = 10f.pow(preampDb / 20f)
        var any = preampDb != 0f
        for (band in 0 until 6) {
            bandTargets[band] = if (s.eqOn) s.gain(band) else 0f
            bands[band] = bandTargets[band]
            filters.set(band, bands[band])
            if (bands[band] != 0f) any = true
        }
        filters.clear()
        eqRunning = any
    }

    /** Moves the equaliser's bands and preamp a step towards where they are going, once a
     *  block; once they are all at zero with nowhere to go, the filters stop running. */
    private fun stepEq() {
        var moving = false
        var any = preampTarget != 0f || preampDb != 0f
        for (band in 0 until 6) {
            val target = bandTargets[band]
            val now = bands[band]
            if (now != target) {
                val next = approach(now, target, eqStepDb)
                bands[band] = next
                filters.set(band, next)
                moving = true
            }
            if (bands[band] != 0f || target != 0f) any = true
        }
        if (preampDb != preampTarget) {
            preampDb = approach(preampDb, preampTarget, eqStepDb)
            preamp = 10f.pow(preampDb / 20f)
            moving = true
        }
        if (any && !eqRunning) {
            filters.clear()
            eqRunning = true
        } else if (!any && !moving) {
            eqRunning = false
        }
    }

    /** Nothing to do, and nothing still ramping. */
    private fun settled(): Boolean =
        songGain == 1f && ease == 1f && mono == 0f && monoTarget == 0f &&
            !eqRunning && preampDb == 0f && bands.all { it == 0f } && bandTargets.all { it == 0f } && preampTarget == 0f

    private fun identity() {
        songGain = 1f; ease = 1f; easeStep = 0f
        mono = 0f; monoTarget = 0f
        bands.fill(0f); bandTargets.fill(0f)
        preampDb = 0f; preampTarget = 0f; preamp = 1f
        eqRunning = false
        if (rate > 0) for (band in 0 until 6) filters.set(band, 0f)
    }

    private fun approach(now: Float, target: Float, step: Float): Float =
        if (abs(target - now) <= step) target else if (target > now) now + step else now - step

    private companion object {
        /** How long a change while music plays takes to come in. */
        const val RAMP_SECONDS = 0.05f

        /** How many frames the equaliser's bands hold still between steps. */
        const val BLOCK = 32
    }
}
