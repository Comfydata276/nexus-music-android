package org.medianexus.music.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * **Linking's two lines, "music" and "relay", through the real library and the real
 * client** (`CoreHttp`, and `native/src/wire.rs` below it), against stand-in servers on
 * this machine. Each reaches one place, at every step: a request for anywhere else is not
 * sent, an answer that sends it on is not followed, and the relay is never the place the
 * music server or Lidarr is at. What goes with every request on these lines is the
 * person's sign-in on the one and the relay's key and a place's token on the other.
 */
class LinkLinesTest {
    private val closing = mutableListOf<StandIn>()

    @get:Rule
    val noLongerThan: Timeout = Timeout.builder().withTimeout(120, TimeUnit.SECONDS).build()

    private fun standIn(answer: (StandIn.Asked, StandIn.Reply) -> Unit) = StandIn(answer).also { closing += it }

    @Before
    fun standInsAreHere() {
        CoreHttp.publicOnly = false
    }

    @After
    fun tidy() {
        CoreHttp.publicOnly = true
        CoreHttp.linkMusic = { null }
        CoreHttp.linkRelay = { null }
        CoreHttp.linkClient = { null }
        closing.forEach { it.close() }
    }

    private fun probe(probe: Core.Probe): Outcome<Core.Probed> = runBlocking { withTimeout(60_000) { Core.wireProbe(probe) } }

    private fun <T> Outcome<T>.got(): T = (this as? Outcome.Got<T>)?.value ?: throw AssertionError("expected an answer, got $this")

    private val notLinkings = Outcome.Failed("It was for an address that linking your devices doesn't use, so it wasn't sent.")

    /** The client linking is given: every address but this machine's own turned to
     *  `server`, which is told what was asked for (`X-Was`). So a test can ask for
     *  `https://music.example/…`, as a person's server is asked. */
    private fun everythingGoesTo(server: StandIn): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host == "127.0.0.1") return@addInterceptor chain.proceed(request)
        chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).header("X-Was", request.url.toString()).build())
    }.build()

    private val signIn = listOf(listOf("Content-Type", "application/x-www-form-urlencoded"))
    private val key = listOf(listOf("X-Jam-Key", "the-host-key"), listOf("X-Jam-Token", "a-place's-token"))

    @Test
    fun `each line reaches its own place and is not sent anywhere else`() {
        val music = standIn { _, reply -> reply.json("""{"music":true}""") }
        val relay = standIn { _, reply -> reply.json("""{"relay":true}""") }
        val other = standIn { _, reply -> reply.json("""{"other":true}""") }
        CoreHttp.linkMusic = { music.address }
        CoreHttp.linkRelay = { relay.address }
        CoreHttp.linkClient = { OkHttpClient() }

        assertEquals("""{"music":true}""", probe(Core.Probe(line = "music", method = "POST", url = "${music.address}/rest/getPlaylists", headers = signIn, body = "u=someone&t=abc&s=def")).got().body)
        assertEquals("""{"relay":true}""", probe(Core.Probe(line = "relay", url = "${relay.address}/room/r/poll", headers = key)).got().body)
        // Each for the other's place, and each for a third: not sent.
        assertEquals(notLinkings, probe(Core.Probe(line = "music", method = "POST", url = "${relay.address}/rest/getPlaylists", headers = signIn, body = "u=someone&t=abc&s=def")))
        assertEquals(notLinkings, probe(Core.Probe(line = "music", method = "POST", url = "${other.address}/rest/getPlaylists", headers = signIn, body = "u=someone&t=abc&s=def")))
        assertEquals(notLinkings, probe(Core.Probe(line = "relay", url = "${music.address}/room/r/poll", headers = key)))
        assertEquals(notLinkings, probe(Core.Probe(line = "relay", method = "POST", url = "${other.address}/room/r/send", headers = key, body = "{}")))
        assertEquals(1, music.asked.size)
        assertEquals(1, relay.asked.size)
        assertEquals(0, other.asked.size)
        // What went was the library's own: the sign-in to the one, the key to the other.
        assertEquals("u=someone&t=abc&s=def", music.asked.single().body)
        assertEquals("the-host-key", relay.asked.single().header("X-Jam-Key"))
        assertNull(music.asked.single().header("X-Jam-Key"))
    }

    @Test
    fun `with neither place given, both lines are closed`() {
        val server = standIn { _, reply -> reply.json("{}") }
        CoreHttp.linkClient = { OkHttpClient() }
        assertEquals(notLinkings, probe(Core.Probe(line = "music", method = "POST", url = "${server.address}/rest/ping", headers = signIn, body = "u=someone")))
        assertEquals(notLinkings, probe(Core.Probe(line = "relay", url = "${server.address}/room/r/poll", headers = key)))
        assertEquals(0, server.asked.size)
    }

    @Test
    fun `an answer that sends a request on is not followed on either line, whatever it was`() {
        // (From https to https, as the one kind of sending on this transport follows for a
        // public service: here it would carry the relay's key, or the sign-in, with it.)
        val server = standIn { asked, reply ->
            val was = asked.header("X-Was").orEmpty()
            if ("elsewhere.example" in was) reply.json("""{"reached":"elsewhere"}""") else reply.send(307, "", "Location" to "https://elsewhere.example/taken")
        }
        CoreHttp.linkMusic = { "https://music.example:443" }
        CoreHttp.linkRelay = { "https://relay.example:443" }
        CoreHttp.linkClient = { everythingGoesTo(server) }

        for (method in listOf("GET", "POST")) {
            val relay = probe(Core.Probe(line = "relay", method = method, url = "https://relay.example/room/r/poll", headers = key, body = "{}".takeIf { method == "POST" })).got()
            assertEquals(307, relay.status)
            val music = probe(Core.Probe(line = "music", method = method, url = "https://music.example/rest/getPlaylists", headers = signIn, body = "u=someone".takeIf { method == "POST" })).got()
            assertEquals(307, music.status)
        }
        assertEquals(4, server.asked.size)
        assertTrue(server.asked.none { "elsewhere.example" in it.header("X-Was").orEmpty() })
    }

    /**
     * **A stop always stops one of linking's operations.** The library says "keep going"
     * before a look's first write, and for a request made for the app's screens a stop
     * after that does nothing. One of linking's is stopped all the same: its wait ends,
     * and nothing more is sent. (Its sign-in has ended, or linking has stopped.)
     */
    @Test
    fun `a stop stops one of linking's operations after the library said to see it through, and nothing more is sent`() {
        val server = standIn { _, reply -> reply.json("{}") }
        CoreHttp.linkMusic = { server.address }
        CoreHttp.linkClient = { OkHttpClient() }
        val ended = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Outcome<Core.Probed>>()
        val began = System.nanoTime()
        val doing = Core.wireProbeLinking(Core.Probe(line = "music", method = "POST", url = "${server.address}/rest/createPlaylist", headers = signIn, body = "u=someone", keepGoing = "first", pause = 30_000)) {
            outcome.set(it)
            ended.countDown()
        } ?: throw AssertionError("there was no room for it")
        // (The library has said "keep going", and is waiting before its request.)
        Thread.sleep(500)
        doing.stop()
        assertTrue("the wait did not end at the stop", ended.await(10, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - began) / 1_000_000 < 15_000)
        assertEquals("a request was sent after the stop: ${outcome.get()}", 0, server.asked.size)
    }

    /** What that saying still buys: the request in flight at the stop, which is a write,
     *  is let finish, and its answer is read. */
    @Test
    fun `the write in flight when one of linking's operations is stopped is let finish`() {
        val arrived = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val server = standIn { _, reply ->
            arrived.countDown()
            stopped.await(20, TimeUnit.SECONDS)
            reply.json("""{"written":true}""")
        }
        CoreHttp.linkMusic = { server.address }
        CoreHttp.linkClient = { OkHttpClient() }
        val ended = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Outcome<Core.Probed>>()
        val doing = Core.wireProbeLinking(Core.Probe(line = "music", method = "POST", url = "${server.address}/rest/createPlaylist", headers = signIn, body = "u=someone", keepGoing = "first")) {
            outcome.set(it)
            ended.countDown()
        } ?: throw AssertionError("there was no room for it")
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        doing.stop()
        stopped.countDown()
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        assertEquals("""{"written":true}""", outcome.get().got().body)
        // One that the library has not said to see through is cut off at the stop.
        val cut = CountDownLatch(1)
        val held = CountDownLatch(1)
        val slow = standIn { _, reply ->
            cut.countDown()
            held.await(20, TimeUnit.SECONDS)
            reply.json("{}")
        }
        CoreHttp.linkMusic = { slow.address }
        val over = CountDownLatch(1)
        val looking = Core.wireProbeLinking(Core.Probe(line = "music", method = "POST", url = "${slow.address}/rest/getPlaylists", headers = signIn, body = "u=someone")) {
            outcome.set(it)
            over.countDown()
        } ?: throw AssertionError("there was no room for it")
        assertTrue(cut.await(10, TimeUnit.SECONDS))
        looking.stop()
        assertTrue("a look in flight was not cut off by the stop", over.await(10, TimeUnit.SECONDS))
        held.countDown()
        assertTrue(outcome.get().toString(), outcome.get() !is Outcome.Got)
    }

    @Test
    fun `a relay at the music server's own place is not asked anything`() {
        val server = standIn { _, reply -> reply.json("{}") }
        CoreHttp.linkMusic = { server.address }
        CoreHttp.linkRelay = { server.address }
        CoreHttp.linkClient = { OkHttpClient() }
        assertEquals(notLinkings, probe(Core.Probe(line = "relay", url = "${server.address}/jam/room/r/poll", headers = key)))
        assertEquals(notLinkings, probe(Core.Probe(line = "relay", method = "POST", url = "${server.address}/jam/room/r/send", headers = key, body = "{}")))
        assertEquals(0, server.asked.size)
        // (The music line is as it was: that place is its own.)
        probe(Core.Probe(line = "music", method = "POST", url = "${server.address}/rest/ping", headers = signIn, body = "u=someone")).got()
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `the account changes with a request in flight - the answer cannot carry the old sign-in to the new server`() {
        // The request was sent to the old account's server; while it waits, the person
        // signs in to another, which is then the music line's one place. The old server
        // answers by sending the request on to exactly there.
        val sent = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val server = standIn { asked, reply ->
            val was = asked.header("X-Was").orEmpty()
            if ("new.example" in was) {
                reply.json("""{"reached":"the new server"}""")
            } else {
                sent.countDown()
                changed.await(30, TimeUnit.SECONDS)
                reply.send(307, "", "Location" to "https://new.example/rest/getPlaylists")
            }
        }
        val place = java.util.concurrent.atomic.AtomicReference("https://old.example:443")
        CoreHttp.linkMusic = { place.get() }
        CoreHttp.linkClient = { everythingGoesTo(server) }
        val changer = Thread {
            sent.await(30, TimeUnit.SECONDS)
            place.set("https://new.example:443")
            changed.countDown()
        }.apply { start() }

        val answer = probe(Core.Probe(line = "music", url = "https://old.example/rest/getPlaylists?u=someone&t=abc&s=def", headers = signIn)).got()
        changer.join()
        assertEquals(307, answer.status)
        assertEquals(1, server.asked.size)
        // And what the old account's look asks next, of its own server, is not sent.
        assertEquals(notLinkings, probe(Core.Probe(line = "music", method = "POST", url = "https://old.example/rest/getPlaylists", headers = signIn, body = "u=someone&t=abc&s=def")))
        assertEquals(1, server.asked.size)
    }
}
