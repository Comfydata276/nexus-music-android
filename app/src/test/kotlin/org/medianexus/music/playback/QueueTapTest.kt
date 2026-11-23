package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /** How many times the queue has been published (to the car: the session sends it the
     *  queue whenever the player says its timeline has changed). */
    private var published = 0

    private fun watchPublishing() {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                published++
            }
        })
    }

    /** A tap that must do nothing but have the queue sent again. */
    private fun tapIsIgnored(place: Int, stillPlaying: String) {
        val before = published
        tap(place)
        assertEquals("a song the driver did not tap", stillPlaying, playing())
        assertTrue("the queue was not sent again for the car to redraw", published > before)
    }

    @Test
    fun aTapOnARowAsItWasBeforeTheWindowSlidPlaysNothing() {
        play(songs)
        watchPublishing()
        // The player has gone along the queue to its 131st song, and the window has slid: the
        // playlist now starts at the 101st, and every place means the song 100 further on.
        tap(130)
        assertEquals("s130", playing())
        assertEquals("s100", shown().first())
        now += 60_000
        // Past the next step. The car is still showing what it was sent before this slide
        // when the driver taps the entry twelve below.
        val shownBeforeTheSlide = shown()
        tap(125 + 5)
        assertEquals("s230", playing())
        assertEquals("s200", shown().first())
        now += 300
        // That place meant "s242" on the car's screen and means "s342" now. Which the driver
        // was looking at can't be known: neither is played, and the car is sent the queue.
        tapIsIgnored(shownBeforeTheSlide.indexOf("s242"), stillPlaying = "s230")
        // A second after the places last moved the car has redrawn: a place is what it says.
        now += 2_000
        tap(10)
        assertEquals("s210", playing())
    }

    @Test
    fun aTapOnARowJustRedrawnAfterASlideIsNotReadAsTheRowThatWasThere() {
        play(songs)
        watchPublishing()
        tap(130)
        now += 60_000
        val old = shown()
        tap(125 + 5)
        assertEquals("s230", playing())
        // The car redraws at once this time, and within the second the driver taps a row of
        // the queue as it now is: "s260". That place used to be read as the car was shown it
        // before the slide, and played the song that had been there: "s160".
        val redrawn = shown()
        val place = redrawn.indexOf("s260")
        assertEquals("s160", old[place])
        now += 400
        tapIsIgnored(place, stillPlaying = "s230")
        // Tapped again once the second is up, it is the row.
        now += 1_000
        tap(place)
        assertEquals("s260", playing())
    }

    @Test
    fun twoChangesInASecondAreThreeQueuesATapCouldHaveBeenMadeOn() {
        play(songs.take(20))
        watchPublishing()
        val first = shown()
        // On the phone, the sixth song is dragged to straight after the one playing...
        player.moveEntry(player.queue.playingUids()[5], after = null, queued = true)
        settle()
        val second = shown()
        now += 300
        // ...and three hundred milliseconds later the tenth, too.
        player.moveEntry(player.queue.playingUids()[9], after = null, queued = true)
        settle()
        val third = shown()
        now += 300
        // A place that means three different songs in the three: nothing plays. (Only the
        // oldest of them used to be considered.)
        val place = 3
        assertEquals(3, setOf(first[place], second[place], third[place]).size)
        tapIsIgnored(place, stillPlaying = "s0")
        // A place the first change left alone and the second moved: two candidates, nothing.
        assertEquals(first[7], second[7])
        assertNotEquals(second[7], third[7])
        tapIsIgnored(7, stillPlaying = "s0")
        // A place that means the same song in all three is that song, at once.
        val same = 15
        assertEquals(setOf(third[same]), setOf(first[same], second[same], third[same]))
        tap(same)
        assertEquals(third[same], playing())
    }

    @Test
    fun aTapStraightAfterTheWholeQueueIsReplacedPlaysNothing() {
        play(songs.take(40).map { it.copy(id = "old-" + it.id) })
        watchPublishing()
        // Another list is started over it. The car's queue still shows the old one.
        player.play(List(10) { Song(id = "n$it") }, 0, "Another", playNow = false)
        settle()
        now += 100
        // The fourth row: "old-s3" on the car's screen, "n3" in the player. It used to be
        // taken for the new queue's without question, however lately it had been replaced.
        tapIsIgnored(3, stillPlaying = "n0")
        now += 1_500
        tap(3)
        assertEquals("n3", playing())
    }

    @Test
    fun aTapAcrossAnEditPlaysNothingThatWasNotTapped() {
        play(songs.take(20))
        watchPublishing()
        // A song ahead is taken out of the queue: everything after it moves up one.
        var before = shown()
        player.removeEntry(player.queue.playingUids()[2])
        settle()
        now += 400
        tapIsIgnored(before.indexOf("s9"), stillPlaying = "s0")
        // What comes before the change has not moved: it is what it says.
        tap(1)
        assertEquals("s1", playing())
        now += 60_000

        // A song is queued to play next: everything after the one playing moves down one.
        before = shown()
        player.enqueue(listOf(Song(id = "extra", title = "Extra")), playNext = true)
        settle()
        now += 400
        tapIsIgnored(before.indexOf("s12"), stillPlaying = "s1")
        // The song that has left the queue is not there to play, and the one that now sits
        // in its place is not what was tapped.
        now += 60_000
        before = shown()
        player.removeEntry(player.queue.playingUids()[player.queue.cursor + 3])
        settle()
        now += 300
        tapIsIgnored(before.indexOf(before[player.queue.cursor + 3]), stillPlaying = "s1")
    }

    @Test
    fun aPlaceIsTakenAsItIsWhenNothingHasMovedAndFromTheAppsOwnScreens() {
        play(songs.take(20))
        tap(7)
        assertEquals("s7", playing())
        now += 60_000
        // The app's own controllers: its screens name an entry by its own number, and the
        // rig's debug control means the place it says.
        player.moveEntry(player.queue.playingUids()[12], after = null, queued = true)
        settle()
        now += 100
        car = false
        val atNine = shown()[9]
        tap(9)
        assertEquals(atNine, playing())
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
        val told = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, mayPlay = { wasPlaying += exo.playWhenReady; true })
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
