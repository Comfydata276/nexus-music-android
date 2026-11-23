package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * A tap on an entry of the queue, from a car. Media3 gives a car's queue items their place
 * in the player's playlist as an id, and sends a tap back as that place: so whenever the
 * places move, a tap made on a screen that has not caught up names another song. These run
 * the real ExoPlayer under the queue's player, and send the tap the way Media3's session
 * does for a car (`seekToDefaultPosition(place)`); the same through a real
 * `MediaControllerCompat`, from another app, is `scripts/carcheck --mode queue_slide`.
 */
@RunWith(RobolectricTestRunner::class)
class QueueTapTest {
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private var now = 100_000L

    /** Whether the one asking is a car (or anyone else who names an entry by its place). */
    private var car = true

    private val songs = List(600) { Song(id = "s$it", title = "Song $it") }

    @Before
    fun start() {
        exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, byPlace = { car }, clock = { now })
    }

    @After
    fun stop() {
        player.release()
        exo.release()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** What a car is shown: the songs of the session's queue, by place. */
    private fun shown(): List<String> {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        return List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem.mediaId }
    }

    private fun playing() = player.queue.current()!!.id

    /** The car's tap: "play the entry at this place". */
    private fun tap(place: Int) {
        player.seekToDefaultPosition(place)
        settle()
    }

    private fun play(list: List<Song>, start: Int = 0) {
        player.play(list, start, "A list", playNow = false)
        settle()
        // Long enough ago that the car has it on its screen.
        now += 60_000
    }

    @Test
    fun aTapAcrossTheWindowSlidingPlaysTheSongThatWasShown() {
        play(songs)
        val before = shown()
        assertEquals("s0", before.first())
        // The player has gone along the queue to its 131st song, and the window has slid: the
        // playlist now starts at the 101st, and every place means the song 100 further on.
        tap(130)
        assertEquals("s130", playing())
        assertEquals("s100", shown().first())
        now += 60_000
        // Past the next step: the car is still showing what it was sent before this slide
        // when the driver taps the entry twelve below.
        val shownBeforeTheSlide = shown()
        tap(125 + 5)
        assertEquals("s230", playing())
        assertEquals("s200", shown().first())
        now += 300
        tap(shownBeforeTheSlide.indexOf("s242"))
        assertEquals("the song at that place on the car's screen", "s242", playing())
        // A second later the car has redrawn: a place is what it says.
        now += 2_000
        tap(10)
        assertEquals("s210", playing())
    }

    @Test
    fun aTapAcrossASlideInTheFirstSecondOfANewListIsStillReadRightly() {
        // A list is started over another, and within the same second the car goes far down
        // it and then taps an entry of the queue it was first shown. (The places of the list
        // before this one, replaced a moment ago, must not be what the tap is read against.)
        play(songs.take(40).map { it.copy(id = "old-" + it.id) })
        player.play(songs, 0, "A list", playNow = false)
        settle()
        now += 150
        val first = shown()
        tap(130)
        assertEquals("s130", playing())
        assertEquals("s100", shown().first())
        now += 200
        tap(first.indexOf("s142"))
        assertEquals("s142", playing())
    }

    @Test
    fun aTapOnASongTheWindowNoLongerHoldsStillFindsIt() {
        play(songs)
        tap(130)
        now += 60_000
        val shownBeforeTheSlide = shown()
        // A long jump forward moves the window far along; the car taps an entry near the
        // top of what it was showing, which the player no longer holds at all.
        tap(shownBeforeTheSlide.size - 1)
        assertEquals("s424", playing())
        assertEquals("s300", shown().first())
        now += 200
        tap(shownBeforeTheSlide.indexOf("s105"))
        assertEquals("s105", playing())
    }

    @Test
    fun aTapAcrossAReorderOrAnEditPlaysTheSongThatWasShown() {
        play(songs.take(20))
        var before = shown()
        // On the phone, the sixth song is dragged to straight after the one playing.
        player.moveEntry(player.queue.playingUids()[5], after = null, queued = true)
        settle()
        assertEquals(listOf("s0", "s5", "s1", "s2"), shown().take(4))
        now += 400
        tap(before.indexOf("s3"))
        assertEquals("s3", playing())
        now += 60_000

        // A song ahead is taken out of the queue: everything after it moves up one.
        before = shown()
        player.removeEntry(player.queue.playingUids()[player.queue.cursor + 2])
        settle()
        now += 400
        tap(before.indexOf("s9"))
        assertEquals("s9", playing())
        now += 60_000

        // A song is queued to play next: everything after the one playing moves down one.
        before = shown()
        player.enqueue(listOf(Song(id = "extra", title = "Extra")), playNext = true)
        settle()
        now += 400
        tap(before.indexOf("s12"))
        assertEquals("s12", playing())
    }

    @Test
    fun aTapOnASongThatHasLeftTheQueuePlaysNothingElse() {
        play(songs.take(20))
        val before = shown()
        player.removeEntry(player.queue.playingUids()[4])
        settle()
        now += 300
        // The car's screen still shows the fifth song; it is tapped. It isn't there to play,
        // and the sixth, which now sits in its place, is not what was tapped.
        tap(before.indexOf("s4"))
        assertEquals("s0", playing())
    }

    @Test
    fun aPlaceIsTakenAsItIsWhenNothingHasMovedAndFromTheAppsOwnScreens() {
        play(songs.take(20))
        tap(7)
        assertEquals("s7", playing())
        // A new list: the old one's places mean nothing, however lately it was replaced.
        player.play(List(10) { Song(id = "n$it") }, 0, "Another", playNow = false)
        settle()
        now += 100
        tap(3)
        assertEquals("n3", playing())
        now += 60_000
        // The app's own controllers: its screens name an entry by its own number, and the
        // rig's debug control means the place it says.
        player.moveEntry(player.queue.playingUids()[8], after = null, queued = true)
        settle()
        now += 100
        car = false
        val atFive = shown()[5]
        tap(5)
        assertEquals(atFive, playing())
    }

    // ---- The window as the player moves along the queue ---------------------------------------

    @Test
    fun whatThePlayerHasPassedIsLetGo() {
        play(songs)
        car = false
        // A long session: on through the queue a song at a time, as playing it does.
        repeat(450) {
            player.seekToNextMediaItem()
            settle()
        }
        assertEquals("s450", playing())
        // The media items of the songs left behind used to be kept for the life of the queue.
        assertTrue("${player.heldItems} items held", player.heldItems <= QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER)
        assertEquals(null, player.songFor("s3"))
        assertEquals("s450", player.songFor("s450")?.id)
    }

    @Test
    fun nextOnAOneSongQueueStartsItAgainAndStopsThereUnlessItRepeats() {
        play(songs.take(1))
        exo.seekTo(30_000)
        exo.playWhenReady = true
        settle()
        assertEquals(30_000, player.currentPosition)
        // The end of the queue: back to the top, paused, as on Windows. With one song the
        // top is the song itself, and nothing used to happen at all.
        player.seekToNext()
        settle()
        assertEquals(0, player.currentPosition)
        assertEquals(false, player.playWhenReady)
        // With repeat on it carries on from the top.
        player.setRepeat(Repeat.ALL)
        exo.seekTo(30_000)
        exo.playWhenReady = true
        settle()
        player.seekToNext()
        settle()
        assertEquals(0, player.currentPosition)
        assertEquals(true, player.playWhenReady)
    }

    @Test
    fun withRepeatOnTheFirstSongsFollowTheLastInThePlayer() {
        play(songs, start = 595)
        assertEquals("s599", shown().last())
        player.setRepeat(Repeat.ALL)
        settle()
        assertEquals(listOf("s599", "s0", "s1", "s2"), shown().takeLast(4))
        assertEquals("s595", playing())
        // On to the last song and past it, as the player goes by itself.
        car = false
        repeat(5) {
            exo.seekToNextMediaItem()
            settle()
        }
        assertEquals("s0", playing())
        assertEquals(0, player.queue.cursor)
        assertEquals("s0", shown().first())
        // Turned off again, nothing follows the last song.
        player.play(songs, 598, "A list", playNow = false)
        settle()
        assertEquals(listOf("s599", "s0", "s1", "s2"), shown().takeLast(4))
        player.setRepeat(Repeat.OFF)
        settle()
        assertEquals("s599", shown().last())
    }

    @Test
    fun whoeverAsksToPlayTheServiceIsToldBeforeThePlayerIs() {
        // What the service does with this is enter the foreground: Android gives the sound to
        // an app in the background only if it is already running a foreground service, so it
        // must be told before the player asks, not when playback is seen to have begun.
        val wasPlaying = mutableListOf<Boolean>()
        val told = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, aboutToPlay = { wasPlaying += exo.playWhenReady })
        told.play(songs.take(5), 0, "A list", playNow = false)
        settle()
        assertEquals(emptyList<Boolean>(), wasPlaying)
        // The session's Play (a car, a headset, the notification).
        told.play()
        settle()
        assertEquals(listOf(false), wasPlaying)
        assertEquals(true, exo.playWhenReady)
        told.pause()
        settle()
        // A list started from the app, and a jump down the queue.
        told.play(songs.take(5), 2, "A list")
        told.pause()
        told.jumpAhead(1)
        settle()
        assertEquals(listOf(false, false, false), wasPlaying)
        told.release()
    }
}
