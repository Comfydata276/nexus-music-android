package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.net.Song

/** The line under the song in Now Playing, worded as Windows' player bar words it. */
class FormatLineTest {
    private val live = "Live stream"

    @Test
    fun aLosslessFileAsStoredGivesItsRateAndDepth() {
        val flac = Song(suffix = "flac", samplingRate = 48_000, bitDepth = 24, bitRate = 2_100)
        assertEquals(FormatLine("FLAC · 48 kHz · 24-bit", true), formatLine(flac, 0, false, live))
        assertEquals(FormatLine("FLAC · 44.1 kHz", true), formatLine(Song(suffix = "flac", samplingRate = 44_100), 0, false, live))
    }

    @Test
    fun aLossyFileGivesItsBitRate() {
        assertEquals(FormatLine("MP3 · 320 kbps", false), formatLine(Song(suffix = "mp3", bitRate = 320), 0, false, live))
        assertEquals(FormatLine("OPUS · 128 kbps", false), formatLine(Song(suffix = "opus", bitRate = 128), 0, false, live))
    }

    @Test
    fun whatTheServerConvertsIsAnMp3() {
        // A limit: converted to it, unless it's an MP3 already within it.
        assertEquals(FormatLine("MP3 · 160 kbps", false), formatLine(Song(suffix = "flac", bitDepth = 16), 160, false, live))
        assertEquals(FormatLine("MP3 · 128 kbps", false), formatLine(Song(suffix = "mp3", bitRate = 128), 160, false, live))
        // A file the phone turned out not to play: 320 kbps with no limit.
        assertEquals(FormatLine("MP3 · 320 kbps", false), formatLine(Song(suffix = "m4a", bitRate = 256), 0, true, live))
        assertEquals(FormatLine("MP3 · 320 kbps", false), formatLine(Song(suffix = "wma", bitRate = 192), 0, false, live))
    }

    @Test
    fun aRadioStationIsALiveStream() {
        assertEquals(FormatLine(live, false), formatLine(Song(radioUrl = "http://radio"), 0, false, live))
    }
}
