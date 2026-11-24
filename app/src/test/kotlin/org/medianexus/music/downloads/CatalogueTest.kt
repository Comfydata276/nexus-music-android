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
        again.sweep(emptySet())
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
}
