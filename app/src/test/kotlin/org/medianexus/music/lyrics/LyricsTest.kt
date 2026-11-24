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
        lyrics.ahead(listOf("s", "t")) { true }
        advanceUntilIdle()
        assertEquals(0, asked.get())
    }

    @Test
    fun fetched_ahead_once_a_run_and_not_again_within_a_day_unless_it_had_none() = runTest {
        val asked = mutableListOf<String>()
        var clock = 0L
        val lyrics = book({ asked += it; if (it == "none") null else timed }, now = { clock })
        lyrics.ahead(listOf("a", "none", "b")) { true }
        advanceUntilIdle()
        assertEquals(listOf("a", "none", "b"), asked)
        // The same songs again in this run: not asked.
        lyrics.ahead(listOf("a", "none", "b")) { true }
        advanceUntilIdle()
        assertEquals(3, asked.size)
        // A new run (a new book over the same folders) the next hour: only the one that had
        // none is asked again; a day later, all of them.
        clock = System.currentTimeMillis()
        val next = book({ asked += it; timed }, now = { clock })
        next.ahead(listOf("a", "none", "b")) { true }
        advanceUntilIdle()
        assertEquals(listOf("a", "none", "b", "none"), asked)
        clock += LyricsBook.FRESH_MS + 60_000
        val later = book({ asked += it; timed }, now = { clock })
        later.ahead(listOf("a", "b")) { true }
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

    // ---- Rule 7 of the fixes ---------------------------------------------------------------

    /** Opened offline, nothing kept: said so. Opened again once the server is back: asked
     *  again, and shown. (The view used to be remembered as it was, and never asked again.) */
    @Test
    fun opened_again_once_the_server_is_back_it_asks_again() = runTest {
        var online = false
        val lyrics = book({ timed }, offline = { !online })
        val first = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals(LyricsShown.None(offline = true), first.value)
        online = true
        val again = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals(LyricsShown.of(timed), again.value)
        assertEquals(timed, lyrics.kept("s")?.lyrics)
    }

    /** One result per song: what fetching ahead finds reaches a view that is open. */
    @Test
    fun what_fetching_ahead_finds_is_what_the_open_view_shows() = runTest {
        var reachable = false
        val lyrics = book({ if (reachable) timed else throw java.io.IOException("gone") })
        val shown = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals("couldn't ask, nothing kept", LyricsShown.None(offline = false), shown.value)
        assertNull("and \"couldn't ask\" is not kept as none", lyrics.kept("s"))
        reachable = true
        lyrics.ahead(listOf("s")) { true }
        advanceUntilIdle()
        assertEquals(LyricsShown.of(timed), shown.value)
    }

    /** The network is looked at before each request: it turns metered (or Data Saver goes
     *  on) after the first, and nothing more is fetched. */
    @Test
    fun fetching_ahead_looks_at_the_network_before_each_request() = runTest {
        val asked = mutableListOf<String>()
        var allowed = true
        val lyrics = book({ asked += it; allowed = false; timed })
        lyrics.ahead(listOf("a", "b", "c")) { allowed }
        advanceUntilIdle()
        assertEquals(listOf("a"), asked)
        // Allowed again: what wasn't fetched is still to be (it wasn't counted as asked).
        allowed = true
        lyrics.ahead(listOf("a", "b", "c")) { true }
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), asked)
    }

    /** Times out of order: shown as plain lyrics (Windows does nothing about them). A line
     *  timed past the song's end is shown but is no place to seek to. */
    @Test
    fun times_out_of_order_are_plain_and_a_line_past_the_end_is_not_a_place_to_seek() {
        val muddled = Lyrics(true, listOf(LyricLine(5000, "Two"), LyricLine(1000, "One")))
        assertFalse((LyricsShown.of(muddled) as LyricsShown.Lines).timed)
        val long = LyricsShown.of(Lyrics(true, listOf(LyricLine(1000, "In"), LyricLine(25_000, "Past the end")))) as LyricsShown.Lines
        assertTrue(long.timed)
        assertTrue(long.seekable(0, songMs = 20_000))
        assertFalse(long.seekable(1, songMs = 20_000))
        // With the song's length not known (to the player nor the library), no line is.
        assertFalse(long.seekable(0, songMs = 0))
    }

    // ---- The second round of fixes, item 3 ------------------------------------------------

    /** A downloaded song's lyrics that arrive while its panel is open are shown there (they
     *  were written to the phone and shown only when it was opened again). */
    @Test
    fun a_downloaded_songs_lyrics_arriving_while_its_panel_is_open_are_shown() = runTest {
        var online = false
        val lyrics = book({ timed }, offline = { !online })
        val shown = lyrics.forSong("s")
        advanceUntilIdle()
        assertEquals(LyricsShown.None(offline = true), shown.value)
        online = true
        lyrics.downloaded(setOf("s")) { true }
        advanceUntilIdle()
        assertTrue(File(folder.root, "data/s.json").exists())
        assertEquals(LyricsShown.of(timed), shown.value)
    }

    /** Whether the network may be used is asked immediately before the request: here it
     *  stops being allowed while the kept copy is read, and nothing is asked. */
    @Test
    fun fetching_ahead_asks_about_the_network_after_reading_what_is_kept() = runTest {
        val asked = mutableListOf<String>()
        var allowed = true
        val inner = StandardTestDispatcher(testScheduler)
        // Reading the kept copy (on the io dispatcher) is where the network changes.
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                allowed = false
                inner.dispatch(context, block)
            }
        }
        val lyrics = LyricsBook(File(folder.root, "cache"), File(folder.root, "data"), { asked += it; timed }, this, io, { false })
        lyrics.ahead(listOf("a")) { allowed }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), asked)
        // Allowed again: it is fetched (it wasn't counted as asked).
        lyrics.ahead(listOf("a")) { true }
        advanceUntilIdle()
        assertEquals(listOf("a"), asked)
    }
}
