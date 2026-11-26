package org.medianexus.music.playback

import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
    fun nothingIsSavedUntilTheSavedQueueIsInThePlayer() = runBlocking {
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
        // It has been read from the disk. The service is now making it ready for the player
        // (off the main thread: for a long queue that takes a while), and the player is
        // still empty. Signing out, or the service being destroyed, in this gap used to save
        // that emptiness: "read" was taken for "put back".
        assertEquals(4, withTimeout(10_000) { kept.read() }!!.songs.size)
        assertFalse(kept.settled)
        assertFalse("saved while the queue was still on its way into the player", kept.save { asked = true; SavedQueue() })
        assertFalse(kept.save(revision = 0, cursor = 0, position = 0.0, stamp = 0) { asked = true; SavedQueue() })
        assertFalse(asked)
        withTimeout(10_000) { saver.flush() }
        assertEquals("the saved queue was erased", 4, files().load()?.songs?.size)
        // Once it is in the player, the queue is the player's to save, empty or not.
        kept.settle()
        assertTrue(kept.save { queue("new") })
        withTimeout(10_000) { saver.flush() }
        assertEquals(listOf("new"), files().load()!!.songs.map { it.id })
        assertTrue(kept.save { SavedQueue() })
        withTimeout(10_000) { saver.flush() }
        assertNull(files().load())
    }

    /** What the playback service holds for the account's saved queue, each time one is made. */
    private fun serviceLife(account: QueueFiles) = KeptQueue(account, saver)

    @Test
    fun aServiceMadeAgainInTheSameProcessStartsWithNothingPutBack() = runBlocking {
        // The account's files stay for as long as it is signed in; the service comes and goes.
        val account = files()
        val first = serviceLife(account)
        assertNull(withTimeout(10_000) { first.read() })
        first.settle()
        assertTrue(first.save(revision = 1, cursor = 1, position = 30.0, stamp = 5) { queue("a", "b", "c").copy(cursor = 1, position = 30.0) })
        // The service is destroyed and made again (paused and let go; then a headset button).
        // Its player is empty again and its queue numbered afresh, while it reads the saved
        // queue. Whether a queue "has been put back" used to be remembered with the account,
        // so this second service saved at once: its emptiness, or its place beside the old queue.
        val second = serviceLife(account)
        assertFalse(second.settled)
        assertFalse(second.save { SavedQueue() })
        assertFalse(second.save(revision = 1, cursor = 0, position = 0.0, stamp = 6) { SavedQueue() })
        val back = withTimeout(10_000) { second.read() }
        assertEquals(listOf("a", "b", "c"), back!!.songs.map { it.id })
        assertEquals(1, back.cursor)
        assertEquals(30.0, back.position, 0.0)
    }

    /** Stops anything being written in the folder (as a full disk would), until given back. */
    private fun unwritable(dir: File): Boolean {
        dir.setWritable(false)
        val really = !runCatching { File(dir, "probe").apply { writeText("x") }.delete() }.getOrDefault(false)
        if (!really) dir.setWritable(true)
        return really
    }

    @Test
    fun aQueueThatCouldNotBeWrittenIsNeverGivenTheNewQueuesPlace() = runBlocking {
        val dir = folder.newFolder("account")
        files(dir).save(queue("old", "queue", "here").copy(cursor = 0, position = 1.0))
        val kept = KeptQueue(files(dir), saver)
        withTimeout(10_000) { kept.read() }
        kept.settle()
        // Another list is started, and the disk won't take it.
        org.junit.Assume.assumeTrue("this user can write anywhere", unwritable(dir))
        val next = queue("a", "new", "list", "of", "five")
        assertTrue(kept.save(revision = 7, cursor = 0, position = 0.0, stamp = 10) { next })
        withTimeout(10_000) { saver.flush() }
        dir.setWritable(true)
        // What is on disk is still the old queue, whole, at its own place.
        files(dir).load()!!.let {
            assertEquals(listOf("old", "queue", "here"), it.songs.map { s -> s.id })
            assertEquals(0, it.cursor)
            assertEquals(1.0, it.position, 0.0)
        }
        // Playback moves along the new list; nothing about the list changes. The failed write
        // used to be taken for written, so from here on only the place was saved: the old
        // queue, at a song and a time that belong to the new one.
        var snapshots = 0
        assertTrue(kept.save(revision = 7, cursor = 2, position = 12.5, stamp = 11) { snapshots++; next.copy(cursor = 2, position = 12.5) })
        withTimeout(10_000) { saver.flush() }
        val saved = files(dir).load()!!
        assertEquals("the new place was saved beside the old queue", listOf("a", "new", "list", "of", "five"), saved.songs.map { it.id })
        assertEquals(2, saved.cursor)
        assertEquals(12.5, saved.position, 0.0)
        assertEquals("the queue that failed to save is handed over whole again", 1, snapshots)
        // And from then on, while only the place moves, only the place is handed over.
        assertTrue(kept.save(revision = 7, cursor = 3, position = 2.0, stamp = 12) { snapshots++; next })
        withTimeout(10_000) { saver.flush() }
        assertEquals(1, snapshots)
        assertEquals(3, files(dir).load()!!.cursor)
    }

    @Test
    fun aPlaceIsOnlyEverWrittenBesideTheQueueItWasHandedOverWith() {
        val files = files()
        assertTrue(files.save(queue("one", "two", "three"), token = 5))
        assertTrue(files.savePlace(token = 5, cursor = 2, position = 9.0, stamp = 3))
        assertEquals(2, files().load()!!.cursor)
        // A place for some other hand-over (a queue that never reached the disk, or one from
        // another life of the service) is not this queue's.
        assertFalse(files.savePlace(token = 6, cursor = 1, position = 50.0, stamp = 4))
        assertFalse(files.savePlace(token = 0, cursor = 1, position = 50.0, stamp = 4))
        files().load()!!.let {
            assertEquals(2, it.cursor)
            assertEquals(9.0, it.position, 0.0)
        }
        // Nor is one for a queue that was read from the disk rather than handed over.
        val read = files()
        read.load()
        assertFalse(read.savePlace(token = 5, cursor = 0, position = 77.0, stamp = 9))
        assertEquals(9.0, files().load()!!.position, 0.0)
    }

    @Test
    fun aPlaceHandedOverBehindAQueueStillWaitingGoesWithThatQueueOrNotAtAll() = runBlocking {
        // The writer is held at a gate (not a wait), so everything below is still in line
        // when the next thing is handed over.
        val gate = java.util.concurrent.CountDownLatch(1)
        val thread = java.util.concurrent.Executors.newSingleThreadExecutor()
        thread.submit { gate.await() }
        val saver = QueueSaver(scope, thread.asCoroutineDispatcher())
        val files = files()
        files.save(queue("x", "y"), token = 0)
        val told = mutableListOf<Boolean>()
        saver.save(files, queue("a", "b", "c"), token = 1) { synchronized(told) { told += it } }
        // Its own place, a moment later: written with it.
        saver.savePlace(files, token = 1, cursor = 2, position = 4.0, stamp = 2)
        // A place for an older hand-over, arriving late: dropped, not put on the new queue.
        saver.savePlace(files, token = 99, cursor = 1, position = 8.0, stamp = 3)
        gate.countDown()
        withTimeout(10_000) { saver.flush() }
        thread.shutdown()
        val saved = files().load()!!
        assertEquals(listOf("a", "b", "c"), saved.songs.map { it.id })
        assertEquals(2, saved.cursor)
        assertEquals(4.0, saved.position, 0.0)
        assertEquals(listOf(true), synchronized(told) { told.toList() })
    }

    @Test
    fun aButtonOnlyStartsThePlayerWhenThereIsSomethingToResume() {
        // What the Play button looks at before it promises the system that music is coming.
        val password = File(folder.root, "password.sealed")
        val saved = File(folder.root, "queue.json")
        fun worth(server: String = "https://music.example", name: String = "ada") = Resumption.possible(server, name, password, saved)
        // Signed out: no password. (The address and name stay after a sign-out.)
        assertFalse(worth())
        password.writeText("sealed")
        // Signed in, and nothing was ever played.
        assertFalse(worth())
        saved.writeText("")
        assertFalse(worth())
        files().save(queue("a", "b"))
        assertTrue(worth())
        // Never signed in at all.
        assertFalse(worth(server = ""))
        assertFalse(worth(name = ""))
        password.delete()
        assertFalse(worth())
    }

    @Test
    fun readingNothingIsStillHavingRead() = runBlocking {
        val kept = KeptQueue(files(), saver)
        assertFalse(kept.settled)
        assertNull(withTimeout(10_000) { kept.read() })
        // Nothing to put back: the service says so, and the queue is the player's to save.
        kept.settle()
        assertTrue(kept.save { queue("first") })
        withTimeout(10_000) { saver.flush() }
        assertNotNull(files().load())
    }

    /**
     * **Whether the last thing asked for is on disk is that ask's own result.** A queue
     * marked and written, and then another save whose write fails: the second is not
     * taken for written because the first once put the same words there. (Linking asks
     * this of the mark of a queue handed to another device.)
     */
    @Test
    fun whetherASaveIsOnDiskIsThatSavesOwnResult() = runBlocking {
        val dir = folder.newFolder()
        val place = File(File(dir, "place"), "queue-place.json").also { it.parentFile!!.mkdirs() }
        val kept = KeptQueue(QueueFiles(File(dir, "queue.json"), place), saver)
        kept.settle()
        assertFalse("nothing was asked for yet", kept.written())
        assertTrue(kept.save(revision = 1, cursor = 0, position = 0.0, stamp = 1, taken = "handed to another device") { queue("a", "b").copy(taken = "handed to another device") })
        saver.flush()
        assertTrue(kept.written())
        // The same queue, the same words, and the place can no longer be written.
        place.parentFile!!.deleteRecursively()
        place.parentFile!!.writeText("in the way")
        assertTrue(kept.save(revision = 1, cursor = 1, position = 3.0, stamp = 2, taken = "handed to another device") { queue("a", "b") })
        saver.flush()
        assertFalse("a save that failed was taken for written because an earlier one had been", kept.written())
        // And a later one that does get there is.
        place.parentFile!!.delete()
        place.parentFile!!.mkdirs()
        assertTrue(kept.save(revision = 1, cursor = 1, position = 4.0, stamp = 3, taken = "handed to another device") { queue("a", "b") })
        saver.flush()
        assertTrue(kept.written())
    }
}
