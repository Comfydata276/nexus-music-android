package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.medianexus.music.sync.QueueHere
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Linking the person's devices, switched on for the test, **with the shared library and the
 * real player**: the playback service whole. What is here needs no other device: that the
 * service makes it and lets go of it, a fault, and the end of a sign-in. (What needs a
 * command from another device is walked: these make no request and have no relay.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class LinkingTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()

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

    @Before
    fun start() {
        org.medianexus.music.sync.InStepBuild.forTests = true
        org.medianexus.music.link.LinkBuild.forTests = true
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        server.start()
        server.signIn(app)
        settle()
    }

    @After
    fun stop() {
        service.onDestroy()
        settle()
        server.stop()
        Linking.forgetFaultForTests()
        org.medianexus.music.link.LinkBuild.forTests = null
        org.medianexus.music.sync.InStepBuild.forTests = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Time passes: the ticks that were due run. */
    private fun pass(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
    }

    private fun link(): Linking = Linking.current.get() ?: error("the service made no linking")
    private fun here(): QueueHere = app.graph.accounts.state.value!!.queueStep!!.here!!
    private fun note(): String = link().shown?.get("note")?.jsonPrimitive?.content.orEmpty()
    private fun mine() = List(5) { Song(id = "s$it", title = "Song $it", duration = 200) }

    @Test
    fun whereLinkingExistsTheServiceMakesItAndItTicks() {
        val link = link()
        assertTrue(link.signIn > 0)
        pass(1_000)
        // The list is there after a tick. (No relay is set up, so nothing is asked of one.)
        assertNotNull(link.shown)
        assertFalse(link.stopped)
        // The service going: nothing of it is left.
        service.onDestroy()
        assertEquals(null, Linking.current.get())
        assertEquals(null, org.medianexus.music.core.CoreHttp.linkClient())
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        settle()
    }

    private fun toasts(): List<String> = org.robolectric.shadows.ShadowToast.shownToastCount().let { count -> if (count == 0) emptyList() else List(count) { org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString() } }

    /**
     * A call into the player that fails is a fault: nothing stands in for its answer.
     * Linking stops on the phone **for this run of the app**, tells the person once in the
     * library's words, lets go of everything, and calls nothing after it: not at the next
     * tick, not at a sign-out, and not from a playback service made again in the same
     * process. And a queue left marked as handed on while the phone is the one playing is
     * the phone's again.
     */
    @Test
    fun aCallIntoThePlayerThatFailsStopsLinkingUntilTheAppStartsAgain() {
        val link = link()
        inside.player.play(mine(), 0, "A list", playNow = true)
        settle()
        pass(500)
        // (As if its music had moved to another device and it had not yet been stopped.)
        link.handedOn()
        assertEquals(LinkSide.HANDED, here().taken())
        // The person presses something; telling the library of it, the app's side throws.
        var asked = 0
        link.insideForTests = {
            asked++
            throw IllegalStateException("the queue's owner could not be written")
        }
        link.chose()
        settle()
        assertEquals(1, asked)
        assertTrue(link.stopped)
        assertTrue(Linking.faulted)
        assertNotEquals("a queue left marked as handed on while the phone plays", LinkSide.HANDED, here().taken())
        // The person is told, once, in the library's words.
        assertEquals(listOf("Linking has stopped on this phone until the app is next opened."), toasts())
        // Everything was let go of: nothing is listed as the one linking there is, and
        // neither of its lines leads anywhere.
        assertNull(Linking.current.get())
        assertNull(org.medianexus.music.core.CoreHttp.linkClient())
        assertNull(org.medianexus.music.core.CoreHttp.linkMusic())
        // Tick after tick, a press, a sign-out: nothing is asked of the player or said again.
        pass(2_000)
        link.chose()
        settle()
        link.signInEnded()
        pass(1_000)
        assertEquals("the library asked the player something after a fault", 1, asked)
        assertTrue(link.stopped)
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
        // The player itself is as it was: playing what it was playing.
        assertTrue(inside.exo.playWhenReady)
        assertEquals("s0", inside.player.queue.current()?.id)
        // A playback service made again in the same process does not start linking again.
        service.onDestroy()
        settle()
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        settle()
        pass(1_000)
        assertNull("a service made again brought linking back after a fault", Linking.current.get())
        assertNull(org.medianexus.music.core.CoreHttp.linkClient())
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
    }

    /** The end of a sign-in is said to linking before the player is switched, and the
     *  sign-in after it has another number, whoever it is. */
    @Test
    fun theEndOfASignInIsSaidBeforeThePlayerIsSwitched() {
        val link = link()
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        pass(500)
        val before = link.signIn
        server.signIn(app, username = "somebody-else")
        settle()
        pass(500)
        assertTrue("the sign-in's number did not move with the account", link.signIn > before)
        assertTrue(inside.player.queue.isEmpty)
        assertFalse(link.stopped)
    }

    /** The person's own press is noted at the press and told to the library with what it
     *  did, and claims the queue once: by the service, as it always did. */
    @Test
    fun aPressOnThePhoneClaimsTheQueueOnce() {
        val link = link()
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        pass(500)
        var claims = 0
        link.insideForTests = { if (it == "claim") claims++ }
        val stamp = here().revision()
        link.chose()
        // (Said once the press's own work is done, which here is at once.)
        assertEquals(0, claims)
        settle()
        // (The library asked for the claim that goes with a press here; the service had
        // made it, and it is not made twice.)
        assertEquals(1, claims)
        assertEquals(stamp, here().revision())
        assertFalse(link.stopped)
    }
}
