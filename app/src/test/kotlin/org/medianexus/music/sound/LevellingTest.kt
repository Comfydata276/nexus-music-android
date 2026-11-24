package org.medianexus.music.sound

import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song
import org.medianexus.music.store.Settings

/** Windows' tests of `level_gain` and `in_album_order` (src/playback.rs), ported. */
class LevellingTest {
    private fun sound(settings: Settings, shuffled: Boolean = false) = Sound.of(settings, shuffled)

    private fun tags(gain: Float, peak: Float) = ReplayGain(trackGain = gain, albumGain = gain - 5f, trackPeak = peak, albumPeak = peak)

    private fun decibels(gain: Float) = 20f * log10(gain)

    @Test
    fun album_order_decides_auto_levelling() {
        fun track(album: String, disc: Int, track: Int) = Song(albumId = album, discNumber = disc, track = track)
        val one = track("a", 1, 1)
        val two = track("a", 1, 2)
        val three = track("a", 1, 3)
        // Playing an album through, in order.
        assertTrue(Levelling.inAlbumOrder(two, one, three, false))
        assertTrue(Levelling.inAlbumOrder(one, null, two, false))
        assertTrue(Levelling.inAlbumOrder(three, two, null, false))
        // Across a disc change, with or without disc numbers on the first disc.
        assertTrue(Levelling.inAlbumOrder(track("a", 2, 1), track("a", 1, 12), null, false))
        assertTrue(Levelling.inAlbumOrder(track("a", 2, 1), track("a", 0, 12), null, false))
        // Shuffled, even if two happen to land in order.
        assertFalse(Levelling.inAlbumOrder(two, one, three, true))
        // A playlist: neighbours from other albums, or from the same album out of order.
        assertFalse(Levelling.inAlbumOrder(two, track("b", 1, 1), track("c", 1, 3), false))
        assertFalse(Levelling.inAlbumOrder(two, track("a", 1, 7), track("a", 1, 9), false))
        assertFalse(Levelling.inAlbumOrder(two, three, one, false))
        // Two consecutive tracks of an album within a playlist keep their balance.
        assertTrue(Levelling.inAlbumOrder(two, track("b", 1, 1), three, false))
        // No track numbers: any neighbour from the album counts; no album never does.
        assertTrue(Levelling.inAlbumOrder(track("a", 0, 0), track("a", 0, 0), null, false))
        assertFalse(Levelling.inAlbumOrder(track("", 1, 2), track("", 1, 1), null, false))
        // A lone song.
        assertFalse(Levelling.inAlbumOrder(one, null, null, false))
    }

    @Test
    fun turns_down_songs_that_have_no_levelling_tags() {
        fun level(settings: Settings) = Levelling.gain(sound(settings), null, false)
        // Treated as tagged "6 dB down" unless told otherwise: at the Quiet level (1 dB under
        // the tags' own reference) that's 7 dB down, at Normal (4 dB over it) 2 dB down.
        var settings = Settings(normalize = true, volumeLevel = 0)
        assertEquals(-7f, decibels(level(settings)), 0.01f)
        settings = settings.copy(volumeLevel = 1)
        assertEquals(-2f, decibels(level(settings)), 0.01f)
        settings = settings.copy(untaggedDb = -9f)
        assertEquals(-5f, decibels(level(settings)), 0.01f)
        // Never up: nothing says how much room the song's peaks leave.
        settings = settings.copy(untaggedDb = -3f)
        assertEquals(1f, level(settings))
        // "Leave" leaves them exactly as they are, at every level.
        settings = settings.copy(untaggedDb = 0f, volumeLevel = 0)
        assertEquals(1f, level(settings))
        // With levelling off, nothing is touched.
        settings = Settings(normalize = false, untaggedDb = -9f)
        assertEquals(1f, level(settings))
        // A song with tags that say nothing about the track goes the same way as one without.
        val empty = ReplayGain(null, null, null, null)
        assertEquals(-2f, decibels(Levelling.gain(sound(Settings(normalize = true, volumeLevel = 1)), empty, false)), 0.01f)
    }

    @Test
    fun loudness_levels_respect_peaks() {
        var settings = Settings(normalize = true, volumeLevel = 1)
        // A loud modern master is turned down: -8 dB of ReplayGain plus Normal's +4 dB.
        assertEquals(10f.pow(-4f / 20f), Levelling.gain(sound(settings), tags(-8f, 1f), false), 0.001f)
        // A quiet track with full-scale peaks can't be raised without clipping.
        assertEquals(1f, Levelling.gain(sound(settings), tags(3f, 1f), false))
        // Unless Loud is chosen, which allows a little and leans on the limiter.
        settings = settings.copy(volumeLevel = 2)
        assertEquals(1.41f, Levelling.gain(sound(settings), tags(3f, 1f), false), 0.001f)
        // Off means untouched; a file with no tag is the untagged amount (see above), and with
        // "Leave" untouched.
        settings = settings.copy(normalize = false)
        assertEquals(1f, Levelling.gain(sound(settings), tags(-8f, 1f), false))
        settings = settings.copy(normalize = true, untaggedDb = 0f)
        assertEquals(1f, Levelling.gain(sound(settings), null, false))
        // Playing through an album, Auto uses the album's value (5 dB lower in this fixture);
        // "Song" never does, and "Album" always does.
        settings = settings.copy(volumeLevel = 1)
        val album = 10f.pow((-8f - 5f + 4f) / 20f)
        assertEquals(album, Levelling.gain(sound(settings), tags(-8f, 1f), true), 0.001f)
        settings = settings.copy(levelMode = 1)
        assertEquals(10f.pow(-4f / 20f), Levelling.gain(sound(settings), tags(-8f, 1f), true), 0.001f)
        settings = settings.copy(levelMode = 2)
        assertEquals(album, Levelling.gain(sound(settings), tags(-8f, 1f), false), 0.001f)
    }

    /** The phone's own cases: a peak that would clip at Loud, and an album value missing. */
    @Test
    fun a_peak_limits_the_gain_at_every_target_and_album_falls_back_to_the_song() {
        val quietSong = ReplayGain(trackGain = 6f, trackPeak = 0.5f)
        // Normal wants +10 dB (x3.16); the peak at half of full scale allows x2 and no more.
        assertEquals(2f, Levelling.gain(sound(Settings(normalize = true, volumeLevel = 1)), quietSong, false), 0.001f)
        // Loud allows the peak 3 dB over: x2.82.
        assertEquals(2.82f, Levelling.gain(sound(Settings(normalize = true, volumeLevel = 2)), quietSong, false), 0.001f)
        // Quiet wants +5 dB (x1.78), under what the peak allows.
        assertEquals(10f.pow(5f / 20f), Levelling.gain(sound(Settings(normalize = true, volumeLevel = 0)), quietSong, false), 0.001f)
        // Album mode with no album value: the song's own, and its own peak.
        assertEquals(2f, Levelling.gain(sound(Settings(normalize = true, levelMode = 2)), quietSong, true), 0.001f)
        // An album value with no album peak takes the song's peak.
        val noAlbumPeak = ReplayGain(trackGain = -3f, albumGain = 6f, trackPeak = 0.5f)
        assertEquals(2f, Levelling.gain(sound(Settings(normalize = true, levelMode = 2)), noAlbumPeak, true), 0.001f)
    }
}
