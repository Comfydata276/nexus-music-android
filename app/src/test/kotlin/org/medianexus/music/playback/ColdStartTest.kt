package org.medianexus.music.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.session.DefaultMediaNotificationProvider
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The playback service itself, with Media3's real session service under it, started cold the
 * way a headset or a car's Play button starts it. Nobody is signed in on this stand-in
 * phone, so there turns out to be nothing to resume: the case in which the old order of
 * things (read first, foreground when playing) never reached the foreground at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class ColdStartTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun playButton() = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(app, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

    @Test
    fun aPlayButtonPutsTheServiceInTheForegroundBeforeAnythingIsRead() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
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
        // The saved sign-in is read (on another thread): nobody. Nothing to resume, so the
        // start is ended cleanly: out of the foreground, notification gone, stopped.
        val until = System.currentTimeMillis() + 20_000
        while (!shadow.isStoppedBySelf && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertTrue("left running with nothing to play", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        service.onDestroy()
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
