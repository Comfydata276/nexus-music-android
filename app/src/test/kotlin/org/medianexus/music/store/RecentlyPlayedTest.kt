package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

class RecentlyPlayedTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun stop() = scope.cancel()

    /** Its reads and writes happen there and then, in the order asked, on the test's thread. */
    private val io = Dispatchers.Unconfined
    private var clock = 1_000L

    private fun played(file: File = File(folder.root, "history.json")) = RecentlyPlayed(file, scope, io) { clock }

    private fun RecentlyPlayed.ids() = state.value.map { it.song.id }

    private fun openNow(history: RecentlyPlayed) = runBlocking { history.open() }

    @Test
    fun keepsTheLastHundredNewestFirst() {
        val history = played()
        openNow(history)
        for (n in 1..105) {
            clock++
            history.played(Song(id = "s$n"))
        }
        assertEquals(100, history.state.value.size)
        assertEquals("s105", history.ids().first())
        assertEquals("s6", history.ids().last())
        assertEquals(1_105L, history.state.value.first().at)
    }

    @Test
    fun theSameSongStartingAgainIsNotANewPlay() {
        val history = played()
        openNow(history)
        history.played(Song(id = "a"))
        history.played(Song(id = "a"))
        history.played(Song(id = "b"))
        history.played(Song(id = "a"))
        assertEquals(listOf("a", "b", "a"), history.ids())
        // A radio station's entry, or something with no id, isn't anything to play again.
        history.played(Song(id = ""))
        assertEquals(3, history.state.value.size)
    }

    @Test
    fun keptOnThePhoneInWindowsForm() {
        val file = File(folder.root, "history.json")
        val history = played(file)
        openNow(history)
        history.played(Song(id = "a", title = "Lantern Season", duration = 245))
        val text = file.readText()
        assertEquals("""[{"song":{"id":"a","title":"Lantern Season","duration":245},"at":1000}]""", text)
        // Read again, as it was.
        val again = played(file)
        openNow(again)
        assertEquals(listOf("a"), again.ids())
        assertEquals("Lantern Season", again.state.value[0].song.title)
    }

    @Test
    fun nothingIsWrittenOverBeforeTheListIsRead() {
        val file = File(folder.root, "history.json")
        file.writeText("""[{"song":{"id":"old"},"at":5}]""")
        val history = played(file)
        // A song starts before the account's list has been read (a car, at a cold start).
        history.played(Song(id = "new"))
        assertTrue(file.readText().contains("\"old\""))
        openNow(history)
        assertEquals(listOf("new", "old"), history.ids())
        // A file that can't be read is an empty list, never a reason to stop.
        File(folder.root, "broken.json").writeText("[{\"song\":")
        val broken = played(File(folder.root, "broken.json"))
        openNow(broken)
        assertTrue(broken.state.value.isEmpty())
        assertFalse(broken.ids().contains("old"))
    }

    /** Windows' `plays_from_two_devices_interleave`. */
    @Test
    fun playsFromTwoDevicesInterleave() {
        val here = listOf("c" to 30L, "a" to 10L)
        val there = listOf("d" to 40L, "b" to 20L, "a" to 10L)
        // (The rule is the shared core's, asked through its one door.)
        fun merge(a: List<Pair<String, Long>>, b: List<Pair<String, Long>>) =
            org.medianexus.music.core.Core.history(a.map { org.medianexus.music.core.Play(it.first, it.second) }, b.map { org.medianexus.music.core.Play(it.first, it.second) }).map { it.song to it.at }
        val merged = merge(here, there)
        assertEquals(listOf("d" to 40L, "c" to 30L, "b" to 20L, "a" to 10L), merged)
        assertEquals(merged, merge(there, here))
        // The same song on both devices back to back is one entry.
        assertEquals(listOf("a" to 12L), merge(listOf("a" to 12L), listOf("a" to 10L)))
    }

    @Test
    fun aPlayBeforeTheFileIsReadIsSavedAndNotDoubled() {
        val file = File(folder.root, "history.json")
        // On the phone from before: b, then a (a the newest).
        val earlier = played(file)
        openNow(earlier)
        earlier.played(Song(id = "b"))
        clock++
        earlier.played(Song(id = "a"))
        // Started again: "a" carries on playing and is noted before the file has been read,
        // then "c" starts, all before the read.
        val history = played(file)
        clock++
        history.played(Song(id = "a"))
        clock++
        history.played(Song(id = "c"))
        openNow(history)
        // One "a" at the join, not two; nothing dropped.
        assertEquals(listOf("c", "a", "b"), history.ids())
        // And saved once read, with nothing more played: read again, the same.
        val again = played(file)
        openNow(again)
        assertEquals(listOf("c", "a", "b"), again.ids())
    }
}
