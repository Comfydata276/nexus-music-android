package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Passing over a song that can't be played, to the next that can (offline hardening 3, item
 * 5): on the real ExoPlayer under the queue's player, whose playlist is only a window of a
 * long queue. The next that plays is looked for in the queue itself, in the order it will
 * play, and the player is then moved to it wherever it is.
 */
@RunWith(RobolectricTestRunner::class)
class PassOverTest {
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private val songs = List(600) { Song(id = "s$it", title = "Song $it") }

    @Before
    fun start() {
        exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, byPlace = { false })
    }

    @After
    fun stop() {
        player.release()
        exo.release()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun playing() = player.queue.current()!!.id

    /** The songs the player itself holds, in order. */
    private fun held(): List<String> {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        return List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem.mediaId }
    }

    /** As the service looks for it (`PlaybackService.nextOnThePhone`). */
    private fun nextOnThePhone(onPhone: Set<String>): Int? {
        val queue = player.queue
        return Recovery.nextThatPlays(queue.cursor, queue.order.size, queue.repeat) { queue.songs[queue.order[it]].id in onPhone }
    }

    @Test
    fun theNextThatPlaysIsFoundBeyondThePartOfTheQueueThePlayerHolds() {
        player.play(songs, 0, "A list", playNow = false)
        settle()
        // The player holds the first couple of hundred songs. The only song on the phone is
        // the 451st.
        assertFalse("s450" in held())
        val next = nextOnThePhone(setOf("s450"))
        // (Looked for along the player's own playlist, there was "nothing further on that is
        // on the phone", and it stopped.)
        assertEquals(450, next)
        player.passTo(next!!)
        settle()
        assertEquals("s450", playing())
        assertEquals("s450", exo.currentMediaItem!!.mediaId)
        assertTrue("s450" in held())
        // The queue is as it was: nothing was taken out of it or brought forward to get there.
        assertEquals(600, player.queue.order.size)
        assertEquals(450, player.queue.cursor)
        assertEquals(songs.map { it.id }, player.queue.playingOrder().map { it.id })
    }

    @Test
    fun withRepeatOnItGoesRoundToTheTop() {
        player.play(songs, 590, "A list", playNow = false)
        settle()
        // Nothing after the 591st is on the phone; the 4th is.
        assertEquals(null, nextOnThePhone(setOf("s3")))
        player.setRepeat(Repeat.ALL)
        settle()
        val next = nextOnThePhone(setOf("s3"))
        assertEquals(3, next)
        player.passTo(next!!)
        settle()
        assertEquals("s3", playing())
        assertEquals("s3", exo.currentMediaItem!!.mediaId)
    }

    @Test
    fun shuffledItIsTheNextInTheOrderThatWillPlay() {
        player.play(songs, 0, "A list", shuffle = true, playNow = false)
        settle()
        val onPhone = setOf("s5", "s300", "s599")
        val order = player.queue.playingOrder().map { it.id }
        val expected = order.withIndex().first { (place, id) -> place > player.queue.cursor && id in onPhone }
        val next = nextOnThePhone(onPhone)
        assertEquals(expected.index, next)
        player.passTo(next!!)
        settle()
        assertEquals(expected.value, playing())
        assertEquals(expected.value, exo.currentMediaItem!!.mediaId)
        assertEquals(order, player.queue.playingOrder().map { it.id })
    }
}
