package org.medianexus.music.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.StatedPlaylist
import org.medianexus.music.net.Timed

/**
 * The exchange of the shared record with the server, and the rules that keep the person's
 * PCs safe from the phone (docs/DESIGN.md, "In step with other devices"). The server here is
 * a pretend one that counts every question and every write.
 */
class InStepTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:org.junit.Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    /** A device: what it holds, as one record. Taking in is Windows' rule (the newer). */
    private class Device(var holds: Shared?) : StepPhone {
        val took = ArrayList<Shared>()

        /** Run as the exchange takes in what came back: a change made here meanwhile. */
        var meanwhile: () -> Unit = {}

        override suspend fun record(): Shared? = holds

        override fun withChanges(merged: Shared, now: Long) = Changed(merged, emptyList())

        override suspend fun take(theirs: Shared, applied: List<org.medianexus.music.store.Waiting>) {
            meanwhile()
            took += theirs
            val mine = holds ?: return
            // As the phone's own stores take it: each part only where theirs is newer.
            holds = Merge.merge(mine, theirs, NOW)
        }
    }

    private fun step(server: PretendServer, device: Device, may: () -> Boolean = { true }, notes: MutableList<String> = ArrayList(), account: String = "acct-${System.nanoTime()}", pause: suspend (Long) -> Unit = {}, clock: () -> Long = { NOW }) =
        InStep(server, device, username = "Tester", account = account, may = may, scope = scope, clock = clock, pause = pause, note = { notes += it })

    private fun text(shared: Shared) = SharedText.write(shared)

    private val pc = Shared(
        layout = 1, pins = listOf("p1", "p2"), pinsAt = 1_700_000_000, theme = "Ember", themeAt = 1_700_000_100,
        lists = mapOf("playlist:p1" to SharedPrefs(shuffle = true, sort = 3u, descending = true, sortChosen = true, at = 1_700_000_200)),
        added = mapOf("p1" to mapOf("s1" to SharedNote(listOf(1_700_000_300), 1_700_000_300))),
        history = listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400)),
        plays = listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400)),
        playSources = listOf(PlayFrom("s1", 1_700_000_400, "playlist:p1")),
    )

    /** A phone freshly installed, everything read and nothing in any of it. */
    private val nothing = Shared(layout = 1)

    // ---- rule 2: never before it has loaded ----------------------------------------------------

    @Test
    fun anExchangeAskedForBeforeThePhoneHasReadItsOwnSendsNothing() = runBlocking {
        val server = PretendServer().apply { put("a", text(pc)) }
        val device = Device(holds = null)
        assertEquals(InStep.Outcome.NOT_READ_YET, step(server, device).exchange())
        // Not a question, not a write: an empty list that means "not read yet" never leaves.
        assertEquals(0, server.asked())
        assertEquals(text(pc), server.comment("a"))
        assertTrue(device.took.isEmpty())
    }

    // ---- the case that matters most ----------------------------------------------------------

    @Test
    fun aFreshPhoneLeavesThePCsRecordExactlyAsItIs() = runBlocking {
        // The text as the Windows app itself wrote it (its own order of fields and maps).
        val windows = """{"nexus_music_sync":1,"pins":["p1","p2"],"pins_at":1700000000,"theme":"Ember","theme_at":1700000100,"lists":{"playlist:p1":{"shuffle":true,"sort":3,"descending":true,"sort_chosen":true,"at":1700000200},"liked":{"shuffle":false,"sort":5,"descending":true,"sort_chosen":true,"at":1700000250}},"added":{"p1":{"s9":{"times":[],"at":1794000000},"s1":{"times":[1700000300],"at":1700000300}}},"history":[["s2",1700000500],["s1",1700000400]],"plays":[["s2",1700000500],["s1",1700000400]],"play_sources":[["s1",1700000400,"playlist:p1"]]}"""
        val server = PretendServer().apply { put("a", windows) }
        val device = Device(holds = nothing)
        assertEquals(InStep.Outcome.DONE, step(server, device).exchange())
        assertTrue("nothing was written", server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        // And the phone has taken in what the PC had.
        assertEquals(listOf("p1", "p2"), device.holds!!.pins)
        assertEquals("Ember", device.holds!!.theme)
        // Again, now that it holds it: still nothing to write.
        assertEquals(InStep.Outcome.DONE, step(server, device).exchange())
        assertTrue(server.writes.isEmpty())
    }

    // ---- rule 4: what it doesn't understand is preserved ---------------------------------------

    @Test
    fun aCommentThatIsNotARecordIsLeftAloneAndSaidInDiagnostics() = runBlocking {
        // (A comment of spaces among them: only the empty text is "nothing shared yet".)
        for (comment in listOf("My sync playlist, do not delete", """{"nexus_music_sync":1,"pins":["a","b""", """{"pins":null}""", "null", """{"nexus_music_sync":4294967296}""", "  \n ", " ")) {
            val server = PretendServer().apply { put("a", comment) }
            val notes = ArrayList<String>()
            val device = Device(holds = pc)
            assertEquals(comment, InStep.Outcome.UNREADABLE, step(server, device, notes = notes).exchange())
            assertTrue("nothing was written over: $comment", server.writes.isEmpty())
            assertEquals(comment, server.comment("a"))
            assertTrue(device.took.isEmpty())
            assertEquals(1, notes.size)
            // (The line says what happened and nothing of what the comment held.)
            assertFalse(notes[0].contains("sync playlist") || notes[0].contains("pins"))
        }
    }

    @Test
    fun aRecordInALaterLayoutIsReadAndNeverWritten() = runBlocking {
        val later = """{"nexus_music_sync":2,"pins":["z"],"pins_at":1790000000,"shape":{"of":"things to come"}}"""
        val server = PretendServer().apply { put("a", later) }
        val device = Device(holds = pc)
        assertEquals(InStep.Outcome.NEWER_LAYOUT, step(server, device).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(later, server.comment("a"))
        // What is understood of it is taken in (as Windows does).
        assertEquals(listOf("z"), device.holds!!.pins)
    }

    @Test
    fun aRecordThePhoneCouldNotWriteBackWholeIsReadAndNotWritten() = runBlocking {
        // Inside a list's choices and inside a note, fields this version doesn't know: it
        // would drop them if it wrote. So it takes in what it understands and writes nothing.
        for (text in listOf(
            """{"nexus_music_sync":1,"pins":["z"],"pins_at":1790000000,"lists":{"liked":{"shuffle":true,"at":9,"columns":[1,2]}}}""",
            """{"nexus_music_sync":1,"pins":["z"],"pins_at":1790000000,"added":{"p":{"s":{"times":[4],"at":4,"by":"pc"}}}}""",
        )) {
            val server = PretendServer().apply { put("a", text) }
            val notes = ArrayList<String>()
            val device = Device(holds = pc)
            assertEquals(InStep.Outcome.MORE_THAN_KNOWN, step(server, device, notes = notes).exchange())
            assertTrue(server.writes.isEmpty())
            assertEquals(text, server.comment("a"))
            assertEquals(listOf("z"), device.holds!!.pins)
            assertEquals(1, notes.size)
        }
    }

    @Test
    fun fieldsThisVersionDoesNotKnowAreWrittenBackAsTheyWere() = runBlocking {
        val server = PretendServer().apply { put("a", """{"nexus_music_sync":1,"devices":{"pc":[1,2.50,{"x":null}]},"pins":[],"pins_at":0,"note":"later"}""") }
        val device = Device(holds = pc)
        assertEquals(InStep.Outcome.DONE, step(server, device).exchange())
        val wrote = server.writes.single().second
        assertTrue(wrote, wrote.endsWith(""","devices":{"pc":[1,2.50,{"x":null}]},"note":"later"}"""))
        assertEquals(pc, SharedText.read(wrote)!!.copy(unknown = emptyList()))
    }

    // ---- rule 7: the playlist that holds the record ----------------------------------------------

    @Test
    fun thePlaylistIsFoundByNameAndOwnerTheLowestIdOfSeveral() = runBlocking {
        val server = PretendServer().apply {
            put("zz", text(Shared(layout = 1, pins = listOf("wrong"), pinsAt = 5)))
            put("b2", text(pc))
            put("a9", "someone else's", by = "guest")
            put("b1", "", name = "Nexus Music sync 2")
            put("c1", "not it", name = "Road trip")
        }
        val device = Device(holds = nothing)
        assertEquals(InStep.Outcome.DONE, step(server, device).exchange())
        assertTrue(server.made.isEmpty() && server.writes.isEmpty())
        assertEquals(listOf("p1", "p2"), device.holds!!.pins)
    }

    @Test
    fun theLowestIdThatIsOursIsUsedAtEveryExchangeNotOneRemembered() = runBlocking {
        // Two devices each made one, having found none: this phone has been using "m7".
        val server = PretendServer().apply { put("m7", text(pc.copy(pins = listOf("mine"), pinsAt = 1_700_000_900))) }
        val device = Device(holds = nothing)
        val step = step(server, device)
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(listOf("mine"), device.holds!!.pins)
        // The other device's turns up, with a lower id. A fresh phone would use that one; a
        // phone that went on with the id it remembered would never meet it again.
        server.put("a1", text(Shared(layout = 1, pins = listOf("theirs"), pinsAt = 1_700_001_000)))
        assertEquals(InStep.Outcome.DONE, step.exchange())
        // The lower one is the record now: what this phone holds is written into it (so
        // nothing of the higher one's is lost), and its newer pins are taken in.
        assertEquals("a1", server.writes.single().first)
        assertEquals(listOf("theirs"), device.holds!!.pins)
        assertEquals(pc.history, SharedText.read(server.comment("a1")!!)!!.history)
        // One that was the record and has been renamed, or is someone else's, is never used.
        server.put("a0", text(pc), name = "Renamed by another app")
        server.put("a00", "", by = "guest")
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(1, server.writes.size)
        assertTrue(server.made.isEmpty())
    }

    @Test
    fun thePlaylistIsMadeOnlyWhenTheServerSaysThereIsNoneAndSharingIsOn() = runBlocking {
        // None there: made, and the record written into it.
        val server = PretendServer().apply { put("c1", "not it", name = "Road trip") }
        val device = Device(holds = pc)
        assertEquals(InStep.Outcome.DONE, step(server, device).exchange())
        assertEquals(1, server.made.size)
        assertEquals(pc, SharedText.read(server.comment(server.made[0])!!))
        // Sharing off, or the server out of reach: nothing is asked, read, made or written.
        val quiet = PretendServer()
        assertEquals(InStep.Outcome.NOT_NOW, step(quiet, Device(holds = pc), may = { false }).exchange())
        assertEquals(0, quiet.asked())
        // Switched off while the server was being asked what it has: not made.
        var on = true
        val late = PretendServer().apply { gate = { if (it == "list") on = false } }
        assertEquals(InStep.Outcome.NOT_NOW, step(late, Device(holds = pc), may = { on }).exchange())
        assertTrue(late.made.isEmpty() && late.writes.isEmpty())
        // The list of playlists can't be had: nothing is made on the strength of that.
        val down = PretendServer().apply { gate = { if (it == "list") throw ApiError.Network("The connection timed out.") } }
        assertEquals(InStep.Outcome.FAILED, step(down, Device(holds = pc)).exchange())
        assertTrue(down.made.isEmpty())
    }

    // ---- what the server did not say is not "empty" ---------------------------------------------

    @Test
    fun aServerThatLeavesOutTheOwnerOrTheCommentIsNotTakenToHaveNone() = runBlocking {
        // Navidrome leaves a playlist's owner and comment out of its answers for clients it
        // has been told to treat as minimal. The PC's record is there all the while.
        for (phone in listOf(nothing, pc.copy(pins = listOf("mine"), pinsAt = NOW - 5))) {
            // Neither stated.
            val neither = PretendServer().apply { put("a", comment = null, by = null) }
            val notes = ArrayList<String>()
            assertEquals(InStep.Outcome.NOT_STATED, step(neither, Device(holds = phone), notes = notes).exchange())
            // Nothing written into it, and no second one made beside it.
            assertTrue(neither.writes.isEmpty() && neither.made.isEmpty())
            assertEquals(1, notes.size)
            // The owner stated, the comment left out: read as empty, the phone's own record
            // would be written over everything the PCs had put there.
            val noComment = PretendServer().apply { put("a", comment = null) }
            val device = Device(holds = phone)
            assertEquals(InStep.Outcome.NOT_STATED, step(noComment, device).exchange())
            assertTrue(noComment.writes.isEmpty() && noComment.made.isEmpty())
            assertTrue(device.took.isEmpty())
            // The comment stated, the owner left out: not known to be ours.
            val noOwner = PretendServer().apply { put("a", comment = text(pc), by = null) }
            assertEquals(InStep.Outcome.NOT_STATED, step(noOwner, Device(holds = phone)).exchange())
            assertTrue(noOwner.writes.isEmpty() && noOwner.made.isEmpty())
            // One that is stated to be ours beside one that isn't stated: ours is used.
            val both = PretendServer().apply { put("a", comment = "whose?", by = null); put("b", comment = text(pc)) }
            assertEquals(InStep.Outcome.DONE, step(both, Device(holds = nothing)).exchange())
            assertTrue(both.writes.isEmpty() && both.made.isEmpty())
        }
    }

    @Test
    fun aPhoneWithNothingToSayMakesNoRecordAndFillsNoEmptyOne() = runBlocking {
        // No playlist at all: none is made.
        val none = PretendServer().apply { put("c1", "not it", name = "Road trip") }
        assertEquals(InStep.Outcome.DONE, step(none, Device(holds = nothing)).exchange())
        assertTrue(none.made.isEmpty() && none.writes.isEmpty())
        // An empty one (just made by another device, not yet written): left empty.
        val empty = PretendServer().apply { put("a", "") }
        assertEquals(InStep.Outcome.DONE, step(empty, Device(holds = nothing)).exchange())
        assertTrue(empty.writes.isEmpty())
        assertEquals("", empty.comment("a"))
        // The default theme's name, with no time on it, is nobody's choice: still nothing.
        assertEquals(InStep.Outcome.DONE, step(empty, Device(holds = Shared(layout = 1, theme = "Violet"))).exchange())
        assertTrue(empty.writes.isEmpty())
        // With one pin of the person's, it is filled.
        assertEquals(InStep.Outcome.DONE, step(empty, Device(holds = Shared(layout = 1, pins = listOf("p"), pinsAt = NOW - 9))).exchange())
        assertEquals(listOf("p"), SharedText.read(empty.comment("a")!!)!!.pins)
    }

    // ---- a clock that is wrong ----------------------------------------------------------------

    @Test
    fun aPhoneWhoseClockDisagreesWithTheServersTakesInAndSharesNothing() = runBlocking {
        val removed = SharedNote(emptyList(), NOW - 10)
        val record = pc.copy(added = mapOf("p1" to mapOf("gone" to removed)))
        for (off in listOf(3_600L, -3_600L, 301L, 100 * 24 * 3_600L)) {
            val server = PretendServer().apply { put("a", text(record)) }
            // The phone has a pin of its own to tell of, stamped by its own clock.
            var phoneClock = NOW + off
            val device = Device(holds = Shared(layout = 1, pins = listOf("mine"), pinsAt = NOW + off, history = listOf(Play("heard-here", NOW + off))))
            val notes = ArrayList<String>()
            val step = step(server, device, notes = notes, clock = { phoneClock })
            assertEquals("$off", InStep.Outcome.CLOCK_WRONG, step.exchange())
            // Nothing written: not its pin (which, from a clock an hour ahead, no PC could
            // beat for an hour), and not the record with the PC's word of a removal dropped
            // (which is what a clock a hundred days ahead makes of it).
            assertTrue("$off", server.writes.isEmpty())
            assertEquals(text(record), server.comment("a"))
            assertTrue(step.clockWrong.value)
            assertEquals(1, notes.size)
            // What the server has was taken in all the same.
            assertEquals(record, device.took.single())
            // Again: still not shared, and said only once.
            assertEquals(InStep.Outcome.CLOCK_WRONG, step.exchange())
            assertEquals(1, notes.size)
            // The clock put right: what the phone holds is shared then.
            phoneClock = NOW + 200
            assertEquals(InStep.Outcome.DONE, step.exchange())
            assertFalse(step.clockWrong.value)
            assertEquals(1, server.writes.size)
        }
        // Within five minutes either way, or with a server that gives no time: shared.
        for (time in listOf<Long?>(NOW + 299, NOW - 300, null)) {
            val server = PretendServer().apply { put("a", text(pc)); this.time = time }
            val step = step(server, Device(holds = Shared(layout = 1, pins = listOf("mine"), pinsAt = NOW)))
            assertEquals(InStep.Outcome.DONE, step.exchange())
            assertFalse(step.clockWrong.value)
            assertEquals(1, server.writes.size)
        }
    }

    // ---- a write with no answer ------------------------------------------------------------------

    @Test
    fun aWriteThatGetsNoAnswerIsSafeToMakeAgain() = runBlocking {
        val server = PretendServer().apply { put("a", text(pc.copy(pins = listOf("old"), pinsAt = 5))) }
        val device = Device(holds = pc)
        val step = step(server, device)
        // It reached the server; the phone never heard.
        server.loseAnswers = 1
        assertEquals(InStep.Outcome.FAILED, step.exchange())
        val after = server.comment("a")
        // Again: the server already has it, so there is nothing to write, and it is the same.
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(1, server.writes.size)
        assertEquals(after, server.comment("a"))
        assertEquals(pc, SharedText.read(after!!))
    }

    // ---- rule 6: one at a time, nothing lost, nothing stale ------------------------------------------

    @Test
    fun aSecondExchangeWaitsForNothingAndAnotherFollowsTheFirst() = runBlocking {
        val server = PretendServer().apply { put("a", text(nothing)) }
        val device = Device(holds = pc)
        val held = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        server.gate = { if (it == "write") { reached.complete(Unit); held.await() } }
        var followed = 0
        val step = step(server, device, account = "one-at-a-time", pause = { followed++ })
        val first = async { step.exchange() }
        reached.await()
        // Asked for while the first is out: it does nothing itself...
        assertEquals(InStep.Outcome.BUSY, step.exchange())
        // ...and nor does another of this account's objects (signed out and straight back in).
        assertEquals(InStep.Outcome.BUSY, step(server, Device(holds = pc), account = "one-at-a-time").exchange())
        assertEquals(0, followed)
        held.complete(Unit)
        assertEquals(InStep.Outcome.DONE, first.await())
        // ...but one follows, once the first is back and things have settled.
        assertEquals(1, followed)
        assertEquals(1, server.writes.size)
    }

    @Test
    fun aChangeMadeHereDuringAnExchangeIsNeitherLostNorSentStale() = runBlocking {
        val server = PretendServer().apply { put("a", text(pc)) }
        // The phone holds what the PC has; then, while an exchange is out, the person unpins.
        val device = Device(holds = pc)
        val unpinned = pc.copy(pins = listOf("p2"), pinsAt = NOW - 1)
        device.meanwhile = {
            device.holds = unpinned
            device.meanwhile = {}
        }
        val step = step(server, device)
        assertEquals(InStep.Outcome.DONE, step.exchange())
        // What came back (the pins as they were) did not put the unpinned one back...
        assertEquals(listOf("p2"), device.holds!!.pins)
        // ...and the first exchange wrote nothing stale: it had nothing to add.
        assertTrue(server.writes.isEmpty())
        // The next carries the change.
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(listOf("p2"), SharedText.read(server.comment("a")!!)!!.pins)
    }

    @Test
    fun twoDevicesInterleavedAtEveryStepConvergeOnTheMergeAndLoseNothing() = runBlocking {
        val a = Shared(
            layout = 1, pins = listOf("pa"), pinsAt = 300, theme = "Ocean", themeAt = 100,
            lists = mapOf("liked" to SharedPrefs(shuffle = true, at = 50), "album:1" to SharedPrefs(sort = 2u, at = 70)),
            added = mapOf("p" to mapOf("s" to SharedNote(listOf(40), 40))),
            history = listOf(Play("x", 900), Play("y", 700)), plays = listOf(Play("x", 900), Play("y", 700)),
            playSources = listOf(PlayFrom("x", 900, "playlist:p")),
        )
        val b = Shared(
            layout = 1, pins = listOf("pb", "pc"), pinsAt = 200, theme = "Rose", themeAt = 150,
            lists = mapOf("liked" to SharedPrefs(shuffle = false, at = 60), "songs" to SharedPrefs(sort = 1u, at = 10)),
            added = mapOf("p" to mapOf("s" to SharedNote(emptyList(), NOW - 5), "t" to SharedNote(listOf(45), 45))),
            history = listOf(Play("z", 800), Play("y", 700)), plays = listOf(Play("z", 800), Play("y", 700)),
        )
        val wanted = Merge.merge(a, b, NOW)
        // Each device's exchange is a read then (perhaps) a write. Every order in which the
        // two devices' steps can fall, each held at a gate until its turn: no sleeps.
        val orders = listOf("AABB", "ABAB", "ABBA", "BAAB", "BABA", "BBAA")
        for (order in orders) {
            val server = PretendServer().apply { put("r", "") }
            val turns = order.map { CompletableDeferred<Unit>() }
            val done = order.map { CompletableDeferred<Unit>() }
            fun gateFor(who: Char): suspend (String) -> Unit {
                val mine = order.indices.filter { order[it] == who }
                var step = 0
                var last: Int? = null
                return { _ ->
                    // The step before this one is over: the next in line may go.
                    last?.let { done[it].complete(Unit) }
                    val turn = mine[step++]
                    last = turn
                    turns[turn].await()
                }
            }
            val (gateA, gateB) = gateFor('A') to gateFor('B')
            // One pretend server seen by both, each through its own gate.
            fun through(gate: suspend (String) -> Unit) = object : StepServer {
                override suspend fun playlists() = server.playlists()
                override suspend fun playlist(id: String): Timed<StatedPlaylist> { gate("read"); return server.playlist(id) }
                override suspend fun create(name: String) = server.create(name)
                override suspend fun setComment(id: String, comment: String) { gate("write"); server.setComment(id, comment) }
            }
            val (deviceA, deviceB) = Device(a) to Device(b)
            fun stepOf(device: Device, gate: suspend (String) -> Unit) =
                InStep(through(gate), device, "tester", "acct-${System.nanoTime()}", { true }, scope, clock = { NOW }, pause = {})
            val (stepA, stepB) = stepOf(deviceA, gateA) to stepOf(deviceB, gateB)
            val runA = async(Dispatchers.Default) { stepA.exchange().also { order.indices.filter { order[it] == 'A' }.forEach { done[it].complete(Unit) } } }
            val runB = async(Dispatchers.Default) { stepB.exchange().also { order.indices.filter { order[it] == 'B' }.forEach { done[it].complete(Unit) } } }
            withTimeout(10_000) {
                for (i in order.indices) {
                    turns[i].complete(Unit)
                    done[i].await()
                }
                assertEquals(InStep.Outcome.DONE, runA.await())
                assertEquals(InStep.Outcome.DONE, runB.await())
            }
            // Crossed like that, the later write may have been made without the other's
            // (the server has no way to refuse it). Nothing is lost for it: each device still
            // holds its own, and the next look by each puts it right, whichever goes first.
            val quiet = InStep(server, deviceA, "tester", "a-${System.nanoTime()}", { true }, scope, clock = { NOW }, pause = {})
            val quietB = InStep(server, deviceB, "tester", "b-${System.nanoTime()}", { true }, scope, clock = { NOW }, pause = {})
            repeat(2) {
                assertEquals(InStep.Outcome.DONE, quiet.exchange())
                assertEquals(InStep.Outcome.DONE, quietB.exchange())
            }
            assertEquals(order, wanted, SharedText.read(server.comment("r")!!))
            assertEquals(order, wanted, deviceA.holds)
            assertEquals(order, wanted, deviceB.holds)
            // Settled: another round writes nothing.
            val writes = server.writes.size
            quiet.exchange()
            quietB.exchange()
            assertEquals(order, writes, server.writes.size)
        }
    }

    @Test
    fun anExchangeThatFailsLeavesThePhoneAsItWas() = runBlocking {
        val server = PretendServer().apply { put("a", text(pc)) }
        server.gate = { if (it == "read") throw ApiError.Network("The connection dropped.") }
        val device = Device(holds = nothing)
        assertEquals(InStep.Outcome.FAILED, step(server, device).exchange())
        assertTrue(device.took.isEmpty())
        assertNull(device.holds!!.pins.firstOrNull())
    }

    private companion object {
        const val NOW = 1_795_000_000L
    }
}
