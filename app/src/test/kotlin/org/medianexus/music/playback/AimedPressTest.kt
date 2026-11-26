package org.medianexus.music.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.net.Song

/**
 * **A press that looks something up first goes to the device it was made for, or nowhere**
 * (review X1): a Play, Add to queue or Play next from a card whose songs have to be fetched
 * remembers what it was aimed at when the finger came down (this phone's own player, or the
 * device then steered), and if that has changed by the time the songs are in, nothing is done
 * anywhere and the person is told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AimedPressTest {
    @get:Rule val limit: Timeout = Timeout.seconds(20)

    private val steered = Steered()
    private var acted = 0
    private var moved = 0
    private val held = CompletableDeferred<List<Song>>()

    private fun device(where: String) = Steered.Shown(
        song = Song(id = "s0", title = "Now"), playing = true, positionMs = 0, at = 0, durationMs = 200_000,
        queue = UpNext(), shuffle = false, repeat = 0, volume = 1f, where = where,
    )

    private val hands = object : Steered.Hands {
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun remove(uid: Long) = Unit
        override fun move(uid: Long, after: Long?, queued: Boolean) = Unit
        override fun clear(queued: Boolean) = Unit
        override fun jump(uid: Long) = Unit
        override fun shuffle() = Unit
        override fun repeat() = Unit
        override fun volume(to: Float) = Unit
        override fun add(songs: List<Song>, playNext: Boolean) = Unit
        override fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) = Unit
    }

    /** The tap: the look-up is held until `then` has happened, then let go. */
    private fun TestScope.tapThen(then: () -> Unit) {
        PlayerConnection.afterLookup(
            this, StandardTestDispatcher(testScheduler), { held.await() },
            stands = { true }, aim = { PlayerConnection.aimOf(steered) },
            act = { acted++ }, moved = { moved++ },
        )
        advanceUntilIdle()
        then()
        held.complete(listOf(Song(id = "s1")))
        advanceUntilIdle()
    }

    @Test
    fun aimedAtTheDeviceAndStopSteeringMeanwhileNothingIsDoneAnywhere() = runTest {
        steered.show(device("Study PC"), hands)
        tapThen { steered.clear() }
        assertEquals("not on the phone's own queue either", listOf(0, 1), listOf(acted, moved))
    }

    @Test
    fun aimedAtThePhoneAndADeviceSteeredMeanwhileNothingIsDoneAnywhere() = runTest {
        tapThen { steered.show(device("Study PC"), hands) }
        assertEquals("not on the device's queue", listOf(0, 1), listOf(acted, moved))
    }

    @Test
    fun aimedAtOneDeviceAndAnotherSteeredMeanwhileNothingIsDone() = runTest {
        steered.show(device("Study PC"), hands)
        tapThen { steered.show(device("Laptop"), hands) }
        assertEquals(listOf(0, 1), listOf(acted, moved))
    }

    @Test
    fun stillAimedWhereItWasItIsDone() = runTest {
        steered.show(device("Study PC"), hands)
        // (A later word of the same device, its song moved on, is the same device.)
        tapThen { steered.show(device("Study PC").copy(song = Song(id = "s9")), hands) }
        assertEquals(listOf(1, 0), listOf(acted, moved))
    }

    @Test
    fun onThePhoneThroughoutItIsDone() = runTest {
        tapThen { }
        assertEquals(listOf(1, 0), listOf(acted, moved))
    }
}
