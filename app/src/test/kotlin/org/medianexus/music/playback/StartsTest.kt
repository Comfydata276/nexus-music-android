package org.medianexus.music.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * An older request to start something must never replace a newer choice. Each case holds the
 * older request at a gate at exactly the point where the service waits (building the queue
 * off the main thread; the session carrying the items back to the player), makes the newer
 * choice there, and opens the gate: no timing. The real ExoPlayer is under the queue's
 * player, and the items reach it the way the session delivers them (`setMediaItems`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StartsTest {
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private val scope = CoroutineScope(Dispatchers.Unconfined)

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

    private fun list(name: String, count: Int = 12) = List(count) { Song(id = "$name$it", title = "$name $it") }

    private fun playing() = player.queue.current()?.id

    /**
     * What the service does for a song chosen in the car or asked for aloud, once it knows
     * the list: makes the queue (off the main thread in the app; here, held at `built`),
     * then hands it to the session for the start it was begun as.
     */
    private fun chosen(ticket: Long, songs: List<Song>, built: CompletableDeferred<Unit>): Deferred<Pair<List<MediaItem>, Int>?> = scope.async {
        val next = player.prepare(songs, 0, "Chosen in the car", shuffle = false)
        built.await()
        player.stage(next, ticket)
    }

    /** The session delivering what it was handed to the player, as it does a moment later. */
    private fun deliver(handed: Pair<List<MediaItem>, Int>) {
        player.setMediaItems(handed.first, handed.second, 0)
        settle()
    }

    /** A choice made on the phone: played at once. */
    private fun onThePhone(songs: List<Song>) {
        player.play(songs, 0, "Chosen on the phone", playNow = false)
        settle()
    }

    @Test
    fun aCarsChoiceStillBeingBuiltDoesNotReplaceAChoiceMadeOnThePhoneMeanwhile() {
        val car = player.beginStart()
        val building = CompletableDeferred<Unit>()
        val handed = chosen(car, list("car"), building)
        // The queue for the car's choice is being made. On the phone, something else is chosen.
        onThePhone(list("phone"))
        assertEquals("phone0", playing())
        building.complete(Unit)
        // It checked before it began building, and that was all: it used to be handed over
        // and take the player.
        assertNull("the older choice was handed to the session", handed.getCompleted())
        assertEquals("phone0", playing())
        assertFalse(player.stands(car))
    }

    @Test
    fun aChoiceMadeAfterTheHandOverStandsWhenTheOlderItemsArrive() {
        val car = player.beginStart()
        val handed = chosen(car, list("car"), CompletableDeferred(Unit)).getCompleted()
        assertNotNull(handed)
        // Handed to the session, and on its way back to the player. In that moment, a choice
        // on the phone.
        onThePhone(list("phone"))
        deliver(handed!!)
        assertEquals("phone0", playing())
    }

    @Test
    fun aStaleSpokenRequestCannotSpoilWhatANewerChoiceHandedOver() {
        // A spoken request is being looked up; the driver gives up and taps a song instead.
        val spoken = player.beginStart()
        val lookingUp = CompletableDeferred<Unit>()
        val stale = chosen(spoken, list("spoken"), lookingUp)
        val tapped = player.beginStart()
        val handed = chosen(tapped, list("tapped"), CompletableDeferred(Unit)).getCompleted()!!
        // The spoken request's answer arrives now, between the tap's hand-over and its items
        // reaching the player. It used to take the place the tap's queue was waiting in, so
        // the tap's items were not recognised when they arrived, and were dropped.
        lookingUp.complete(Unit)
        assertNull(stale.getCompleted())
        deliver(handed)
        assertEquals("tapped0", playing())
    }

    @Test
    fun theNewestOfTwoHandedOverIsTheOneThatPlaysWhicheverArrivesFirst() {
        val first = player.beginStart()
        val one = chosen(first, list("one"), CompletableDeferred(Unit)).getCompleted()!!
        val second = player.beginStart()
        val two = chosen(second, list("two"), CompletableDeferred(Unit)).getCompleted()!!
        deliver(two)
        deliver(one)
        assertEquals("two0", playing())
    }

    @Test
    fun carryingOnGivesWayToAChoiceAndIsNeverAppliedToADifferentQueue() {
        onThePhone(list("first"))
        // "Play", with the queue as it stands, handed to the session...
        val resume = player.stageCurrent()!!
        // ...and before it arrives, a song is chosen in the car, and arrives.
        val car = player.beginStart()
        deliver(chosen(car, list("car"), CompletableDeferred(Unit)).getCompleted()!!)
        assertEquals("car0", playing())
        val revision = player.queue.revision
        deliver(resume)
        // The resume was of another queue: nothing is loaded again, nothing restarted.
        assertEquals("car0", playing())
        assertEquals(revision, player.queue.revision)
        // And a resume doesn't overtake a choice still being looked up.
        val later = player.beginStart()
        player.stageCurrent()
        assertTrue(player.stands(later))
    }

    @Test
    fun aChangeOfAccountOvertakesEveryStart() {
        val mine = player.beginStart()
        val building = CompletableDeferred<Unit>()
        val handed = chosen(mine, list("theirs"), building)
        // Signed out while the queue is being made.
        player.clear()
        settle()
        building.complete(Unit)
        assertNull(handed.getCompleted())
        assertNull(playing())
        // And a named page, ready after its account has gone, isn't played either.
        val page = player.beginStart()
        val next = player.prepare(list("page"), 0, "A page", shuffle = false)
        player.clear()
        assertFalse(player.playFor(page, next))
        assertNull(playing())
    }
}
