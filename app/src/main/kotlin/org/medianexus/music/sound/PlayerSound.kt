package org.medianexus.music.sound

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineDispatcher
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
class PlayerSound(
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** Log what the processor costs, every half minute (debug builds). */
    logCost: Boolean = org.medianexus.music.BuildConfig.DEBUG,
) {
    /** Levelling values from the phone's copy of the library, for the songs the player holds
     *  that came without their own (a download kept before its song had them). They are the
     *  account's whose copy they came from: gone the moment another account takes over.
     *  Replaced whole; read on the audio thread. */
    @Volatile
    private var fromLibrary: Map<String, ReplayGain> = emptyMap()

    val processor = SoundProcessor(::tagsOf)

    /** A song's levelling values: its own, else what the library copy has. */
    fun tagsOf(song: Song): ReplayGain? = song.replayGain ?: fromLibrary[song.id]

    /** Whose turn it is at the player (the account's objects), or nobody's. */
    private var owner: Any? = null

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
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(processor) + after)
            .build()

    init {
        // Debug builds say what the processor costs, as a share of the time the sound it
        // processed lasts (TESTING.md, "Sound and lyrics").
        if (logCost) scope.launch {
            var sound = 0L
            var busy = 0L
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val s = processor.soundNanos
                val b = processor.busyNanos
                if (s > sound) {
                    android.util.Log.d("NexusSound", "processed %.1f s of sound in %.1f ms: %.3f%% of the time".format((s - sound) / 1e9, (b - busy) / 1e6, (b - busy) * 100.0 / (s - sound)))
                }
                sound = s
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

    /** Another account takes over, or nobody: what was known of the last one's songs goes
     *  first, and a look still going for it is called off. */
    fun account(owner: Any?) {
        if (owner === this.owner) return
        this.owner = owner
        looking?.cancel()
        looking = null
        fromLibrary = emptyMap()
        asked = emptySet()
        askedOf = null
    }

    /**
     * The songs the player holds have changed, or the library copy has: for any that came without levelling values,
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
        val mine = owner
        looking = scope.launch {
            val found = withContext(work) {
                val table = library.songs
                val out = HashMap<String, ReplayGain>()
                for (i in 0 until table.size) {
                    val id = table.id(i)
                    if (id in missing) table.song(i).replayGain?.let { out[id] = it }
                }
                out
            }
            if (owner === mine) fromLibrary = found
        }
    }

    private fun sameSound(a: Settings, b: Settings) =
        a.normalize == b.normalize && a.volumeLevel == b.volumeLevel && a.levelMode == b.levelMode && a.untaggedDb == b.untaggedDb &&
            a.mono == b.mono && a.equalizer == b.equalizer && a.eqGains == b.eqGains && a.eqPreamp == b.eqPreamp
}
