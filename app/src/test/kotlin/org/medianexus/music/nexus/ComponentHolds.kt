package org.medianexus.music.nexus

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import okhttp3.internal.concurrent.TaskRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.medianexus.music.Graph
import org.medianexus.music.nexus.BackendStandIn.Broken

/**
 * Everything [NeverWaitsTest] does with the Nexus server's client, in one place: **puts it in
 * the case's state before an entry point is driven, acknowledged, and checks afterwards that it
 * is still so.** That is the proof that the entry point did not wait on it: it completed with
 * the client held throughout.
 *
 * (The suite is also run on the app as released, which has no such client, to fix the times
 * it is held to (E13a): then this file is replaced by one whose every function does nothing.
 * See `docs/TESTING.md`.)
 */
internal class ComponentHolds(private val broken: Broken, private val standIn: BackendStandIn, private val filesDir: File, private val until: (String, () -> Boolean) -> Unit) {
    /** What every hold waits on; down when the test ends. */
    private val holding = CountDownLatch(1)

    /** Each hold that has been taken, by name: 0 not entered yet, 1 its thread in it, 2 left. */
    private val held = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private val seen = HashSet<NexusClient>()

    /** A cold WORKERS_HELD start's launcher hold has been acknowledged. */
    private var coldHeld = false

    /** Each client's worker, held before it asks ([Broken.WORKERS_HELD]): its state as [held]'s. */
    private val workerHeld = java.util.concurrent.ConcurrentHashMap<NexusClient, java.util.concurrent.atomic.AtomicInteger>()

    /** The client put in the case's state by [stuck], last. */
    var client: NexusClient? = null
        private set

    private var escapedBefore = 0

    private val nexusFiles get() = File(filesDir, "nexus")

    /** The modes in which the stand-in takes the backend's `hello` and keeps it. */
    private val keeps get() = broken in setOf(Broken.BLACK_HOLE, Broken.STALLED, Broken.SATURATED, Broken.WORKERS_HELD, Broken.DISK_BLOCKED, Broken.DISK_FULL)

    /** Before the app's objects are made (each test's start, and a test's own cold start). */
    fun beforeAppStarts() {
        escapedBefore = NexusClient.escaped.get()
        // (The client exists only in a build made with it: these tests switch it on.)
        NexusBuild.forTests = true
        NexusLauncher.period = 200
        // (Before any client exists: none may ask before its worker is held.)
        if (broken == Broken.WORKERS_HELD) NexusClient.beforeAsk = { client ->
            val state = workerHeld.getOrPut(client) { java.util.concurrent.atomic.AtomicInteger() }
            state.set(1)
            try { holding.await() } catch (_: InterruptedException) {} finally { state.set(2) }
        }
        when (broken) {
            Broken.DISK_BLOCKED -> if (!nexusFiles.exists()) check(ProcessBuilder("mkfifo", nexusFiles.path).start().waitFor() == 0) { "no pipe made" }
            Broken.DISK_FULL -> if (!nexusFiles.exists()) nexusFiles.writeBytes(ByteArray(0))
            else -> {}
        }
    }

    /**
     * For a cold start: with [Broken.WORKERS_HELD], the launcher of the graph about to be made is
     * held at its first instruction, so no client can be made at all, until the test ends.
     */
    fun holdTheLauncherFromItsStart() {
        if (broken != Broken.WORKERS_HELD) return
        // (Not entered until an account is published and the launcher made: until then nothing
        // of the client exists at all.)
        NexusLauncher.gate = gate("launcher from its start")
    }

    /** The app is in use (its screens in front): the client may ask. */
    fun inUse(graph: Graph) {
        graph.front.value = true
    }

    /**
     * The client of the account `graph` has published, once it is in the case's state, every
     * hold acknowledged: its `hello` kept by the stand-in, or answered where the stand-in answers;
     * with [Broken.WORKERS_HELD] its worker held before it asks, its dispatcher's thread, its own
     * OkHttp scheduler and its launcher held as well.
     */
    fun stuck(graph: Graph) {
        inUse(graph)
        var made: NexusClient? = null
        until("the backend's client is made for the account") { graph.nexus?.current?.takeIf { it !in seen }?.also { made = it } != null }
        val client = made!!
        seen += client
        this.client = client
        when {
            broken == Broken.WORKERS_HELD -> {
                until("the client's worker is held before it asks") { workerHeld[client]?.get() == 1 }
                hold("calls", client.workers[1])
                holdLock("its OkHttp scheduler", client.runner)
                holdTheLauncher(graph)
            }
            // (Its own requests, and the stand-in's: no other client's request is counted.)
            keeps -> until("the backend's client is kept at the stand-in") { ownRequestHeld(client) }
            broken == Broken.HALF_CONNECTED -> until("the backend's client is reading half a hello") { ownAnswerHalfRead(client) }
            else -> until("the backend's client has had its answer") { client.asked.get() > 0 && !client.calling }
        }
    }

    /** Checks that every hold taken is still in place, after `what` completed. */
    fun stillStuck(what: String) {
        for ((name, state) in held) assertTrue("$what: the hold of the client's $name had let go", state.get() != 2)
        // (A cold WORKERS_HELD start, once acknowledged, is held, not merely not yet entered.)
        if (coldHeld) assertEquals("$what: the launcher not held at its start", 1, held["launcher from its start"]?.get())
        val client = client ?: return
        if (broken == Broken.WORKERS_HELD) assertEquals("$what: the client's worker had let go", 1, workerHeld[client]?.get())
        when {
            broken == Broken.WORKERS_HELD -> assertEquals("$what: the held client sent something", 0, client.sent.get())
            keeps -> assertTrue("$what: the stand-in no longer keeps the client's hello", ownRequestHeld(client))
            broken == Broken.HALF_CONNECTED -> assertTrue("$what: the client is no longer reading its answer", ownAnswerHalfRead(client))
            else -> {}
        }
    }

    /** This test's client's own request, and no other, held at the stand-in now: it has sent one
     *  request, the stand-in has had one under `/nexus/`, and is holding it. */
    private fun ownRequestHeld(client: NexusClient) =
        client.calling && client.sent.get() >= 1 && standIn.nexusArrived.get() == client.sent.get() && standIn.nexusActive.get() >= 1

    private fun ownAnswerHalfRead(client: NexusClient) =
        client.reading && client.sent.get() >= 1 && standIn.nexusArrived.get() == client.sent.get()

    /**
     * Before a timed step: the client's hold acknowledged again. (Its request can outlast a
     * test's setup only by the client's own read limit, ten seconds: when it has given up it
     * asks again, a fifth of a second later in the tests, and that request is held as the first
     * was. Every request at the stand-in is still this client's.)
     */
    fun beforeStep(what: String) {
        val client = client ?: return
        when {
            broken == Broken.WORKERS_HELD -> {}
            keeps -> until("$what: the backend's client is kept at the stand-in") { ownRequestHeld(client) }
            broken == Broken.HALF_CONNECTED -> until("$what: the backend's client is reading half a hello") { ownAnswerHalfRead(client) }
            else -> {}
        }
    }

    /** A graph made by a test, as `App.onCreate` does once `App.graph` is it. */
    fun appStarted(graph: Graph) = graph.started()

    /**
     * A cold start, the account just published: the client's state acknowledged before the
     * entry point's outcome is judged. With [Broken.WORKERS_HELD] the launcher is held at its
     * first instruction (it can only be made once the account is published); otherwise the
     * account's client is made and held as [stuck] holds it.
     */
    fun coldAcknowledged(graph: Graph) {
        if (broken == Broken.WORKERS_HELD) {
            until("the launcher held at its start") { held["launcher from its start"]?.get() == 1 }
            coldHeld = true
        } else {
            stuck(graph)
        }
    }

    /** After each test: nothing escaped the client; where its files would be is as left. */
    fun after() {
        try {
            assertEquals("a failure got past the client's own catches", escapedBefore, NexusClient.escaped.get())
            when (broken) {
                Broken.DISK_BLOCKED -> assertTrue(java.nio.file.Files.getAttribute(nexusFiles.toPath(), "unix:mode").let { (it as Int) and 0xF000 == 0x1000 })
                Broken.DISK_FULL -> assertTrue(nexusFiles.isFile && nexusFiles.length() == 0L)
                else -> {}
            }
        } finally {
            NexusLauncher.gate = null
            NexusClient.beforeAsk = null
            NexusBuild.forTests = null
            NexusLauncher.period = NexusLauncher.HOURLY_MILLIS
            holding.countDown()
        }
    }

    /** What the row says for the account `graph` has published now: never a backend here. */
    fun shown(graph: Graph): String? = graph.nexusShown(graph.session.state.value)

    /** Lets every hold go (a test that needs the launcher again). */
    fun release() = holding.countDown()

    /** Holds the launcher of `graph` too (once): no account change reaches the client. */
    fun holdTheLauncher(graph: Graph) {
        if (!held.containsKey("launcher")) hold("launcher", graph.nexus!!.workers[0])
    }

    /** After an account change made with every hold in place: let go, the old client is let
     *  go of, and every thread of its own ends. */
    fun oldClientLetGo() {
        val old = client ?: return
        holding.countDown()
        until("the old account's client is let go of") { old.isStopped }
        for (worker in old.workers) assertTrue("a thread of the old client's is still running", worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun gate(name: String): () -> Unit {
        val state = java.util.concurrent.atomic.AtomicInteger()
        held[name] = state
        return { state.set(1); try { holding.await() } catch (_: InterruptedException) {} finally { state.set(2) } }
    }

    private fun hold(name: String, worker: ExecutorService) {
        val run = gate(name)
        worker.execute(run)
        until("the client's $name is held") { held[name]!!.get() == 1 }
    }

    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private fun holdLock(name: String, runner: TaskRunner) {
        val run = gate(name)
        Thread { synchronized(runner) { run() } }.apply { isDaemon = true }.start()
        until("the client's $name is held") { held[name]!!.get() == 1 }
    }
}
