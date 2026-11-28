package org.medianexus.music

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.playback.StandInServer
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountDirs
import org.medianexus.music.store.accountKey
import org.medianexus.music.sync.Chosen
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Whose the shared choices are, as one account gives way to another: **through the app's own
 * graph**, its session, its queue of taps and its files, not past them. (`Belongs` and
 * `Chosen` have their own tests of the rules; these are of the handing over.)
 *
 * Each test makes a graph of its own over the app's folders, with a password box that
 * needs no Keystore, so that a saved sign-in can be started from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class GraphHandoffTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = StandInServer()
    private val graphs = ArrayList<Graph>()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        graphs.forEach { it.scope.cancel() }
        server.stop()
    }

    private fun graph() = Graph(app, PlainBox).also { graphs += it }

    /** The person's folder, as a sign-in leaves it (`SignedInForTests`): who they are to the
     *  Nexus server. */
    private fun key(username: String) = org.medianexus.music.nexus.Folders.personOf(org.medianexus.music.nexus.KeptAccount(TEST_NEXUS, TEST_BACKEND, "$username@example.org"))
    private fun dataOf(key: String) = accountDirs(app.filesDir, app.cacheDir, key).data

    /** Signs in, and returns once the account's objects are there (or not within `millis`). */
    private fun signIn(graph: Graph, username: String, millis: Long = 30_000): Boolean {
        graph.session.signIn(server.address, username, "a password")
        return signedIn(graph, username, millis)
    }

    private fun signedIn(graph: Graph, username: String, millis: Long = 30_000): Boolean = runBlocking {
        withTimeoutOrNull(millis) {
            val now = graph.session.state.first { it is SessionState.SignedIn && it.account.username == username } as SessionState.SignedIn
            graph.accounts.state.first { it?.api === now.api }
        } != null
    }

    private fun signOut(graph: Graph) = runBlocking { withTimeout(30_000) { graph.session.signOut().join() } }

    @Test
    fun aTapStillOnItsWayWhenAnotherAccountSignsInIsCarriedOutForItsOwnAccountFirst() {
        val graph = graph()
        assertTrue(signIn(graph, "ada"))
        // Ada pins a playlist; the phone is busy, and the tap is still waiting its turn at
        // the settings' file...
        val busy = CountDownLatch(1)
        graph.settings.waitingToCommit = { busy.await(20, TimeUnit.SECONDS) }
        graph.choose(Chosen.Did.Pin("adas", true))
        // ...when Ada signs out and Bob signs in. Bob is not signed in while a tap made
        // under Ada is still on its way: taken on first, he would get it, or Ada lose it.
        graph.session.signOut()
        graph.session.signIn(server.address, "bob", "a password")
        assertFalse("Bob was signed in with Ada's tap still on its way", signedIn(graph, "bob", millis = 3_000))
        busy.countDown()
        graph.settings.waitingToCommit = null
        assertTrue(signedIn(graph, "bob"))
        // Bob has nothing of Ada's.
        assertEquals(key("bob"), graph.settings.value.sharedFor)
        assertTrue(graph.settings.value.pinnedPlaylists.isEmpty())
        assertFalse(graph.settings.value.choices.toString().contains("adas"))
        // And Ada, signing in again, finds her pin: shown, and still waiting to be shared.
        signOut(graph)
        assertTrue(signIn(graph, "ada"))
        assertEquals(listOf("adas"), graph.settings.value.pinnedPlaylists)
        assertTrue(graph.settings.value.choices.toString().contains("adas"))
    }

    @Test
    fun putAwayChoicesThatReadAndAreNoChoicesAreKeptAsideAndTheAccountSignsIn() {
        val graph = graph()
        assertTrue(signIn(graph, "ada"))
        graph.choose(Chosen.Did.Pin("adas", true))
        signOut(graph)
        // What was put away for Bob, the last time he gave way, reads and is not what this
        // app wrote (cut short, say).
        val bobs = File(dataOf(key("bob")), "in-step.json")
        bobs.parentFile!!.mkdirs()
        val spoilt = "{\"pinnedPlaylists\":[\"bobs\""
        bobs.writeText(spoilt)
        // He signs in all the same, with no choices; and what was there is kept beside its
        // place, where nothing is ever written again.
        assertTrue(signIn(graph, "bob"))
        assertEquals(key("bob"), graph.settings.value.sharedFor)
        assertTrue(graph.settings.value.pinnedPlaylists.isEmpty())
        val aside = File(bobs.path + ".damaged")
        assertEquals(spoilt, aside.readText())
        assertTrue(graph.diagnostics.entries().any { "kept beside" in it.text })
        // Bob pins something; Ada signs in (Bob's choices are put away, in the file's own
        // place); Bob again: his pin is back, and what was kept aside is as it was.
        graph.choose(Chosen.Did.Pin("bobs-new", true))
        signOut(graph)
        assertTrue(signIn(graph, "ada"))
        assertEquals(listOf("adas"), graph.settings.value.pinnedPlaylists)
        signOut(graph)
        assertTrue(signIn(graph, "bob"))
        assertEquals(listOf("bobs-new"), graph.settings.value.pinnedPlaylists)
        assertEquals(spoilt, aside.readText())
    }

    @Test
    fun theSavedPasswordStartTakesTheAccountOnBeforeAnyoneIsSignedInOrSignsNobodyIn() {
        // Ada is signed in, and what the sign-in left is kept.
        val first = graph()
        assertTrue(signIn(first, "ada"))
        // The choices in the settings are another account's (a change of account that did
        // not finish), and they can't be put away: the disk won't take the file.
        first.settings.update { it.copy(sharedFor = "someone-else", pinnedPlaylists = listOf("theirs")) }
        runBlocking { assertTrue(first.settings.flush()) }
        first.scope.cancel()
        val away = File(dataOf("someone-else"), "in-step.json")
        away.mkdirs()
        // The app starts, with Ada's saved password. She is not signed in over them: the
        // sign-in screen says why, and the choices are still the other account's.
        val second = graph()
        assertEquals(SessionState.SignedOut, runBlocking { withTimeout(30_000) { second.session.restored() } })
        assertTrue(second.session.signIn.value.choicesNotPutAway)
        assertNull(second.accounts.state.value)
        assertEquals("someone-else" to listOf("theirs"), second.settings.value.let { it.sharedFor to it.pinnedPlaylists })
        second.scope.cancel()
        // Once they can be put away, the next start signs her in, with the choices hers.
        away.delete()
        val third = graph()
        assertTrue(runBlocking { withTimeout(30_000) { third.session.restored() } } is SessionState.SignedIn)
        assertTrue(signedIn(third, "ada"))
        assertEquals(key("ada"), third.settings.value.sharedFor)
        assertTrue(third.settings.value.pinnedPlaylists.isEmpty())
        assertTrue(away.readText().contains("theirs"))
    }
}
