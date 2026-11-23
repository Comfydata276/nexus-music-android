package org.medianexus.music.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Start intents sent to the exported playback service by another app, shaped like taps on
 * the notification's buttons. The service itself, on Media3's real session service, signed
 * in to a stand-in server; the intents are what any app on the phone can send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class ForgedStartTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = StandInServer()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside

    @Before
    fun start() {
        server.start()
        server.signIn(app)
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        inside = service.inside()
        settle()
        inside.player.play(List(4) { Song(id = "s$it", title = "Song $it") }, 1, "A list", playNow = false)
        settle()
    }

    @After
    fun stop() {
        service.onDestroy()
        settle()
        server.stop()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun likes() = app.graph.accounts.state.value!!.likes

    private fun noted() = app.graph.diagnostics.entries().map { it.text }

    /** A start intent shaped the way Media3 shapes a tap on one of the notification's own
     *  buttons: the names are Media3's, and public. */
    private fun forged(action: String, extras: Bundle = Bundle()) = Intent("androidx.media3.session.CUSTOM_NOTIFICATION_ACTION")
        .setComponent(ComponentName(app, PlaybackService::class.java))
        .putExtra("androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION", action)
        .putExtra("androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS", extras)

    /** Has Media3 connect its notification controller and post its notification, as it does
     *  once something is bound to the service and the player has been made ready. */
    private fun notificationShown() {
        service.addSession(inside.session)
        settle()
        inside.player.prepare()
        settle()
    }

    @Test
    fun aForgedStartCannotChangeALike() {
        notificationShown()
        assertFalse(likes().isLiked("s1"))
        service.onStartCommand(forged("org.medianexus.music.LIKE"), 0, 1)
        settle()
        // It used to be carried out as the notification's own: the song liked, on the
        // person's server, by whichever app sent the intent.
        assertFalse("another app liked a song", likes().isLiked("s1"))
        // Nor with extras of its own invention where the mark would be.
        service.onStartCommand(forged("org.medianexus.music.LIKE", Bundle().apply { putByteArray("org.medianexus.music.MARK", ByteArray(24)) }), 0, 2)
        service.onStartCommand(forged("org.medianexus.music.LIKE", Bundle().apply { putString("org.medianexus.music.MARK", "yes") }), 0, 3)
        settle()
        assertFalse(likes().isLiked("s1"))
        assertFalse(server.asked.any { it.startsWith("star") })
        assertTrue(noted().any { it.startsWith("a start request shaped like a notification button (like, shuffle or repeat), without this app's mark") })
    }

    @Test
    fun norShuffleRepeatOrAnythingElse() {
        notificationShown()
        service.onStartCommand(forged("org.medianexus.music.SHUFFLE"), 0, 1)
        service.onStartCommand(forged("org.medianexus.music.REPEAT"), 0, 2)
        // One of the app's own commands, and something made up.
        service.onStartCommand(forged(AppActions.CLEAR), 0, 3)
        service.onStartCommand(forged("com.example.ANYTHING secret-looking text"), 0, 4)
        settle()
        assertFalse(inside.player.queue.shuffle)
        assertEquals(Repeat.OFF, inside.player.queue.repeat)
        assertEquals(4, inside.player.queue.order.size)
        // And what the intent called itself is not written down.
        assertFalse(noted().any { it.contains("ANYTHING") || it.contains("secret") })
        assertTrue(noted().any { it.startsWith("a start request shaped like a notification button (other)") })
    }

    @Test
    fun theNotificationsOwnLikeButtonStillLikes() {
        notificationShown()
        // The notification Media3 posted, and the intent behind its like button: made by
        // Media3's own action factory from the button this app gave it.
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.lastOrNull()
        assertNotNull("no media notification was posted", posted)
        val like = posted!!.actions.orEmpty().firstOrNull { it.title == app.getString(R.string.add_to_liked) }
        assertNotNull("the notification has no like button: ${posted.actions.orEmpty().map { it.title }}", like)
        val tapped = shadowOf(like!!.actionIntent).savedIntent
        service.onStartCommand(tapped, 0, 1)
        settle()
        assertTrue(likes().isLiked("s1"))
        assertFalse(noted().any { it.contains("without this app's mark") })
    }

    @Test
    fun theSystemsOwnMediaControlsStillLike() {
        notificationShown()
        // The lock screen's and Quick Settings' media controls show the like button too, and
        // press it through the platform's session. Media3 reports System UI to the app under
        // its notification controller's name: that must not have been closed with the rest.
        val systemUi = inside.session.mediaNotificationControllerInfo
        assertNotNull(systemUi)
        val done = inside.callback.onCustomCommand(inside.session, systemUi!!, PlaybackService.LIKE, Bundle.EMPTY)
        settle()
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, done.get().resultCode)
        assertTrue(likes().isLiked("s1"))
    }

    @Test
    fun whatAStartIntentCallsItselfIsNotWrittenDown() {
        // The first thing ever sent to the service is noted as what started it.
        service.onStartCommand(Intent("com.example.SOMETHING.private-looking").setComponent(ComponentName(app, PlaybackService::class.java)), 0, 1)
        settle()
        assertFalse(noted().any { it.contains("private-looking") || it.contains("SOMETHING") })
        assertTrue(noted().any { it.startsWith("player started by a start request (other)") })
    }
}
