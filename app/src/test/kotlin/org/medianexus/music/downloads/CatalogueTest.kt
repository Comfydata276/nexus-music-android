package org.medianexus.music.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

/** The catalogue: the mark that a download is whole, as it survives the app being stopped
 *  at any moment. */
class CatalogueTest {
    @get:Rule val folder = TemporaryFolder()

    private fun entry(dir: File, id: String): Downloaded {
        File(dir, "$id.flac").writeBytes(ByteArray(3))
        return Downloaded(Song(id = id, title = id.uppercase()), "$id.flac", 3, at = 1, suffix = "flac")
    }

    @Test
    fun marksAndUnmarksSurviveARestartInOrder() {
        val dir = folder.newFolder()
        val first = Catalogue(dir)
        first.add(entry(dir, "a"))
        first.add(entry(dir, "b"))
        first.remove(listOf("a"))
        first.add(entry(dir, "c"))
        val again = Catalogue(dir)
        assertEquals(listOf("b", "c"), again.state.value.songs.keys.toList())
        assertFalse(File(dir, "a.flac").exists())
    }

    @Test
    fun aMarkCutShortIsNoMark() {
        val dir = folder.newFolder()
        Catalogue(dir).add(entry(dir, "a"))
        // The app stopped part-way through writing the next song's mark: its file is there,
        // its line isn't whole.
        entry(dir, "b")
        File(dir, Catalogue.LOG_FILE).appendText("""{"add":{"song":{"id":"b","tit""")
        val again = Catalogue(dir)
        assertTrue(again.has("a"))
        assertFalse(again.has("b"))
        // And the file nobody marked is cleared away.
        again.sweep(emptySet(), began = Long.MAX_VALUE)
        assertFalse(File(dir, "b.flac").exists())
        assertTrue(File(dir, "a.flac").exists())
    }

    @Test
    fun theLogIsFoldedIntoTheListNowAndThen() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir)
        for (i in 0 until 450) catalogue.add(entry(dir, "s$i"))
        // Written whole at least once, with the log begun afresh after it.
        assertTrue(File(dir, Catalogue.SONGS_FILE).isFile)
        assertTrue(File(dir, Catalogue.LOG_FILE).readLines().size < 450)
        assertEquals(450, Catalogue(dir).state.value.songs.size)
    }

    @Test
    fun aFileThatHasGoneIsNotADownload() {
        val dir = folder.newFolder()
        Catalogue(dir).add(entry(dir, "a"))
        File(dir, "a.flac").delete()
        assertFalse(Catalogue(dir).has("a"))
    }

    // ---- Surviving being interrupted (offline review 2) -------------------------------------

    private val notes = mutableListOf<String>()

    @Test
    fun aMarkWrittenAfterALineCutShortIsNotLostWithIt() {
        val dir = folder.newFolder()
        Catalogue(dir).add(entry(dir, "a"))
        // The app was killed part-way through writing b's mark.
        File(dir, Catalogue.LOG_FILE).appendText("""{"add":{"song":{"id":"b","tit""")
        // Started again, c is downloaded and marked.
        val second = Catalogue(dir, notes::add)
        second.add(entry(dir, "c"))
        // Started once more: c is still a download (its line was not written onto the end
        // of b's fragment, to be skipped with it), and its file is not swept as nobody's.
        val third = Catalogue(dir)
        assertTrue(third.has("a"))
        assertTrue("the mark after the torn one was lost", third.has("c"))
        third.sweep(emptySet(), began = Long.MAX_VALUE)
        assertTrue(File(dir, "c.flac").exists())
        // The log is whole lines and nothing else, and the repair was said.
        assertTrue(File(dir, Catalogue.LOG_FILE).readText().let { it.endsWith("\n") && it.lines().filter { l -> l.isNotEmpty() }.all { l -> l.endsWith("}") } })
        assertTrue(notes.any { "cut short" in it })
    }

    @Test
    fun aFragmentLeftByAWriteThatFailedIsCutOffBeforeTheNextMark() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir)
        catalogue.add(entry(dir, "a"))
        // A write failed part-way in this run (the disk was full), and left this behind.
        File(dir, Catalogue.LOG_FILE).appendText("""{"add":{"song":{"id":"b""")
        catalogue.add(entry(dir, "c"))
        assertEquals(listOf("a", "c"), Catalogue(dir).state.value.songs.keys.toList())
    }

    @Test
    fun theSweepLeavesWhatIsMarkedWhatIsWantedAndWhatIsNew() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir)
        catalogue.add(entry(dir, "kept"))
        val began = 2_000_000_000_000L
        fun stray(name: String, at: Long) = File(dir, name).also { it.writeBytes(ByteArray(5)); it.setLastModified(at) }
        val old = stray("old.flac", began - 60_000)
        val oldPart = stray(SongFetch.partName("gone.flac"), began - 60_000)
        // Made since this run began: this run's own work, whatever the queue says.
        val fresh = stray("fresh.flac", began)
        // Of a song the queue still wants: its part, the part's record, and a file at its name.
        val part = stray(SongFetch.partName("busy.flac"), began - 60_000)
        val meta = stray(SongFetch.metaName("busy.flac"), began - 60_000)
        val unmarked = stray("busy.flac", began - 60_000)
        File(dir, "kept.flac").setLastModified(began - 60_000)
        catalogue.sweep(setOf("busy"), began)
        assertFalse(old.exists())
        assertFalse(oldPart.exists())
        assertTrue(fresh.exists())
        assertTrue(part.exists() && meta.exists() && unmarked.exists())
        assertTrue(File(dir, "kept.flac").exists())
        assertTrue(File(dir, Catalogue.LOG_FILE).exists())
    }

    @Test
    fun ifTheListCantBeWrittenWholeThatIsSaidAndItIsTriedAgain() {
        val dir = folder.newFolder()
        // Something is in the way of the list (a folder where the file should go).
        val list = File(dir, Catalogue.SONGS_FILE)
        File(list, "in-the-way").apply { parentFile.mkdirs(); writeText("x") }
        val catalogue = Catalogue(dir, notes::add, compactAfter = 4)
        notes.clear()
        for (i in 0 until 10) catalogue.add(entry(dir, "s$i"))
        // Said, once, not every time it was tried.
        assertEquals(1, notes.count { "couldn't be written whole" in it })
        // Nothing is lost meanwhile: the log has it all.
        assertEquals(10, Catalogue(dir).state.value.songs.size)
        // The way is cleared: at a later change the list is written, and that is said too.
        list.deleteRecursively()
        for (i in 10 until 16) catalogue.add(entry(dir, "s$i"))
        assertTrue(list.isFile)
        assertTrue(notes.any { "written whole again" in it })
        assertEquals(16, Catalogue(dir).state.value.songs.size)
    }

    @Test
    fun aStopAtAnyStepOfWritingTheListWholeLosesNothing() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir, compactAfter = 3)
        catalogue.add(entry(dir, "a"))
        catalogue.add(entry(dir, "b"))
        val logBefore = File(dir, Catalogue.LOG_FILE).readText()
        catalogue.add(entry(dir, "c"))
        // Written whole: the list has the three, the log is empty.
        assertEquals(0L, File(dir, Catalogue.LOG_FILE).length())
        catalogue.add(entry(dir, "d"))
        catalogue.add(entry(dir, "e"))
        catalogue.add(entry(dir, "f"))
        // Written whole a second time: the list before it is kept beside it.
        assertTrue(File(dir, Catalogue.BACKUP_FILE).isFile)
        catalogue.add(entry(dir, "g"))
        val all = setOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(all, Catalogue(dir).state.value.ids)

        // Stopped after the new list was in place and before the log was emptied: the log
        // still holds changes the list already has. Read again, they come to the same.
        val copy = folder.newFolder()
        dir.copyRecursively(copy, overwrite = true)
        File(copy, Catalogue.LOG_FILE).writeText(logBefore + File(copy, Catalogue.LOG_FILE).readText())
        assertEquals(all, Catalogue(copy).state.value.ids)

        // The list unreadable (or, the same to the reader, not there: stopped between the old
        // list being moved aside and the new one put in place): the one before it stands in,
        // and says so. What it lacks is what was marked between the two.
        File(dir, Catalogue.SONGS_FILE).writeText("{ not a list")
        val fallen = Catalogue(dir, notes::add)
        assertEquals(setOf("a", "b", "c", "g"), fallen.state.value.ids)
        assertTrue(notes.any { "couldn't be read" in it })
    }

    @Test
    fun aDownloadIsNotRemovedUnlessItsUnmarkingReachesTheDisk() {
        val dir = folder.newFolder()
        val catalogue = Catalogue(dir, notes::add, compactAfter = 1)
        catalogue.add(entry(dir, "a"))
        // The log can't be written (a folder is where it should be).
        File(dir, Catalogue.LOG_FILE).let { it.delete(); File(it, "in-the-way").apply { parentFile.mkdirs(); writeText("x") } }
        assertTrue(catalogue.remove(listOf("a")).isEmpty())
        // Still a download, file and all: not a file deleted that the list calls a download.
        assertTrue(catalogue.has("a"))
        assertTrue(File(dir, "a.flac").exists())
        assertTrue(notes.any { "couldn't be removed" in it })
    }

    // ---- Withdrawing a wish, and "Remove all" (offline hardening 3, item 4) ----------------------

    @Test
    fun aListIsForgottenOnlyOnceThatIsOnTheDisk() {
        val notes = mutableListOf<String>()
        val catalogue = Catalogue(folder.root, note = { notes += it })
        catalogue.keep(DownloadedList("album:a", "A", songs = listOf("s1")))
        catalogue.single(listOf("s9"))
        folder.root.setWritable(false)
        try {
            // It can't be written: it is still wanted, here and for an app started again.
            assertFalse(catalogue.forget("album:a"))
            assertFalse(catalogue.unsingle(listOf("s9")))
            assertEquals("album:a", catalogue.collection("album:a")?.key)
            assertEquals(setOf("s9"), catalogue.state.value.singles)
            assertEquals(2, notes.count { "nothing was removed" in it })
        } finally {
            folder.root.setWritable(true)
        }
        assertEquals(listOf("album:a"), Catalogue(folder.root).state.value.collections.map { it.key })
        // Written, it is gone, and stays gone. Forgetting what isn't wanted is no failure.
        assertTrue(catalogue.forget("album:a"))
        assertTrue(catalogue.unsingle(listOf("s9")))
        assertTrue(catalogue.forget("album:never"))
        assertTrue(Catalogue(folder.root).state.value.let { it.collections.isEmpty() && it.singles.isEmpty() })
    }

    @Test
    fun removeAllSaysWhetherEverythingWent() {
        val notes = mutableListOf<String>()
        val catalogue = Catalogue(folder.root, note = { notes += it })
        File(folder.root, "s1.flac").writeBytes(ByteArray(10))
        catalogue.add(Downloaded(Song(id = "s1", title = "One"), "s1.flac", 10, 1, "flac"))
        File(folder.root, ".s2.flac.part").writeBytes(ByteArray(5))
        // A cover kept with the downloads, in a folder nothing can be taken out of.
        val covers = File(folder.root, "covers").apply { mkdirs() }
        File(covers, "c1.jpg").writeBytes(ByteArray(7))
        covers.setWritable(false)
        try {
            // The downloads are unmarked and their files go; the cover would not: it says
            // no. (It said yes whatever became of each file.)
            assertFalse(catalogue.clear())
            assertTrue(catalogue.state.value.ids.isEmpty())
            assertFalse(File(folder.root, "s1.flac").exists())
            assertEquals(1, notes.count { "could not be removed" in it })
        } finally {
            covers.setWritable(true)
        }
        // Asked again with nothing in the way: everything has gone.
        assertTrue(catalogue.clear())
        assertTrue(folder.root.listFiles()!!.all { it.isFile && it.name in setOf(Catalogue.SONGS_FILE, Catalogue.LOG_FILE) })
    }
}
