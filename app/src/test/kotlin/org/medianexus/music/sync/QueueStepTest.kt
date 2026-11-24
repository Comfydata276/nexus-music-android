package org.medianexus.music.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.medianexus.music.net.PlayQueue
import org.medianexus.music.net.Song

/**
 * The play queue shared through the server's saved queue: when the phone saves its own, and
 * when it takes up, or offers, another device's. The server here is two functions and a
 * list of what was saved.
 */
class QueueStepTest {
    /** No test here may wait for ever (see InStepTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private val me = "NexusMusic-feedf00d"
    private val pc = "NexusMusic-0badc0de"

    /** The server's saved queue, and every save made to it, in the order it took them. */
    private inner class Server {
        var queue = PlayQueue()
        val saves = ArrayList<QueueUpload>()
        var asked = 0

        /** Held as a save, or a read, goes through. */
        var onSave: suspend (QueueUpload) -> Unit = {}
        var onRead: suspend () -> Unit = {}

        fun savedBy(device: String, at: String, vararg ids: String, current: String = ids[0], position: Long = 0) {
            queue = PlayQueue(ids.map { Song(id = it, title = "T$it", artist = "Someone", duration = 200) }, current, position, at, device)
        }

        suspend fun save(device: String, upload: QueueUpload) {
            onSave(upload)
            saves += upload
            // (The server puts its own clock on it: "now", whenever that is.)
            queue = PlayQueue(upload.ids.map { Song(id = it) }, upload.current, upload.positionMs, "1970-01-02T00:10:00Z", device)
        }

        suspend fun saved(): PlayQueue {
            asked++
            onRead()
            return queue
        }
    }

    private var on = true

    private fun step(server: Server, player: PretendPlayer?, now: Long = DAY + 700) =
        QueueStep(save = server::save, saved = server::saved, device = { me }, may = { on }, scope = scope, clock = { now }, pause = {}, onMain = Dispatchers.Unconfined).also { it.here = player }

    // ---- saving: only if it is still the newest thing that happened --------------------------------

    @Test
    fun aQueueAnotherDeviceSavedSinceIsNotSavedOver() = runBlocking {
        val server = Server()
        // The person changed the queue here at 00:05:00 (paused: nothing else moves it)...
        val player = PretendPlayer(stamp = DAY + 300)
        val step = step(server, player)
        // ...and in the three seconds before its save goes, a PC saved its own, at 00:05:02.
        server.savedBy(pc, "1970-01-02T00:05:02Z", "a", "b")
        step.changedHere()
        assertTrue("the PC's queue stands", server.saves.isEmpty())
        assertEquals(pc, server.queue.changedBy)
        // One the PC saved before the change here is older than it: the phone's is saved.
        server.savedBy(pc, "1970-01-02T00:04:59Z", "a", "b")
        step.changedHere()
        assertEquals(listOf(player.queue), server.saves)
        // And its own, read back, is no reason not to save again.
        player.stamp = DAY + 310
        step.changedHere()
        assertEquals(2, server.saves.size)
        // Nothing saved on the server at all: saved.
        server.queue = PlayQueue()
        step.changedHere()
        assertEquals(3, server.saves.size)
    }

    @Test
    fun savesGoOneAtATimeInTheOrderAsked() = runBlocking {
        val server = Server()
        val player = PretendPlayer(stamp = DAY + 300)
        val step = step(server, player)
        // The first save is on its way (held) when the queue changes again.
        val held = CompletableDeferred<Unit>()
        val going = CompletableDeferred<Unit>()
        server.onSave = { going.complete(Unit); held.await() }
        step.changedHere()
        going.await()
        val first = player.queue!!
        val second = QueueUpload(listOf("other"), "other", 0)
        player.queue = second
        player.stamp = DAY + 301
        server.onSave = {}
        step.changedHere()
        yield()
        // The second waits its turn: nothing of it has reached the server, not even its question.
        assertTrue(server.saves.isEmpty())
        assertEquals(1, server.asked)
        held.complete(Unit)
        // In order: the newer save is the one the server ends with.
        assertEquals(listOf(first, second), server.saves)
        assertEquals("other", server.queue.current)
    }

    @Test
    fun aQueueTakenUpFromAnotherDeviceIsNotSavedAsThePhonesOwn() = runBlocking {
        val server = Server()
        val player = PretendPlayer(stamp = DAY + 100)
        // A save has been asked for, and is waiting out its three seconds...
        val waits = ArrayList<CompletableDeferred<Unit>>()
        val step = QueueStep(save = server::save, saved = server::saved, device = { me }, may = { on }, scope = scope, clock = { DAY + 700 },
            pause = { CompletableDeferred<Unit>().also { waits += it }.await() }, onMain = Dispatchers.Unconfined).also { it.here = player }
        step.changedHere()
        // ...when the PC's queue, saved later than anything done here, is taken up.
        server.savedBy(pc, "1970-01-02T00:10:00Z", "a", "b", "c", current = "b", position = 30_000)
        step.check()
        assertEquals(listOf("[a, b, c] at 1, 30000 ms, paused"), player.replaced)
        // The three seconds are up. What is in the player is the PC's queue, untouched: not saved.
        waits.forEach { it.complete(Unit) }
        yield()
        assertTrue(server.saves.isEmpty())
        assertEquals(pc, server.queue.changedBy)
        // Played on here, it is this phone's to save.
        player.stamp = DAY + 660
        step.progressed()
        waits.forEach { it.complete(Unit) }
        yield()
        assertEquals(1, server.saves.size)
    }

    // ---- taking up, and offering -----------------------------------------------------------------

    @Test
    fun theSavedQueueIsTakenUpOfferedOrLeft() = runBlocking {
        val server = Server()
        server.savedBy(pc, "1970-01-02T00:10:00Z", "a", "b", "c", current = "b", position = 30_000)
        // Not playing, and nothing done here since: taken up, paused, and nothing offered.
        val idle = PretendPlayer(stamp = DAY + 100)
        val idleStep = step(server, idle)
        idleStep.check()
        assertEquals(listOf("[a, b, c] at 1, 30000 ms, paused"), idle.replaced)
        assertNull(idleStep.elsewhere.value)
        // Playing something of its own: left alone, and offered.
        val busy = PretendPlayer(playing = true, stamp = DAY + 100)
        val busyStep = step(server, busy)
        busyStep.check()
        assertTrue(busy.replaced.isEmpty())
        assertEquals("Tb", busyStep.elsewhere.value?.title)
        // No player at all (the playback service isn't up): noted, for when there is one.
        val none = step(server, null)
        none.check()
        assertEquals("Tb", none.elsewhere.value?.title)
    }

    private companion object {
        /** 1970-01-02T00:00:00Z. */
        const val DAY = 86_400L
    }
}
