package org.medianexus.music.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

/** The player's playlist kept in step with the queue: a list stands in for ExoPlayer. */
class QueueSyncTest {
    /** A playlist that does what it is told and notes it, and never lets the current item
     *  be removed (as that is what would break the song that's playing). */
    private class Fake : QueueSync.Playlist {
        val uids = mutableListOf<Long>()
        var current = 0
        var position = 0L
        var loads = 0
        var changes = 0
        val log = mutableListOf<String>()

        override val count get() = uids.size
        override fun uidAt(index: Int) = uids.getOrNull(index)
        override val currentIndex get() = current

        override fun set(slots: List<QueueSync.Slot>, index: Int, positionMs: Long) {
            uids.clear(); slots.mapTo(uids) { it.uid }
            current = index; position = positionMs; loads++
            log += "set ${slots.size} at $index"
        }

        override fun clear() {
            uids.clear(); current = 0; loads++
            log += "clear"
        }

        override fun remove(from: Int, to: Int) {
            assertTrue("the song playing must not be removed ($from until $to, current $current)", current !in from until to)
            repeat(to - from) { uids.removeAt(from) }
            if (from < current) current -= to - from
            changes++
            log += "remove $from..$to"
        }

        override fun add(at: Int, slots: List<QueueSync.Slot>) {
            uids.addAll(at, slots.map { it.uid })
            if (at <= current) current += slots.size
            changes++
            log += "add ${slots.size} at $at"
        }

        override fun seekTo(index: Int, positionMs: Long) {
            current = index; position = positionMs
            log += "seek $index"
        }

        /** The player runs on into the next song by itself. */
        fun advance() { current++ }
    }

    private fun queueOf(count: Int, shuffle: Boolean = false, start: Int = 0) = Queue().apply {
        replace(List(count) { Song(id = "s$it") }, start, context = "", shuffle = shuffle, random = Random(7))
    }

    private fun windowUids(q: Queue) = QueueSync.slots(q).map { it.uid }

    /** The playlist is the queue's window, and its current item is the queue's current song. */
    private fun assertInStep(q: Queue, p: Fake) {
        assertEquals(windowUids(q), p.uids)
        if (!q.isEmpty) assertEquals(q.currentUid(), p.uids[p.current])
    }

    @Test
    fun aShortQueueIsHeldWhole() {
        val q = queueOf(40)
        val p = Fake()
        QueueSync.load(q, p, 1234)
        assertEquals(40, p.count)
        assertEquals(1234, p.position)
        assertTrue(QueueSync.whole(q))
        assertInStep(q, p)
    }

    @Test
    fun aLongQueueIsHeldAsAWindowThatMovesInSteps() {
        val q = queueOf(20_000, start = 7_000)
        val p = Fake()
        QueueSync.load(q, p, 0)
        assertFalse(QueueSync.whole(q))
        assertTrue("${p.count} items", p.count in QueueSync.AFTER..QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER)
        assertInStep(q, p)
        // A hundred and fifty songs go by. The playlist is never reloaded, the song playing
        // is never touched, there are always hundreds queued up ahead of it, and the window
        // itself moves only once in every [QueueSync.STEP] songs (not at every one).
        repeat(150) {
            p.advance()
            q.moveTo(q.placeOf(p.uids[p.current]))
            QueueSync.sync(q, p)
            assertInStep(q, p)
            assertTrue(p.count - p.current - 1 >= QueueSync.AFTER)
            assertTrue(p.current >= QueueSync.BEFORE)
        }
        assertEquals(1, p.loads)
        assertTrue("${p.changes} changes: ${p.log}", p.changes <= 2 * (150 / QueueSync.STEP + 1))
        assertEquals(7_150, q.cursor)
    }

    @Test
    fun theLastSongsOfALongQueueAreAllThere() {
        val q = queueOf(20_000, start = 19_990)
        val p = Fake()
        QueueSync.load(q, p, 0)
        assertEquals(q.playingUids().takeLast(9), p.uids.takeLast(9))
        assertEquals(p.count - 10, p.current)
        assertInStep(q, p)
    }

    @Test
    fun anEditChangesOnlyWhatItMustAroundTheSongPlaying() {
        val q = queueOf(1_000, start = 310)
        val p = Fake()
        QueueSync.load(q, p, 0)
        val next = p.uids[p.current + 1]
        // The song four places on is taken out: one removal and one arrival at the far end,
        // and the next song (already loaded for a gapless join) is left alone.
        assertTrue(q.dropUid(p.uids[p.current + 4]))
        p.log.clear()
        assertTrue(QueueSync.sync(q, p))
        assertEquals(next, p.uids[p.current + 1])
        assertEquals(1, p.loads)
        assertEquals(2, p.log.size)
        assertInStep(q, p)
        // Nothing changed: nothing done.
        assertFalse(QueueSync.sync(q, p))
        // A song queued by hand goes in straight after the one playing.
        q.queueUp(listOf(Song(id = "extra")), playNext = true)
        QueueSync.sync(q, p)
        assertEquals("extra", q.songs[q.order[q.cursor + 1]].id)
        assertInStep(q, p)
        // Shuffle: everything to come changes, the song playing doesn't.
        val playing = p.uids[p.current]
        q.setShuffle(true, Random(3))
        QueueSync.sync(q, p)
        assertEquals(playing, p.uids[p.current])
        assertEquals(1, p.loads)
        assertInStep(q, p)
    }

    @Test
    fun aJumpNearbyMovesThePlayerAndAJumpFarAwayReloads() {
        val q = queueOf(5_000, start = 100)
        val p = Fake()
        QueueSync.load(q, p, 0)
        // Ten on: within the window.
        assertTrue(q.jumpAhead(9))
        assertTrue(q.cursor < QueueSync.window(100, 5_000).last)
        QueueSync.sync(q, p, 0)
        assertEquals(1, p.loads)
        assertInStep(q, p)
        // To the top from the end (Next on the last song): far outside it.
        q.moveTo(4_999)
        QueueSync.sync(q, p)
        q.moveTo(0)
        QueueSync.sync(q, p)
        assertEquals(0, p.current)
        assertInStep(q, p)
        // The song playing leaves the queue: the playlist starts afresh at what is current.
        q.dropPlaces(0, 1)
        QueueSync.sync(q, p)
        assertInStep(q, p)
        // Everything goes.
        q.clear()
        assertTrue(QueueSync.sync(q, p))
        assertEquals(0, p.count)
        assertFalse(QueueSync.sync(q, p))
    }

    @Test
    fun whateverIsDoneToTheQueueThePlaylistFollows() {
        // A few thousand edits and songs going by, in an order that changes with the seed.
        for (seed in 1..6) {
            val random = Random(seed)
            val q = queueOf(if (seed % 2 == 0) 3_000 else 120, shuffle = seed % 3 == 0, start = random.nextInt(100))
            val p = Fake()
            QueueSync.load(q, p, 0)
            repeat(600) { step ->
                val ahead = q.order.size - q.cursor - 1
                when (random.nextInt(11)) {
                    0, 1, 2 -> if (p.current + 1 < p.count) {
                        p.advance()
                        q.moveTo(q.placeOf(p.uids[p.current]))
                    }
                    3 -> if (ahead > 0) q.dropUid(q.playingUids()[q.cursor + 1 + random.nextInt(minOf(ahead, 300))])
                    4 -> q.queueUp(listOf(Song(id = "q$seed-$step")), playNext = random.nextBoolean())
                    5 -> if (ahead > 1) {
                        val uids = q.playingUids()
                        q.moveUid(uids[q.cursor + 1 + random.nextInt(minOf(ahead, 300))], uids[q.cursor + random.nextInt(minOf(ahead, 300))], random.nextBoolean())
                    }
                    6 -> q.setShuffle(!q.shuffle, random)
                    7 -> if (ahead > 0) q.jumpAhead(random.nextInt(minOf(ahead, 500)))
                    8 -> if (random.nextInt(8) == 0) q.clearAhead(queued = random.nextBoolean())
                    9 -> if (q.order.size > 3) q.move(random.nextInt(q.order.size - 2), q.order.size - 1, random.nextInt(q.order.size - 2))
                    10 -> if (random.nextInt(10) == 0 && !q.isEmpty) q.moveTo(random.nextInt(q.order.size))
                }
                QueueSync.sync(q, p)
                assertInStep(q, p)
            }
        }
    }

    @Test
    fun aRepeatingQueueRunsFromItsLastSongIntoItsFirstWithoutBeingLoadedAgain() {
        // Too long to hold whole, so the player's own "repeat all" can't go round it.
        val q = queueOf(1_000, start = 990).apply { repeat = Repeat.ALL }
        val p = Fake()
        QueueSync.load(q, p, 0)
        // The window reaches the end, and after the last song come the first few again.
        val last = q.uids[q.order[999]]
        val first = q.uids[q.order[0]]
        assertEquals(listOf(last, first, q.uids[q.order[1]], q.uids[q.order[2]]), p.uids.takeLast(4))
        assertEquals(QueueSync.ROUND, QueueSync.round(q).count())
        // The player plays to the last song and on, by itself, into what follows it.
        while (p.uids[p.current] != first) {
            p.advance()
            q.moveTo(q.placeOf(p.uids[p.current]))
            QueueSync.sync(q, p)
        }
        // It is the queue's first song, and nothing was loaded afresh to get there: the song
        // was already in the player, as the next always is. (It used to stop at the end and
        // be started again from the top.)
        assertEquals(0, q.cursor)
        assertEquals(1, p.loads)
        assertInStep(q, p)
        assertEquals(q.uids[q.order[1]], p.uids[p.current + 1])
        // Without repeat, or with the queue held whole, nothing is held twice.
        assertTrue(QueueSync.round(queueOf(1_000, start = 990)).isEmpty())
        assertTrue(QueueSync.round(queueOf(40, start = 39).apply { repeat = Repeat.ALL }).isEmpty())
        assertTrue(QueueSync.round(queueOf(1_000, start = 10).apply { repeat = Repeat.ALL }).isEmpty())
        // And never a song the window already holds.
        val short = queueOf(QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER + 2, start = 200).apply { repeat = Repeat.ALL }
        val slots = QueueSync.slots(short).map { it.uid }
        assertEquals(slots.size, slots.toSet().size)
    }
}
