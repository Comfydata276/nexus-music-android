package org.medianexus.music.playback

import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

class QueueSaverTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saver = QueueSaver(scope, Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    private fun files(dir: File = folder.root) = QueueFiles(File(dir, "queue.json"), File(dir, "queue-place.json"))

    private fun queue(vararg ids: String, position: Double = 0.0) =
        SavedQueue(ids.map { Song(id = it) }, ids.indices.toList(), 0, position, "Ctx")

    @Test
    fun theLastSnapshotAlwaysWins() = runBlocking {
        // Four threads saving as fast as they can, as the writer and the main thread once
        // both did; then one last save, as when the service is going. Whatever the writer
        // was in the middle of, the last one asked for is what is on disk.
        val files = files()
        List(4) { n ->
            thread {
                for (i in 1..200) saver.save(files, queue("t$n", "x", position = i.toDouble()))
            }
        }.forEach { it.join() }
        val last = queue("the", "last", "one", position = 42.0)
        saver.save(files, last)
        withTimeout(10_000) { saver.flush() }
        assertEquals(last, files().load())
        // And nothing half-written was left beside it.
        assertEquals(setOf("queue.json", "queue-place.json"), folder.root.list()!!.toSet())
    }

    @Test
    fun aQueueIsReadAfterEverythingAskedToBeWrittenBeforeIt() = runBlocking {
        val before = files()
        saver.save(before, queue("a", "b"))
        // A new sign-in to the same account, straight after: it reads what the last one left,
        // not the file as it was before that was written.
        val saved = withTimeout(10_000) { saver.load(files()) }
        assertEquals(listOf("a", "b"), saved!!.songs.map { it.id })
    }

    @Test
    fun twoAccountsQueuesDoNotStandInForEachOther() = runBlocking {
        val one = files(folder.newFolder("one"))
        val two = files(folder.newFolder("two"))
        saver.save(one, queue("mine"))
        saver.save(two, queue("yours"))
        saver.save(one, queue("mine", "again"))
        withTimeout(10_000) { saver.flush() }
        assertEquals(listOf("mine", "again"), one.load()!!.songs.map { it.id })
        assertEquals(listOf("yours"), two.load()!!.songs.map { it.id })
    }

    @Test
    fun nothingIsSavedUntilTheSavedQueueHasBeenRead() = runBlocking {
        val files = files()
        files.save(queue("kept", "from", "last", "time"))
        val kept = KeptQueue(files(), saver)
        // The account is taken on and goes again (or the service is destroyed) before its
        // queue has been read: the player is empty at that moment, and "empty" used to be
        // written over the queue.
        var asked = false
        assertFalse(kept.save { asked = true; SavedQueue() })
        assertFalse("no snapshot is even taken", asked)
        withTimeout(10_000) { saver.flush() }
        assertEquals(4, files().load()!!.songs.size)
        // Once it has been read, the queue is the player's to save, empty or not.
        assertEquals(4, withTimeout(10_000) { kept.restore() }!!.songs.size)
        assertTrue(kept.save { queue("new") })
        withTimeout(10_000) { saver.flush() }
        assertEquals(listOf("new"), files().load()!!.songs.map { it.id })
        assertTrue(kept.save { SavedQueue() })
        withTimeout(10_000) { saver.flush() }
        assertNull(files().load())
    }

    @Test
    fun readingNothingIsStillHavingRead() = runBlocking {
        val kept = KeptQueue(files(), saver)
        assertFalse(kept.restored)
        assertNull(withTimeout(10_000) { kept.restore() })
        assertTrue(kept.restored)
        assertTrue(kept.save { queue("first") })
        withTimeout(10_000) { saver.flush() }
        assertNotNull(files().load())
    }
}
