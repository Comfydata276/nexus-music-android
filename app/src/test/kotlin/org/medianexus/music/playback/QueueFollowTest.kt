package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.library.Arrange
import org.medianexus.music.library.ListView
import org.medianexus.music.library.columns
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Following a playlist that changed never drops the song that is playing: on the real
 * ExoPlayer under the queue's player, a queue started from a playlist found in ("amber"),
 * the playlist refreshed with the song playing renamed so that it no longer matches.
 */
@RunWith(RobolectricTestRunner::class)
class QueueFollowTest {
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private val list = listOf("Amber road", "Grey sky", "Amber light", "Amber sky", "Cold harbour", "Amber dusk").mapIndexed { n, t -> Song(id = "s$n", title = t) }

    @Before
    fun start() {
        exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {})
    }

    @After
    fun stop() {
        player.release()
        exo.release()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun startFiltered(at: Int) {
        val view = ListView(filter = "amber")
        val shown = Arrange.arrange(list.columns(), view)
        player.play(shown.map { list[it] }, at, "Mix", "playlist:p", origins = shown.toList(), sourceList = list.map { it.id }, shuffle = false, playNow = false)
        player.queue.filter = view.filter
        settle()
    }

    private fun follow(now: List<Song>, joins: (Song) -> Boolean = { true }) {
        player.follow("playlist:p", now, Arrange.arrange(now.columns(), ListView(filter = "amber")).toList(), joins)
        settle()
    }

    @Test
    fun theSongPlayingStaysWhereItIsWhenItNoLongerMatches() {
        // Playing "Amber light", the second of the four that match.
        startFiltered(1)
        assertEquals("s2", player.queue.current()!!.id)
        // The playlist is refreshed: "Amber light" renamed "Grey light", and a new "Amber"
        // song added at the end.
        val now = list.map { if (it.id == "s2") it.copy(title = "Grey light") else it } + Song(id = "s6", title = "Amber new")
        follow(now)
        // It goes on playing, where it was; around it, the list as it now is. (It used to be
        // dropped, and the first song left played.)
        assertEquals("s2", player.queue.current()!!.id)
        assertEquals("s2", exo.currentMediaItem!!.mediaId)
        assertEquals(listOf("s0", "s2", "s3", "s5", "s6"), player.queue.playingOrder().map { it.id })
        assertEquals(1, player.queue.cursor)
    }

    @Test
    fun offlineASongThatJoinsTheListJoinsTheQueueOnlyIfItPlays() {
        startFiltered(0)
        val now = list + Song(id = "s6", title = "Amber new") + Song(id = "s7", title = "Amber kept")
        // (As on Windows: offline, what isn't on the phone doesn't join.)
        follow(now) { it.id != "s6" }
        assertEquals(listOf("s0", "s2", "s3", "s5", "s7"), player.queue.playingOrder().map { it.id })
    }
}
