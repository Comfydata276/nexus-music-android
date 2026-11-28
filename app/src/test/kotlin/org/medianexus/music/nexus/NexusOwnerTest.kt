package org.medianexus.music.nexus

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.Graph
import org.medianexus.music.graph
import org.medianexus.music.nexus.BackendStandIn.Broken
import org.medianexus.music.store.SecretBox
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Nexus server's client in the app's own graph: when it is made (never on the way the app
 * starts), whose it is (only the account published now), and what of it can fail without the
 * app noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class NexusOwnerTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun trust() = BackendStandIn.trust()

        @JvmStatic
        @AfterClass
        fun untrust() = BackendStandIn.untrust()
    }

    private val app = RuntimeEnvironment.getApplication()
    private val standIns = ArrayList<BackendStandIn>()
    private val graphs = ArrayList<Graph>()
    private val release = CountDownLatch(1)
    private var escapedBefore = 0

    @Before
    fun start() {
        escapedBefore = NexusClient.escaped.get()
        NexusBuild.forTests = true
        NexusLauncher.period = 100
        app.graph.front.value = true
        runBlocking { app.graph.session.restored() }
    }

    @After
    fun stop() {
        try {
            assertEquals("a failure got past the client's own catches", escapedBefore, NexusClient.escaped.get())
        } finally {
            release.countDown()
            NexusLauncher.gate = null
            NexusBuild.forTests = null
            NexusLauncher.period = NexusLauncher.HOURLY_MILLIS
            graphs.forEach { it.scope.cancel() }
            standIns.forEach { it.stop() }
        }
    }

    private fun standIn(answer: (mockwebserver3.RecordedRequest) -> mockwebserver3.MockResponse) =
        BackendStandIn(Broken.ABSENT).also { it.answer = answer; it.start(); standIns += it }

    private fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.nanoTime() < end) { "not so within thirty seconds: $what" }
            Thread.sleep(2)
        }
    }

    /**
     * Review P1, MUST CHANGE 3. A is found at its origin; its next `hello` is kept unanswered;
     * the launcher is held; A signs out and B signs in at another origin. **At the moment B is
     * published** the row says nothing of A's; A's late answer, a valid hello, is dropped; A
     * stops by itself and asks nothing more, with the launcher still held. Let go, the launcher
     * makes B's client, which finds B's own.
     */
    @Test
    fun anAccountChangeLeavesNothingOfTheOldAccountsBehind() {
        val late = CountDownLatch(1)
        val asks = AtomicInteger()
        val a = standIn { if (asks.incrementAndGet() >= 2) late.await(30, TimeUnit.SECONDS); BackendStandIn.hello() }
        val b = standIn { BackendStandIn.hello() }
        a.signIn(app, "ada")
        val sessionA = app.graph.session.state.value
        until("A found") { app.graph.nexusShown(sessionA) == a.shown }
        val clientA = app.graph.nexus!!.current!!
        until("A's next hello kept") { asks.get() >= 2 && clientA.calling }
        // The launcher held: nothing of its can help.
        val launcherHeld = CountDownLatch(1)
        app.graph.nexus!!.workers[0].execute { launcherHeld.countDown(); release.await() }
        assertTrue(launcherHeld.await(5, TimeUnit.SECONDS))
        app.graph.session.signOut()
        // B signs in. The row is judged against the session's own value, the one its heading is
        // drawn from: the moment the session says B, nothing of A's is shown, whether or not
        // the accounts have published B yet.
        app.graph.session.signIn(b.address, "bob", "a password")
        until("the session says B") { (app.graph.session.state.value as? org.medianexus.music.SessionState.SignedIn)?.account?.username == "bob" }
        val sessionB = app.graph.session.state.value
        assertNull("A's backend shown under B's heading", app.graph.nexusShown(sessionB))
        until("B published") { app.graph.accounts.state.value?.account?.username == "bob" }
        val accountB = app.graph.accounts.state.value!!
        assertNull("A's backend shown under B", app.graph.nexusShown(sessionB))
        // A's late answer comes, and is dropped; A stops by itself, and asks nothing more.
        late.countDown()
        until("A stopped by itself, the launcher still held") { clientA.isStopped }
        for (worker in clientA.workers) assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        assertNull("A's late answer shown under B", app.graph.nexusShown(sessionB))
        assertTrue("A's answer kept for B", app.graph.nexus!!.found.value?.owner !== accountB.api)
        Thread.sleep(500)
        assertEquals("A asked again after B was published", 2, asks.get())
        assertTrue(b.asked.none { "/nexus/" in it })
        // The launcher let go: B's own client, B's own backend.
        release.countDown()
        until("B found") { app.graph.nexusShown(sessionB) == b.shown }
        assertTrue(app.graph.nexus!!.current !== clientA)
    }

    /**
     * Ruling C: B's client starts without waiting for anything of A's. A found, so its pool
     * holds a connection whose letting go takes A's own scheduler's lock: that lock held by
     * another thread throughout, A is let go of and B signs in at another origin; B's client is
     * made and finds B's backend all the same. Released, A's threads end.
     */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    @Test
    fun theNextAccountsClientWaitsForNothingOfTheLastOnes() {
        val a = standIn { BackendStandIn.hello() }
        val b = standIn { BackendStandIn.hello() }
        a.signIn(app, "ada")
        until("A found") { app.graph.nexusShown(app.graph.session.state.value) == a.shown }
        val clientA = app.graph.nexus!!.current!!
        val held = CountDownLatch(1)
        Thread { synchronized(clientA.runner) { held.countDown(); release.await() } }.apply { isDaemon = true }.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        app.graph.session.signOut()
        b.signIn(app, "bob")
        until("B found, A's letting go held") { app.graph.nexusShown(app.graph.session.state.value) == b.shown }
        assertTrue(clientA.isStopped)
        release.countDown()
        for (executor in clientA.executors) assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    private class GatedBox : SecretBox {
        val open = CountDownLatch(1)
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray): ByteArray {
            open.await(30, TimeUnit.SECONDS)
            return sealed
        }
    }

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    /**
     * Review P1, MUST CHANGE 1: nothing of the client is made while the app's objects are made.
     * Made on the main thread over a saved sign-in held unread, a graph has no launcher; once the
     * account is published one is made, and not on the main thread.
     */
    @Test
    fun nothingOfTheClientIsMadeOnTheWayTheAppStarts() {
        val origin = standIn { BackendStandIn.hello() }
        val first = Graph(app, PlainBox).also { graphs += it }
        first.session.signIn(origin.address, "ada", "a password")
        until("signed in") { first.accounts.state.value != null }
        runBlocking { first.settings.flush() }
        first.scope.cancel()
        val box = GatedBox()
        val graph = Graph(app, box).also { graphs += it }
        graph.front.value = true
        assertNull("a launcher was made with the app's objects", graph.nexus)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("a launcher was made before the account was published", graph.nexus)
        box.open.countDown()
        until("published") { graph.accounts.state.value != null }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        // (The app has not said it has started: App.graph is not these objects yet.)
        assertNull("a launcher was made before the app had started", graph.nexus)
        graph.started()
        until("the launcher made, once published and started") { graph.nexus != null }
        assertTrue("made on the main thread", graph.nexus!!.madeOn !== Looper.getMainLooper().thread)
        until("found") { graph.nexusShown(graph.session.state.value) == origin.shown }
    }

    /** Review P1, MUST CHANGE 1: a failure in the launcher's own start ends there. The app goes
     *  on: the account is published, and another signs in. */
    @Test
    fun aFailureInsideTheClientNeverReachesTheApp() {
        val origin = standIn { BackendStandIn.hello() }
        NexusLauncher.gate = { throw IllegalStateException("the launcher's own failure") }
        origin.signIn(app, "ada")
        until("the launcher made") { app.graph.nexus != null }
        until("its failure caught by its own handler") { NexusClient.escaped.get() == escapedBefore + 1 }
        escapedBefore++ // (the one meant to happen)
        app.graph.session.signOut()
        origin.signIn(app, "bob")
        assertNotNull(app.graph.accounts.state.value)
        assertEquals("bob", app.graph.accounts.state.value!!.account.username)
    }

    /** Review P1, MUST CHANGE 4: with offline mode on by hand, from the start, nothing is asked;
     *  switched off, it asks. */
    @Test
    fun offlineByHandNothingIsAsked() {
        val origin = standIn { BackendStandIn.hello() }
        app.graph.settings.update { it.copy(offline = true) }
        origin.signIn(app, "ada")
        until("the client made") { app.graph.nexus?.current != null }
        val client = app.graph.nexus!!.current!!
        until("looked several times") { client.asked.get() >= 5 }
        assertTrue("asked with offline mode on", origin.asked.none { "/nexus/" in it })
        app.graph.settings.update { it.copy(offline = false) }
        until("found once offline mode is off") { app.graph.nexusShown(app.graph.session.state.value) == origin.shown }
        assertEquals(1, origin.asked.count { "/nexus/" in it })
    }

    /** SHOULD CHANGE 1: only while the app is in use. */
    @Test
    fun notInUseNothingIsAsked() {
        val origin = standIn { BackendStandIn.hello() }
        app.graph.front.value = false
        origin.signIn(app, "ada")
        until("the client made") { app.graph.nexus?.current != null }
        val client = app.graph.nexus!!.current!!
        until("looked several times") { client.asked.get() >= 5 }
        assertTrue("asked with the app not in use", origin.asked.none { "/nexus/" in it })
        app.graph.playing = true
        until("found once music plays") { app.graph.nexusShown(app.graph.session.state.value) == origin.shown }
        app.graph.playing = false
    }
}
