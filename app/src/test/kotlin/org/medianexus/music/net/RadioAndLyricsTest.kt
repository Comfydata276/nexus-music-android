package org.medianexus.music.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Ported from the tests in the Windows app's src/subsonic.rs.
class RadioAndLyricsTest {
    @Test
    fun takesALyricsFilesOffsetOffItsTimes() {
        fun line(start: Long?) = LyricLine(start, "la")
        // 500 ms sooner; a line can't start before the song does.
        val sooner = shifted(listOf(line(200), line(1500), line(null)), 500)
        assertEquals(listOf(0L, 1000L, null), sooner.map { it.start })
        val later = shifted(listOf(line(200)), -300)
        assertEquals(500L, later[0].start)
    }

    @Test
    fun findsTheStreamInStationPlaylists() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Some Station\nhttp://example.org/live.mp3\n"
        assertEquals("http://example.org/live.mp3", streamInPlaylist(m3u))
        val pls = "[playlist]\nNumberOfEntries=1\nFile1=https://example.org/stream\nTitle1=Some Station\n"
        assertEquals("https://example.org/stream", streamInPlaylist(pls))
        assertNull(streamInPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\nhttp://x/seg1.ts\n"))
        assertNull(streamInPlaylist("nothing here"))
    }

    /** The Windows test reads a whole stream through its announcement filter; the phone's
     *  player does the filtering, so this checks the reading of one announcement. */
    @Test
    fun readsTheTitleOutOfAStationsAnnouncement() {
        assertEquals("Band - Song", streamTitle("StreamTitle='Band - Song';\u0000\u0000\u0000"))
        assertEquals("Band - Song", streamTitle("StreamTitle='Band - Song';StreamUrl='';"))
        assertEquals("Cut short", streamTitle("StreamTitle='Cut short\u0000\u0000"))
        assertNull(streamTitle("StreamUrl='x';"))
    }
}
