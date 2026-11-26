package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * **A command from another of the person's devices, in flight, against the real player**:
 * the playback service whole, the shared library, and what another device and the relay
 * would bring handed to the library as they would bring it (`Core.linkTest…`, which only the
 * library built for this machine has). No request is made: every ask the library makes of
 * the network is taken by the test.
 *
 * What these are for is the Windows repository's `docs/linking-design.md`, 8.1: one remote
 * command is one choice, however the player's listeners catch up with it afterwards; a Play
 * the phone refuses is a refusal; the sign-in ending from inside a call into the player stops
 * what was to follow; and a call into the player that fails is a fault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class LinkCommandsTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()
    private val android = Android()

    private class Android : PlaybackService.Foreground {
        var allows = true
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) {
            if (!allows) throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
            inIt = true
        }

        override fun leave() {
            inIt = false
        }
    }

    private val relayKey get() = java.io.File(app.noBackupFilesDir, "link-relay.sealed")

    /** Every ask the library made of the network, in order: none is carried out. */
    private val asked = ArrayList<JsonElement>()
    private var session = 0L
    private var nonce = 0

    @Before
    fun start() {
        org.medianexus.music.sync.InStepBuild.forTests = true
        org.medianexus.music.link.LinkBuild.forTests = true
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = android
        inside = service.inside()
        server.start()
        server.signIn(app)
        // A relay set up, with a key kept for it, so that linking is wanted. (Nothing is
        // ever sent to it, and the key is never opened: every ask of the network is taken
        // by the test. The file only has to be there; this machine has no keystore to seal
        // a real one with.)
        relayKey.writeText("not a key")
        app.graph.settings.update { it.copy(linkDevices = true, jamHost = false) }
        settle()
        link().asksForTests = { ask ->
            asked += ask
            true
        }
    }

    @After
    fun stop() {
        Linking.current.get()?.let {
            it.insideForTests = null
            it.asksForTests = null
        }
        service.onDestroy()
        settle()
        server.stop()
        Linking.forgetFaultForTests()
        relayKey.delete()
        org.medianexus.music.link.LinkBuild.forTests = null
        org.medianexus.music.sync.InStepBuild.forTests = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pass(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
    private fun link(): Linking = Linking.current.get() ?: error("the service made no linking")
    private fun mine() = List(6) { Song(id = "s$it", title = "Song $it", duration = 200) }

    private fun asks(name: String): List<JsonObject> = asked.mapNotNull { (it as? JsonObject)?.get(name) as? JsonObject }

    /** The phone joins the room: the look it asked for reads a secret, and the stay it then
     *  asks for is given a place. */
    private fun join() {
        pass(600)
        val look = asks("look").lastOrNull() ?: error("no look was asked for: $asked")
        link().forTests { now, today -> Core.linkTestLooked(look.getValue("number").jsonPrimitive.long, now, today) }
        session = asks("begin").lastOrNull()?.getValue("session")?.jsonPrimitive?.long ?: error("no session was begun: $asked")
        link().forTests { now, today -> Core.linkTestEntered(session, now, today) }
        asked.clear()
    }

    private fun look(): JsonObject = buildJsonObject {
        put("n", ++nonce)
        put("look", "brief")
    }

    private fun command(name: String, generation: Long, with: JsonObject? = null): JsonObject = buildJsonObject {
        put("n", ++nonce)
        put("generation", generation)
        put("command", buildJsonObject {
            put("do", name)
            with?.let { put("with", it) }
        })
    }

    /** The relay hands these over in one go; what the phone answered each with, in order. */
    private fun hear(vararg asks: JsonObject): List<JsonObject> {
        asked.clear()
        link().forTests { now, today -> Core.linkTestHeard(session, asks.toList(), now, today) }
        // (What is to be sent names the place it goes to as text, which is how it is handed
        // back to be sent: found on the walk, where it crossed as sixteen numbers.)
        for (send in this.asks("send")) assertTrue(send.toString(), (send.getValue("to") as? JsonPrimitive)?.isString == true && send.getValue("token").jsonPrimitive.isString)
        return this.asks("send").map { Core.linkTestOpen(it.getValue("frame").jsonPrimitive.content) }
    }

    private fun generation(): Long = hear(look()).single().getValue("generation").jsonPrimitive.long

    @Test
    fun oneCommandFromAnotherDeviceIsOneChoiceHoweverThePlayerCatchesUp() {
        // (Paused throughout: nothing here needs sound, and a player trying to play from a
        // server that has no songs would be doing things of its own.)
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        join()
        val at = generation()
        // Next, from the other device: carried out, answered from the player as it now is.
        val next = hear(command("next", at)).single()
        assertEquals(true, next.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals("Song 1", next.getValue("title").jsonPrimitive.content)
        assertEquals(nonce, next.getValue("by").jsonPrimitive.intOrNull)
        assertTrue(next.getValue("done").jsonPrimitive.boolean)
        assertEquals(at + 1, next.getValue("generation").jsonPrimitive.long)
        // The player's listeners catch up with it, and time passes: tick after tick.
        settle()
        pass(2_000)
        // The order of choices has not moved again, and the state is still that command's:
        // the next press, made against the state in that answer, goes through.
        val seek = hear(command("seek", at + 1, buildJsonObject { put("to", 30.0) })).single()
        assertEquals("a remote Next was counted as two choices: $seek", true, seek.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals(30_000L, inside.exo.currentPosition)
        settle()
        pass(1_000)
        assertEquals(at + 2, generation())
        // What the phone does by itself is found, and is a choice of nobody's: a command
        // made before it is refused, and changes nothing.
        inside.player.passTo(inside.player.queue.cursor + 1)
        settle()
        val late = hear(command("previous", at + 2)).single()
        assertEquals(false, late.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals("changed", late.getValue("why").jsonPrimitive.contentOrNull)
        assertEquals("s2", inside.player.queue.current()?.id)
        assertEquals(at + 3, generation())
        // And the person's own press, which may change nothing that could be found by
        // looking (a seek), is told before anything else is asked of the library.
        link().chose()
        assertEquals(at + 4, generation())
    }

    /**
     * **A press on the phone and what it did are one choice.** The press is noted where
     * it is made, the player is then changed, and the library is told once, as the
     * person's: so a command another device makes against the state it is then told of
     * goes through, where two choices would have refused it as made too early.
     */
    @Test
    fun aPressOnThePhoneAndWhatItDidAreOneChoice() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        join()
        val at = generation()
        // As the service does it: the press, and then the player.
        link().chose()
        inside.player.seekToNext()
        settle()
        pass(1_000)
        assertEquals("s1", inside.player.queue.current()?.id)
        assertEquals("a press and what it did were counted as two choices", at + 1, generation())
        // And a press that changes nothing that could be found by looking is one too.
        link().chose()
        settle()
        pass(1_000)
        assertEquals(at + 2, generation())
        // What the library's own command does is not a press, though it goes through the
        // same ways in: said from inside the call, it is nothing.
        val link = link()
        link.insideForTests = { call -> if (call == "carryOut") link.chose() }
        assertEquals(true, hear(command("next", at + 2)).single().getValue("ok").jsonPrimitive.booleanOrNull)
        link.insideForTests = null
        settle()
        pass(1_000)
        assertEquals(at + 3, generation())
    }

    @Test
    fun aPlayThePhoneRefusesIsAnsweredAsCouldNot() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        join()
        val at = generation()
        android.allows = false
        val asItWas = inside.exo.playbackState
        val refused = hear(command("play", at)).single()
        // (Nothing was made ready for a start the phone would not allow.)
        assertEquals(asItWas, inside.exo.playbackState)
        assertEquals(false, refused.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals("cannot", refused.getValue("why").jsonPrimitive.contentOrNull)
        assertFalse(refused.getValue("playing").jsonPrimitive.boolean)
        assertFalse(inside.exo.playWhenReady)
        // Nothing moved, and nothing is named as having made the state.
        assertEquals(at, refused.getValue("generation").jsonPrimitive.long)
        assertEquals("null", refused.getValue("by").toString())
        // Allowed, it is carried out; and asked again, it is so already.
        android.allows = true
        assertEquals(true, hear(command("play", generation())).single().getValue("ok").jsonPrimitive.booleanOrNull)
        val already = hear(command("play", generation())).single()
        assertEquals(true, already.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals("nothing", already.getValue("why").jsonPrimitive.contentOrNull)
    }

    /**
     * The app says the sign-in is over from inside a call that only reads the player (the
     * number of its queue, read immediately before a command changes the player). The
     * command that was about to be carried out is not, and nothing is answered.
     */
    @Test
    fun theSignInEndingInsideAReadStopsTheCommandThatWasToFollow() {
        inside.player.play(mine(), 0, "A list", playNow = true)
        settle()
        join()
        val at = generation()
        val link = link()
        val before = link.signIn
        var reads = 0
        var carried = 0
        link.insideForTests = { call ->
            if (call == "carryOut") carried++
            // (The deciding reads the number once, and once more just before the act.)
            if (call == "queue" && ++reads == 2) link.signInEnded()
        }
        val answers = hear(command("pause", at), command("next", at + 1))
        assertEquals(2, reads)
        assertEquals("the command was carried out on a player whose sign-in had ended", 0, carried)
        assertTrue(inside.exo.playWhenReady)
        assertEquals("s0", inside.player.queue.current()?.id)
        assertTrue(answers.isEmpty())
        assertTrue(link.signIn > before)
        assertFalse(link.stopped)
    }

    /** The sign-in ends while a command is being carried out: what came of the command is
     *  nobody's, nothing is answered, and what the relay handed over with it is not carried
     *  out for the sign-in that follows. */
    @Test
    fun theSignInEndingInsideACommandDropsWhatCameOfIt() {
        inside.player.play(mine(), 0, "A list", playNow = true)
        settle()
        join()
        val at = generation()
        val link = link()
        var carried = 0
        link.insideForTests = { call ->
            if (call == "carryOut" && ++carried == 1) link.signInEnded()
        }
        val answers = hear(command("pause", at), command("play", at + 1), command("next", at + 2))
        assertEquals("the rest of what the relay handed over was carried out", 1, carried)
        assertTrue(answers.isEmpty())
        assertFalse(link.stopped)
        // The session it belonged to was ended for the app's side.
        assertEquals(session, asks("end").single().getValue("session").jsonPrimitive.long)
    }

    /** A call into the player that fails while a command is being carried out is a fault:
     *  linking stops, nothing is answered, and nothing more is asked of the player. */
    @Test
    fun aCallIntoThePlayerThatFailsInsideACommandIsAFault() {
        inside.player.play(mine(), 0, "A list", playNow = true)
        settle()
        join()
        val at = generation()
        val link = link()
        var calls = 0
        link.insideForTests = { call ->
            calls++
            if (call == "carryOut") throw IllegalStateException("the player could not be changed")
        }
        val answers = hear(command("next", at), command("pause", at + 1))
        assertTrue(link.stopped)
        assertTrue(answers.isEmpty())
        val after = calls
        pass(2_000)
        assertEquals("the player was asked something after a fault", after, calls)
        assertEquals("Linking has stopped on this phone until the app is next opened.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString())
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
        // No request of linking's can be made either: neither line leads anywhere, and
        // nothing in this run of the app starts it again.
        link.insideForTests = null
        link.signInEnded()
        pass(1_000)
        assertTrue(link.stopped)
        assertNull(CoreHttp.linkClient())
        assertNull(CoreHttp.linkRelay())
    }
}
