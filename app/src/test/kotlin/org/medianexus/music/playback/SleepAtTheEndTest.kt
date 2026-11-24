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
 * The case the reviewer raised for the sleep timer: repeat all, a queue too long for the
 * player to hold whole (it goes round to the top by hand when the player ends), and "End of
 * this song" pausing at the end of the last song the player holds. What this checks is that
 * Media3 pauses there and stays there, ready and paused, and nothing starts it again. The
 * real ExoPlayer under the queue's player.
 *
 * **The guard has no test.** The queue's going round at the true end now lines up without
 * playing if the person (or the timer) had stopped (`QueuePlayer`, `carryOn` in the ended
 * branch of repeat all). That branch runs only when the player ends, and paused at the end of
 * its last item by "End of this song" it never does: it stays ready. No other way to the end
 * with the music stopped was found, so the guard is there for safety and nothing here would
 * fail without it.
 */
@RunWith(RobolectricTestRunner::class)
class SleepAtTheEndTest {
    @Test
    fun paused_at_the_end_of_the_last_song_held_on_repeat_all_media3_stays_ready_and_paused() {
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
