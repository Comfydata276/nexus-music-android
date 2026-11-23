package org.medianexus.music.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

/** The Windows app's queue tests (src/playback.rs), which are the queue's specification,
 *  and a few for what the phone adds. */
class QueueTest {
    private fun named(id: String) = Song(id = id)

    /** A queue of these songs in this order, with the first one playing. */
    private fun queueOf(vararg ids: String) = Queue().apply { replace(ids.map(::named), 0, context = "", shuffle = false) }

    /** What's playing, then what's to come, with a star on the songs queued by hand. */
    private fun coming(q: Queue): String {
        val rest = q.order.drop(q.cursor + 1).joinToString(" ") { q.songs[it].id + if (q.songs[it].queued) "*" else "" }
        return "${q.songs[q.order[q.cursor]].id} | $rest"
    }

    @Test
    fun songsQueuedByHandPlayBeforeTheRestOfTheList() {
        val q = queueOf("a", "b", "c")
        q.queueUp(listOf(named("x")), playNext = false)
        assertEquals("a | x* b c", coming(q))
        // The next one queued goes behind the first, not in front of it...
        q.queueUp(listOf(named("y")), playNext = false)
        assertEquals("a | x* y* b c", coming(q))
        // ...unless it's to play next. A song that's already in the list is queued all the
        // same, and stays in the list too.
        q.queueUp(listOf(named("c")), playNext = true)
        assertEquals("a | c* x* y* b c", coming(q))
        assertEquals(3, q.queuedAhead())
        // Once the first of them is playing, two are left waiting.
        q.cursor += 1
        assertEquals(2, q.queuedAhead())
    }

    @Test
    fun jumpingAheadKeepsWhatWasQueuedByHand() {
        val q = queueOf("a", "b", "c", "d")
        q.queueUp(listOf(named("x"), named("y")), playNext = false)
        // To a song of the list: the queued songs wait behind it.
        assertTrue(q.jumpAhead(3))
        assertEquals("c | x* y* d", coming(q))
        // To one of the queued songs: the others stay where they were.
        assertTrue(q.jumpAhead(1))
        assertEquals("y | x* d", coming(q))
        assertFalse(q.jumpAhead(9))
    }

    @Test
    fun movingASongTakesItIntoTheQueuedPartOrOutOfIt() {
        val q = queueOf("a", "b", "c")
        q.queueUp(listOf(named("x"), named("y")), playNext = false)
        q.moveAhead(2, 0, queued = true)
        assertEquals("a | b* x* y* c", coming(q))
        q.moveAhead(1, 3, queued = false)
        assertEquals("a | b* y* c x", coming(q))
        // Out of range: nothing happens.
        q.moveAhead(1, 9, queued = true)
        assertEquals("a | b* y* c x", coming(q))
    }

    @Test
    fun clearsOnePartOfTheQueueAndLeavesTheOther() {
        val q = queueOf("a", "b", "c")
        q.cursor = 1
        q.queueUp(listOf(named("x"), named("y")), playNext = false)
        assertEquals("b | x* y* c", coming(q))
        val list = queueOf("a", "b", "c")
        list.cursor = 1
        list.queueUp(listOf(named("x"), named("y")), playNext = false)
        list.clearAhead(queued = false)
        assertEquals("b | x* y*", coming(list))
        q.clearAhead(queued = true)
        assertEquals("b | c", coming(q))
        // What was played before is still there to go back to.
        assertEquals("a", q.songs[q.order[0]].id)
    }

    @Test
    fun shufflingLeavesWhatWasQueuedByHandNext() {
        val q = queueOf("a", "b", "c", "d", "e", "f")
        q.cursor = 2
        q.queueUp(listOf(named("x"), named("y")), playNext = false)
        q.setShuffle(true, Random(7))
        assertTrue(coming(q), coming(q).startsWith("c | x* y* "))
        assertEquals(8, q.order.size)
        // And turning shuffle off again puts the list back in order around them.
        q.setShuffle(false)
        assertEquals("c | x* y* d e f", coming(q))
    }

    @Test
    fun droppingAnEntryKeepsThePlaceInTheQueue() {
        val q = queueOf("a", "b", "c", "d")
        q.cursor = 2
        q.dropAt(0)
        assertEquals("c | d", coming(q))
        q.dropAt(2)
        assertEquals("c | ", coming(q))
        assertEquals(2, q.songs.size)
    }

    @Test
    fun comparesListsCopyForCopy() {
        val (moved, arrivals) = compareLists(listOf("a", "b", "a", "c"), listOf("b", "a", "d", "a", "a"))
        assertEquals(listOf(1, 0, 3, null), moved)
        assertEquals(listOf(2, 4), arrivals)
    }

    /** A queue of these songs, each from the same place in its list, playing the one at `cursor`. */
    private fun lineup(ids: List<String>, cursor: Int) = Lineup(ids.map(::named), ids.indices.toList(), ids.indices.toList(), cursor)

    /** The queue in playing order, with the song that's playing in brackets. */
    private fun playingOrder(l: Lineup) = l.order.mapIndexed { at, entry -> l.songs[entry].id.let { if (at == l.cursor) "[$it]" else it } }.joinToString(" ")

    private fun follow(l: Lineup, shuffled: Boolean, old: List<String>, new: List<String>, view: List<Int>? = null): Lineup {
        val (moved, arrivals) = compareLists(old, new)
        // "Random" here means as late as possible, to keep the tests the same every run.
        return followList(l, shuffled, moved, arrivals, view ?: new.indices.toList(), { new.getOrNull(it)?.let(::named) }, { it - 1 })
    }

    @Test
    fun theQueueFollowsItsList() {
        val list = listOf("a", "b", "c", "d")
        // Songs added to the list join the queue in their place; removed ones leave it.
        var after = follow(lineup(list, 1), false, list, listOf("a", "b", "x", "c", "y"))
        assertEquals("a [b] x c y", playingOrder(after))
        assertEquals(listOf(0, 1, 2, 3, 4), after.origins)
        // The song that's playing stays even if it was removed, and what followed it still does.
        after = follow(lineup(list, 1), false, list, listOf("a", "c", "d"))
        assertEquals("a [b] c d", playingOrder(after))
        assertEquals(listOf(0, null, 1, 2), after.origins)
        // A reordered list is played in its new order from the current song on.
        after = follow(lineup(list, 1), false, list, listOf("d", "b", "a", "c"))
        assertEquals("d [b] a c", playingOrder(after))
        // The page may show the list in another order (newest first, say): that's the order.
        // (The queue was started from that view, so its entries came from places 2, 1, 0.)
        val queue = lineup(listOf("c", "b", "a"), 0).copy(origins = listOf(2, 1, 0))
        after = follow(queue, false, listOf("a", "b", "c"), listOf("a", "b", "c", "x"), listOf(3, 2, 1, 0))
        assertEquals("x [c] b a", playingOrder(after))
    }

    @Test
    fun songsQueuedByHandKeepTheirPlace() {
        // "q" was added to play after "b"; it isn't part of the list.
        var queue = lineup(listOf("a", "b", "q", "c"), 1).copy(origins = listOf(0, 1, null, 2))
        var after = follow(queue, false, listOf("a", "b", "c"), listOf("a", "b", "x", "c"))
        assertEquals("a [b] q x c", playingOrder(after))
        // A queue played from part of a list (a filtered view) doesn't gain the rest of it.
        queue = lineup(listOf("b", "d"), 0).copy(origins = listOf(1, 3))
        after = follow(queue, false, listOf("a", "b", "c", "d"), listOf("a", "b", "c", "d", "e"))
        assertEquals("[b] d e", playingOrder(after))
    }

    @Test
    fun aShuffledQueueTakesArrivalsAmongWhatIsToCome() {
        val list = listOf("a", "b", "c", "d")
        val queue = lineup(list, 1).copy(order = listOf(2, 0, 3, 1))
        // Playing "a"; "c" has been played, "d" and "b" are to come. "b" is removed, "x" added.
        val after = follow(queue, true, list, listOf("a", "x", "c", "d"))
        assertEquals("c [a] d x", playingOrder(after))
        // Underneath the shuffle the songs are in the list's order, for when shuffle is turned off.
        assertEquals(listOf("a", "x", "c", "d"), after.songs.map { it.id })
    }

    // ---- What the phone adds ---------------------------------------------------------------

    @Test
    fun aNewQueueForgetsWhatWasQueuedByHandAndCanOpenAnywhereWhenShuffled() {
        val q = Queue()
        q.replace(listOf(named("a").copy(queued = true), named("b")), 1, "List", shuffle = false)
        assertEquals("b", q.current()?.id)
        assertFalse(q.songs.any { it.queued })
        // Shuffled with no song asked for: any may come first, and it is first in the order.
        q.replace(listOf("a", "b", "c", "d").map(::named), 0, "List", shuffle = true, anyStart = true, random = Random(3))
        assertEquals(0, q.cursor)
        assertEquals(4, q.order.toSet().size)
        // Shuffled from a chosen song: that song plays first.
        q.replace(listOf("a", "b", "c", "d").map(::named), 2, "List", shuffle = true, random = Random(3))
        assertEquals("c", q.current()?.id)
    }

    @Test
    fun queuingIntoAnEmptyQueueMakesItTheQueue() {
        val q = Queue()
        q.queueUp(listOf(named("a"), named("b")), playNext = false)
        assertEquals("a | b", coming(q))
        assertEquals(0, q.queuedAhead())
    }

    @Test
    fun aQueuedSongIsNoLongerWaitingOnceItPlays() {
        val q = queueOf("a", "b")
        q.queueUp(listOf(named("x")), playNext = false)
        q.moveTo(1)
        assertEquals("x | b", coming(q))
        assertEquals(0, q.queuedAhead())
    }

    @Test
    fun whatFollowsDependsOnRepeat() {
        val q = queueOf("a", "b")
        q.cursor = 1
        assertNull(q.following())
        q.repeat = Repeat.ALL
        assertEquals(0, q.following())
        q.repeat = Repeat.ONE
        assertEquals(1, q.following())
    }

    @Test
    fun everyEntryHasItsOwnNumberEvenForTheSameSong() {
        val q = queueOf("a", "b")
        q.queueUp(listOf(named("a")), playNext = true)
        assertEquals(3, q.playingUids().toSet().size)
        val uid = q.playingUids()[1]
        assertEquals(1, q.placeOf(uid))
        // Dropping an entry keeps the others' numbers.
        q.dropAt(1)
        assertEquals(-1, q.placeOf(uid))
        assertEquals(2, q.playingUids().size)
    }

    @Test
    fun movingEntriesAsAPlayerIsAskedKeepsTheSongPlaying() {
        val q = queueOf("a", "b", "c", "d")
        q.cursor = 1
        q.move(0, 1, 3)
        assertEquals(listOf("b", "c", "d", "a"), q.playingOrder().map { it.id })
        assertEquals("b", q.current()?.id)
    }

    @Test
    fun aSavedQueueComesBackAsItWas() {
        val q = queueOf("a", "b", "c")
        q.queueUp(listOf(named("x")), playNext = false)
        q.setShuffle(true, Random(1))
        q.context = "Slow Rivers"
        q.source = "album:1"
        val saved = q.saved(position = 12.5, stamp = 99)
        assertTrue(saved.valid)
        val back = Queue()
        back.restore(saved)
        assertEquals(q.playingOrder(), back.playingOrder())
        assertEquals(q.cursor, back.cursor)
        assertEquals("Slow Rivers", back.context)
        assertEquals(1, back.queuedAhead())
    }
}
