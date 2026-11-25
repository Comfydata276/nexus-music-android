package org.medianexus.music.sync

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.medianexus.music.core.QueueSaved
import org.medianexus.music.net.PlayQueue
import org.medianexus.music.net.Song
import org.medianexus.music.net.Timed
import org.medianexus.music.playback.Choices

/**
 * The play queue shared through the server's saved queue: when the phone saves its own, and
 * when it takes up, or offers, another device's. The server here is two functions and a
 * list of what was saved.
 */
class QueueStepTest {
    /** No test here may wait for ever (see InStepContractTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private val me = "NexusMusic-feedf00d"
    private val pc = "NexusMusic-0badc0de"

    /** The server's saved queue, and every save made to it, in the order it took them. */
    private inner class Server {
        var queue = PlayQueue()
        val saves = ArrayList<QueueSaved>()
        var asked = 0

        /** The server's own clock, as its answers give it. */
        var time: Long? = DAY + 700

        /** Held as a save, or a read, goes through. */
        var onSave: suspend (QueueSaved) -> Unit = {}
        var onRead: suspend () -> Unit = {}

        fun savedBy(device: String, at: String, vararg ids: String, current: String = ids[0], position: Long = 0) {
            queue = PlayQueue(ids.map { Song(id = it, title = "T$it", artist = "Someone", duration = 200) }, current, position, at, device)
        }

        suspend fun save(device: String, upload: QueueSaved) {
            onSave(upload)
            saves += upload
            // (The server puts its own clock on it: "now", whenever that is.)
            queue = PlayQueue(upload.ids.map { Song(id = it) }, upload.current, upload.position, "1970-01-02T00:10:00Z", device)
        }

        suspend fun saved(): Timed<PlayQueue> {
            asked++
            onRead()
            return Timed(queue, time)
        }
    }

    /** The setting; and whether the server can be reached. */
    private var on = true
    private var reached = true

    /** The main thread: things sent to it are run at once, or wait while it is `busy`. */
    private class Main : CoroutineDispatcher() {
        var busy = false
        private val waiting = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = busy
        override fun dispatch(context: CoroutineContext, block: Runnable) { waiting += block }
        fun free() {
            busy = false
            while (waiting.isNotEmpty()) waiting.removeFirst().run()
        }
    }

    private val main = Main()

    private fun step(server: Server, player: PretendPlayer?, now: Long = DAY + 700) =
        QueueStep(save = server::save, saved = server::saved, deviceId = { "feedf00d" }, may = { on && reached }, on = { on }, scope = scope, clock = { now }, pause = {}, onMain = main).also { it.here = player }

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
        assertEquals(listOf(QueueSaved(listOf("mine", "next"), "mine", 5_000)), server.saves)
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
        val first = QueueSaved(listOf("mine", "next"), "mine", 5_000)
        val second = QueueSaved(listOf("other"), "other", 0)
        player.songs = listOf("other")
        player.current = "other"
        player.positionSeconds = 0.0
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
        val step = QueueStep(save = server::save, saved = server::saved, deviceId = { "feedf00d" }, may = { on }, scope = scope, clock = { DAY + 700 },
            pause = { CompletableDeferred<Unit>().also { waits += it }.await() }, onMain = main).also { it.here = player }
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
        // And it is still the PC's after a restart: what says so is kept with the queue.
        assertEquals("1970-01-02T00:10:00Z", player.taken)
        // Played on here, it is this phone's to save.
        player.stamp = DAY + 660
        player.taken = null
        player.playing = true
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
        // No player at all (the playback service isn't up): there is nothing to take the
        // queue up or to offer it to. It is looked at again when there is one.
        val none = step(server, null)
        none.check()
        assertNull(none.elsewhere.value)
    }

    // ---- "Continue here" is a choice like any other ---------------------------------------------------

    @Test
    fun continueHereTakesItsPlaceInTheOrderOfChoicesAtTheTap() = runBlocking {
        val server = Server()
        server.savedBy(pc, "1970-01-02T00:10:00Z", "a", "b", "c", current = "b", position = 30_000)
        val player = PretendPlayer(playing = true, stamp = DAY + 100)
        val step = step(server, player)
        step.check()
        assertEquals("Tb", step.elsewhere.value?.title)
        // Tapped, with the main thread busy: the queue is not yet with the player...
        main.busy = true
        val before = Choices.latest
        step.continueHere()
        assertTrue(player.replaced.isEmpty())
        // ...but its place is taken: whatever the person does next (a pause, here) is later.
        assertEquals(before + 1, Choices.latest)
        val pause = Choices.take()
        main.free()
        // The player is handed the tap's place, which the pause has overtaken (what it does
        // with that is the playback service's: see ContinueHereTest).
        assertEquals(listOf<Long?>(pause - 1), player.choices)
        assertFalse(Choices.stands(player.choices.single()!!))
        // And the offer is gone once taken.
        assertNull(step.elsewhere.value)
        // The server out of reach is no reason to refuse it: the songs may be on the phone.
        // (The person has gone back to something of their own, and it is offered again.)
        player.current = "mine"
        step.check()
        reached = false
        step.continueHere()
        assertEquals(2, player.replaced.size)
    }

    @Test
    fun onceTheSettingIsOffNothingIsOfferedAndAnAnswerOnItsWayIsNotUsed() = runBlocking {
        val server = Server()
        server.savedBy(pc, "1970-01-02T00:10:00Z", "a", "b", "c", current = "b", position = 30_000)
        // Two phones' worth: one playing (it would be offered the PC's queue), one idle (it
        // would take it up). Each has asked the server, and the answer is on its way...
        val held = CompletableDeferred<Unit>()
        server.onRead = { held.await() }
        val busy = PretendPlayer(playing = true, stamp = DAY + 100)
        val idle = PretendPlayer(stamp = DAY + 100)
        val offered = step(server, busy)
        val taken = step(server, idle)
        val looks = listOf(scope.launch { offered.check() }, scope.launch { taken.check() })
        // ...when the setting is switched off.
        on = false
        offered.forget()
        taken.forget()
        held.complete(Unit)
        looks.joinAll()
        assertNull("offered after the setting was switched off", offered.elsewhere.value)
        assertTrue("taken up after the setting was switched off", idle.replaced.isEmpty())

        // An offer made while it was on is not acted on once it is off, even before anything
        // has cleared it away...
        on = true
        server.onRead = {}
        offered.check()
        assertEquals("Tb", offered.elsewhere.value?.title)
        on = false
        val place = Choices.latest
        offered.continueHere()
        assertTrue(busy.replaced.isEmpty())
        assertEquals("a choice that was not made took a place", place, Choices.latest)
        assertNull(offered.elsewhere.value)
        // ...and a queue being made ready for the player when it goes off is not put in.
        on = true
        idle.meanwhile = { on = false }
        taken.check()
        assertTrue(idle.replaced.isEmpty())
    }

    private companion object {
        /** 1970-01-02T00:00:00Z. */
        const val DAY = 86_400L
    }
}
