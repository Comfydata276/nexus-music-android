package org.medianexus.music.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

/**
 * Every operation of the queue with nothing in it and with one song: the cases a list of
 * three never meets. Shuffle on an empty queue used to crash the player.
 */
class QueueEdgesTest {
    private fun named(id: String) = Song(id = id)

    private fun one() = Queue().apply { replace(listOf(named("a")), 0, context = "One", source = "album:1", origins = listOf(0), sourceList = listOf("a"), shuffle = false) }

    /** The queue holds together: the order is the songs' places, each once, and the cursor
     *  is on one of them (or 0 when there are none). */
    private fun assertSound(q: Queue) {
        assertEquals(q.songs.size, q.uids.size)
        assertEquals(q.songs.size, q.origins.size)
        assertEquals(q.songs.indices.toList(), q.order.sorted())
        if (q.order.isEmpty()) assertEquals(0, q.cursor) else assertTrue(q.cursor in q.order.indices)
        assertEquals(q.uids.size, q.uids.toSet().size)
    }

    @Test
    fun shuffleOnAnEmptyQueueIsOnlyASetting() {
        val q = Queue()
        q.setShuffle(true, Random(1))
        assertTrue(q.shuffle)
        assertSound(q)
        q.setShuffle(false, Random(1))
        assertFalse(q.shuffle)
        assertSound(q)
    }

    @Test
    fun anEmptyQueueTakesEveryOperation() {
        val q = Queue()
        assertTrue(q.isEmpty)
        assertNull(q.current())
        assertNull(q.currentUid())
        assertNull(q.currentOrigin())
        assertNull(q.following())
        assertEquals(0, q.queuedAhead())
        assertEquals(emptyList<Song>(), q.playingOrder())
        assertEquals(emptyList<Long>(), q.playingUids())
        assertEquals(-1, q.placeOf(7))
        q.moveTo(0)
        q.moveTo(3)
        q.trackChanged()
        q.dropAt(0)
        q.dropAt(-1)
        q.moveAhead(0, 0, queued = true)
        q.moveAhead(0, 1, queued = false)
        q.move(0, 1, 0)
        q.move(0, 0, 0)
        q.clearAhead(queued = true)
        q.clearAhead(queued = false)
        assertFalse(q.jumpAhead(0))
        assertFalse(q.follow(listOf("a"), { named("a") }, Random(1)))
        q.shuffleOrder(Random(1))
        q.rearrangeKeepingQueued { shuffleOrder(Random(1)) }
        q.replace(emptyList(), 0, context = "", shuffle = true, anyStart = true, random = Random(1))
        q.clear()
        assertSound(q)
        assertFalse(q.saved(0.0, 0).valid)
        // Songs queued by hand into nothing become the queue.
        q.shuffle = true
        q.queueUp(listOf(named("x"), named("y")), playNext = true)
        assertEquals(listOf("x", "y"), q.playingOrder().map { it.id })
        assertTrue(q.shuffle)
        assertSound(q)
    }

    @Test
    fun aQueueOfOneSongTakesEveryOperation() {
        val q = one()
        assertEquals("a", q.current()?.id)
        assertNull(q.following())
        q.repeat = Repeat.ALL
        assertEquals(0, q.following())
        q.repeat = Repeat.ONE
        assertEquals(0, q.following())
        q.repeat = Repeat.OFF
        q.setShuffle(true, Random(1))
        assertEquals(listOf("a"), q.playingOrder().map { it.id })
        q.setShuffle(false, Random(1))
        assertEquals(listOf("a"), q.playingOrder().map { it.id })
        q.moveTo(0)
        q.moveTo(1)
        q.moveAhead(0, 0, queued = true)
        q.move(0, 1, 0)
        q.move(0, 1, 5)
        q.clearAhead(queued = true)
        q.clearAhead(queued = false)
        assertFalse(q.jumpAhead(0))
        assertEquals("a", q.current()?.id)
        assertSound(q)
        // The list it came from changing to the same one song is no change.
        assertFalse(q.follow(listOf("a"), { named("a") }, Random(1)))
        // The song leaving its list: it's playing, so it stays until it has had its turn.
        assertTrue(q.follow(emptyList(), { null }, Random(1)))
        assertEquals(listOf("a"), q.playingOrder().map { it.id })
        assertSound(q)
    }

    @Test
    fun theOnlySongCanBeTakenOut() {
        val q = one()
        q.dropAt(0)
        assertTrue(q.isEmpty)
        assertNull(q.current())
        assertSound(q)
        // And the queue is still good for a new start.
        q.setShuffle(true, Random(3))
        q.queueUp(listOf(named("b")), playNext = false)
        assertEquals("b", q.current()?.id)
        assertSound(q)
    }

    @Test
    fun oneSongAndOneQueuedByHand() {
        val q = one()
        q.queueUp(listOf(named("x")), playNext = false)
        assertEquals(1, q.queuedAhead())
        q.setShuffle(true, Random(5))
        // Shuffled or not, the queued song is next.
        assertEquals(listOf("a", "x"), q.playingOrder().map { it.id })
        q.setShuffle(false, Random(5))
        assertEquals(listOf("a", "x"), q.playingOrder().map { it.id })
        assertTrue(q.jumpAhead(0))
        assertEquals("x", q.current()?.id)
        assertFalse(q.current()!!.queued)
        q.clearAhead(queued = false)
        q.clearAhead(queued = true)
        assertSound(q)
    }

    @Test
    fun shufflingAFreshListOfOneSongPlaysIt() {
        val q = Queue()
        q.replace(listOf(named("a")), 0, context = "", shuffle = true, anyStart = true, random = Random(9))
        assertEquals("a", q.current()?.id)
        assertEquals(0, q.cursor)
        assertSound(q)
    }
}
