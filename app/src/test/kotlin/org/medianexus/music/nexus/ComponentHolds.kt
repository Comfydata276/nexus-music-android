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
 * Everything [NeverWaitsTest] does with the Nexus server's part of the app ([Nexus]: the shared
 * core's machine, driven), in one place: **puts it in the case's state before an entry point is
 * driven, acknowledged, and checks afterwards that it is still so.** That is the proof that the
 * entry point did not wait on it: it completed with it held throughout.
 *
 * Signed in, the machine asks the backend whether the session stands at every start and every
 * time the network comes back (`client::after_reach`): that request is the one held. To have one
 * out at the moment a step begins, the network is said to have come back ([Nexus.network]).
 *
 * (The suite is also run on the app as released, which has no such part, to fix the times it is
 * held to (E13a): then this file is replaced by one whose every function does nothing. See
 * `docs/TESTING.md`.)
 */
internal class ComponentHolds(private val broken: Broken, private val standIn: BackendStandIn, private val filesDir: File, private val until: (String, () -> Boolean) -> Unit) {
    /** What every hold waits on; down when the test ends. */
    private val holding = CountDownLatch(1)

    /** Each hold that has been taken, by name: 0 not entered yet, 1 its thread in it, 2 left. */
    private val held = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** A cold WORKERS_HELD start's hold at the machine's start has been acknowledged. */
    private var coldHeld = false

    /** A request held before it is sent ([Broken.WORKERS_HELD]): its state as [held]'s. */
    private val requestHeld = java.util.concurrent.atomic.AtomicInteger()

    /** The part put in the case's state by [stuck], last; and what it had sent then. */
    var client: Nexus? = null
        private set
    private var sentWhenHeld = 0

    private var escapedBefore = 0

    /** What had arrived under `/nexus/` before the app held here was started: a cold start's
     *  earlier process signed in at the same stand-in. */
    private var arrivedBefore = 0

    private val nexusFiles get() = File(filesDir, "nexus")

    /** The modes in which the stand-in takes the request and keeps it. */
    private val keeps get() = broken in setOf(Broken.BLACK_HOLE, Broken.STALLED, Broken.SATURATED, Broken.WORKERS_HELD, Broken.DISK_BLOCKED, Broken.DISK_FULL)

    /** Before the app's objects are made (each test's start, and a test's own cold start). */
    fun beforeAppStarts() {
        escapedBefore = Nexus.escaped.get()
        // (A request the stand-in holds stays held for the whole test, not the machine's 15 s.)
        Nexus.withinForTests = 600_000
        when (broken) {
            Broken.DISK_BLOCKED -> if (!nexusFiles.exists()) check(ProcessBuilder("mkfifo", nexusFiles.path).start().waitFor() == 0) { "no pipe made" }
            Broken.DISK_FULL -> if (!nexusFiles.exists()) nexusFiles.writeBytes(ByteArray(0))
            else -> {}
        }
    }

    /** For a cold start: with [Broken.WORKERS_HELD], the machine of the graph about to be made is
     *  held at its first instruction, so it never starts, until the test ends. */
    fun holdTheLauncherFromItsStart() {
        if (broken != Broken.WORKERS_HELD) return
        Nexus.gate = gate("machine from its start")
    }

    /** The app is in use (its screens in front). */
    fun inUse(graph: Graph) {
        graph.front.value = true
    }

    /**
     * The Nexus server's part of `graph`, once it is in the case's state, every hold acknowledged:
     * its check on the session kept by the stand-in, or answered where the stand-in answers; with
     * [Broken.WORKERS_HELD] that request held on its own thread before it is sent, the machine's
     * own thread held, and its own OkHttp scheduler.
     */
    fun stuck(graph: Graph) {
        inUse(graph)
        until("the Nexus server's part is made and started") { graph.nexus?.started == true }
        val client = graph.nexus!!
        this.client = client
        when {
            broken == Broken.WORKERS_HELD -> {
                Nexus.beforeAsk = { _ -> requestHeld.set(1); try { holding.await() } catch (_: InterruptedException) {} finally { requestHeld.set(2) } }
                client.network(true)
                until("the check is held before it is sent") { requestHeld.get() == 1 }
                sentWhenHeld = client.sent.get()
                hold("machine", client.workers[0])
                holdLock("its OkHttp scheduler", client.runner)
            }
            keeps -> {
                client.network(true)
                until("the check is kept at the stand-in") { ownRequestHeld(client) }
            }
            broken == Broken.HALF_CONNECTED -> {
                client.network(true)
                until("the check's answer is half read") { ownAnswerHalfRead(client) }
            }
            else -> {
                val before = standIn.nexusArrived.get()
                client.network(true)
                until("the check has had its answer") { standIn.nexusArrived.get() > before && client.calling.get() == 0 }
            }
        }
    }

    /** Checks that every hold taken is still in place, after `what` completed. */
    fun stillStuck(what: String) {
        for ((name, state) in held) assertTrue("$what: the hold of the $name had let go", state.get() != 2)
        if (coldHeld) assertEquals("$what: the machine not held at its start", 1, held["machine from its start"]?.get())
        val client = client ?: return
        when {
            broken == Broken.WORKERS_HELD -> {
                assertEquals("$what: the held request had let go", 1, requestHeld.get())
                assertEquals("$what: the held part sent something", sentWhenHeld, client.sent.get())
            }
            // (Saturated: answered "busy" after eight seconds, when it is asked again: see
            // [beforeStep]. Every other kept request is held to the end.)
            // (A step can end with a new ask on its way to the stand-in, sent and not yet arrived:
            // looked at until it has.)
            keeps && broken != Broken.SATURATED -> until("$what: the stand-in still keeps the check") { ownRequestHeld(client) }
            broken == Broken.HALF_CONNECTED -> until("$what: the check's answer is still being read") { ownAnswerHalfRead(client) }
            else -> {}
        }
    }

    /** The part's own request, and no other, held at the stand-in now: everything it has sent
     *  has arrived there, and one of them is being held. */
    private fun ownRequestHeld(client: Nexus) =
        client.calling.get() >= 1 && client.sent.get() >= 1 && standIn.nexusArrived.get() - arrivedBefore == client.sent.get() && standIn.nexusActive.get() >= 1

    private fun ownAnswerHalfRead(client: Nexus) =
        client.reading.get() >= 1 && client.sent.get() >= 1 && standIn.nexusArrived.get() - arrivedBefore == client.sent.get()

    /** Before a timed step: the hold acknowledged again. A saturated backend answers its check
     *  "busy" after eight seconds: the network is said to be back, and the check asked again is
     *  held as the first was. */
    fun beforeStep(what: String) {
        val client = client ?: return
        when {
            broken == Broken.WORKERS_HELD -> {}
            keeps -> {
                if (!ownRequestHeld(client)) client.network(true)
                until("$what: the check is kept at the stand-in") { ownRequestHeld(client) }
            }
            broken == Broken.HALF_CONNECTED -> until("$what: the check's answer is half read") { ownAnswerHalfRead(client) }
            else -> {}
        }
    }

    /** A graph made by a test, as `App.onCreate` does once `App.graph` is it. */
    fun appStarted(graph: Graph) {
        arrivedBefore = standIn.nexusArrived.get()
        graph.started()
    }

    /**
     * A cold start, the account just published: the part's state acknowledged before the entry
     * point's outcome is judged. With [Broken.WORKERS_HELD] the machine is held at its first
     * instruction; otherwise its check held as [stuck] holds it.
     */
    fun coldAcknowledged(graph: Graph) {
        if (broken == Broken.WORKERS_HELD) {
            until("the machine held at its start") { held["machine from its start"]?.get() == 1 }
            coldHeld = true
        } else {
            stuck(graph)
        }
    }

    /** After each test: nothing escaped the part; where its files would be is as left. */
    fun after() {
        try {
            assertEquals("a failure got past the Nexus server's part's own catches", escapedBefore, Nexus.escaped.get())
            when (broken) {
                Broken.DISK_BLOCKED -> assertTrue(java.nio.file.Files.getAttribute(nexusFiles.toPath(), "unix:mode").let { (it as Int) and 0xF000 == 0x1000 })
                Broken.DISK_FULL -> assertTrue(nexusFiles.isFile && nexusFiles.length() == 0L)
                else -> {}
            }
        } finally {
            Nexus.gate = null
            Nexus.beforeAsk = null
            Nexus.withinForTests = null
            holding.countDown()
        }
    }

    /** Lets every hold go. */
    fun release() = holding.countDown()

    private fun gate(name: String): () -> Unit {
        val state = java.util.concurrent.atomic.AtomicInteger()
        held[name] = state
        return { state.set(1); try { holding.await() } catch (_: InterruptedException) {} finally { state.set(2) } }
    }

    private fun hold(name: String, worker: ExecutorService) {
        val run = gate(name)
        worker.execute(run)
        until("the $name is held") { held[name]!!.get() == 1 }
    }

    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private fun holdLock(name: String, runner: TaskRunner) {
        val run = gate(name)
        Thread { synchronized(runner) { run() } }.apply { isDaemon = true }.start()
        until("the $name is held") { held[name]!!.get() == 1 }
    }
}
