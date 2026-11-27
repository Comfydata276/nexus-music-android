package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * With "Same as Wi-Fi" the app is the released one (the lead's ruling R3), on the real playback
 * service: the sequences the recheck of the mobile data quality found it was not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class DefaultAsReleasedTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var controller: MediaController
    private val server = StandInServer()

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
    }

    @After
    fun stop() {
        controller.release()
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

    @Test
    fun aQualityChangedWhileAStreamPlaysWhoseDownloadHasFinishedOpensItAgainAsReleased() {
        assertEquals(org.medianexus.music.store.SAME_AS_WIFI, app.graph.settings.value.mobileBitrate)
        val inside = service.inside()
        // A song streamed (the server holds it: the player is making it ready from the stream).
        server.holdStreams = true
        val song = Song(id = "s1", title = "One", suffix = "mp3", bitRate = 128, duration = 200)
        inside.player.play(listOf(song), 0, "A list")
        until("the stream asked for") { server.asked.contains("stream s1") }
        // Its download finishes meanwhile.
        val catalogue = app.graph.accounts.state.value!!.catalogue
        File(catalogue.dir, "s1.mp3").writeBytes(ByteArray(64))
        catalogue.add(Downloaded(song, "s1.mp3", 64, at = 1, suffix = "mp3"))
        var idle = 0
        inside.exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_IDLE) idle++
            }
        })
        // The streaming quality changed: released stopped and made the song ready again,
        // because what was playing was the stream (not because a download is in the catalogue).
        app.graph.settings.update { it.copy(bitrate = 160) }
        until("the song opened again") { idle > 0 }
        assertTrue(idle > 0)
    }
}
