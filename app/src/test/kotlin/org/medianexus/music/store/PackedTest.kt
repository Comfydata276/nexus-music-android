package org.medianexus.music.store

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

/** The packed form's writer never writes what its reader would refuse. */
class PackedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun table(vararg songs: Song) = SongTable.Builder().apply { songs.forEach { add(it) } }.build()

    private fun roundTrip(table: SongTable): SongTable {
        val out = Packer().also { table.write(it) }
        return SongTable.read(Unpacker(out.bytes, 0, out.size))
    }

    @Test
    fun textAsLongAsTheReaderTakesIsWrittenAndReadBack() {
        val longest = "x".repeat(MOST_TEXT)
        assertEquals(longest, roundTrip(table(Song(id = "a", title = longest))).title(0))
    }

    @Test
    fun textLongerThanTheReaderTakesIsRefusedWhenWritten() {
        // A mebibyte and one byte: the reader would say the file was damaged, and the whole
        // copy would be fetched again at every start.
        try {
            roundTrip(table(Song(id = "a", title = "é".repeat(MOST_TEXT / 2) + "x")))
            fail("written")
        } catch (e: IOException) {
            assertFalse("refused by the reader rather than the writer: ${e.message}", e.message.orEmpty().startsWith("damaged"))
        }
    }

    @Test
    fun aCountTheReaderWouldRefuseIsRefusedWhenWritten() {
        val out = Packer()
        out.count(MOST_COUNT)
        try {
            out.count(MOST_COUNT + 1)
            fail("written")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aFileThatWouldNotReadBackIsNotLeftBehind() {
        val file = File(folder.root, "copy.bin")
        try {
            writeAtomically(file) { raw -> Packer(1024, raw).text("y".repeat(MOST_TEXT + 1)) }
            fail("written")
        } catch (_: IOException) {
        }
        assertFalse(file.exists())
        assertEquals(0, folder.root.listFiles()!!.size)
    }
}
