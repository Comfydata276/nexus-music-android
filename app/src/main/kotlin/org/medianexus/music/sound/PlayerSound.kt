package org.medianexus.music.sound

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.store.Settings

/**
 * The playback service's side of the sound: the one [SoundProcessor], the sink it sits in,
 * and what reaches it from the main thread (the settings and shuffle, as one [Sound]; the
 * levelling values the library copy has for songs that came without them).
 */
@OptIn(UnstableApi::class)
class PlayerSound(private val scope: CoroutineScope) {
    /** Levelling values from the phone's copy of the library, for the songs the player holds
     *  that came without their own (a download kept before its song had them). Replaced
     *  whole; read on the audio thread. */
    @Volatile
    private var fromLibrary: Map<String, ReplayGain> = emptyMap()

    val processor = SoundProcessor { song -> song.replayGain ?: fromLibrary[song.id] }

    private var settings: Settings? = null
    private var shuffled = false
    private var asked: Set<String> = emptySet()
    private var askedOf: LibrarySnapshot? = null
    private var looking: Job? = null

    /**
     * Media3's sink as the player uses it, with the processor first among its own processors
     * (after the trimming that makes joins gapless, which Media3 puts before every processor
     * it is given) and `after` (the debug builds' capture) after it, so a capture is what
     * reaches the output.
     */
    fun sink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean, after: Array<AudioProcessor>): AudioSink =
        SoundSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor) + after)
                .build(),
            processor,
        )

    init {
        // Debug builds say what the processor costs, as a share of the time the sound it
        // processed lasts (TESTING.md, "Measurements").
        if (org.medianexus.music.BuildConfig.DEBUG) scope.launch {
            var frames = 0L
            var busy = 0L
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val f = processor.framesProcessed
                val b = processor.busyNanos
                if (f > frames) {
                    val seconds = (f - frames) / 44_100.0
                    android.util.Log.d("NexusSound", "processed %.1f s of sound in %.1f ms: %.3f%% of the time".format(seconds, (b - busy) / 1e6, (b - busy) / 1e7 / seconds))
                }
                frames = f
                busy = b
            }
        }
    }

    /** The settings or shuffle may have changed: a new value for the processor if they have. */
    fun update(settings: Settings, shuffled: Boolean) {
        val before = this.settings
        val wasShuffled = this.shuffled
        this.settings = settings
        this.shuffled = shuffled
        if (before != null && wasShuffled == shuffled && sameSound(before, settings)) return
        processor.sound = Sound.of(settings, shuffled)
    }

    /**
     * The songs the player holds have changed: for any that came without levelling values,
     * the library copy is looked through for them (once, off the main thread, for all of them
     * together). A song whose values arrive after it has begun is levelled from its next play.
     */
    fun songsHeld(songs: List<Song>, library: LibrarySnapshot?) {
        val missing = songs.asSequence().filter { it.replayGain == null && it.radioUrl.isEmpty() }.map { it.id }.toSet()
        if (library == null || (missing == asked && library === askedOf)) return
        asked = missing
        askedOf = library
        looking?.cancel()
        if (missing.isEmpty()) return
        looking = scope.launch {
            val found = withContext(Dispatchers.Default) {
                val table = library.songs
                val out = HashMap<String, ReplayGain>()
                for (i in 0 until table.size) {
                    val id = table.id(i)
                    if (id in missing) table.song(i).replayGain?.let { out[id] = it }
                }
                out
            }
            fromLibrary = found
        }
    }

    private fun sameSound(a: Settings, b: Settings) =
        a.normalize == b.normalize && a.volumeLevel == b.volumeLevel && a.levelMode == b.levelMode && a.untaggedDb == b.untaggedDb &&
            a.mono == b.mono && a.equalizer == b.equalizer && a.eqGains == b.eqGains && a.eqPreamp == b.eqPreamp
}
