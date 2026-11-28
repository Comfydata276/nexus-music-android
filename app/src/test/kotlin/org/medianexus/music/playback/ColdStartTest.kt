package org.medianexus.music.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.session.DefaultMediaNotificationProvider
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.graph
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The playback service itself, with Media3's real session service under it, started the way
 * a headset or a car's Play button starts it, with nothing to resume: the case in which the
 * old order of things (read first, foreground when playing) never reached the foreground at
 * all. Nothing here waits in real time for the service to do something: the main thread
 * moves, and its clock moves, only when a test says so.
 *
 * What these can't show is the button's journey through the platform's own media session,
 * which a cold start takes before Media3's notification controller has connected: that is
 * the emulator's (docs/TESTING.md, "A Play button with the app not running").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class ColdStartTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun playButton() = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(app, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private var service: PlaybackService? = null
    private val server = StandInServer()

    private fun start(): PlaybackService = Robolectric.buildService(PlaybackService::class.java).create().get().also { service = it }

    /** The saved sign-in has been read before a test begins (there is none on this stand-in
     *  phone: nobody is signed in). That is another thread's work; done with, what a test
     *  sets going happens on the main thread, which only moves when the test says so. */
    @Before
    fun signInRead() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
    }

    @After
    fun stop() {
        service?.onDestroy()
        settle()
        server.stop()
    }

    @Test
    fun aPlayButtonPutsTheServiceInTheForegroundBeforeAnythingIsRead() {
        val service = start()
        val shadow = shadowOf(service)
        assertEquals(0, shadow.lastForegroundNotificationId)
        service.onStartCommand(playButton(), 0, 1)
        // At once: nothing has been read, no message has been handled, no time has passed.
        // (It used to wait here for the saved sign-in and the saved queue, up to six
        // seconds, and enter the foreground only once the player was buffering.)
        assertEquals(
            "not in the foreground when the start command returned",
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, shadow.lastForegroundNotificationId,
        )
        assertNotNull(shadow.lastForegroundNotification)
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun aStartThatComesToNothingIsEndedWhenItsTimeRunsOutAndNotBefore() {
        // Nobody is signed in, so Play is not something the session offers, and the button
        // that started the service is carried out as nothing at all. The start is ended by
        // its time limit: this is that case, and says so. (The test this replaces waited in
        // real time for the service to stop "because there was nothing to resume", moving
        // the main thread's clock on as it waited: what stopped it was this limit.)
        val service = start()
        val shadow = shadowOf(service)
        service.onStartCommand(playButton(), 0, 1)
        settle()
        assertFalse("ended before its time", shadow.isStoppedBySelf)
        // Six seconds on, still held; past seven, given up: out of the foreground, the
        // notification gone, stopped. (The clock is the main thread's own, moved by the test.)
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
        assertFalse(shadow.isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue("left running with nothing to play", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
    }

    /** Whether Media3 will still keep the service in the foreground for its ten minutes after
     *  playback stops (its `MediaNotificationManager`'s "user engaged" timeout): a switch it
     *  only ever turns off, which nothing outside Media3 can read but this. */
    private fun graceStands(service: PlaybackService): Boolean {
        val field = androidx.media3.session.MediaSessionService::class.java.getDeclaredField("mediaNotificationManager").apply { isAccessible = true }
        val manager = field.get(service) ?: return true
        return manager.javaClass.getDeclaredField("isUserEngagedTimeoutEnabled").apply { isAccessible = true }.getBoolean(manager)
    }

    @Test
    fun standingDownLeavesMediaThreesTenMinutesForWhatPlaysLater() {
        // A start that came to nothing is stood down. The service is stopped, but a car bound
        // to it keeps it alive; whatever it plays later must have Media3's ten minutes in the
        // foreground after a stop like any other playback. Media3's own way of stopping
        // (`pauseAllPlayersAndStopSelf`) switches them off for the rest of the service's
        // life, so the next Wi-Fi dropout would put it out of the foreground at once.
        val service = start()
        val shadow = shadowOf(service)
        service.onStartCommand(playButton(), 0, 1)
        settle()
        assertTrue(graceStands(service))
        shadowOf(Looper.getMainLooper()).idleFor(8, TimeUnit.SECONDS)
        assertTrue("not stood down", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertTrue("Media3's ten minutes after a stop were switched off for good", graceStands(service))
    }

    @Test
    fun swipedAwayWhilePausedStopsWithoutSwitchingOffMediaThreesTenMinutes() {
        val service = start()
        service.onTaskRemoved(null)
        settle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue("Media3's ten minutes after a stop were switched off for good", graceStands(service))
    }

    @Test
    fun playWithNothingToResumeIsEndedAtOnceNotWaitedOut() {
        // Someone is signed in, and nothing was ever saved for them to carry on with.
        server.start()
        server.signIn(app)
        val service = start()
        val inside = service.inside()
        val shadow = shadowOf(service)
        // Media3's own controller for the notification is connected, as it is once anything
        // has bound to the service: a button then goes through Media3 to the session.
        service.addSession(inside.session)
        // The account is taken on, and its saved queue read (there is none): files, read on
        // another thread. Waited for here, before the part that is being tested; the main
        // thread's clock does not move while it is, so no time limit can run out.
        val until = System.nanoTime() + 30_000_000_000L
        while (!inside.session.sessionExtras.getBoolean(PlaybackService.EXTRA_SETTLED)) {
            settle()
            check(System.nanoTime() < until) { "the saved queue was not read in thirty seconds" }
            Thread.yield()
        }
        service.onStartCommand(playButton(), 0, 1)
        assertEquals(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        // The button is carried out, by Media3: Play, with nothing in the player; the app is
        // asked what to resume, and has nothing. It ends there. No time has passed: it is
        // not the limit on a start that ended it.
        settle()
        assertTrue("left in the foreground with nothing to play", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertFalse(inside.exo.playWhenReady)
        assertTrue(app.graph.diagnostics.entries().any { it.text.startsWith("asked to play with nothing to play") })
    }

    @Test
    fun aRefusedLoginSkipsTheOnlineSongAndExplainsWhenThereIsNothingLeft() {
        server.start()
        server.signIn(app)
        val service = start()
        val inside = service.inside()
        val until = System.nanoTime() + 30_000_000_000L
        while (!inside.session.sessionExtras.getBoolean(PlaybackService.EXTRA_SETTLED)) {
            settle()
            check(System.nanoTime() < until) { "the saved queue was not read in thirty seconds" }
            Thread.yield()
        }
        val songs = listOf("first", "online", "download").map {
            org.medianexus.music.net.Song(id = it, title = it, suffix = "flac")
        }
        val catalogue = app.graph.accounts.state.value!!.catalogue
        catalogue.add(org.medianexus.music.downloads.Downloaded(songs[2], "download.flac", 4, at = 1, suffix = "flac"))
        inside.player.play(songs, 1, "Mix", playNow = false)
        val failed = StreamFailure.of(org.medianexus.music.net.RefusedLogin())
        val error = androidx.media3.common.PlaybackException(failed.message, failed, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        inside.watcher.onPlayerError(error)
        assertEquals("download", inside.player.queue.current()?.id)
        assertEquals(songs, inside.player.queue.playingOrder())
        catalogue.remove(setOf("download"))
        inside.player.play(songs.take(2), 1, "Mix", playNow = false)
        inside.watcher.onPlayerError(error)
        assertEquals("online", inside.player.queue.current()?.id)
        assertEquals(app.getString(org.medianexus.music.R.string.car_sign_in_refused), service.explain(error, songs[1])?.message)
    }

    @Test
    fun theButtonReceiversOldNameStillLeadsSomewhere() {
        val packages = app.packageManager
        // The first builds named Media3's own receiver. The system goes on sending a Play
        // button to the name it was last given until the app is next opened.
        val old = packages.getReceiverInfo(ComponentName(app, "androidx.media3.session.MediaButtonReceiver"), 0)
        assertFalse("open to other apps", old.exported)
        // And the session still finds exactly one receiver for media buttons: with two,
        // Media3 refuses to make a session at all.
        val found = packages.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName), 0)
        assertEquals(listOf(PlayButtonReceiver::class.java.name), found.map { it.activityInfo.name })
    }
}
