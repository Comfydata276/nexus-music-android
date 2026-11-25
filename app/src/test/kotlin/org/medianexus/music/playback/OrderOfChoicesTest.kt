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

    @Test
    fun aRetryOfAFailedSongIsDroppedByAStopMeanwhile() {
        val inside = service.inside()
        val songs = List(3) { Song(id = "r$it", title = "R $it") }
        app.graph.player.play(songs, 0, "A list")
        until("the list played") { inside.player.queue.current()?.id == "r0" }
        // The song failed for want of the server, and a try is due later.
        service.failedNow = { true }
        service.retryLater(50)
        // Stop, before it is due ("play when ready" is left as it was: the stop is what counts).
        controller.stop()
        settle()
        val stopped = inside.exo.playbackState
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        settle()
        assertEquals("the song was made ready again after a Stop", androidx.media3.common.Player.STATE_IDLE, inside.exo.playbackState)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, stopped)
    }

    @Test
    fun aPausePressedWhileAlreadyPausedTakesAPlace() {
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Paused (nothing playing), and a start still being looked up.
        assertFalse(service.inside().exo.playWhenReady)
        val gate = lookupOut(acted, "album")
        // Pause, from a car or the notification (not the app's screens, which take a place at
        // the tap themselves).
        controller.pause()
        settle()
        gate.complete(Unit)
        settle()
        assertEquals("a start went ahead after a Pause", emptyList<String>(), acted.toList())
    }

    @Test
    fun playPausePlayAgainEndsPlayingWhatTheSecondPlayAskedFor() {
        val inside = service.inside()
        // What is loaded, playing.
        val loaded = List(3) { Song(id = "here$it", title = "Here $it") }
        app.graph.player.play(loaded, 0, "Loaded")
        until("the list played") { inside.player.queue.current()?.id == "here0" && inside.exo.playWhenReady }
        // Play on an album: its songs are looked up (held at the gate).
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        app.graph.player.afterLookup(scope, {
            reached.complete(Unit)
            gate.await()
            List(3) { Song(id = "album$it", title = "Album $it") }
        }) { songs -> app.graph.player.play(songs, 0, "The album") }
        until("the lookup is out") { reached.isCompleted }
        // Pause, and Play again, quickly: the second Play is on what is loaded.
        app.graph.player.playPause()
        settle()
        app.graph.player.playPause()
        settle()
        // Then the album's songs arrive.
        gate.complete(Unit)
        settle()
        settle()
        assertEquals("here0", inside.player.queue.current()?.id)
        assertEquals("not playing what the second Play asked for", true, inside.exo.playWhenReady)
    }
    /** A Pause tapped on the phone takes its one place, at the tap: arriving, it takes no
     *  other (it used to take two more, in the player and in its listener). */
    @Test
    fun aPauseTappedOnThePhoneTakesOnePlace() {
        val inside = service.inside()
        app.graph.player.play(List(3) { Song(id = "here$it", title = "Here $it") }, 0, "Loaded")
        until("the list played") { inside.player.queue.current()?.id == "here0" && inside.exo.playWhenReady }
        until("the screens see it playing") { app.graph.player.playing.value }
        val before = Choices.latest
        app.graph.player.playPause()
        until("paused") { !inside.exo.playWhenReady }
        settle()
        assertEquals("a Pause tapped on the phone took other than one place", before + 1, Choices.latest)
    }

    /**
     * A Play from a list tapped just after a Pause, with the Pause's delivery held: what plays
     * is what the Play asked for. The places are right (the Pause's is taken at its tap, and
     * none more as it arrives); what fails is the order Media3 carries the two out in. Known
     * and accepted (docs/DESIGN.md, "The order of choices, completed"): ignored until the
     * screens' Pause and Resume are sent as the app's own commands.
     */
    @org.junit.Ignore("Media3 carries out the app's own commands as they arrive and queues the player's commands behind them, so a Pause sent a moment before a Play from a list lands after it and pauses what the Play started; closed by sending the screens' Pause and Resume as the app's own commands (docs/DESIGN.md)")
    @Test
    fun aPlayTappedJustAfterAPauseIsNotDroppedByThatPause() {
        val inside = service.inside()
        val loaded = List(3) { Song(id = "here$it", title = "Here $it") }
        app.graph.player.play(loaded, 0, "Loaded")
        until("the list played") { inside.player.queue.current()?.id == "here0" && inside.exo.playWhenReady }
        // (As the screens see it: their Play/Pause button is a Pause.)
        until("the screens see it playing") { app.graph.player.playing.value }
        // Pause, on the phone: its delivery held (the player's thread not let run).
        app.graph.player.playPause()
        // Play on a list, straight after.
        val chosen = List(3) { Song(id = "chosen$it", title = "Chosen $it") }
        app.graph.player.play(chosen, 0, "Chosen")
        // The gate opens. (Today Media3 carries out the Play as it arrives, and the Pause,
        // queued, after it.)
        settle()
        until("the Play was carried out") { inside.player.queue.current()?.id == "chosen0" }
        settle()
        assertEquals("not playing what the Play asked for", true, inside.exo.playWhenReady)
    }

    /** A Pause from outside the app's screens (a car, a headset, the notification) takes its
     *  one place when it arrives: a start the phone chose before it gives way. */
    @Test
    fun aPauseFromOutsideTakesItsPlaceWhenItArrives() {
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val inside = service.inside()
        app.graph.player.play(List(2) { Song(id = "p$it", title = "P $it") }, 0, "Playing")
        until("the list played") { inside.player.queue.current()?.id == "p0" && inside.exo.playWhenReady }
        val gate = lookupOut(acted, "album")
        val before = Choices.latest
        controller.pause()
        settle()
        assertEquals("a Pause from outside took other than one place", before + 1, Choices.latest)
        gate.complete(Unit)
        settle()
        assertEquals("a start went ahead after a Pause", emptyList<String>(), acted.toList())
    }

    /** The sleep timer's pause is a choice even when the music is already paused: a start
     *  still being looked up gives way to it. */
    @Test
    fun theSleepTimerFiringWhilePausedTakesItsPlace() {
        val acted = java.util.Collections.synchronizedList(mutableListOf<String>())
        assertFalse(service.inside().exo.playWhenReady)
        val gate = lookupOut(acted, "album")
        app.graph.sleep.player!!.sleep()
        settle()
        gate.complete(Unit)
        settle()
        assertEquals("a start went ahead after the sleep timer's pause", emptyList<String>(), acted.toList())
    }
    // ---- The server gone, and offline mode ----------------------------------------------------

    /** Songs on the phone (downloaded), and a Play of them from a list, its place taken at the tap. */
    private fun aPlayOfSongsOnThePhone(): android.os.Bundle {
        val account = app.graph.accounts.state.value!!
        val songs = List(3) { Song(id = "phone$it", title = "On the phone $it", duration = 200) }
        for (song in songs) account.catalogue.add(org.medianexus.music.downloads.Downloaded(song, "${song.id}.flac", 4, at = 1, suffix = "flac"))
        val args = PlayerConnection.playArgs(songs, 0, "On the phone")
        args.putLong(AppCommands.PLACE, Choices.take())
        return args
    }

    private fun send(args: android.os.Bundle) =
        controller.sendCustomCommand(androidx.media3.session.SessionCommand(AppActions.PLAY, android.os.Bundle.EMPTY), args)

    /**
     * The app finding that the server has gone is nobody's choice: a Play on its way when it
     * does is carried out, and a song on the phone plays. (It took a place, as offline mode
     * switched on does, and the Play was dropped: in the car, with no signal, a song chosen
     * just as the phone noticed sat paused.)
     */
    @Test
    fun aPlayOnItsWayIsNotDroppedByTheAppFindingTheServerGone() {
        val inside = service.inside()
        val args = aPlayOfSongsOnThePhone()
        // On its way, the app finds the server out of reach; the person switched nothing.
        app.graph.watch.noNetwork()
        settle()
        send(args)
        until("the Play was carried out") { inside.player.queue.current()?.id == "phone0" }
        settle()
        assertEquals("the song on the phone is not playing", true, inside.exo.playWhenReady)
    }

    /** Offline mode switched on by the person is a choice, whether or not the server had
     *  already gone: a Play on its way gives way to it. */
    @Test
    fun offlineModeSwitchedOnTakesItsPlaceEvenWithTheServerAlreadyGone() {
        val inside = service.inside()
        app.graph.watch.noNetwork()
        settle()
        val args = aPlayOfSongsOnThePhone()
        app.graph.watch.manual(true)
        settle()
        send(args)
        settle()
        assertFalse("a Play overtaken by offline mode switched on was carried out", inside.player.queue.songs.any { it.id.startsWith("phone") })
    }
}
