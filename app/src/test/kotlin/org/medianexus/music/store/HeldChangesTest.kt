package org.medianexus.music.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError

/** Held changes: `sendAll` is Windows' `send_all` and these first two are its tests
 *  (src/offline.rs), word for word; the rest are the phone's file. */
class HeldChangesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun star(id: String, starred: Boolean) = Pending.Star("song", id, starred)

    private fun ids(changes: List<Pending>) = changes.map { it.id }

    @Test
    fun keepsWhatTheServerHasNotDealtWith() = runBlocking {
        val waiting = listOf(star("a", true), star("b", true), star("c", true), star("d", true))
        // Everything goes through.
        var sent = sendAll(waiting) { }
        assertEquals(Triple(4, 0, false), Triple(sent.done, sent.kept.size, sent.lostTouch))
        // The connection drops at the third: it and the rest wait, in order.
        var calls = 0
        sent = sendAll(waiting) {
            calls++
            if (calls >= 3) throw ApiError.Network("gone")
        }
        assertEquals(Triple(2, listOf("c", "d"), true), Triple(sent.done, ids(sent.kept), sent.lostTouch))
        // The server refuses one for good: it isn't asked again, and the rest go.
        sent = sendAll(waiting) { if (it.id == "b") throw ApiError.Server(70, "not found") }
        assertEquals(4 to 0, sent.done to sent.kept.size)
        // A sign-in the server no longer accepts refuses nothing in particular: all of it waits.
        sent = sendAll(waiting) { throw ApiError.Server(40, "wrong password") }
        assertEquals(Triple(0, 4, true), Triple(sent.done, sent.kept.size, sent.lostTouch))
    }

    @Test
    fun keepsAChangeThatAProxyAnsweredFor() = runBlocking {
        val page = { throw ApiError.NotSubsonic("502") }
        // One odd answer: that change waits, and so does a later change to the same song.
        var waiting = listOf(star("a", true), star("b", true), star("a", false), star("c", true))
        var sent = sendAll(waiting) { if (it.id == "a") page() }
        assertEquals(Triple(2, listOf("a", "a"), false), Triple(sent.done, ids(sent.kept), sent.lostTouch))
        // Nothing but error pages: stop asking and keep the lot.
        waiting = listOf(star("a", true), star("b", true), star("c", true), star("d", true))
        var calls = 0
        sent = sendAll(waiting) {
            calls++
            page()
        }
        assertEquals(listOf(0, 4, 1, 3), listOf(sent.done, sent.kept.size, if (sent.lostTouch) 1 else 0, calls))
        assertEquals(listOf("a", "b", "c", "d"), ids(sent.kept))
    }

    @Test
    fun heldChangesOutliveTheAppInOrderWithTheirTimes() = runBlocking {
        val file = folder.newFile("held.json").also { it.delete() }
        val held = HeldChanges(file, Dispatchers.IO)
        held.hold(star("a", true))
        held.hold(Pending.Played("b", at = 1_700_000_000_123))
        held.hold(Pending.Rating("c", 4))
        // The app stopped and started again: read from the file, in order, plays with their times.
        val again = HeldChanges(file, Dispatchers.IO)
        assertEquals(listOf(star("a", true), Pending.Played("b", 1_700_000_000_123), Pending.Rating("c", 4)), again.all())
        assertEquals(3, again.waiting.value.let { again.all().size })
    }

    @Test
    fun whatTheServerDidNotTakeStaysAheadOfWhatCameMeanwhile() = runBlocking {
        val file = folder.newFile("held.json").also { it.delete() }
        val held = HeldChanges(file, Dispatchers.IO)
        held.hold(star("a", true))
        held.hold(star("b", true))
        // The round is held at its second send until the test says; a change is made meanwhile.
        val atSecond = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val round = async(Dispatchers.IO) {
            held.flush { change ->
                if (change.id == "b") {
                    atSecond.complete(Unit)
                    goOn.await()
                    throw ApiError.Network("gone")
                }
            }
        }
        atSecond.await()
        held.hold(star("c", true))
        // While the round is out, what it is sending is still on file: the app killed now
        // loses nothing.
        assertEquals(listOf("a", "b", "c"), ids(HeldChanges(file, Dispatchers.IO).all()))
        goOn.complete(Unit)
        val sent = round.await()!!
        assertEquals(1, sent.done)
        assertTrue(sent.lostTouch)
        // "b" waits, ahead of "c", which came after it.
        assertEquals(listOf("b", "c"), ids(held.all()))
        assertEquals(listOf("b", "c"), ids(HeldChanges(file, Dispatchers.IO).all()))
    }

    @Test
    fun oneRoundAtATime() = runBlocking {
        val file = folder.newFile("held.json").also { it.delete() }
        val held = HeldChanges(file, Dispatchers.IO)
        held.hold(star("a", true))
        val inside = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) { held.flush { inside.complete(Unit); goOn.await() } }
        inside.await()
        // A second round asked for while the first is out does nothing.
        assertNull(held.flush { error("not to be sent twice") })
        goOn.complete(Unit)
        assertEquals(1, first.await()!!.done)
        assertTrue(held.all().isEmpty())
    }

    @Test
    fun aRoundCutShortPutsItsChangesBack() = runBlocking {
        val file = folder.newFile("held.json").also { it.delete() }
        val held = HeldChanges(file, Dispatchers.IO)
        held.hold(star("a", true))
        held.hold(star("b", true))
        val inside = CompletableDeferred<Unit>()
        val round = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
            held.flush { inside.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        inside.await()
        // The account went (its scope was cancelled) while a round was out.
        round.cancelAndJoin()
        assertEquals(listOf("a", "b"), ids(held.all()))
    }
}
