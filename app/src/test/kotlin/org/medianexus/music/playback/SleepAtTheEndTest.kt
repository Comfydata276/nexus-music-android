package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.media3.test.utils.FakeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Rule 6 of the fixes: when the sleep timer pauses the music, that counts as the person
 * having stopped it, and nothing starts it again. The case the reviewer raised: repeat all,
 * a queue too long for the player to hold whole (it goes round to the top by hand when the
 * player ends), and "End of this song" pausing at the end of the last song the player holds.
 * Media3 pauses there and stays ready at the end, so the queue's going round (which happens
 * when the player ends) is never reached; it now also goes round only to line up, not to
 * play, if the player was not playing. The real ExoPlayer under the queue's player.
 */
@RunWith(RobolectricTestRunner::class)
class SleepAtTheEndTest {
    @Test
    fun paused_by_the_sleep_timer_at_the_end_of_the_queue_on_repeat_all_it_stays_paused() {
        val context = RuntimeEnvironment.getApplication()
        val exo = madeUpClockPlayer(context, FakeMediaSourceFactory(), FakeRenderer(C.TRACK_TYPE_VIDEO))
        val player = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, byPlace = { false })
        try {
            val songs = List(400) { Song(id = "s$it", title = "s$it") }
            player.play(songs, 399, "A long list", playNow = false)
            shadowOf(Looper.getMainLooper()).idle()
            player.repeatMode = Player.REPEAT_MODE_ALL
            exo.prepare()
            advance(exo).untilState(Player.STATE_READY)
            // To the last thing the player holds, near its end; "End of this song" is set,
            // and the player pauses at the song's end by itself (Media3 stays there, ready).
            exo.seekTo(exo.mediaItemCount - 1, 9_000)
            exo.pauseAtEndOfMediaItems = true
            player.play()
            advance(exo).untilPlayWhenReadyIs(false)
            shadowOf(Looper.getMainLooper()).idle()
            // Media3 stays there, ready, paused: nothing goes round to the top and starts.
            advance(exo).untilPendingCommandsAreFullyHandled()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("still paused", exo.playWhenReady)
            assertEquals(Player.STATE_READY, exo.playbackState)
        } finally {
            player.release()
            exo.release()
        }
    }
}
