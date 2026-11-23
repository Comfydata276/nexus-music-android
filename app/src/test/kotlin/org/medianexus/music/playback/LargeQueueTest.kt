package org.medianexus.music.playback

import java.io.File
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

/**
 * A queue of 20,000 songs: starting it, editing it and saving it each stay well inside what
 * a main thread can spare. The bounds are generous (this runs on whatever machine builds
 * the app), but an edit that does work for every song for every song is thousands of times
 * over them: the old "clear the rest of the queue" took a removal from the middle of four
 * lists for each of 20,000 songs.
 */
class LargeQueueTest {
    @get:Rule val folder = TemporaryFolder()

    private val count = 20_000
    private val songs = List(count) { Song(id = "song-%05d".format(it), title = "Song number $it", artist = "An artist", album = "An album", duration = 200) }

    /** A stand-in for the player's playlist that only counts. */
    private class Counting : QueueSync.Playlist {
        val uids = ArrayList<Long>()
        var current = 0
        var most = 0
        override val count get() = uids.size
        override fun uidAt(index: Int) = uids.getOrNull(index)
        override val currentIndex get() = current
        override fun set(slots: List<QueueSync.Slot>, index: Int, positionMs: Long) { uids.clear(); slots.mapTo(uids) { it.uid }; current = index; most = maxOf(most, uids.size) }
        override fun clear() { uids.clear(); current = 0 }
        override fun remove(from: Int, to: Int) { repeat(to - from) { uids.removeAt(from) }; if (from < current) current -= to - from }
        override fun add(at: Int, slots: List<QueueSync.Slot>) { uids.addAll(at, slots.map { it.uid }); if (at <= current) current += slots.size; most = maxOf(most, uids.size) }
        override fun seekTo(index: Int, positionMs: Long) { current = index }
    }

    /** The longest of a few runs, in milliseconds, after one to warm up. */
    private fun millis(runs: Int = 5, block: () -> Unit): Double {
        block()
        return (1..runs).maxOf { measureNanoTime(block) } / 1_000_000.0
    }

    private fun assertQuick(what: String, limitMillis: Double, took: Double) =
        assertTrue("$what took %.1f ms; the limit is %.0f ms".format(took, limitMillis), took <= limitMillis)

    @Test
    fun startingIsQuickAndThePlayerHoldsOnlyAWindow() {
        val playlist = Counting()
        val queue = Queue()
        val start = millis {
            queue.replace(songs, 12_345, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = false)
            QueueSync.load(queue, playlist, 0)
        }
        assertQuick("starting 20,000 songs in order", 100.0, start)
        val shuffled = millis {
            queue.replace(songs, 0, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = true, anyStart = true, random = Random(1))
            QueueSync.load(queue, playlist, 0)
        }
        assertQuick("starting 20,000 songs shuffled", 100.0, shuffled)
        // What ExoPlayer, the session, the car and the screens are given: a few hundred.
        assertTrue("${playlist.most} items", playlist.most <= QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER)
        assertEquals(count, queue.order.size)
    }

    @Test
    fun everyEditIsQuick() {
        val playlist = Counting()
        val queue = Queue()
        fun fresh(at: Int = 5_000) {
            queue.replace(songs, at, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = false)
            QueueSync.load(queue, playlist, 0)
        }
        fresh()
        val ahead = queue.playingUids()
        assertQuick("removing a song", 50.0, millis { queue.dropUid(queue.playingUids()[queue.cursor + 3]); QueueSync.sync(queue, playlist) })
        assertQuick("moving a song", 50.0, millis {
            val uids = queue.playingUids()
            queue.moveUid(uids[queue.cursor + 200], uids[queue.cursor + 1], queued = true)
            QueueSync.sync(queue, playlist)
        })
        assertQuick("queueing a song next", 50.0, millis { queue.queueUp(listOf(Song(id = "x")), playNext = true); QueueSync.sync(queue, playlist) })
        assertQuick("jumping ahead", 50.0, millis { queue.jumpAhead(250); QueueSync.sync(queue, playlist) })
        assertQuick("shuffle on, then off", 100.0, millis {
            queue.setShuffle(true, Random(2)); QueueSync.sync(queue, playlist)
            queue.setShuffle(false, Random(2)); QueueSync.sync(queue, playlist)
        })
        assertQuick("the player moving on to the next song", 20.0, millis {
            queue.moveTo(queue.cursor + 1)
            QueueSync.sync(queue, playlist)
        })
        // The ones that used to do work for every song, for every song.
        assertQuick("clearing the 15,000 songs still to come", 100.0, millis(runs = 3) { fresh(); queue.clearAhead(queued = false); QueueSync.sync(queue, playlist) })
        assertQuick("a controller removing 10,000 songs at once", 100.0, millis(runs = 3) { fresh(); queue.dropUids(ahead.subList(6_000, 16_000).toHashSet()); QueueSync.sync(queue, playlist) })
        assertQuick("shuffle with 2,000 songs queued by hand", 150.0, millis(runs = 3) {
            fresh()
            queue.queueUp(List(2_000) { Song(id = "q$it") }, playNext = false)
            queue.setShuffle(true, Random(3))
            QueueSync.sync(queue, playlist)
        })
        assertQuick("the list it came from losing a song", 150.0, millis(runs = 3) {
            fresh()
            val now = songs.filterIndexed { index, _ -> index != 9_000 }
            queue.follow(now.map { it.id }, now::getOrNull, Random(4))
            QueueSync.sync(queue, playlist)
        })
        // Never much more than the window (a little over it for a moment, while what is
        // after the song playing has been put right and what is before it hasn't yet).
        assertTrue("${playlist.most} items", playlist.most <= 2 * (QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER))
    }

    @Test
    fun savingTakesTheMainThreadAlmostNothing() {
        val queue = Queue()
        queue.replace(songs, 100, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = true, random = Random(5))
        val files = QueueFiles.of(folder.root)
        // What happens on the main thread: a copy of the queue when it has changed...
        assertQuick("taking a snapshot of the queue", 20.0, millis { queue.saved(12.5, 1_700_000_000) })
        // ...and off it: working out which queue this is, and writing it.
        val saved = queue.saved(12.5, 1_700_000_000)
        assertQuick("writing 20,000 songs to disk", 3_000.0, millis(runs = 2) { File(folder.root, "queue.json").delete(); QueueFiles.of(folder.root).save(saved) })
        files.save(saved, token = 1)
        // Where playback has got to, saved every few seconds: nothing of the queue is
        // copied, hashed or written again.
        val written = File(folder.root, "queue.json").lastModified()
        assertQuick("saving only the place", 50.0, millis { files.savePlace(1, 101, 33.0, 1_700_000_100) })
        assertEquals(written, File(folder.root, "queue.json").lastModified())
        val back = QueueFiles.of(folder.root).load()!!
        assertEquals(count, back.songs.size)
        assertEquals(101, back.cursor)
        assertEquals(33.0, back.position, 0.0)
        assertEquals(queue.order, back.order)
    }
}
