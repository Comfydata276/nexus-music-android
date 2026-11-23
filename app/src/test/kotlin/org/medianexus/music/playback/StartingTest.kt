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
        // The saved sign-in has been read (there is none: nobody is signed in on this
        // stand-in phone), so nothing a test does waits on another thread for it.
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
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

    @Test
    fun aRequestFromThePhoneThatComesToNothingDoesNotStartWhatIsThere() {
        // Someone is signed in (with nobody signed in a controller isn't offered Play at
        // all), and something is in the player, paused.
        val server = StandInServer().also { it.start() }
        try {
            server.signIn(app)
            settle()
            inside.player.play(songs(5), 1, "A list", playNow = false)
            settle()
            // "Play X on Nexus Music", said to the phone: the voice activity sends the
            // words, "make ready" and "play" as three commands, through a controller of its
            // own.
            val connecting = androidx.media3.session.MediaController.Builder(app, inside.session.token).buildAsync()
            settle()
            val phone = connecting.get()
            assertTrue(phone.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            // (An item with nothing to say which song: the request is refused as soon as it
            // is looked at, with no word to the server.)
            phone.setMediaItems(listOf(MediaItem.Builder().setMediaId("a").build(), MediaItem.Builder().setMediaId("b").build()))
            phone.prepare()
            phone.play()
            settle()
            // The request came to nothing. Its "make ready" and "play" arrived all the same,
            // and used to start the paused queue.
            assertFalse("the paused queue was started by a request that failed", inside.exo.playWhenReady)
            assertEquals("s1", inside.player.queue.current()?.id)
            // The same controller's next Play is a Play.
            phone.play()
            settle()
            assertTrue(inside.exo.playWhenReady)
            phone.release()
        } finally {
            server.stop()
        }
    }

    // ---- Who is asking ------------------------------------------------------------------------

    @Test
    fun onAnOlderPhoneACallerIsNotTrustedUnderANameThatIsNotItsOwn() {
        // Android 9 to 12, the platform's own transport: the name comes from the caller and
        // nobody has checked it, and Media3's "trusted" includes "an app with notification
        // access", which it looks up by that name alone. So an app that gives the name of
        // one that has it (the system's own, a watch's companion) arrives as trusted.
        val borrowed = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.example.watch", 4_321, 10_555, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle.EMPTY, false,
        )
        assertFalse("let in under another app's name", inside.callback.onConnect(inside.session, borrowed).isAccepted)
        assertTrue(noted().any { it.startsWith("connection from com.example.watch") && it.contains("refused") && it.contains("its name is not its own") })
        // The app that really has that name, at its own uid, is let in.
        org.robolectric.Shadows.shadowOf(app.packageManager).setPackagesForUid(10_556, "com.example.watch")
        val real = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.example.watch", 4_322, 10_556, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle.EMPTY, false,
        )
        assertTrue(inside.callback.onConnect(inside.session, real).isAccepted)
        // And where the system has checked the name (Android 13 and later), nothing more is asked.
        assertTrue(inside.callback.onConnect(inside.session, car).isAccepted)
    }

    // ---- What a car is told when the server says no ---------------------------------------------

    /** Lets the main thread run until `future` is done: what it waits on is other threads'
     *  work (a request to the stand-in server), which comes back to the main thread. */
    private fun <T> await(future: com.google.common.util.concurrent.ListenableFuture<T>): T {
        val until = System.nanoTime() + 30_000_000_000L
        while (!future.isDone) {
            settle()
            check(System.nanoTime() < until) { "no answer in thirty seconds" }
            Thread.yield()
        }
        return future.get()
    }

    @Test
    fun theServersOwnWordsNeverReachTheSession() {
        val server = StandInServer().also { it.start() }
        try {
            server.signIn(app)
            settle()
            // The server refuses, in words of its own: a path on its disk, as servers do.
            val words = "open /srv/music/Private Sessions/demo.flac: permission denied"
            server.refuses["getPlaylist"] = words
            val answer = await(inside.callback.onGetChildren(inside.session, car, "playlist/p1", 0, Int.MAX_VALUE, null))
            // The car is told something went wrong, in the app's words for that kind of
            // failure. The server's went into the session's error before: where a car's
            // screen, and any other app with a controller, reads it.
            val said = answer.sessionError!!.message
            assertEquals(app.getString(R.string.car_server_refused), said)
            assertFalse(said.contains("Private") || said.contains("permission"))
            // Inside the app they are kept, for the phone's own screens to show the person
            // whose server it is.
            assertTrue(app.graph.refusals.last.value!!.words.contains(words))
            assertFalse(noted().any { it.contains("Private") })
        } finally {
            server.stop()
        }
    }
}
