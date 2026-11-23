package org.medianexus.music.playback

import android.app.Notification
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.R
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the playback service does when it is asked for music from somewhere Android may not
 * let it play: the service itself, on Media3's real session service, with a stand-in for the
 * one thing that can say no (Android's foreground). Nothing here waits: the main thread only
 * moves when a test says so, which is what holds a request at its arrival, before anything
 * has been looked up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class StartingTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val android = Android()

    /** Android's foreground: it lets the service in, or (`allows` false) refuses as Android
     *  12 and later refuse a service that has no standing to enter it. */
    private class Android : PlaybackService.Foreground {
        var allows = true
        var inIt = false
        var entered = 0
        var asked = 0

        override fun isIn() = inIt

        override fun enter(notification: Notification) {
            asked++
            if (!allows) throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
            inIt = true
            entered++
        }

        override fun leave() {
            inIt = false
        }
    }

    /** A car, as Media3 describes one that speaks the platform's older protocol. */
    private val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.example.car", 4_321, 10_123, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle.EMPTY, true,
    )

    @Before
    fun start() {
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = android
        inside = service.inside()
    }

    @After
    fun stop() {
        service.onDestroy()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun songs(count: Int) = List(count) { Song(id = "s$it", title = "Song $it") }

    private fun noted() = app.graph.diagnostics.entries().map { it.text }

    @Test
    fun aSongChosenInTheCarIsInTheForegroundBeforeAnythingIsLookedUp() {
        val asked = inside.callback.onSetMediaItems(
            inside.session, car, listOf(MediaItem.Builder().setMediaId("track/2/s2/album/a1").build()), C.INDEX_UNSET, C.TIME_UNSET,
        )
        // The request has only arrived. Who is signed in has not been asked, no list has
        // been looked for, and the main thread has not moved on: and the service is already
        // in the foreground. (It used to enter it only when the queue was built and Play was
        // on its way, by when Android's allowance for a command from a car may have gone.)
        assertFalse("answered before the main thread moved", asked.isDone)
        assertEquals(1, android.entered)
        assertTrue(noted().any { it.startsWith("foreground: entered at once for a choice") })
    }

    @Test
    fun aSpokenRequestIsInTheForegroundBeforeItsSevenSeconds() {
        val words = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("slow rivers").build()).build()
        val asked = inside.callback.onSetMediaItems(inside.session, car, listOf(words), C.INDEX_UNSET, C.TIME_UNSET)
        assertFalse(asked.isDone)
        assertEquals(1, android.entered)
    }

    @Test
    fun playWithNothingLoadedIsInTheForegroundBeforeTheSavedQueueIsWaitedFor() {
        // The system asking what it would resume, to draw its card: nothing is to play, so
        // nothing is entered.
        inside.callback.onPlaybackResumption(inside.session, car, false)
        assertEquals(0, android.asked)
        // Play: twice three seconds may be waited from here, for the sign-in and the queue.
        val asked = inside.callback.onPlaybackResumption(inside.session, car, true)
        assertFalse(asked.isDone)
        assertEquals(1, android.entered)
    }

    @Test
    fun whenAndroidRefusesTheForegroundNothingIsPlayedAndTheCarIsToldWhy() {
        inside.player.play(songs(5), 2, "A list", playNow = false)
        settle()
        android.allows = false
        // Play, from the car's screen or its wheel.
        inside.player.play()
        settle()
        // Not played: it used to be noted, and played all the same, in a service Android
        // would silence (Android 17) or give no sound to (15 and 16).
        assertFalse("asked to play from the background", inside.exo.playWhenReady)
        assertFalse(inside.player.playWhenReady)
        assertTrue(noted().any { it.startsWith("foreground: Android refused it") })
        // The queue is as it was chosen, and the car has the reason in words, on a player
        // that still offers Play.
        assertEquals("s2", inside.player.queue.current()?.id)
        assertEquals(app.getString(R.string.car_open_to_play), inside.player.playerError?.message)
        assertTrue(inside.player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        // The next Play that Android allows (the app was opened; or the car asks again and
        // this time it may) plays, and the words go.
        android.allows = true
        inside.player.play()
        settle()
        assertTrue(inside.exo.playWhenReady)
        assertNotEquals(app.getString(R.string.car_open_to_play), inside.player.playerError?.message)
        assertEquals("s2", inside.player.queue.current()?.id)
    }

    @Test
    fun whenAndroidWillNotGiveTheSoundStraightAfterPlayTheCarIsToldWhy() {
        inside.player.play(songs(5), 0, "A list", playNow = false)
        settle()
        // Play is pressed, and at once the player is told it may not have the sound (what
        // Android 15 and later do to an app with no standing to be heard).
        inside.watcher.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        inside.watcher.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        settle()
        assertTrue(noted().any { it.startsWith("audio focus: refused") })
        assertFalse(inside.exo.playWhenReady)
        assertEquals(app.getString(R.string.car_open_to_play), inside.player.playerError?.message)
        // Later on, another app taking the sound is only that: nothing is said to the car.
        android.allows = true
        inside.player.play()
        settle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(3))
        inside.watcher.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        settle()
        assertNotEquals(app.getString(R.string.car_open_to_play), inside.player.playerError?.message)
        assertNull(noted().firstOrNull { it.contains("could not be started") && it.contains("another app") })
    }

    @Test
    fun mediaThreeIsNotTakenToHoldTheForegroundUnlessAndroidSaysTheServiceIsInIt() {
        inside.player.play(songs(3), 0, "A list", playNow = false)
        settle()
        inside.player.play()
        settle()
        assertEquals(1, android.entered)
        // Media3 is asked to bring its notification up to date "in the foreground". With
        // nothing it thinks worth showing, it takes the notification away instead, and the
        // foreground that was being held for the start goes with it; and it reports success.
        val updated = service.onUpdateNotificationAsync(inside.session, true)
        android.inIt = false
        settle()
        assertTrue(updated.isDone)
        // That used to be taken for "Media3's notification holds the foreground from here".
        assertFalse(noted().any { it.startsWith("foreground: playing in it") })
        assertTrue(noted().any { it.startsWith("foreground: Media3 did not take it over") })
    }
}
