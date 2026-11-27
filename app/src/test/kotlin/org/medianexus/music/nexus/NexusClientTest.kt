package org.medianexus.music.nexus

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.medianexus.music.core.Core
import org.medianexus.music.nexus.BackendStandIn.Broken

/**
 * The Nexus server's client for one account, against the stand-in origin (HTTPS, the
 * certificate made for "localhost"): what it calls "found", what it never follows, what it
 * never asks, and how it lets go. With the shared library itself deciding, as the app does.
 */
class NexusClientTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun trust() = BackendStandIn.trust()

        @JvmStatic
        @AfterClass
        fun untrust() = BackendStandIn.untrust()
    }

    private val standIns = ArrayList<BackendStandIn>()
    private val clients = ArrayList<NexusClient>()

    @After
    fun stop() {
        clients.forEach { it.stop() }
        standIns.forEach { it.stop() }
    }

    private fun standIn(broken: Broken = Broken.ABSENT, https: Boolean = true, answer: ((mockwebserver3.RecordedRequest) -> MockResponse)? = null) =
        BackendStandIn(broken, https).also { it.answer = answer; it.start(); standIns += it }

    /** What one client says, in order, for the account at `address`. */
    private class Told {
        val said = CopyOnWriteArrayList<NexusFound>()
    }

    private fun client(address: String, every: Long = NexusLauncher.HOURLY_MILLIS, told: Told = Told()): Pair<NexusClient, Told> {
        val client = NexusClient(address, "NexusMusic/test (Android)", every).also { clients += it }
        client.start { told.said += it }
        return client to told
    }

    private fun until(what: String, millis: Long = 15_000, done: () -> Boolean) {
        val end = System.nanoTime() + millis * 1_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not so in time: $what" }
            Thread.sleep(5)
        }
    }

    private fun first(address: String): NexusFound {
        val (client, told) = client(address)
        until("asked") { client.asked.get() > 0 }
        return told.said.single()
    }

    @Test
    fun aBackendThatAnswersHelloIsFoundAtTheMusicServersOrigin() {
        val origin = standIn { BackendStandIn.hello() }
        // (The music server under a path of its own: the backend is at the origin's root.)
        assertEquals(NexusFound.At(origin.shown), first(origin.address + "/navidrome/"))
        assertEquals(listOf("GET /nexus/api/hello"), origin.asked)
    }

    @Test
    fun noHeaderIsNeededOrTrustedOnlyTheStatusAndTheWholeBody() {
        // A hello with no Content-Type, or the wrong one, is still the backend's.
        val bare = standIn { MockResponse.Builder().code(200).body(BackendStandIn.HELLO).build() }
        assertEquals(NexusFound.At(bare.shown), first(bare.address))
        val html = standIn { MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body(BackendStandIn.HELLO).build() }
        assertEquals(NexusFound.At(html.shown), first(html.address))
        // Another status, with a hello's body: not the backend.
        val created = standIn { MockResponse.Builder().code(201).setHeader("Content-Type", "application/json").body(BackendStandIn.HELLO).build() }
        assertEquals(NexusFound.None, first(created.address))
    }

    @Test
    fun whatNavidromeAnswersUnderNexusIsNoBackend() {
        // Measured on Navidrome 0.64.2 (E1): a redirect to its web app for a GET.
        assertEquals(NexusFound.None, first(standIn(Broken.ABSENT).address))
        // "Not signed in", a proxy's own answers, the music server's JSON, a web page.
        for (answer in listOf(
            MockResponse.Builder().code(401).body("""{"error":"not signed in"}""").build(),
            MockResponse.Builder().code(404).body("Not Found").build(),
            MockResponse.Builder().code(502).body("Bad Gateway").build(),
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok"}}""").build(),
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html><body>Navidrome</body></html>").build(),
            MockResponse.Builder().code(200).build(),
        )) {
            val origin = standIn { answer }
            assertEquals("${answer.code}", NexusFound.None, first(origin.address))
        }
    }

    @Test
    fun aRedirectIsAnAnswerNeverAPlaceToGo() {
        // Even to a hello at the same origin.
        val origin = standIn { request ->
            if (request.url.encodedPath == "/nexus/api/hello") {
                MockResponse.Builder().code(302).setHeader("Location", "/nexus/api/hello2").build()
            } else {
                BackendStandIn.hello()
            }
        }
        assertEquals(NexusFound.None, first(origin.address))
        assertEquals(listOf("GET /nexus/api/hello"), origin.asked)
    }

    @Test
    fun aBodyLargerThanAHelloMayBeOrCutOffIsNoBackend() {
        // A hello padded past the most the core reads.
        val padded = BackendStandIn.HELLO.dropLast(1) + ",\"x\":\"" + "x".repeat(Core.HELLO_MAX_BYTES) + "\"}"
        val large = standIn { MockResponse.Builder().code(200).body(padded).build() }
        assertEquals(NexusFound.None, first(large.address))
        // A hello whose connection is cut half way through its body.
        val cut = standIn { MockResponse.Builder().code(200).body(BackendStandIn.HELLO).onResponseBody(SocketEffect.CloseSocket()).build() }
        assertEquals(NexusFound.None, first(cut.address))
    }

    @Test
    fun theBodyIsHandedToTheCoreAsReadToItsEndOrOneBytePast() {
        val most = Core.HELLO_MAX_BYTES
        // Exactly the most: read whole.
        val (exact, whole) = NexusClient.read(Buffer().write(ByteArray(most)))
        assertEquals(most, exact.size)
        assertTrue(whole)
        // Larger: one byte past, and no more read.
        val source = Buffer().write(ByteArray(most * 3))
        val (over, _) = NexusClient.read(source)
        assertEquals(most + 1, over.size)
        assertEquals("too_large", Core.nexusHello(200, over, true).why)
        // A failure before the end is said, and the core takes it as cut.
        assertEquals("cut", Core.nexusHello(200, BackendStandIn.HELLO.toByteArray().copyOf(20), false).why)
        assertTrue(Core.nexusHello(200, BackendStandIn.HELLO.toByteArray(), true).backend)
    }

    @Test
    fun overPlainHttpNothingIsAsked() {
        val plain = standIn(https = false) { BackendStandIn.hello() }
        assertEquals(NexusFound.None, first(plain.address))
        assertTrue("the backend was asked over plain HTTP", plain.asked.isEmpty())
        // Nor anything that is not a web address.
        assertEquals(NexusFound.None, first("not an address"))
        assertEquals(null, NexusClient.origin("http://music.example.org"))
        assertEquals("https://music.example.org:8443/".toHttpUrl(), NexusClient.origin("https://music.example.org:8443/navidrome?x=1#y"))
        assertEquals("music.example.org", NexusClient.shown(NexusClient.origin("https://music.example.org/")!!))
    }

    @Test
    fun itAsksAgainEveryPeriod() {
        val origin = standIn { BackendStandIn.hello() }
        val (client, told) = client(origin.address, every = 100)
        until("asked three times") { client.asked.get() >= 3 }
        assertTrue(told.said.all { it == NexusFound.At(origin.shown) })
    }

    @Test
    fun letGoWhileStalledItReturnsAtOnceAndItsThreadsEnd() {
        for (broken in listOf(Broken.STALLED, Broken.BLACK_HOLE, Broken.SATURATED, Broken.HALF_CONNECTED)) {
            val origin = standIn(broken)
            val (client, told) = client(origin.address)
            until("$broken: held") { origin.nexusHeld > 0 }
            val began = System.nanoTime()
            client.stop()
            val took = (System.nanoTime() - began) / 1_000_000
            assertTrue("$broken: letting go took $took ms", took < 100)
            assertTrue(client.isStopped)
            // It stops by itself afterwards: every thread of its own ends, and it says nothing.
            for (worker in client.workers) assertTrue("$broken: a thread of its own is still running", worker.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue("$broken: said something after being let go: ${told.said}", told.said.isEmpty())
        }
    }
}
