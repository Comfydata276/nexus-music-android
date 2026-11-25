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
    }

    @Test
    fun aListThatCouldNotBeReadIsNeverTakenForNoListAndNeverWrittenOver() {
        val file = File(folder.root, "history.json")
        val years = """[{"song":{"id":"old"},"at":5}]"""
        file.writeText(years)
        // It can't be read (the disk gave an error).
        assertTrue(file.setReadable(false))
        org.junit.Assume.assumeFalse("whoever runs this may read anything", runCatching { file.readBytes() }.isSuccess)
        val notes = mutableListOf<String>()
        val history = RecentlyPlayed(file, scope, io, note = { notes += it }) { clock }
        openNow(history)
        assertTrue(notes.any { "couldn't be read" in it })
        // What is here is not the account's history: not shared, not merged, never written.
        assertFalse(history.isOpen)
        history.played(Song(id = "new"))
        assertFalse(runBlocking { history.take(listOf(org.medianexus.music.core.Play("theirs", 9))) { emptyMap() } })
        assertEquals("the song playing still shows", listOf("new"), history.ids())
        assertTrue(file.setReadable(true))
        assertEquals("the file is exactly as it was", years, file.readText())
        // The next start reads it.
        val again = played(file)
        openNow(again)
        assertEquals(listOf("old"), again.ids())
    }

    @Test
    fun aListThatReadsAndIsNoListIsKeptAsideBeforeAnythingIsWritten() {
        val file = File(folder.root, "history.json")
        file.writeText("[{\"song\":")
        val history = played(file)
        openNow(history)
        // (Cut short, or not this app's: the history starts again from nothing, which is
        // the account's, and what was there is kept beside it.)
        assertTrue(history.isOpen && history.state.value.isEmpty())
        assertFalse(File(folder.root, "history.json.damaged").exists())
        history.played(Song(id = "new"))
        assertEquals("[{\"song\":", File(folder.root, "history.json.damaged").readText())
        assertTrue(file.readText().contains("\"new\""))
    }

    @Test
    fun whatWasTakenInAndCouldNotBeWrittenIsStillDueAtTheNextTry() {
        val dir = folder.newFolder("account")
        val file = File(dir, "history.json")
        file.writeText("""[{"song":{"id":"old"},"at":5}]""")
        val history = played(file)
        openNow(history)
        val theirs = listOf(org.medianexus.music.core.Play("theirs", 9))
        val songs: suspend (List<String>) -> Map<String, Song> = { ids -> ids.associateWith { Song(id = it) } }
        // The disk won't take it: the exchange is told, and has failed.
        assertTrue(dir.setWritable(false))
        org.junit.Assume.assumeFalse("whoever runs this may write anywhere", runCatching { File(dir, "probe").writeText("") }.isSuccess)
        assertTrue(runCatching { runBlocking { history.take(theirs, songsOf = songs) } }.exceptionOrNull() is java.io.IOException)
        assertTrue(file.readText().contains("\"old\"") && !file.readText().contains("theirs"))
        // The same again, with nothing new to merge: it is still due, and is written now.
        assertTrue(dir.setWritable(true))
        runBlocking { history.take(theirs, songsOf = songs) }
        assertTrue("what was taken in is on the disk", file.readText().contains("\"theirs\""))
        // (And again fails if the disk fails again, rather than saying all is well.)
        history.played(Song(id = "now"))
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
