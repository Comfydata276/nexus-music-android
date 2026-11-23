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
    fun aQueueThatDoesNotHoldTogetherIsNotPutBack() {
        val queueFile = File(folder.root, "queue.json")
        queueFile.writeText("""{"songs":[{"id":"a"}],"order":[0,1],"cursor":0}""")
        assertNull(QueueFiles(queueFile, File(folder.root, "place.json")).load())
        queueFile.writeText("not json")
        assertNull(QueueFiles(queueFile, File(folder.root, "place.json")).load())
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
