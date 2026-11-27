package org.medianexus.music.nexus

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.concurrent.TaskRunner
import okio.Buffer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.medianexus.music.core.Core
import org.medianexus.music.nexus.BackendStandIn.Broken

/**
 * The Nexus server's client for one account, against the stand-in origin (HTTPS, the
 * certificate made for "localhost"), with the shared library itself deciding, as the app does:
 * what it calls found, what it never follows, never asks and never sends twice, what it never
 * waits on, and how it lets go.
 */
class NexusClientTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun start() {
            BackendStandIn.trust()
            Core.warm()
        }

        @JvmStatic
        @AfterClass
        fun untrust() = BackendStandIn.untrust()
    }

    private val standIns = ArrayList<BackendStandIn>()
    private val clients = ArrayList<NexusClient>()
    private val cleanup = ArrayList<() -> Unit>()
    private var escapedBefore = 0

    @Before
    fun count() {
        escapedBefore = NexusClient.escaped.get()
    }

    @After
    fun stop() {
        try {
            assertEquals("a failure got past the client's own catches", escapedBefore, NexusClient.escaped.get())
        } finally {
            cleanup.asReversed().forEach { runCatching(it) }
            clients.forEach { it.stop() }
            standIns.forEach { it.stop() }
        }
    }

    private fun standIn(broken: Broken = Broken.ABSENT, https: Boolean = true, answer: ((mockwebserver3.RecordedRequest) -> MockResponse)? = null) =
        BackendStandIn(broken, https).also { it.answer = answer; it.start(); standIns += it }

    private class Made(val client: NexusClient, val shown: MutableStateFlow<NexusShown?>, val owner: Any) {
        @Volatile var live = true
        @Volatile var may = true
        val address: String? get() = shown.value?.takeIf { it.owner === owner }?.address
    }

    private fun client(address: String, every: Long = NexusLauncher.HOURLY_MILLIS, may: Boolean = true): Made {
        val owner = Any()
        val shown = MutableStateFlow<NexusShown?>(null)
        lateinit var made: Made
        val client = NexusClient(owner, address, "NexusMusic/test (Android)", every, live = { made.live }, mayAsk = { made.may }, shown = shown)
        made = Made(client, shown, owner).also { it.may = may }
        clients += client
        client.start()
        return made
    }

    private fun until(what: String, millis: Long = 15_000, done: () -> Boolean) {
        val end = System.nanoTime() + millis * 1_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not so in time: $what" }
            Thread.sleep(5)
        }
    }

    /** What one client finds at `address`, at its first look. */
    private fun first(address: String): String? {
        val made = client(address)
        until("looked") { made.client.asked.get() > 0 }
        return made.address
    }

    @Test
    fun aBackendThatAnswersHelloIsFoundAtTheMusicServersOrigin() {
        val origin = standIn { BackendStandIn.hello() }
        // (The music server under a path of its own: the backend is at the origin's root.)
        assertEquals(origin.shown, first(origin.address + "/navidrome/"))
        assertEquals(listOf("GET /nexus/api/hello"), origin.asked)
    }

    @Test
    fun noHeaderIsNeededOrTrustedOnlyTheStatusAndTheWholeBody() {
        val bare = standIn { MockResponse.Builder().code(200).body(BackendStandIn.HELLO).build() }
        assertEquals(bare.shown, first(bare.address))
        val html = standIn { MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body(BackendStandIn.HELLO).build() }
        assertEquals(html.shown, first(html.address))
        val created = standIn { MockResponse.Builder().code(201).setHeader("Content-Type", "application/json").body(BackendStandIn.HELLO).build() }
        assertNull(first(created.address))
    }

    @Test
    fun whatNavidromeAnswersUnderNexusIsNoBackend() {
        assertNull(first(standIn(Broken.ABSENT).address))
        for (answer in listOf(
            MockResponse.Builder().code(401).body("""{"error":"not signed in"}""").build(),
            MockResponse.Builder().code(404).body("Not Found").build(),
            MockResponse.Builder().code(502).body("Bad Gateway").build(),
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok"}}""").build(),
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html><body>Navidrome</body></html>").build(),
            MockResponse.Builder().code(200).build(),
        )) {
            val origin = standIn { answer }
            assertNull("${answer.code}", first(origin.address))
        }
    }

    @Test
    fun aRedirectIsAnAnswerNeverAPlaceToGo() {
        val origin = standIn { request ->
            if (request.url.encodedPath == "/nexus/api/hello") MockResponse.Builder().code(302).setHeader("Location", "/nexus/api/hello2").build() else BackendStandIn.hello()
        }
        assertNull(first(origin.address))
        assertEquals(listOf("GET /nexus/api/hello"), origin.asked)
    }

    /** Review P1, MUST CHANGE 4: OkHttp follows these up by itself, whatever is switched off. */
    @Test
    fun oneProbeIsOneRequestOnTheWireWhateverTheAnswer() {
        for (answer in listOf(
            MockResponse.Builder().code(503).setHeader("Retry-After", "0").body("busy").build(),
            MockResponse.Builder().code(408).body("timeout").build(),
            MockResponse.Builder().code(421).body("misdirected").build(),
            MockResponse.Builder().code(401).setHeader("WWW-Authenticate", "Basic realm=\"x\"").build(),
            MockResponse.Builder().code(307).setHeader("Location", "/nexus/api/hello").build(),
        )) {
            // The first answer is the refusal; any second would be a hello.
            var n = 0
            val origin = standIn { if (n++ == 0) answer else BackendStandIn.hello() }
            assertNull("${answer.code}", first(origin.address))
            assertEquals("${answer.code}: requests on the wire for one probe", 1, origin.asked.size)
        }
    }

    @Test
    fun aBodyLargerThanAHelloMayBeOrCutOffIsNoBackend() {
        val padded = BackendStandIn.HELLO.dropLast(1) + ",\"x\":\"" + "x".repeat(Core.HELLO_MAX_BYTES) + "\"}"
        assertNull(first(standIn { MockResponse.Builder().code(200).body(padded).build() }.address))
        assertNull(first(standIn { MockResponse.Builder().code(200).body(BackendStandIn.HELLO).onResponseBody(SocketEffect.CloseSocket()).build() }.address))
    }

    @Test
    fun theBodyOfAnythingButA200IsNotWaitedFor() {
        // A redirect whose body trickles for a minute: answered as soon as its head is in.
        val origin = standIn { MockResponse.Builder().code(302).setHeader("Location", "/app/").throttleBody(1, 1, TimeUnit.SECONDS).body("x".repeat(60)).build() }
        val made = client(origin.address)
        until("answered with its head alone", millis = 5_000) { made.client.asked.get() > 0 }
        assertNull(made.address)
    }

    @Test
    fun theBodyIsHandedToTheCoreAsReadToItsEndOrOneBytePast() {
        val most = Core.HELLO_MAX_BYTES
        val (exact, whole) = NexusClient.read(Buffer().write(ByteArray(most)))
        assertEquals(most, exact.size)
        assertTrue(whole)
        val (over, _) = NexusClient.read(Buffer().write(ByteArray(most * 3)))
        assertEquals(most + 1, over.size)
        assertEquals("too_large", Core.nexusHello(200, over, true).why)
        assertEquals("cut", Core.nexusHello(200, BackendStandIn.HELLO.toByteArray().copyOf(20), false).why)
        assertTrue(Core.nexusHello(200, BackendStandIn.HELLO.toByteArray(), true).backend)
    }

    @Test
    fun overPlainHttpNothingIsAsked() {
        val plain = standIn(https = false) { BackendStandIn.hello() }
        assertNull(first(plain.address))
        assertTrue("the backend was asked over plain HTTP", plain.asked.isEmpty())
        assertNull(first("not an address"))
        assertNull(NexusClient.origin("http://music.example.org"))
        assertEquals("https://music.example.org:8443/".toHttpUrl(), NexusClient.origin("https://music.example.org:8443/navidrome?x=1#y"))
        assertEquals("music.example.org", NexusClient.shown(NexusClient.origin("https://music.example.org/")!!))
        assertEquals("[::1]:8443", NexusClient.shown(NexusClient.origin("https://[::1]:8443/")!!))
    }

    /** Review P1, MUST CHANGE 4 and SHOULD CHANGE 1: offline by hand, the home network not
     *  allowed, the app not in use: nothing is asked, and it asks once it may. */
    @Test
    fun whileItMayNotNothingIsAsked() {
        val origin = standIn { BackendStandIn.hello() }
        val made = client(origin.address, every = 50, may = false)
        until("looked several times") { made.client.asked.get() >= 5 }
        assertTrue("asked while it may not", origin.asked.isEmpty())
        assertNull(made.address)
        made.may = true
        until("found once it may") { made.address == origin.shown }
        assertEquals(1, origin.asked.size)
    }

    @Test
    fun itAsksAgainEachPeriod() {
        val origin = standIn { BackendStandIn.hello() }
        val made = client(origin.address, every = 100)
        until("asked three times") { origin.asked.size >= 3 }
        assertEquals(origin.shown, made.address)
    }

    /** Review P1, MUST CHANGE 2: with the library not loaded it asks nothing and never waits
     *  on the load (its lock held by another thread throughout). */
    @Test
    fun withTheCoreNotLoadedItAsksNothingAndNeverWaitsOnTheLoad() {
        val origin = standIn { BackendStandIn.hello() }
        val ready = Core::class.java.getDeclaredField("ready").apply { isAccessible = true }
        val lock = Core::class.java.getDeclaredField("loadLock").apply { isAccessible = true }.get(Core)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread { synchronized(lock) { held.countDown(); release.await() } }.apply { isDaemon = true }.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        ready.setBoolean(Core, false)
        cleanup += { ready.setBoolean(Core, true); release.countDown() }
        val made = client(origin.address)
        until("looked, with the load's lock held") { made.client.asked.get() > 0 }
        assertNull(made.address)
        assertTrue("asked with the library not loaded", origin.asked.isEmpty())
    }

    /** Review P1, MUST CHANGE 2: its OkHttp scheduler is its own. With OkHttp's process-wide
     *  one locked by another thread throughout, it still connects, asks and finds. */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    @Test
    fun itNeedsNothingOfOkHttpsSharedScheduler() {
        val origin = standIn { BackendStandIn.hello() }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread { synchronized(TaskRunner.INSTANCE) { held.countDown(); release.await() } }.apply { isDaemon = true }.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        cleanup += { release.countDown() }
        val made = client(origin.address)
        until("found with the shared scheduler locked") { made.address == origin.shown }
        // And the client's own scheduler is the one its client and pool were given.
        val transport = made.client.transport!!
        val runner = okhttp3.OkHttpClient::class.java.getMethod("getTaskRunner\$okhttp").invoke(transport)
        assertTrue(runner === made.client.runner)
        assertTrue(runner !== TaskRunner.INSTANCE)
    }

    /** Review P1, MUST CHANGE 1: let go while connecting (a loopback port whose queue is
     *  full, so the connect never completes), and while in TLS's handshake with a peer that
     *  says nothing: it returns, its threads end, and nothing escapes. */
    @Test
    fun letGoWhileConnectingOrInTheHandshakeNothingEscapes() {
        // A listener that accepts nothing, its queue filled.
        val full = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val fillers = List(8) { runCatching { Socket().apply { connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), full.localPort), 200) } }.getOrNull() }
        // A listener that accepts, and never says a word.
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val taken = java.util.Collections.synchronizedList(ArrayList<Socket>())
        Thread { while (true) taken += try { silent.accept() } catch (_: java.io.IOException) { break } }.apply { isDaemon = true }.start()
        cleanup += { full.close(); silent.close(); fillers.forEach { runCatching { it?.close() } }; taken.forEach { runCatching { it.close() } } }
        for (port in listOf(full.localPort, silent.localPort)) {
            val made = client("https://localhost:$port")
            until("under way") { made.client.calling }
            Thread.sleep(200)
            made.client.stop()
            assertTrue(made.client.isStopped)
            for (worker in made.client.workers) assertTrue("a thread of its own is still running", worker.awaitTermination(10, TimeUnit.SECONDS))
            assertNull(made.address)
        }
    }

    /** Review P1, MUST CHANGE 3: its account no longer published, a late answer is dropped and
     *  it stops by itself, with nobody telling it to. */
    @Test
    fun aLateAnswerForAnAccountNoLongerPublishedIsDroppedAndItStops() {
        val late = CountDownLatch(1)
        val origin = standIn { late.await(); BackendStandIn.hello() }
        cleanup += { late.countDown() }
        val made = client(origin.address, every = 50)
        until("under way") { made.client.calling && origin.asked.size == 1 }
        made.live = false
        late.countDown()
        until("stopped by itself") { made.client.isStopped }
        for (worker in made.client.workers) assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        assertNull("a late answer was shown", made.shown.value)
        assertEquals(1, origin.asked.size)
    }

    @Test
    fun letGoWhileHeldItReturnsBeforeTheHoldEndsAndItsThreadsEnd() {
        for (broken in listOf(Broken.STALLED, Broken.BLACK_HOLE, Broken.SATURATED, Broken.HALF_CONNECTED)) {
            val origin = standIn(broken)
            val made = client(origin.address)
            until("$broken: held") { made.client.calling && (origin.nexusActive.get() > 0 || broken == Broken.HALF_CONNECTED && made.client.sent.get() > 0) }
            made.client.stop()
            // Returned with the stand-in still holding the request: it waited for nothing.
            assertTrue("$broken: the hold ended before stop returned", origin.nexusActive.get() > 0 || broken != Broken.STALLED)
            assertTrue(made.client.isStopped)
            for (worker in made.client.workers) assertTrue("$broken: a thread of its own is still running", worker.awaitTermination(10, TimeUnit.SECONDS))
            assertNull(made.shown.value)
        }
    }
}
