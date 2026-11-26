package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.core.StandIn
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * **The life of what linking starts** ([Linking]): its start, the requests it makes on
 * threads of their own, and its fault path, with the real shared library, real requests
 * to stand-in servers on this machine, and a host that is the test's own (so that the
 * relay's key can be slow to read, the relay can be at an address it must not be at, and
 * a read of the player can fail).
 *
 * Linking is switched off for the service here: the linking under test is made beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class LinkWorkersTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()
    private val closing = mutableListOf<StandIn>()
    private lateinit var side: LinkSide
    private lateinit var link: Linking
    private val host = Host()
    private val asked = CopyOnWriteArrayList<JsonElement>()
    private var readFails = false
    private var reads = 0

    private class Android : PlaybackService.Foreground {
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) {
            inIt = true
        }

        override fun leave() {
            inIt = false
        }
    }

    private class Owner : LinkSide.Owner {
        override var taken: String? = null
        override fun claim() {
            taken = null
        }

        override fun written() = true
    }

    private class Host : Linking.Host {
        @Volatile var music = ""
        @Volatile var relay = ""
        @Volatile var elsewhere = emptySet<String>()
        @Volatile var deviceFails = false
        @Volatile var reading: (() -> Unit)? = null
        val edits = java.util.concurrent.atomic.AtomicLong()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val said = CopyOnWriteArrayList<String>()
        val notes = CopyOnWriteArrayList<String>()

        override fun device(): Pair<String, String> {
            check(!deviceFails) { "the phone's id could not be read" }
            return "AAAAAAAAAAAAAAAAAAAAAA" to "A phone"
        }

        override fun chosenName() = ""
        override fun account() = "tester@$music"
        override fun wanted() = true
        override fun server() = Core.LinkServer("$music/rest", "u=tester&p=secret&v=1.16.1&c=test&f=json", "tester") to CoreHttp.originOf(music)!!
        override fun relayEdit() = edits.get()
        override fun relay(): Pair<Core.LinkRelay, String> {
            reads.incrementAndGet()
            reading?.invoke()
            return Core.LinkRelay(relay, "a-made-up-key") to CoreHttp.originOf(relay)!!
        }

        override fun elsewhere() = elsewhere
        override fun client() = okhttp3.OkHttpClient()
        override fun remembered(): JsonElement = NOTHING_REMEMBERED
        override fun remember(list: JsonElement) = Unit
        override fun say(text: String) {
            said += text
        }

        override fun note(text: String) {
            notes += text
        }

        override fun ownStoppedWords() = "The phone's own words for it."

        override fun forget(playlist: String) = Unit
    }

    private val library = List(4) { Song(id = "s$it", title = "Song $it", duration = 200) }.associateBy { it.id }

    @Before
    fun start() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        server.start()
        server.signIn(app)
        settle()
        val music = StandIn { request, reply ->
            if ("getSong" in request.target) held?.await(20, TimeUnit.SECONDS)
            reply.json("""{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"x1","title":"Found there","duration":100}}}""")
        }.also { closing += it }
        val relay = StandIn { _, reply -> reply.send(503) }.also { closing += it }
        host.music = music.address
        host.relay = relay.address
        this.relay = relay
        side = LinkSide(inside.player, inside.exo, Owner(), songFor = { id -> link.foundSong(id) ?: library[id] })
        link = Linking(side, host, knows = {
            reads++
            check(!readFails) { "the player could not be read" }
            side.known()
        })
        // Every ask of the network is the test's, but the two whose life is tested.
        link.asksForTests = { ask ->
            asked += ask
            (ask as? JsonObject)?.keys?.firstOrNull() !in setOf("begin", "end", "find", "renew") && !(realLooks && (ask as? JsonObject)?.containsKey("look") == true)
        }
    }

    private lateinit var relay: StandIn

    /** What the music server holds its answer to a look-up of a song for, if anything. */
    @Volatile private var held: CountDownLatch? = null

    @After
    fun stop() {
        held?.countDown()
        link.close()
        service.onDestroy()
        settle()
        server.stop()
        closing.forEach { it.close() }
        Linking.forgetFaultForTests()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pass(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))

    /** Lets the threads that make requests get on, and this one take what they post,
     *  until `so` or for a few seconds. */
    private fun until(seconds: Long = 10, so: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) {
            settle()
            if (so()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun asks(name: String): List<JsonObject> = asked.mapNotNull { (it as? JsonObject)?.get(name) as? JsonObject }

    /** The look the library asked for reads a secret: it then asks for a session to begin. */
    private fun looked(): Long {
        pass(600)
        val look = asks("look").lastOrNull() ?: error("no look was asked for: $asked")
        asked.clear()
        link.forTests { now, today -> Core.linkTestLooked(look.getValue("number").jsonPrimitive.long, now, today) }
        return asks("begin").lastOrNull()?.getValue("session")?.jsonPrimitive?.long ?: error("no session was begun: $asked")
    }

    /**
     * The relay's key is read on a thread of its own. If the sign-in the session was for
     * has ended by the time it has been read, nothing is begun with it: no stay, no place
     * for the relay's line. The sign-in that follows begins its own.
     */
    @Test
    fun aSessionWhoseKeyWasStillBeingReadWhenItsSignInEndedIsNeverBegun() {
        val reading = CountDownLatch(1)
        val go = CountDownLatch(1)
        host.reading = {
            reading.countDown()
            go.await(20, TimeUnit.SECONDS)
        }
        assertTrue(link.start())
        looked()
        assertTrue("the relay's key was never read", reading.await(10, TimeUnit.SECONDS))
        // The same person signs out and in again while it is being read.
        link.signInEnded()
        host.reading = null
        go.countDown()
        // What that reading posts is for a sign-in that has ended.
        assertFalse(until(2) { CoreHttp.linkRelay() != null || relay.asked.isNotEmpty() })
        assertEquals("the relay heard from a session whose sign-in had ended", 0, relay.asked.size)
        // The sign-in after it: its own look, its own session, and a stay at the relay.
        looked()
        assertTrue("the next sign-in's session was not begun", until { relay.asked.isNotEmpty() })
        assertEquals(CoreHttp.originOf(host.relay), CoreHttp.linkRelay())
        // And ended with it: the relay's line leads nowhere again.
        link.signInEnded()
        assertNull(CoreHttp.linkRelay())
    }

    /** A relay set to the address of the music server (or of Lidarr) would be sent its key
     *  there: it is not used, and the diagnostics say why. */
    @Test
    fun aRelayAtTheAddressOfSomethingElseOfThePersonsIsNotUsed() {
        host.elsewhere = setOf(CoreHttp.originOf(host.relay)!!)
        assertTrue(link.start())
        looked()
        assertTrue(host.notes.toString(), until { host.notes.any { "which it cannot be" in it } })
        assertNull(CoreHttp.linkRelay())
        assertEquals(0, relay.asked.size)
    }

    /**
     * A start that fails leaves nothing attached, wherever it fails, **and is a fault like
     * any other**: the person is told, once, and it is not started again in this run of
     * the app.
     */
    @Test
    fun aStartThatFailsLeavesNothingAttachedAndIsAFault() {
        val sentence = "Linking has stopped on this phone until the app is next opened."
        fun nothingLeft(why: String) {
            assertNull(why, Linking.current.get())
            assertNull(why, CoreHttp.linkClient())
            assertNull(why, CoreHttp.linkMusic())
            assertTrue(why, Linking.faulted)
            assertEquals(why, listOf(sentence), host.said.toList())
            // (Nor of the library's own: a linking started now is the only one there is.)
            host.said.clear()
            Linking.forgetFaultForTests()
        }
        host.deviceFails = true
        assertFalse(link.start())
        nothingLeft("the phone's id could not be read")
        pass(1_000)
        assertEquals("a linking that never started read the player", 0, reads)
        // From a thread that is not the player's: the same.
        host.deviceFails = false
        link = Linking(side, host)
        var began = true
        Thread { began = link.start() }.apply { start() }.join()
        assertFalse(began)
        nothingLeft("started off the player's thread")
        // And the reading of the player failing as it starts, after the library answered.
        readFails = true
        link = Linking(side, host, knows = {
            check(!readFails) { "the player could not be read" }
            side.known()
        })
        assertFalse(link.start())
        nothingLeft("the player could not be read")
        // The fault stands for the run: the same linking is not started again.
        readFails = false
        assertFalse(link.start())
        // (With that forgotten, as a new run of the app has it, one starts cleanly:
        // nothing of the failed ones is in its way.)
        link = Linking(side, host)
        assertTrue(link.start())
        pass(600)
        assertNotNull(link.shown)
    }

    /**
     * The relay and its key are one thing: saved again, the session that was using the
     * old ones is ended, and the next begins with what is saved now, read afresh.
     */
    @Test
    fun theRelaySavedAgainEndsTheSessionAndBeginsAnother() {
        assertTrue(link.start())
        val first = looked()
        assertTrue(until { relay.asked.isNotEmpty() })
        val read = host.reads.get()
        asked.clear()
        host.edits.incrementAndGet()
        pass(600)
        assertEquals("the session was not ended when the relay was saved again: $asked", first, asks("end").firstOrNull()?.getValue("session")?.jsonPrimitive?.long)
        assertNull(CoreHttp.linkRelay())
        val second = looked()
        assertTrue(second != first)
        assertTrue("the relay was not read again for the new session", until { host.reads.get() > read && CoreHttp.linkRelay() != null })
    }

    /**
     * **Every asker gets an end.** With every thread kept for linking taken and every
     * place in line too, a look cannot begin: the library is told that look failed, at
     * once, and looks again in its own time. It is not left looking for ever.
     */
    @Test
    fun aLookThereIsNoRoomForIsToldAsALookThatFailed() {
        val hold = CountDownLatch(1)
        // Every thread and every place in line taken, and still so a moment later
        // (something of another test's may be winding down in the same pool, and would
        // give a place back).
        var filling = 0
        var full = 0
        while (full < 3 && filling < 500) {
            if (CoreHttp.linking("fill") { hold.await(60, TimeUnit.SECONDS) } != null) {
                filling++
                full = 0
            } else {
                full++
                Thread.sleep(300)
            }
        }
        try {
            assertTrue("$filling", filling >= CoreHttp.LINKING)
            assertNull(CoreHttp.linking("one more") {})
            realLooks = true
            assertTrue(link.start())
            pass(600)
            assertEquals(1, asks("look").size)
            assertTrue(host.notes.toString(), host.notes.any { "too much was already being asked for a look" in it })
        } finally {
            hold.countDown()
            realLooks = false
        }
        // The library heard that the look failed: when its time to look again comes, it does.
        assertTrue(until { runCatching { CoreHttp.linking("probe") {} }.getOrNull() != null })
        pass(3 * 60_000)
        assertTrue("the library was left looking for ever: ${asks("look").size} look(s) asked", asks("look").size >= 2)
        assertFalse(link.stopped)
    }

    /**
     * Reading what the player holds, which is done before and after every call into the
     * library, is inside the same catch as the call: if it fails, that is a fault like
     * any other. The person is told once, and nothing is read or called after it.
     */
    @Test
    fun readingThePlayerFailingIsAFault() {
        assertTrue(link.start())
        pass(600)
        assertFalse(link.stopped)
        readFails = true
        pass(300)
        assertTrue(link.stopped)
        assertTrue(Linking.faulted)
        assertEquals(listOf("Linking has stopped on this phone until the app is next opened."), host.said.toList())
        assertNull(Linking.current.get())
        assertNull(CoreHttp.linkClient())
        val after = reads
        readFails = false
        pass(3_000)
        link.chose()
        link.signInEnded()
        settle()
        assertEquals("the player was read after a fault", after, reads)
        assertEquals(1, host.said.size)
        // And it is not started again in this run of the app, by this linking or another.
        assertFalse(link.start())
        assertFalse(Linking(side, host).start())
    }

    /**
     * A look-up of songs for a command: what it brings is kept, and the library told, if
     * the sign-in it was asked under is still the one; one that is still out when that
     * ends brings nothing to the next.
     */
    @Test
    fun aLookUpOfSongsStillOutWhenItsSignInEndsBringsNothing() {
        inside.player.play(library.values.toList(), 0, "A list", playNow = false)
        settle()
        assertTrue(link.start())
        val session = looked()
        link.forTests { now, today -> Core.linkTestEntered(session, now, today) }
        fun add(generation: Long) = buildJsonObject {
            put("n", ++nonce)
            put("generation", generation)
            put("command", buildJsonObject {
                put("do", "add")
                put("with", buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive("x1")) })
                    put("next", false)
                })
            })
        }
        // One that is answered: the song is in hand, and the queue has it.
        val at = generation(session)
        link.forTests { now, today -> Core.linkTestHeard(session, listOf(add(at)), now, today) }
        assertTrue("no look-up was asked for: $asked ${host.notes}", asks("find").isNotEmpty())
        assertTrue("the song a look-up brought was not kept: ${host.notes}", until { link.foundSong("x1") != null })
        assertTrue("the song was not added: ${inside.player.queue.playingOrder().map { it.id }}", until { inside.player.queue.playingOrder().any { it.id == "x1" } })
        // One that is still out when the sign-in ends.
        link.signInEnded()
        assertNull(link.foundSong("x1"))
        val next = looked()
        link.forTests { now, today -> Core.linkTestEntered(next, now, today) }
        held = CountDownLatch(1)
        val then = generation(next)
        link.forTests { now, today -> Core.linkTestHeard(next, listOf(add(then)), now, today) }
        assertTrue("no look-up was asked for: $asked", asks("find").isNotEmpty())
        link.signInEnded()
        held?.countDown()
        assertFalse(until(2) { link.foundSong("x1") != null })
    }

    private var nonce = 0

    /** Looks are the test's own, unless one is to be made for real (and find no room). */
    private var realLooks = false

    /** The phone's order of choices, as it tells another device that looks. */
    private fun generation(session: Long): Long {
        asked.clear()
        link.forTests { now, today ->
            Core.linkTestHeard(session, listOf(buildJsonObject {
                put("n", ++nonce)
                put("look", "brief")
            }), now, today)
        }
        val told = asks("send").lastOrNull() ?: error("the look was not answered: $asked")
        return Core.linkTestOpen(told.getValue("frame").jsonPrimitive.content).getValue("generation").jsonPrimitive.long
    }
}
