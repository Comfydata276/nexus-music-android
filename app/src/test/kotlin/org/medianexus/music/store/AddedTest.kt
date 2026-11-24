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

/** The tests of src/added.rs, ported with it. */
class AddedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun songs(vararg ids: String) = ids.map { Song(id = it) }

    private fun added(name: String = "added.json") = Added(File(folder.root, name), null, Dispatchers.IO)

    private fun LongArray.list() = toList()

    @Test
    fun datesSongsAddedAfterTheFirstLook() {
        val added = added()
        // Songs already there on the first look have no date, and nothing to share.
        assertFalse(added.observe("p", songs("a", "b"), 100))
        assertEquals(listOf(0L, 0L), added.dates("p", songs("a", "b")).list())
        assertTrue(added.shared().isEmpty())
        // One that turns up later is dated to when it was seen; a second copy gets its own.
        assertTrue(added.observe("p", songs("a", "b", "c", "a"), 200))
        assertEquals(listOf(0L, 0L, 200L, 200L), added.dates("p", songs("a", "b", "c", "a")).list())
        // Songs the app adds itself are dated at once, and looking again changes nothing.
        added.record("p", listOf("d"), 300)
        assertFalse(added.observe("p", songs("a", "b", "c", "a", "d"), 400))
        assertEquals(listOf(300L, 200L, 0L, 0L, 200L), added.dates("p", songs("d", "c", "a", "b", "a")).list())
        // Removed songs are forgotten, so adding one again dates it afresh.
        added.observe("p", songs("a", "b"), 500)
        added.observe("p", songs("a", "b", "c"), 600)
        assertEquals(listOf(0L, 0L, 600L), added.dates("p", songs("a", "b", "c")).list())
        // It survives a restart.
        assertEquals(listOf(0L, 0L, 600L), added().dates("p", songs("a", "b", "c")).list())
        // A song added to a playlist not yet looked at keeps its date through the first look.
        added.record("q", listOf("a"), 700)
        added.observe("q", songs("x", "a", "a"), 800)
        assertEquals(listOf(0L, 0L, 700L), added.dates("q", songs("x", "a", "a")).list())
    }

    @Test
    fun notesTravelBetweenDevices() {
        val a = added("a.json")
        val b = added("b.json")
        a.observe("p", songs("x", "y"), 100)
        a.record("p", listOf("z"), 200)
        // The other device hears of the dated song before it ever opens the playlist...
        assertTrue(b.merge(a.shared()))
        assertFalse(b.merge(a.shared()))
        // ...so its first look keeps that date, and has nothing new to say.
        assertFalse(b.observe("p", songs("x", "y", "z"), 300))
        assertEquals(listOf(0L, 0L, 200L), b.dates("p", songs("x", "y", "z")).list())
        // A removal seen on one device reaches the other, and a later re-add is dated anew.
        assertTrue(b.observe("p", songs("x", "y"), 400))
        assertTrue(a.merge(b.shared()))
        assertTrue(a.observe("p", songs("x", "y", "z"), 500))
        assertEquals(listOf(0L, 0L, 500L), a.dates("p", songs("x", "y", "z")).list())
        // Notes for playlists that no longer exist aren't passed on.
        assertTrue(a.shared(setOf("other")).isEmpty())
        // Combining is the same whichever way round, and old removals drop out in time.
        val (notesA, notesB) = a.shared() to b.shared()
        assertEquals(Added.mergeNotes(notesA, notesB, 600), Added.mergeNotes(notesB, notesA, 600))
        assertTrue(Added.mergeNotes(b.shared(), emptyMap(), 400 + Added.KEEP_REMOVED + 1).isEmpty())
    }

    @Test
    fun readsTheFileFromBeforeNotesWereShared() {
        File(folder.root, "added.json").writeText("""{"p":{"a":[0],"b":[0,250]}}""")
        val added = added()
        assertEquals(listOf(0L, 0L, 250L), added.dates("p", songs("a", "b", "b")).list())
        // It counts as looked at, so a new song is dated.
        added.observe("p", songs("a", "b", "b", "c"), 300)
        assertEquals(listOf(300L), added.dates("p", songs("c")).list())
    }

    @Test
    fun writesTheFileWindowsReads() {
        val added = added()
        added.observe("p", songs("a"), 100)
        added.record("p", listOf("b"), 200)
        val text = File(folder.root, "added.json").readText()
        assertTrue(text, text.contains("\"lists\":{\"p\":{") && text.contains("\"b\":{\"times\":[200],\"at\":200}") && text.contains("\"seen\":[\"p\"]"))
        // A file that can't be read is not taken for notes, nor written over until there's a change.
        File(folder.root, "broken.json").writeText("{not json")
        assertEquals(listOf(0L), added("broken.json").dates("p", songs("a")).list())
    }
}
