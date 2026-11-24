package org.medianexus.music.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

class SavedQueueTest {
    @get:Rule val folder = TemporaryFolder()

    private val songs = listOf(Song(id = "a", title = "One"), Song(id = "b", title = "Two", queued = true))

    @Test
    fun theShapeIsWorkedOutAsWindowsDoes() {
        // The same figure Windows' queue_shape gives (FNV-1a, each piece followed by 0xff),
        // worked out apart from this code.
        val shape = queueShape(listOf("a", "b"), listOf(1, 0), "Ctx", "album:1", listOf(0, null), listOf("a", "b"))
        assertEquals(13878140399323678609uL, shape)
    }

    @Test
    fun isWrittenInWindowsFormAndReadBack() {
        val files = QueueFiles(File(folder.root, "queue.json"), File(folder.root, "queue-place.json"))
        val saved = SavedQueue(songs, listOf(1, 0), 1, 42.5, "Ctx", "album:1", listOf(0, null), 1700000000, listOf("a", "b"))
        files.save(saved)
        val text = File(folder.root, "queue.json").readText()
        // Windows' field names, and its "queued" left out when false.
        assertTrue(text, text.contains("\"source_list\""))
        assertTrue(text, text.contains("\"queued\":true"))
        assertEquals(1, Regex("\"queued\"").findAll(text).count())
        assertEquals(saved, QueueFiles(File(folder.root, "queue.json"), File(folder.root, "queue-place.json")).load())
    }

    @Test
    fun wherePlaybackHasGotToIsSavedApartAndOnlyForTheSameQueue() {
        val queueFile = File(folder.root, "queue.json")
        val files = QueueFiles(queueFile, File(folder.root, "queue-place.json"))
        val saved = SavedQueue(songs, listOf(0, 1), 0, 0.0, "Ctx")
        files.save(saved)
        val written = queueFile.lastModified()
        queueFile.setLastModified(written - 10_000)
        // Only the place moves on: the queue itself isn't written again.
        files.save(saved.copy(cursor = 1, position = 30.0))
        assertEquals(written - 10_000, queueFile.lastModified())
        val back = QueueFiles(queueFile, File(folder.root, "queue-place.json")).load()!!
        assertEquals(1, back.cursor)
        assertEquals(30.0, back.position, 0.0)
        // A place saved for some other queue is ignored.
        File(folder.root, "queue-place.json").writeText("""{"shape":1,"cursor":1,"position":9.0}""")
        assertEquals(0, QueueFiles(queueFile, File(folder.root, "queue-place.json")).load()!!.cursor)
    }

    @Test
    fun aQueuePlayedAgainWithAnotherFindTextSavesIt() {
        val queueFile = File(folder.root, "queue.json")
        val saved = SavedQueue(songs, listOf(0, 1), 0, 0.0, "Mix", "playlist:p", listOf(0, 1), sourceList = listOf("a", "b"), listFilter = "a")
        QueueFiles(queueFile, File(folder.root, "queue-place.json")).save(saved)
        // Read back (the app started again), then played again from the same list with another
        // find text that happens to show the same rows: the same shape, another filter.
        val files = QueueFiles(queueFile, File(folder.root, "queue-place.json"))
        files.load()
        files.save(saved.copy(listFilter = "b"))
        // It used to keep "a": the queue went on following the list narrowed by the old text.
        assertEquals("b", QueueFiles(queueFile, File(folder.root, "queue-place.json")).load()!!.listFilter)
        // And on the same file again, without reading it back first.
        files.save(saved.copy(listFilter = ""))
        assertEquals("", QueueFiles(queueFile, File(folder.root, "queue-place.json")).load()!!.listFilter)
    }

    @Test
    fun aQueueThatDoesNotHoldTogetherIsNotPutBack() {
        val queueFile = File(folder.root, "queue.json")
        queueFile.writeText("""{"songs":[{"id":"a"}],"order":[0,1],"cursor":0}""")
        assertNull(QueueFiles(queueFile, File(folder.root, "place.json")).load())
        queueFile.writeText("not json")
        assertNull(QueueFiles(queueFile, File(folder.root, "place.json")).load())
    }

    @Test
    fun aPlayOrderThatIsNotEverySongOnceIsDamaged() {
        val queueFile = File(folder.root, "queue.json")
        fun load() = QueueFiles(queueFile, File(folder.root, "place.json")).load()
        // The right length and every place in range, but one song twice and one not at all.
        queueFile.writeText("""{"songs":[{"id":"a"},{"id":"b"},{"id":"c"}],"order":[0,0,2],"cursor":1}""")
        assertNull(load())
        queueFile.writeText("""{"songs":[{"id":"a"},{"id":"b"}],"order":[1,-1],"cursor":0}""")
        assertNull(load())
        // Any true reordering is fine.
        queueFile.writeText("""{"songs":[{"id":"a"},{"id":"b"},{"id":"c"}],"order":[2,0,1],"cursor":1}""")
        assertEquals(listOf(2, 0, 1), load()!!.order)
    }

    @Test
    fun anEmptyQueueClearsWhatWasSaved() {
        val queueFile = File(folder.root, "queue.json")
        val files = QueueFiles(queueFile, File(folder.root, "queue-place.json"))
        files.save(SavedQueue(songs, listOf(0, 1)))
        files.save(SavedQueue())
        assertNull(files.load())
    }
}
