package org.medianexus.music.lyrics

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.net.LyricLine
import org.medianexus.music.net.Lyrics
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsTest {
    @get:Rule val folder = TemporaryFolder()

    private val timed = Lyrics(true, listOf(LyricLine(1200, "One"), LyricLine(5000, "Two"), LyricLine(9000, ""), LyricLine(12000, "Four")))

    @Test
    fun the_current_line_is_the_last_one_begun_including_before_the_first_and_after_the_last() {
        val times = (LyricsShown.of(timed) as LyricsShown.Lines).times
        assertEquals("before the first", -1, LyricsShown.lineAt(times, 0))
        assertEquals(-1, LyricsShown.lineAt(times, 1000))
        // Read 150 ms ahead, as Windows does, so a line lights as it is sung.
        assertEquals(0, LyricsShown.lineAt(times, 1050))
        assertEquals(0, LyricsShown.lineAt(times, 4849))
        assertEquals(1, LyricsShown.lineAt(times, 4850))
        assertEquals(2, LyricsShown.lineAt(times, 9000))
        assertEquals("after the last", 3, LyricsShown.lineAt(times, 600_000))
        assertEquals(-1, LyricsShown.lineAt(LongArray(0), 5000))
    }

    @Test
    fun timed_only_when_the_server_says_synced_and_every_line_has_a_time() {
        assertTrue((LyricsShown.of(timed) as LyricsShown.Lines).timed)
        val gap = timed.copy(line = timed.line + LyricLine(null, "Untimed"))
        assertFalse((LyricsShown.of(gap) as LyricsShown.Lines).timed)
        assertFalse((LyricsShown.of(Lyrics(false, listOf(LyricLine(null, "Plain")))) as LyricsShown.Lines).timed)
        assertEquals(LyricsShown.None(false), LyricsShown.of(null))
        assertEquals(LyricsShown.None(false), LyricsShown.of(Lyrics(true, emptyList())))
    }

    private fun TestScope.book(ask: suspend (String) -> Lyrics?, offline: () -> Boolean = { false }, now: () -> Long = { 0 }) =
        LyricsBook(File(folder.root, "cache"), File(folder.root, "data"), ask, this, StandardTestDispatcher(testScheduler), offline, now)

    @Test
    fun keeps_what_the_server_said_including_that_it_had_none() = runTest {
        val lyrics = book({ null })
        assertNull("never asked", lyrics.kept("a/1"))
        lyrics.keep("a/1", timed)
        lyrics.keep("b", null)
        assertEquals(timed, lyrics.kept("a/1")?.lyrics)
        // That a song has none is kept too, which is different from never having asked.
        val none = lyrics.kept("b")
        assertNotNull(none)
        assertNull(none!!.lyrics)
        assertTrue(File(folder.root, "cache/a_1.json").exists())
    }

    @Test
    fun what_is_kept_shows_at_once_and_the_server_is_asked_behind_it() = runTest {
        val answer = CompletableDeferred<Lyrics?>()
        val lyrics = book({ answer.await() })
        lyrics.keep("s", Lyrics(false, listOf(LyricLine(null, "Old"))))
        val shown = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals(listOf("Old"), (shown.value as LyricsShown.Lines).lines)
        answer.complete(timed)
        advanceUntilIdle()
        assertEquals(listOf("One", "Two", "", "Four"), (shown.value as LyricsShown.Lines).lines)
        assertEquals(timed, lyrics.kept("s")?.lyrics)
    }

    @Test
    fun offline_with_nothing_kept_says_so_and_asks_nothing() = runTest {
        val asked = AtomicInteger()
        val lyrics = book({ asked.incrementAndGet(); timed }, offline = { true })
        val shown = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals(LyricsShown.None(offline = true), shown.value)
        lyrics.ahead(listOf("s", "t"))
        advanceUntilIdle()
        assertEquals(0, asked.get())
    }

    @Test
    fun fetched_ahead_once_a_run_and_not_again_within_a_day_unless_it_had_none() = runTest {
        val asked = mutableListOf<String>()
        var clock = 0L
        val lyrics = book({ asked += it; if (it == "none") null else timed }, now = { clock })
        lyrics.ahead(listOf("a", "none", "b"))
        advanceUntilIdle()
        assertEquals(listOf("a", "none", "b"), asked)
        // The same songs again in this run: not asked.
        lyrics.ahead(listOf("a", "none", "b"))
        advanceUntilIdle()
        assertEquals(3, asked.size)
        // A new run (a new book over the same folders) the next hour: only the one that had
        // none is asked again; a day later, all of them.
        clock = System.currentTimeMillis()
        val next = book({ asked += it; timed }, now = { clock })
        next.ahead(listOf("a", "none", "b"))
        advanceUntilIdle()
        assertEquals(listOf("a", "none", "b", "none"), asked)
        clock += LyricsBook.FRESH_MS + 60_000
        val later = book({ asked += it; timed }, now = { clock })
        later.ahead(listOf("a", "b"))
        advanceUntilIdle()
        assertEquals(listOf("a", "none", "b", "none", "a", "b"), asked)
    }

    @Test
    fun a_downloaded_song_has_its_lyrics_kept_where_android_does_not_clear_them() = runTest {
        val asked = mutableListOf<String>()
        var online = false
        val lyrics = book({ asked += it; timed }, offline = { !online })
        // Already kept in the cache: copied with the download, nothing asked.
        lyrics.keep("kept", timed)
        lyrics.downloaded(setOf("kept", "new")) { true }
        advanceUntilIdle()
        assertTrue(File(folder.root, "data/kept.json").exists())
        assertFalse("offline: waits", File(folder.root, "data/new.json").exists())
        assertEquals(emptyList<String>(), asked)
        // Back online: the one still missing is fetched.
        online = true
        lyrics.downloaded(setOf("kept", "new")) { true }
        advanceUntilIdle()
        assertEquals(listOf("new"), asked)
        assertTrue(File(folder.root, "data/new.json").exists())
        // With the cache cleared by Android, the downloaded song's lyrics are still there.
        File(folder.root, "cache").deleteRecursively()
        assertEquals(timed, lyrics.kept("new")?.lyrics)
        // Removed from the downloads: let go.
        lyrics.downloaded(setOf("kept")) { true }
        advanceUntilIdle()
        assertFalse(File(folder.root, "data/new.json").exists())
    }

    /** What is kept with a download includes its levelling values, so it levels offline. */
    @Test
    fun a_download_keeps_the_songs_levelling_values() {
        val dir = folder.newFolder()
        File(dir, "s.flac").writeBytes(ByteArray(3))
        val gains = ReplayGain(trackGain = -7.6f, albumGain = -4.1f, trackPeak = 0.98f, albumPeak = 0.98f)
        Catalogue(dir).add(Downloaded(Song(id = "s", title = "S", replayGain = gains), "s.flac", 3, at = 1, suffix = "flac"))
        assertEquals(gains, Catalogue(dir).state.value.songs["s"]?.song?.replayGain)
    }
}
