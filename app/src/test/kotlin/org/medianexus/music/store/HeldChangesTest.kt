package org.medianexus.music.store

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError

/**
 * The one sender every like, rating and play goes through: on the disk first, then to the
 * server in order. The first two tests are Windows' `send_all` tests (src/offline.rs), held
 * to the sender itself; the rest are what the review of the offline engine found wanting.
 * Where the order of events matters it is forced by a gate the test opens, never by waiting.
 */
class HeldChangesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun star(id: String, starred: Boolean) = Pending.Star("song", id, starred)

    private fun ids(changes: List<Pending>) = changes.map { it.id }

    private val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var offline = false
    private var lostTouch = 0

    /** What the server was sent, in order ("a+" a like of a, "a-" an unlike). */
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun said(change: Pending) = change.id + when (change) {
        is Pending.Star -> if (change.starred) "+" else "-"
        is Pending.Rating -> "=${change.rating}"
        is Pending.Played -> "!"
    }

    private fun file(): File = File(folder.root, "held.json")

    /** A file as an earlier run left it, holding these. */
    private fun left(vararg changes: Pending) {
        runBlocking {
            offline = true
            val earlier = held()
            for (change in changes) earlier.submit(change)
            offline = false
        }
        sent.clear()
    }

    private fun held(send: suspend (Pending) -> Unit = {}) = HeldChanges(
        file(), Dispatchers.IO,
        send = { change -> send(change); sent += said(change) },
        offline = { offline },
        lostTouch = { lostTouch++ },
        note = { notes += it },
    )

    // ---- Windows' rules ---------------------------------------------------------------------

    @Test
    fun keepsWhatTheServerHasNotDealtWith() = runBlocking {
        val waiting = arrayOf(star("a", true), star("b", true), star("c", true), star("d", true))
        // Everything goes through.
        left(*waiting)
        var held = held()
        assertEquals(4, held.flush())
        assertTrue(held.all().isEmpty())
        // The connection drops at the third: it and the rest wait, in order.
        left(*waiting)
        var calls = 0
        held = held { if (++calls >= 3) throw ApiError.Network("gone") }
        assertEquals(2, held.flush())
        assertEquals(listOf("c", "d"), ids(held.all()))
        assertEquals(1, lostTouch)
        assertEquals(2, held().flush())
        // The server refuses one for good: it isn't asked again, and the rest go.
        left(*waiting)
        held = held { if (it.id == "b") throw ApiError.Server(70, "not found") }
        assertEquals(4, held.flush())
        assertTrue(held.all().isEmpty())
        // A sign-in the server no longer accepts refuses nothing in particular: all of it waits.
        left(*waiting)
        held = held { throw ApiError.Server(40, "wrong password") }
        assertEquals(0, held.flush())
        assertEquals(4, held.all().size)
    }

    @Test
    fun keepsAChangeThatAProxyAnsweredFor() = runBlocking {
        val page = { throw ApiError.NotSubsonic("502") }
        // One odd answer: that change waits, and so does a later change to the same song.
        left(star("a", true), star("b", true), star("a", false), star("c", true))
        var held = held { if (it.id == "a") page() }
        assertEquals(2, held.flush())
        assertEquals(listOf(star("a", true), star("a", false)), held.all())
        assertEquals(listOf("b+", "c+"), sent.toList())
        // (And the server is asked whether it is there at all.)
        assertEquals(1, lostTouch)
        assertEquals(2, held().flush())
        // Nothing but error pages: stop asking and keep the lot.
        left(star("a", true), star("b", true), star("c", true), star("d", true))
        var calls = 0
        held = held {
            calls++
            page()
        }
        assertEquals(0, held.flush())
        assertEquals(3, calls)
        assertEquals(listOf("a", "b", "c", "d"), ids(held.all()))
    }

    // ---- Once, in order (offline review 3) ---------------------------------------------------

    @Test
    fun aChangeIsOnTheDiskBeforeItIsAcknowledgedInOrderWithItsTime() = runBlocking {
        offline = true
        val held = held()
        assertEquals(HeldChanges.Outcome.HELD, held.submit(star("a", true)))
        // Acknowledged: so already on file, for an app killed this instant.
        assertEquals(listOf(star("a", true)), held().all())
        held.submit(Pending.Played("b", at = 1_700_000_000_123))
        held.submit(Pending.Rating("c", 4))
        assertEquals(listOf(star("a", true), Pending.Played("b", 1_700_000_000_123), Pending.Rating("c", 4)), held().all())
        assertEquals(3, held.waiting.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun nothingOvertakesAChangeThatIsWaiting() = runBlocking {
        // Offline mode on: a like, held.
        offline = true
        val held = held()
        held.submit(star("a", true))
        // Offline mode off, and before anything has been sent, an unlike.
        offline = false
        assertEquals(HeldChanges.Outcome.TAKEN, held.submit(star("a", false)))
        // The like went first: the server ends with the unlike.
        assertEquals(listOf("a+", "a-"), sent.toList())
        assertTrue(held.all().isEmpty())
    }

    @Test
    fun aChangeMadeWhileOthersAreBeingSentWaitsItsTurn() = runBlocking {
        left(star("a", true), star("b", true))
        val atFirst = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val held = held { change ->
            if (change == star("a", true)) {
                atFirst.complete(Unit)
                goOn.await()
            }
        }
        val catchingUp = async(Dispatchers.IO) { held.flush() }
        atFirst.await()
        // While the held like of a is on its way, a is unliked.
        val unlike = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { held.submit(star("a", false)) }
        assertFalse(unlike.isCompleted)
        assertTrue(sent.isEmpty())
        goOn.complete(Unit)
        assertEquals(HeldChanges.Outcome.TAKEN, unlike.await())
        catchingUp.await()
        assertEquals(listOf("a+", "b+", "a-"), sent.toList())
    }

    @Test
    fun aChangeLeavesTheFileAsSoonAsItsOwnAnswerArrives() = runBlocking {
        left(Pending.Played("a", 1), Pending.Played("b", 2), Pending.Played("c", 3))
        val atSecond = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val held = held { change ->
            if (change.id == "b") {
                atSecond.complete(Unit)
                goOn.await()
            }
        }
        val round = async(Dispatchers.IO) { held.flush() }
        atSecond.await()
        // The server has answered for a, and b is on its way. Killed now, the app would send
        // b and c again, and not a: a's play is not counted twice.
        assertEquals(listOf("b", "c"), ids(held().all()))
        goOn.complete(Unit)
        assertEquals(3, round.await())
        assertFalse(file().readText().contains("played"))
    }

    @Test
    fun aRefusalIsToldToWhoeverMadeTheChangeAndNotAskedAgain() = runBlocking {
        val held = held { if (it.id == "gone") throw ApiError.Server(70, "not found") }
        try {
            held.submit(star("gone", true))
            fail("the server refused it")
        } catch (e: ApiError.Server) {
            assertEquals(70L, e.code)
        }
        assertTrue(held.all().isEmpty())
        // One the server couldn't be reached for is held, and its maker told so, not failed.
        var down = true
        val other = held { if (down) throw ApiError.Network("gone") }
        assertEquals(HeldChanges.Outcome.HELD, other.submit(star("a", true)))
        assertEquals(1, lostTouch)
        down = false
        assertEquals(1, other.flush())
        assertEquals(listOf("a+"), sent.toList())
    }

    @Test
    fun aChangeThatCanBeNeitherKeptNorSentIsNotCalledSaved() = runBlocking {
        // The file can't be written (a folder is where it should go).
        File(file(), "in-the-way").apply { parentFile.mkdirs(); writeText("x") }
        offline = true
        val held = held()
        try {
            held.submit(star("a", true))
            fail("it was neither kept nor sent")
        } catch (e: ApiError.Network) {
            assertTrue(e.message!!.contains("couldn't be kept"))
        }
        // Not left to be sent behind its maker's back, who has been told it didn't save.
        assertTrue(held.all().isEmpty())
        assertEquals(1, notes.count { "couldn't be written" in it })
        // With the server there it is sent all the same.
        offline = false
        assertEquals(HeldChanges.Outcome.TAKEN, held.submit(star("b", true)))
        assertEquals(listOf("b+"), sent.toList())
    }

    @Test
    fun aFileThatCantBeReadIsSetAsideAndSaidNeverReplacedInSilence() = runBlocking {
        file().writeText("""[{"type":"star","kind":"song","id":"a","starr""")
        offline = true
        val held = held()
        assertTrue(held.all().isEmpty())
        assertTrue(held.damaged.value)
        assertTrue(notes.any { "couldn't be read" in it && "set aside" in it })
        // What was there is still on the phone, as it was.
        assertTrue(File(folder.root, "held.damaged.json").readText().startsWith("""[{"type":"star""""))
        // And the next change has a file of its own.
        held.submit(star("b", true))
        assertEquals(listOf(star("b", true)), held().all())
        assertTrue(File(folder.root, "held.damaged.json").isFile)
    }

    @Test
    fun whenTheAccountGoesPartWayEverythingUnansweredIsStillOnFile() = runBlocking {
        left(star("a", true), star("b", true))
        val inside = CompletableDeferred<Unit>()
        val held = held { inside.complete(Unit); CompletableDeferred<Unit>().await() }
        val round = async(Dispatchers.IO) { held.flush() }
        inside.await()
        // The account went (its scope was cancelled) while a change was on its way.
        round.cancelAndJoin()
        assertEquals(listOf("a", "b"), ids(held.all()))
        assertEquals(listOf("a", "b"), ids(held().all()))
    }
}
