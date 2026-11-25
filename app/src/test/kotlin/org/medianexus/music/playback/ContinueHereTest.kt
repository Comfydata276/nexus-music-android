package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Another device's queue put in the player (M5a fixes, B2): the playback service's side of
 * [org.medianexus.music.sync.QueueStep]. "Continue here" is a choice like any other, with
 * its place in the order of choices taken at the tap; a queue taken up without being asked
 * is nobody's choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class ContinueHereTest {
    // (No JUnit time limit here: it would run the test off the main thread, where the
    // service can't be made. Everything that waits has a limit of its own: see [wait], and
    // the stand-in server's sign-in.)

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** Android's foreground, which lets the service in. */
    private class Android : PlaybackService.Foreground {
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) { inIt = true }
        override fun leave() { inIt = false }
    }

    @Before
    fun start() {
        // Keeping in step is switched off in this build; these are tests of it.
        org.medianexus.music.sync.InStepBuild.forTests = true
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
        scope.cancel()
        service.onDestroy()
        settle()
        server.stop()
        org.medianexus.music.sync.InStepBuild.forTests = false
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** The player's side of sharing the queue, as the service gave it to the account. */
    private fun here(): QueueHere = app.graph.accounts.state.value!!.queueStep!!.here!!

    private fun mine() = List(5) { Song(id = "s$it", title = "Song $it") }
    private fun theirs() = List(3) { Song(id = "pc$it", title = "Theirs $it", duration = 200) }

    /** Runs `block` on the main thread and lets that thread turn until it is done (it waits
     *  on other threads: the queue is made ready off the main thread). */
    private fun <T> wait(block: suspend () -> T): T {
        val doing = scope.async { block() }
        val until = System.nanoTime() + 30_000_000_000L
        while (!doing.isCompleted) {
            settle()
            check(System.nanoTime() < until) { "not done in thirty seconds" }
            Thread.yield()
        }
        @Suppress("OPT_IN_USAGE")
        return doing.getCompleted()
    }

    /** A time after anything done here: another device's queue saved "just now". */
    private fun later() = System.currentTimeMillis() / 1000 + 60

    @Test
    fun aChangeToTheQueueOnAScreenTakesItsPlaceAndIsNotReplacedByAQueueOnItsWay() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // (A change made to the queue on the queue page takes its place in the order of
        // choices as it is tapped: `PlayerConnection.move`, `remove` and `clear`, as `jump`
        // and `enqueue` always did. The screens aren't connected to a player in this test,
        // so the tap is its place, taken.)
        fun tapped() = Choices.take()
        // A queue another device saved is to be taken up, as was decided at this moment...
        val asOf = Choices.latest
        val stampThen = here().stamp()
        // ...and before it is in the player, the person takes a row out of their own.
        tapped()
        assertFalse("a queue taken up unasked replaced one the person had just changed", wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = asOf, stampThen = stampThen, still = { true }) })
        // "Continue here" tapped, and then a row moved before its queue is ready: the same.
        val tap = Choices.take()
        tapped()
        assertFalse("Continue here replaced a queue changed after its tap", wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = tap, asOf = tap, stampThen = 0, still = { true }) })
        settle()
        assertEquals("s1", inside.player.queue.current()?.id)
        // And one whose queue was changed here, however, since it was decided on (the stamp
        // has moved): not replaced either.
        assertFalse(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp() - 1, still = { true }) })
        assertEquals("s1", inside.player.queue.current()?.id)
    }

    @Test
    fun ofTwoQueuesMadeReadyAtOnceOnlyTheFirstToBeReadyGoesIn() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // Two looks decided, at the same moment, to take up what another device had saved
        // (each on what it had read), and both queues are being made ready together.
        val asOf = Choices.latest
        val stampThen = here().stamp()
        val other = List(2) { Song(id = "other$it", title = "Other $it", duration = 200) }
        val one = scope.async { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = asOf, stampThen = stampThen, still = { true }) }
        val two = scope.async { here().replace(other, 0, 5_000, later() + 5, "2026-01-01T00:00:05Z", choice = null, asOf = asOf, stampThen = stampThen, still = { true }) }
        val until = System.nanoTime() + 30_000_000_000L
        while (!(one.isCompleted && two.isCompleted)) {
            settle()
            check(System.nanoTime() < until) { "not done in thirty seconds" }
            Thread.yield()
        }
        // One went in. The other found the queue no longer the one it was decided against,
        // and gave way: it is for the next look to say what stands.
        @Suppress("OPT_IN_USAGE")
        assertEquals(setOf(true, false), setOf(one.getCompleted(), two.getCompleted()))
    }

    @Test
    fun aQueueTakenUpFromAnotherDeviceIsStillThatDevicesAfterARestartUntilSomethingIsDoneToIt() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        assertNull(here().taken())
        assertTrue(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp(), still = { true }) })
        settle()
        // It is the other device's, and says so: kept with the queue, for the next run.
        assertEquals("2026-01-01T00:00:00Z", here().taken())
        // Played here ("Continue here" would be the same): this phone's own from then on.
        val tap = Choices.take()
        assertTrue(wait { here().replace(theirs(), 2, 0, later(), "2026-01-01T00:00:05Z", choice = tap, asOf = tap, stampThen = 0, still = { true }) })
        settle()
        assertNull(here().taken())
    }

    @Test
    fun continueHereIsPlayedIfNothingWasChosenAfterItsTap() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // The tap takes its place; the queue is made ready; nothing else has happened.
        val tap = Choices.take()
        assertTrue(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = tap, asOf = tap, stampThen = 0, still = { true }) })
        settle()
        assertEquals("pc1", inside.player.queue.current()?.id)
        assertTrue(inside.exo.playWhenReady)
    }

    @Test
    fun continueHereGivesWayToAnythingChosenAfterItsTap() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // Tapped; and while its queue is being made ready the person pauses (or stops, or
        // picks something else): a later place in the order of choices.
        val tap = Choices.take()
        Choices.take()
        assertFalse("played over a later choice", wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = tap, asOf = tap, stampThen = 0, still = { true }) })
        settle()
        assertEquals("s1", inside.player.queue.current()?.id)
        assertFalse(inside.exo.playWhenReady)
    }

    @Test
    fun nothingIsPutInThePlayerOnceTheSettingIsOff() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // "Continue here", and the setting off by the time its queue is ready.
        val tap = Choices.take()
        assertFalse(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = tap, asOf = tap, stampThen = 0, still = { false }) })
        // A queue taken up without being asked: the same.
        assertFalse(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp(), still = { false }) })
        settle()
        assertEquals("s1", inside.player.queue.current()?.id)
        assertFalse(inside.exo.playWhenReady)
    }

    @Test
    fun aQueueTakenUpUnaskedIsNobodysChoiceAndOvertakesNothing() {
        // The person has just tapped a list to play: its place is taken, and its queue is
        // being made ready.
        val list = inside.player.prepare(mine(), 2, context = "A list", shuffle = false)
        val start = inside.player.beginStart()
        // Meanwhile a queue another device saved is taken up, paused.
        assertTrue(wait { here().replace(theirs(), 1, 30_000, later(), "2026-01-01T00:00:00Z", choice = null, asOf = Choices.latest, stampThen = here().stamp(), still = { true }) })
        settle()
        assertEquals("pc1", inside.player.queue.current()?.id)
        assertFalse(inside.exo.playWhenReady)
        // The person's start still stands, and plays over it.
        assertTrue("a queue nobody chose overtook the person's start", Choices.stands(start))
        assertTrue(inside.player.playFor(start, list))
        settle()
        assertEquals("s2", inside.player.queue.current()?.id)
        // But one taken up unasked gives way to a choice made while it was being made ready:
        // that is asked of the order of choices when it is ready, which a pause has moved on.
        // (Shown here by its place: the newest when it began, no longer the newest.)
        val asked = Choices.latest
        Choices.take()
        assertFalse(Choices.stands(asked))
    }
}
