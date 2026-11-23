package org.medianexus.music.playback

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.store.CoverProvider

/**
 * Work begun for one account and finished after it has gone. Each case holds the work at a
 * gate, changes who is signed in there, and opens the gate: no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForAccountTest {
    /** The main thread, as the service has it: what is resumed is run in its turn, later,
     *  not in the middle of whatever resumed it. Nothing runs until [turn]. */
    private val main = StandardTestDispatcher()

    private fun turn() = main.scheduler.runCurrent()

    /** An account's turn at the player: its own scope, cancelled when it goes. */
    private inner class Turn(val name: String) {
        val scope = CoroutineScope(SupervisorJob() + main)
    }

    private var signedIn: Turn? = null
    private val asked = mutableListOf<String>()

    /** A folder asked for by the car, answered as the service answers it; `loading` holds the
     *  first account's answer back. */
    private fun folder(loading: CompletableDeferred<Unit>) = CoroutineScope(main).async {
        ForAccount.answer(
            now = { signedIn },
            isNow = { it === signedIn },
            signedOut = { "Sign in" },
            superseded = { "nothing said" },
        ) { turn ->
            turn.scope.async {
                asked += turn.name
                if (turn.name == "first") loading.await()
                "${turn.name}'s albums"
            }.await()
        }
    }

    private fun leave() {
        val old = signedIn
        signedIn = null
        old?.scope?.cancel()
    }

    @Test
    fun aFolderStillLoadingWhenSomeoneElseSignsInIsAnsweredForThem() {
        signedIn = Turn("first")
        val loading = CompletableDeferred<Unit>()
        val answer = folder(loading)
        turn()
        // Someone else signs in while the folder is being fetched. The service takes the new
        // account on (and clears "sign in" from the car's screen) in one go; the answer to
        // the folder that was loading comes after.
        leave()
        signedIn = Turn("second")
        loading.complete(Unit)
        turn()
        // It used to come back as "sign in": and Media3 puts that up on the car's screen, over
        // the library the new sign-in had just brought, until another folder is opened.
        assertEquals("second's albums", answer.getCompleted())
        assertEquals(listOf("first", "second"), asked)
    }

    @Test
    fun withNobodySignedInAfterwardsTheAnswerIsSignIn() {
        signedIn = Turn("first")
        val loading = CompletableDeferred<Unit>()
        val answer = folder(loading)
        turn()
        leave()
        loading.complete(Unit)
        turn()
        assertEquals("Sign in", answer.getCompleted())
    }

    @Test
    fun whatWasFoundForAnAccountThatHasGoneIsNeverHandedOn() {
        // The work finishes for the first account, but only after that account has gone
        // (its work was past the point where it could be stopped).
        val first = Turn("first")
        val second = Turn("second")
        signedIn = first
        var runs = 0
        val answer = CoroutineScope(Dispatchers.Unconfined).async {
            ForAccount.answer(now = { signedIn }, isNow = { it === signedIn }, signedOut = { "Sign in" }, superseded = { "nothing said" }) { turn ->
                runs++
                if (turn === first) signedIn = second
                "${turn.name}'s albums"
            }
        }
        assertEquals("second's albums", answer.getCompleted())
        assertEquals(2, runs)
    }

    @Test
    fun aQuestionOvertakenAgainAndAgainEndsWithoutSayingAnyoneIsSignedOut() {
        // Each time the folder is fetched, whoever it was fetched for has gone and someone
        // else is signed in: the question is put to them, and so on. It is given up in the
        // end, with someone signed in throughout. It used to be given up as "sign in", which
        // Media3 puts on the car's screen, and keeps there, over a library that is signed in.
        var number = 1
        signedIn = Turn("account 1")
        val answer = CoroutineScope(Dispatchers.Unconfined).async {
            ForAccount.answer(now = { signedIn }, isNow = { it === signedIn }, signedOut = { "Sign in" }, superseded = { "nothing said" }) { turn ->
                signedIn = Turn("account ${++number}")
                "${turn.name}'s albums"
            }
        }
        assertEquals("nothing said", answer.getCompleted())
        assertTrue(number > 2)
    }

    @Test
    fun aRequestCalledOffItselfIsNotAskedAgain() {
        signedIn = Turn("first")
        val answer = CoroutineScope(Dispatchers.Unconfined).async {
            ForAccount.answer(now = { signedIn }, isNow = { it === signedIn }, signedOut = { "Sign in" }, superseded = { "nothing said" }) { throw CancellationException("called off") }
        }
        assertTrue(answer.isCancelled)
    }

    @Test
    fun aCoverFetchedForAnAccountThatHasGoneIsNotHandedOut() {
        val first = Any()
        val second = Any()
        var current: Any? = first
        val fetching = CompletableDeferred<Unit>()
        val cover = File("al-1.640")
        val handed = CoroutineScope(Dispatchers.Unconfined).async {
            CoverProvider.forWhoeverIsSignedIn({ current }, { current }) { fetching.await(); cover }
        }
        // The cover is being fetched from the first account's server. Someone else signs in.
        current = second
        fetching.complete(Unit)
        // The first account's album cover used to go to the second account's car.
        assertNull(handed.getCompleted())
        // Still theirs when it arrives: handed over.
        val still = CoroutineScope(Dispatchers.Unconfined).async { CoverProvider.forWhoeverIsSignedIn({ current }, { current }) { cover } }
        assertSame(cover, still.getCompleted())
        current = null
        val nobody = CoroutineScope(Dispatchers.Unconfined).async { CoverProvider.forWhoeverIsSignedIn({ current }, { current }) { cover } }
        assertNull(nobody.getCompleted())
    }

    @Test
    fun whatAConversionWasFoundToBeBelongsToItsAccount() {
        // Two servers, both with a "song 12". On the first it is a file the server sends as it
        // is (seeking is by byte); on the second, a fresh conversion (seeking is by time).
        val first = Conversions()
        val second = Conversions()
        val key = cacheKey("12", StreamForm(128, "mp3"))
        first.heads[key] = ConversionHead(audioStart = 4_096, bytesPerSecond = 9_500, ranged = true)
        // Kept for the life of the service, the first server's answer was the second's too:
        // its song 12 would have been asked for by byte, of a server that ignores bytes.
        assertNull(second.heads[key])
        assertTrue(first.writers.claim(key))
        assertTrue("one account's read kept another account's from being kept", second.writers.claim(key))
    }
}
