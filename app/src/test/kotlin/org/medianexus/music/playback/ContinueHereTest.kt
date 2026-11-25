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
        org.medianexus.music.sync.InStepBuild.forTests = null
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
    fun theQueuesNumberMovesAtWhateverChangesWhatASaveWouldCarry() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        // (The service's own number, which a save of the queue is prepared from and sent
        // only if it is still: `QueueHere.revision`.)
        fun moved(what: String, act: () -> Unit) {
            val was = here().revision()
            act()
            settle()
            assertTrue("$what did not move the queue's number", here().revision() != was)
        }
        // A jump in position within the same second of the same song: the queue's time, in
        // whole seconds, does not see it, and a save prepared before it carries the old place.
        assertEquals(0L, inside.exo.currentPosition)
        moved("a seek within the second") { inside.exo.seekTo(400) }
        assertEquals(400L, inside.exo.currentPosition)
        moved("a seek back within the second") { inside.exo.seekTo(50) }
        // Play, and pause: playing is why a save does not look at the server first.
        moved("play") { inside.exo.play() }
        moved("pause") { inside.exo.pause() }
        // Another song chosen in the queue, and a change to the list itself.
        moved("another song chosen") { inside.player.jumpAhead(1) }
        moved("a song taken out of the queue") { inside.player.removeEntry(inside.player.queue.lastUid()!!) }
        // And with nothing done, it stays: the queue is saved now and then as it stands
        // (as it is while a song simply plays on), and that is no change to it.
        val still = here().revision()
        settle()
        assertEquals(still, here().revision())
    }

    /** What the server answers for the saved queue: one another device saved, just now. */
    private fun savedElsewhere() = """"playQueue":{"current":"pc1","position":30000,"changed":"${java.time.Instant.now().plusSeconds(60)}","changedBy":"NexusMusic-0badc0de","entry":[""" +
        (0..2).joinToString(",") { """{"id":"pc$it","title":"Theirs $it","duration":200}""" } + "]}"

    /** Another account signs in while this service holds back the putting back of its saved
     *  queue ([PlaybackService.holdingRestore]): the account's own look at the server's
     *  queue, as the app makes it. */
    private fun anotherAccountWithItsQueueStillBeingPutBack(held: kotlinx.coroutines.CompletableDeferred<Unit>): org.medianexus.music.sync.QueueStep {
        service.holdingRestore = held
        server.answers["getPlayQueue"] = savedElsewhere()
        server.signIn(app, "second")
        settle()
        return app.graph.accounts.state.value!!.queueStep!!
    }

    /** Lets the main thread turn until `done`, or fails after thirty seconds. */
    private fun turnUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000L
        while (!done()) {
            settle()
            check(System.nanoTime() < until) { "not in thirty seconds: $what" }
            Thread.sleep(5)
        }
        settle()
    }

    @Test
    fun aLookWaitingForTheSavedQueueWhenTheServiceIsDestroyedGivesUpItsTurn() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val step = anotherAccountWithItsQueueStillBeingPutBack(held)
        // A look finds another device's queue to take up, and waits for the phone's own to
        // be back first...
        val asked = server.asked.count { it.startsWith("getPlayQueue") }
        val first = scope.async { step.check() }
        turnUntil("the look has read the server") { server.asked.count { it.startsWith("getPlayQueue") } > asked }
        repeat(20) { settle(); Thread.sleep(5) }
        assertFalse("the look is waiting for the saved queue", first.isCompleted)
        // ...when Android destroys the service. What it was waiting for will never come:
        // the look gives up, does nothing, and lets its turn go.
        service.onDestroy()
        turnUntil("the look gave up") { first.isCompleted }
        // A service again, and a later look: it has its turn, and takes the queue up.
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        inside = service.inside()
        settle()
        val later = scope.async { step.check() }
        turnUntil("a later look ran") { later.isCompleted }
        turnUntil("the queue was taken up") { inside.player.queue.current()?.id == "pc1" }
    }

    @Test
    fun aLookThatWasPreparingWhenSharingWentOffAndOnAgainAppliesNothing() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val step = anotherAccountWithItsQueueStillBeingPutBack(held)
        val asked = server.asked.count { it.startsWith("getPlayQueue") }
        val look = scope.async { step.check() }
        turnUntil("the look has read the server") { server.asked.count { it.startsWith("getPlayQueue") } > asked }
        repeat(20) { settle(); Thread.sleep(5) }
        assertFalse(look.isCompleted)
        // Sharing is switched off, and on again, while the queue it found is waiting to go
        // in. It was let go by the first, and stays let go.
        // (Switching on again asks for a look of its own, which is right. So that it is
        // the first look that is seen here, the server has nothing saved from now on: only
        // the look that had already read could put that queue in.)
        server.answers.remove("getPlayQueue")
        app.graph.settings.update { it.copy(sync = false) }
        app.graph.settings.update { it.copy(sync = true) }
        held.complete(Unit)
        turnUntil("the look is done") { look.isCompleted }
        repeat(40) { settle(); Thread.sleep(5) }
        assertTrue("another device's queue went into the player after sharing had been switched off", inside.player.queue.isEmpty)
        // A look begun since has its own leave.
        server.answers["getPlayQueue"] = savedElsewhere()
        val next = scope.async { step.check() }
        turnUntil("the next look ran") { next.isCompleted }
        turnUntil("the queue was taken up") { inside.player.queue.current()?.id == "pc1" }
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
