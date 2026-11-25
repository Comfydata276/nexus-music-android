package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
    fun aListSortedByDateAddedIsArrangedAgainWhenADateArrivesAndNoOtherListIs() {
        val added = added()
        val view = org.medianexus.music.library.ListView
        val byDate = org.medianexus.music.library.ListView(sort = view.ADDED, descending = true)
        val byTitle = org.medianexus.music.library.ListView(sort = view.TITLE)
        added.sawCopy("p", songs("a", "b"), 100)
        val before = added.version.value
        // A date arrives from another device: what an open list sorted by date is arranged
        // for has changed; what a list in any other order is arranged for has not.
        assertTrue(added.take(mapOf("p" to mapOf("b" to AddedNote(listOf(50), 50)))))
        assertTrue(added.version.value != before)
        assertTrue(view.datesKey(byDate, added.version.value) != view.datesKey(byDate, before))
        assertEquals(view.datesKey(byTitle, before), view.datesKey(byTitle, added.version.value))
        // The same again brings nothing, and nothing is arranged again for it.
        val settled = added.version.value
        assertFalse(added.take(mapOf("p" to mapOf("b" to AddedNote(listOf(50), 50)))))
        added.sawCopy("p", songs("a", "b"), 200)
        assertEquals(settled, added.version.value)
        // A date this phone notes itself does it too.
        added.record("p", listOf("c"), 300)
        assertTrue(added.version.value != settled)
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
    fun aSaveAlreadyUnderWayDoesNotLandOverWhatAnExchangeTookIn() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            val file = File(folder.root, "racing.json")
            val added = Added(file, scope, Dispatchers.IO)
            val theirs = mapOf("p" to mapOf("s" to AddedNote(listOf(5), 5)))
            // An ordinary save has made up its mind to write, with the notes as they were
            // then; and at that moment an exchange takes in a date from another device.
            val took = java.util.concurrent.CompletableFuture<Boolean>()
            val saving = java.util.concurrent.CountDownLatch(1)
            added.duringSave = {
                added.duringSave = null
                kotlin.concurrent.thread { took.complete(added.take(theirs)) }
                // (Long enough for the exchange to have written, if nothing makes it wait
                // for this save: with one writer it waits, and this gives up waiting.)
                runCatching { took.get(2, java.util.concurrent.TimeUnit.SECONDS) }
                saving.countDown()
            }
            added.create("q", listOf("x"), 200)
            assertTrue(took.get(10, java.util.concurrent.TimeUnit.SECONDS))
            // Once both have written (the save goes on to write as this is let go; a
            // moment for it), the file has what was taken in, whichever asked first.
            assertTrue(saving.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(1_000)
            val onDisk = Added(file, null, Dispatchers.IO).let { it.dates("p", songs("s")).list() + it.dates("q", songs("x")).list() }
            assertEquals(listOf(5L, 200L), onDisk)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChangeThatCouldNotBeWrittenIsStillDueAtTheNextExchange() {
        val dir = folder.newFolder("account")
        val file = File(dir, "added.json")
        val added = Added(file, null, Dispatchers.IO)
        added.create("p", listOf("a"), 100)
        // The disk won't take the next one.
        assertTrue(dir.setWritable(false))
        org.junit.Assume.assumeFalse("whoever runs this may write anywhere", runCatching { File(dir, "probe").writeText("") }.isSuccess)
        added.create("q", listOf("x"), 200)
        // An exchange that brings nothing new must still not be done with the notes behind
        // on the disk: it is told so...
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { added.take(emptyMap()) }
        // ...and once the disk takes it, it is written, though nothing new came.
        assertTrue(dir.setWritable(true))
        assertFalse(added.take(emptyMap()))
        assertEquals(listOf(200L), Added(file, null, Dispatchers.IO).dates("q", songs("x")).list())
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
        // A file that holds something other than notes is not taken for notes; and before
        // anything is written over it, it is kept, as it was found, beside the new one.
        val broken = File(folder.root, "broken.json").apply { writeText("{not json") }
        val fresh = added("broken.json")
        assertEquals(listOf(0L), fresh.dates("p", songs("a")).list())
        assertEquals("{not json", broken.readText())
        fresh.record("p", listOf("a"), 300)
        assertEquals("{not json", File(folder.root, "broken.json.damaged").readText())
        assertTrue(broken.readText().contains("\"a\":{\"times\":[300],\"at\":300}"))
    }

    @Test
    fun notesThatCouldNotBeReadAreNeverTakenForNoneAndNeverWrittenOver() {
        val file = File(folder.root, "added.json")
        added().apply { sawCopy("p", songs("a"), 100); record("p", listOf("b"), 200) }
        val kept = file.readText()
        // The file is there and can't be read (something else has it; the disk gave an error).
        assertTrue(file.setReadable(false))
        org.junit.Assume.assumeFalse("whoever runs this may read anything", runCatching { file.readBytes() }.isSuccess)
        val notes = ArrayList<String>()
        val added = Added(file, null, Dispatchers.IO, note = { notes += it })
        // This run has no dates, says so, and writes nothing: not what it notices, not what
        // the person adds, not what another device shares (which is refused, to be offered
        // again, rather than written over the notes that are there).
        assertEquals(listOf(0L), added.dates("p", songs("b")).list())
        assertTrue(notes.any { "couldn't be read" in it })
        added.sawCopy("p", songs("a", "b", "c"), 300)
        added.record("p", listOf("d"), 400)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { added.take(mapOf("p" to mapOf("e" to AddedNote(listOf(5), 5)))) }
        assertTrue(file.setReadable(true))
        assertEquals(kept, file.readText())
        // The next start reads them as they were.
        assertEquals(listOf(200L), added().dates("p", songs("b")).list())
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
