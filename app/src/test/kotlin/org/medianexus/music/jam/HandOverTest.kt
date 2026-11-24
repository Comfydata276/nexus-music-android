package org.medianexus.music.jam

import android.app.Notification
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Choices
import org.medianexus.music.playback.PlaybackService
import org.medianexus.music.playback.StandInServer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rule 3: music arriving from another device is a choice someone made. It takes its place in
 * the order of choices as it arrives, never overrides something chosen on the phone after
 * that, respects offline mode, and is refused (the other device told why) while the phone is
 * in a call or another app holds the sound. The real playback service and its hand-over, an
 * account signed in to a stand-in server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class HandOverTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private val server = StandInServer()
    private val main = CoroutineScope(Dispatchers.Main.immediate)

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
        // The app's own connection to the service, as Android would bind it.
        shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, PlaybackService::class.java),
            service.onBind(android.content.Intent(androidx.media3.session.MediaLibraryService.SERVICE_INTERFACE)),
        )
        until("the hand-over") { app.graph.steered.handOver != null }
    }

    @After
    fun stop() {
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

    private fun <T> onMain(block: suspend () -> T): T {
        val running = main.async { block() }
        until("done") { running.isCompleted }
        return running.getCompleted()
    }

    private val handed = List(4) { Song(id = "pc$it", title = "From the PC $it") }

    @Test
    fun aQueueHandedOverGivesWayToAChoiceMadeOnThePhoneAfterItArrived() {
        val handOver = app.graph.steered.handOver!!
        val arrived = Choices.take()
        // On the phone, after it arrived and before it was ready, the person chooses something.
        val chosen = Choices.take()
        assertEquals(false, onMain { handOver.take(handed, 1, 5_000, play = true, context = "", place = arrived) })
        assertEquals(true, Choices.stands(chosen))
        assertEquals(null, service.inside().player.queue.current()?.id?.takeIf { it.startsWith("pc") })
        // With nothing chosen since it arrived, it plays, from where it was.
        val again = Choices.take()
        assertEquals(true, onMain { handOver.take(handed, 1, 5_000, play = true, context = "", place = again) })
        assertEquals("pc1", service.inside().player.queue.current()?.id)
    }

    @Test
    fun aQueueHandedOverIsRefusedInACallOrWithTheSoundHeldElsewhere() {
        val own = OwnPlayer(app, app.graph)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_CALL
        assertEquals(OwnPlayer.Refusal.IN_CALL, onMain { own.load(handed, 0, 0, play = true, context = "", place = Choices.take()) })
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals(OwnPlayer.Refusal.SOUND_TAKEN, onMain { own.load(handed, 0, 0, play = true, context = "", place = Choices.take()) })
        // Neither changed what was here.
        assertNull(service.inside().player.queue.current()?.id?.takeIf { it.startsWith("pc") })
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertNull(onMain { own.load(handed, 0, 0, play = true, context = "", place = Choices.take()) })
        until("handed over") { service.inside().player.queue.current()?.id == "pc0" }
    }

    @Test
    fun offlineOnlyWhatPlaysHereIsTakenAndNothingIsRefused() {
        val own = OwnPlayer(app, app.graph)
        app.graph.settings.update { it.copy(offline = true) }
        until("offline") { app.graph.watch.offline }
        // Nothing of it is on the phone.
        assertEquals(OwnPlayer.Refusal.OFFLINE, onMain { own.load(handed, 0, 0, play = false, context = "", place = Choices.take()) })
        assertNull(service.inside().player.queue.current()?.id?.takeIf { it.startsWith("pc") })
        app.graph.settings.update { it.copy(offline = false) }
    }
}
