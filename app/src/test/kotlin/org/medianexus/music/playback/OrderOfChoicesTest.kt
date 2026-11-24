package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import androidx.media3.session.MediaController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The order of the person's choices ([Choices]): every choice takes one place, once, so that
 * the newest wins and a stop beats what was asked before it. Each case holds the older thing
 * at a gate, makes the newer choice there, and opens the gate: the real playback service, the
 * app's own controller connected to its session, and an account signed in to a stand-in
 * server. Nothing waits on time but [until], which has a limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class OrderOfChoicesTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var controller: MediaController
    private val server = StandInServer()
    private val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e })

    /** Android's foreground, which lets the service in. */
    private class Android : PlaybackService.Foreground {
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) { inIt = true }
        override fun leave() { inIt = false }
    }

    @Before
    fun start() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = Android()
        server.start()
        server.signIn(app)
        shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, PlaybackService::class.java),
            service.onBind(android.content.Intent(androidx.media3.session.MediaLibraryService.SERVICE_INTERFACE)),
        )
        val connecting = MediaController.Builder(app, service.inside().session.token).buildAsync()
        settle()
        controller = connecting.get()
        app.graph.player.connect()
        until("the screens' connection") { app.graph.player.reported.value || true }
    }

    @After
    fun stop() {
        scope.cancel()
        controller.release()
        app.graph.player.release()
        service.onDestroy()
        settle()
        server.stop()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, done: () -> Boolean) {
        val limit = System.nanoTime() + 30_000_000_000L
        while (!done()) {
            settle()
            check(System.nanoTime() < limit) { "$what: not within thirty seconds" }
            Thread.yield()
        }
    }

    /** A lookup for something chosen (an album's Play), held at a gate once it is out. */
    private fun lookupOut(acted: MutableList<String>, name: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        app.graph.player.afterLookup(scope, {
            reached.complete(Unit)
            gate.await()
            listOf(Song(id = name))
        }) { acted += name }
        until("the lookup is out") { reached.isCompleted }
        return gate
    }

    @Test
    fun resumingTakesAPlaceAtTheTap() {
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = lookupOut(acted, "album")
        // Play on what is loaded, while the album's songs are still being looked up.
        app.graph.player.playPause()
        gate.complete(Unit)
        settle()
        assertEquals("the older lookup replaced what was resumed", emptyList<String>(), acted.toList())
    }

    @Test
    fun aTapOnAnEntryOfTheQueueTakesAPlaceAtTheTap() {
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = lookupOut(acted, "album")
        app.graph.player.jump(42L)
        gate.complete(Unit)
        settle()
        assertFalse("the older lookup replaced the entry tapped", "album" in acted)
    }

    @Test
    fun aPlayFromAListIsPlayedAtTheTapsPlaceOrNotAtAll() {
        val songs = List(3) { Song(id = "late$it", title = "Late $it") }
        // Tapped on the phone: its place taken at the tap and carried.
        val args = PlayerConnection.playArgs(songs, 0, "A list")
        args.putLong(AppCommands.PLACE, Choices.take())
        // On its way, something else takes a later place (a choice in the car, say).
        val car = Choices.take()
        controller.sendCustomCommand(androidx.media3.session.SessionCommand(AppActions.PLAY, android.os.Bundle.EMPTY), args)
        settle()
        assertFalse("the older tap took the player", service.inside().player.queue.songs.any { it.id.startsWith("late") })
        assertEquals(true, Choices.stands(car))
        // Delivered with nothing newer chosen, the same tap plays.
        val again = PlayerConnection.playArgs(songs, 0, "A list")
        again.putLong(AppCommands.PLACE, Choices.take())
        controller.sendCustomCommand(androidx.media3.session.SessionCommand(AppActions.PLAY, android.os.Bundle.EMPTY), again)
        until("the tap played") { service.inside().player.queue.current()?.id == "late0" }
    }

    @Test
    fun aPlayWaitingForTheSavedQueueIsDroppedByAStopMeanwhile() {
        val inside = service.inside()
        // Something loaded to resume, paused.
        val songs = List(3) { Song(id = "q$it", title = "Q $it") }
        app.graph.player.play(songs, 0, "A list")
        until("the list played") { inside.player.queue.current()?.id == "q0" }
        controller.pause()
        settle()
        val gate = CompletableDeferred<Unit>()
        service.resumptionWaited = { gate.await() }
        val headset = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.example.car", 4_321, 10_123, androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, android.os.Bundle.EMPTY, true,
        )
        @Suppress("DEPRECATION")
        val resuming = inside.callback.onPlaybackResumption(inside.session, headset, true)
        settle()
        // While it waits: Stop.
        controller.stop()
        settle()
        gate.complete(Unit)
        until("the resumption answered") { resuming.isDone }
        assertEquals("a Stop was overtaken by the Play it came after", true, runCatching { resuming.get() }.isFailure)
        // With nothing in between, the same Play resumes.
        service.resumptionWaited = {}
        @Suppress("DEPRECATION")
        val again = inside.callback.onPlaybackResumption(inside.session, headset, true)
        until("the second resumption answered") { again.isDone }
        assertEquals("q0", again.get().mediaItems.firstOrNull()?.mediaId?.let { inside.player.queue.current()?.id })
        assertFalse(inside.exo.playWhenReady && inside.exo.isPlaying)
    }

    @Test
    fun aPlayWaitingForACallToEndIsDroppedByAStopMeanwhile() {
        val inside = service.inside()
        val songs = List(3) { Song(id = "c$it", title = "C $it") }
        app.graph.player.play(songs, 0, "A list")
        until("the list played") { inside.player.queue.current()?.id == "c0" }
        controller.pause()
        settle()
        // Play was refused the sound for a call: it waits for the call to end.
        service.waitForCall()
        settle()
        controller.stop()
        settle()
        service.callEnded()
        settle()
        assertFalse("the music started after a Stop", inside.exo.playWhenReady)
        // With nothing in between, it plays when the call ends.
        service.waitForCall()
        settle()
        service.callEnded()
        settle()
        assertEquals(true, inside.exo.playWhenReady)
    }
}
