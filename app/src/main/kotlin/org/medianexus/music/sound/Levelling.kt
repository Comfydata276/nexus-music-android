package org.medianexus.music.sound

import kotlin.math.pow
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.store.VOLUME_LEVELS

/**
 * Volume levelling: how much to turn a song up or down so it lands at the chosen loudness,
 * from the ReplayGain values the server gives for it. A port of Windows' `level_gain` and
 * `in_album_order` (src/playback.rs), with their tests.
 */
object Levelling {
    /**
     * The gain to apply to a song so it lands at the chosen loudness, as a factor (1 leaves
     * it as it is). `albumRun` says the song is playing as part of its album (see
     * [inAlbumOrder]).
     */
    fun gain(sound: Sound, tags: ReplayGain?, albumRun: Boolean): Float {
        if (!sound.levelling) return 1f
        val offset = VOLUME_LEVELS[sound.volumeLevel.coerceIn(0, VOLUME_LEVELS.lastIndex)]
        // A song with no tags to go by is treated as if its tag said a set amount, about what
        // a typical song's does, so it doesn't jump out among tagged ones. Never turned up:
        // nothing says how much room its peaks leave. (0 leaves such songs exactly as they are.)
        val untagged = if (sound.untaggedDb < 0f) minOf(10f.pow((sound.untaggedDb + offset) / 20f), 1f) else 1f
        if (tags == null) return untagged
        // Album gain turns a whole album up or down by one amount, keeping its songs in
        // proportion; it falls back to the song's own value when the album one is missing.
        val byAlbum = when (sound.levelMode) {
            1 -> false
            2 -> true
            else -> albumRun
        }
        val albumGain = tags.albumGain
        val gainDb: Float
        val peak: Float?
        if (byAlbum && albumGain != null) {
            gainDb = albumGain
            peak = tags.albumPeak ?: tags.trackPeak
        } else {
            gainDb = tags.trackGain ?: return untagged
            peak = tags.trackPeak
        }
        val gain = 10f.pow((gainDb + offset) / 20f)
        // Quiet and Normal never push a song's peaks past full scale. Loud may, by up to
        // 3 dB, and the limiter rounds those peaks off: that's the quality cost of the Loud
        // setting.
        val top = peak?.takeIf { it > 0f } ?: 1f
        val headroom = if (sound.volumeLevel >= 2) 1.41f else 1f
        return minOf(gain, headroom / top)
    }

    /**
     * Whether a song is playing as part of its album, which is when Auto levels by album:
     * shuffle is off and the song before or after it is the album's previous or next track.
     * A playlist or a shuffle uses each song's own value instead, except for a run of
     * consecutive tracks from one album, which keeps their levels relative to each other.
     * Without track numbers to go by, any neighbour from the same album counts.
     */
    fun inAlbumOrder(song: Song, before: Song?, after: Song?, shuffled: Boolean): Boolean {
        if (shuffled || song.albumId.isEmpty()) return false
        return (before != null && neighbours(before, song, isBefore = true)) ||
            (after != null && neighbours(after, song, isBefore = false))
    }

    private fun neighbours(other: Song, song: Song, isBefore: Boolean): Boolean {
        if (other.albumId != song.albumId) return false
        if (song.track == 0 || other.track == 0) return true
        return if (isBefore) follows(other, song) else follows(song, other)
    }

    /** Whether `second` follows `first` directly in the album: the next track on the same
     *  disc, or the first track of the next disc. */
    private fun follows(first: Song, second: Song): Boolean {
        val d1 = maxOf(first.discNumber, 1)
        val d2 = maxOf(second.discNumber, 1)
        return (d1 == d2 && first.track + 1 == second.track) || (d1 + 1 == d2 && second.track == 1)
    }
}
