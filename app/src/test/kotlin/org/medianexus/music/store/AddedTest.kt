package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

/** The phone's keeping of when songs were added: its file, and when it is written. (The
 *  rules about the notes are the shared core's.) */
class AddedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun songs(vararg ids: String) = ids.map { Song(id = it) }

    private fun added(name: String = "added.json") = Added(File(folder.root, name), null, Dispatchers.IO)

    private fun LongArray.list() = toList()

    @Test
    fun whatIsNoticedAndWhatIsAddedSurviveARestart() {
        val added = added()
        // (What each of these does to the notes is the shared core's rule, tested there:
        // here, that the phone hands it the playlist's notes and keeps what comes back.)
        added.sawCopy("p", songs("a", "b"), 100)
        added.sawCopy("p", songs("a", "b", "c"), 200)
        added.record("p", listOf("d"), 300)
        assertEquals(listOf(300L, 200L, 0L, 0L), added.dates("p", songs("d", "c", "a", "b")).list())
        // Only the song the app added itself is anything to tell other devices.
        assertEquals(mapOf("p" to mapOf("d" to AddedNote(listOf(300), 300))), added.shared())
        assertEquals(listOf(300L, 200L, 0L, 0L), added().dates("p", songs("d", "c", "a", "b")).list())
        // A playlist that has gone: nothing is kept about it.
        added.forget("p")
        assertEquals(listOf(0L), added().dates("p", songs("d")).list())
    }

    @Test
    fun notesTakenInFromOtherDevicesAreOnTheDiskWhenTheyAreTaken() {
        val added = added()
        added.sawCopy("p", songs("a"), 100)
        assertTrue(added.take(mapOf("p" to mapOf("s" to AddedNote(listOf(5), 5)))))
        // Read back from the file as a phone started again reads it: nothing was waited for.
        assertEquals(listOf(5L), added().dates("p", songs("s")).list())
        // Taken again, nothing changes.
        assertFalse(added.take(mapOf("p" to mapOf("s" to AddedNote(listOf(5), 5)))))
        // The file can't be written (a folder stands where it is): that is an error, and the
        // notes are as they were, so that they are offered and taken again.
        val file = File(folder.root, "added.json")
        file.delete()
        File(file, "in the way").mkdirs()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { added.take(mapOf("p" to mapOf("t" to AddedNote(listOf(9), 9)))) }
        assertEquals(listOf(0L), added.dates("p", songs("t")).list())
    }

    @Test
    fun readsTheFileFromBeforeNotesWereShared() {
        File(folder.root, "added.json").writeText("""{"p":{"a":[0],"b":[0,250]}}""")
        val added = added()
        assertEquals(listOf(0L, 0L, 250L), added.dates("p", songs("a", "b", "b")).list())
        // It counts as looked at, so a new song is dated.
        added.sawCopy("p", songs("a", "b", "b", "c"), 300)
        assertEquals(listOf(300L), added.dates("p", songs("c")).list())
    }

    @Test
    fun writesTheFileWindowsReads() {
        val added = added()
        added.sawCopy("p", songs("a"), 100)
        added.record("p", listOf("b"), 200)
        val text = File(folder.root, "added.json").readText()
        assertTrue(text, text.contains("\"lists\":{\"p\":{") && text.contains("\"b\":{\"times\":[200],\"at\":200}") && text.contains("\"seen\":[\"p\"]"))
        // A file that can't be read is not taken for notes, nor written over until there's a change.
        File(folder.root, "broken.json").writeText("{not json")
        assertEquals(listOf(0L), added("broken.json").dates("p", songs("a")).list())
    }

    @Test
    fun theFileIsNamedAsWindowsNamesItAndAnEarlierOneIsMovedThere() {
        // Windows keeps it as playlist-added.json; the phone kept it as added.json at first.
        val old = java.io.File(folder.root, "added.json")
        val earlier = Added(old, null, kotlinx.coroutines.Dispatchers.Unconfined)
        earlier.sawCopy("p", listOf(org.medianexus.music.net.Song(id = "a")), 100)
        earlier.sawCopy("p", listOf(org.medianexus.music.net.Song(id = "a"), org.medianexus.music.net.Song(id = "b")), 200)
        val now = java.io.File(folder.root, "playlist-added.json")
        val added = Added(now, null, kotlinx.coroutines.Dispatchers.Unconfined, formerly = old)
        org.junit.Assert.assertArrayEquals(longArrayOf(0, 200), added.dates("p", listOf(org.medianexus.music.net.Song(id = "a"), org.medianexus.music.net.Song(id = "b"))))
        org.junit.Assert.assertTrue(now.isFile)
        org.junit.Assert.assertFalse(old.exists())
    }
}
