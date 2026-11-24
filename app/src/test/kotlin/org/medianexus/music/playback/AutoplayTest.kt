package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Autoplay and a song's radio (Windows' `load_autoplay` and `autoplay_loaded`), and how soon a
 * dropped station is joined again. The server is a stand-in whose answers each test chooses;
 * the runner's work runs on a test dispatcher the test moves on, so nothing waits on time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoplayTest {
    private fun songs(prefix: String, n: Int, genre: String = "") = List(n) { Song(id = "$prefix$it", title = "$prefix $it", genre = genre) }

    private class Source(var similar: List<Song> = emptyList(), var ofGenre: List<Song> = emptyList(), var any: List<Song> = emptyList(), val gate: CompletableDeferred<Unit>? = null) : AutoplaySource {
        val asked = mutableListOf<String>()
        override suspend fun similarSongs(id: String, count: Int): List<Song> {
            asked += "similar $id"
            gate?.await()
            return similar
        }
        override suspend fun randomSongs(count: Int, genre: String?): List<Song> {
            asked += "random ${genre ?: "any"}"
            if (genre == null && any.isEmpty()) throw ApiError.Network("gone")
            return if (genre == null) any else ofGenre
        }
    }

    // ---- What is looked for, in Windows' order -------------------------------------------

    @Test
    fun similarSongsFirstThenTheGenreThenAnythingNeverOneAlreadyQueued() = runBlocking {
        val seed = Song(id = "seed", genre = "Folk")
        // The server has similar songs: they are all it takes.
        val rich = Source(similar = songs("s", 40))
        val found = Autoplay.lookup(rich, seed, known = setOf("s0", "s1"))
        assertEquals(30, found.size)
        assertFalse(found.any { it.id == "s0" || it.id == "s1" })
        assertEquals(listOf("similar seed"), rich.asked)
        // No Last.fm: the same genre, then anything, filling up to five at least.
        val poor = Source(similar = emptyList(), ofGenre = songs("g", 3, "Folk"), any = songs("a", 10) + songs("g", 3))
        val fallback = Autoplay.lookup(poor, seed, known = emptySet())
        assertEquals(listOf("similar seed", "random Folk", "random any"), poor.asked)
        assertEquals(listOf("g0", "g1", "g2", "a0"), fallback.take(4).map { it.id })
        // Each once.
        assertEquals(fallback.size, fallback.map { it.id }.toSet().size)
    }

    // ---- When, and when not ----------------------------------------------------------------

    private fun queueOf(list: List<Song>, source: String = "album:1", cursor: Int = list.lastIndex) = Queue().apply {
        replace(list, cursor, context = "Slow Rivers", shuffle = false, source = source)
    }

    private class Found {
        val calls = mutableListOf<Triple<Long, List<String>, Boolean>>()
    }

    private var intent = 0L

    private fun runner(source: AutoplaySource, scope: TestScope, enabled: Boolean = true, offline: Boolean = false, found: Found) =
        AutoplayRunner({ enabled }, source, scope, { offline }, { seed, list, resume -> found.calls += Triple(seed, list.map { it.id }, resume) }, { intent }, StandardTestDispatcher(scope.testScheduler))

    @Test
    fun theLastSongStartingLooksAheadAndAnEndedQueueCarriesOnIntoWhatIsFound() {
        val scope = TestScope()
        val found = Found()
        val source = Source(similar = songs("s", 5))
        val auto = runner(source, scope, found = found)
        // Not the last song yet: nothing is asked.
        auto.lastSong(queueOf(songs("q", 3), cursor = 0))
        scope.runCurrent()
        assertTrue(source.asked.isEmpty())
        // The last song starts: looked for once, however often it is told.
        val queue = queueOf(songs("q", 3))
        auto.lastSong(queue)
        auto.lastSong(queue)
        scope.runCurrent()
        assertEquals(1, source.asked.size)
        assertEquals(listOf(Triple(queue.lastUid()!!, songs("s", 5).map { it.id }, false)), found.calls)
        // The queue ends with nothing found yet: the player waits, and carries on when found.
        val ended = queueOf(songs("r", 2))
        val gate = CompletableDeferred<Unit>()
        val slow = Source(similar = songs("t", 5), gate = gate)
        val auto2 = runner(slow, scope, found = found)
        assertTrue(auto2.ended(ended))
        scope.runCurrent()
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(true, found.calls.last().third)
    }

    @Test
    fun anythingThePersonDoesWhileTheSongsAreFoundMeansNothingStartsByItself() {
        val scope = TestScope()
        val found = Found()
        val gate = CompletableDeferred<Unit>()
        val auto = runner(Source(similar = songs("s", 5), gate = gate), scope, found = found)
        assertTrue(auto.ended(queueOf(songs("q", 2))))
        scope.runCurrent()
        // A pause, a stop, the sleep timer, the sound taken away: the player's intent moves on.
        intent++
        gate.complete(Unit)
        scope.runCurrent()
        // They join the queue, but nothing plays.
        assertEquals(false, found.calls.single().third)
    }

    @Test
    fun theSettingOffMeansNoAutoplayButARadioCarriesOnWhateverItSays() {
        val scope = TestScope()
        val found = Found()
        val off = runner(Source(similar = songs("s", 5)), scope, enabled = false, found = found)
        assertFalse(off.ended(queueOf(songs("q", 2))))
        off.lastSong(queueOf(songs("q", 2)))
        scope.runCurrent()
        assertTrue(found.calls.isEmpty())
        // A song's radio is what was asked for.
        off.lastSong(queueOf(songs("seed", 1), source = Autoplay.RADIO_SOURCE))
        scope.runCurrent()
        assertEquals(1, found.calls.size)
        // Offline, or a station last, or repeat on: nothing.
        val offline = runner(Source(similar = songs("s", 5)), scope, offline = true, found = found)
        assertFalse(offline.ended(queueOf(songs("q", 2))))
        assertFalse(runner(Source(), scope, found = found).ended(queueOf(listOf(Song(id = "radio:1", radioUrl = "http://x")))))
        assertFalse(runner(Source(), scope, found = found).ended(queueOf(songs("q", 2)).apply { repeat = Repeat.ALL }))
    }

    // ---- On the player -------------------------------------------------------------------

    @Test
    fun whatIsFoundJoinsTheEndOfTheQueueItWasFoundForAndNoOther() {
        val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val player = QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, byPlace = { false })
        try {
            player.play(songs("q", 3), 2, "Slow Rivers", playNow = false)
            shadowOf(Looper.getMainLooper()).idle()
            val seed = player.queue.lastUid()!!
            player.carriedOn(seed, songs("s", 4), resume = false)
            assertEquals(listOf("q0", "q1", "q2", "s0", "s1", "s2", "s3"), player.queue.playingOrder().map { it.id })
            // What follows no longer comes from the album chosen.
            assertEquals("", player.queue.context)
            assertEquals("q2", player.queue.current()!!.id)
            // Found for a queue that has since been replaced: nothing joins.
            player.play(songs("x", 2), 0, "Other", playNow = false)
            shadowOf(Looper.getMainLooper()).idle()
            player.carriedOn(seed, songs("s", 4), resume = false)
            assertEquals(listOf("x0", "x1"), player.queue.playingOrder().map { it.id })
        } finally {
            player.release()
            exo.release()
        }
    }

    // ---- Nothing starts after the person stopped it, on the real player (M3b fixes, C1) ----

    private class Player(val exo: ExoPlayer, val player: QueuePlayer) {
        fun release() {
            player.release()
            exo.release()
        }
    }

    private fun realPlayer(): Player {
        val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        return Player(exo, QueuePlayer(exo, Queue(), { MediaMetadata.EMPTY }, changed = {}, byPlace = { false }))
    }

    private fun idle(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))

    @Test
    fun stopDuringAStationsRejoinWaitMeansItIsNotJoinedAgain() {
        val (exo, player) = realPlayer().let { it.exo to it.player }
        try {
            player.play(listOf(Song(id = "radio:1", title = "Test FM", radioUrl = "http://127.0.0.1:1/stream")), 0, "Radio")
            idle()
            // The stream drops; it is to be joined again in a second. Meanwhile, Stop (Media3's
            // stop leaves "play when ready" set).
            assertTrue(player.rejoinStation())
            player.stop()
            idle()
            assertTrue(exo.playWhenReady)
            idle(2_000)
            assertEquals("joined again after a stop", androidx.media3.common.Player.STATE_IDLE, exo.playbackState)
        } finally {
            player.release()
            exo.release()
        }
    }

    @Test
    fun theSleepTimersPauseOrTheSettingSwitchedOffWhileAutoplayLooksMeansNothingCarriesOn() {
        for (case in listOf("the player paused itself", "autoplay switched off")) {
            val (exo, player) = realPlayer().let { it.exo to it.player }
            try {
                var enabled = true
                val gate = CompletableDeferred<Unit>()
                val auto = AutoplayRunner(
                    { enabled }, Source(similar = songs("s", 5), gate = gate),
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), { false },
                    { seed, list, resume -> player.carriedOn(seed, list, resume) }, { player.intent },
                    kotlinx.coroutines.Dispatchers.Main,
                )
                player.end = auto
                player.play(songs("q", 3), 2, "Slow Rivers")
                idle()
                // The queue has ended, and autoplay's songs are being looked for.
                assertTrue(auto.ended(player.queue))
                idle()
                if (case == "the player paused itself") exo.pause() else enabled = false
                idle()
                gate.complete(Unit)
                idle()
                assertEquals(case, 2, player.queue.cursor)
                assertFalse(case, exo.playWhenReady && player.queue.cursor > 2)
                if (case == "autoplay switched off") assertEquals(case, 3, player.queue.order.size)
            } finally {
                player.release()
                exo.release()
            }
        }
    }

    // ---- Stations --------------------------------------------------------------------------

    @Test
    fun aDroppedStationIsJoinedAgainAfterOneTwoFourAndEightSecondsThenNot() {
        val rejoins = Rejoins()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { rejoins.next(7, it * 10_000L) })
        assertNull(rejoins.next(7, 50_000))
        // Three quiet minutes, or another entry: from the start again.
        assertEquals(1_000L, rejoins.next(7, 50_000 + 3 * 60_000L + 1))
        assertEquals(1_000L, rejoins.next(8, 400_000))
    }
}
