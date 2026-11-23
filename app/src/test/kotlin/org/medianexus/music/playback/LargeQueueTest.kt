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
 * A very long queue: starting it, editing it and saving it take time in proportion to its
 * length, never to the square of it.
 *
 * This runs on whatever machine builds the app, beside whatever else that machine is doing,
 * so it does not hold the clock to milliseconds (it used to, and could fail for no reason
 * but a busy machine). What is done is counted instead, exactly: what the player is asked to
 * do, and how many times the playing order has one entry put into or taken out of its middle
 * (each of which shifts everything after it: done once for each of many songs, that is the
 * square of the queue). A clock is still looked at, but only to catch an edit that has gone
 * badly wrong: seconds are allowed for what takes milliseconds. (How long the main thread is
 * really held on a phone is measured on the emulator: see docs/TESTING.md.)
 */
class LargeQueueTest {
    @get:Rule val folder = TemporaryFolder()

    private val count = 200_000
    private val songs = List(count) { Song(id = "song-%06d".format(it), title = "Song number $it", artist = "An artist", album = "An album", duration = 200) }

    /** A stand-in for the player's playlist that counts what it is asked to do. */
    private class Counting : QueueSync.Playlist {
        val uids = ArrayList<Long>()
        var current = 0
        var most = 0
        /** Items handed to the player, and calls made on it, in all. */
        var handed = 0
        var calls = 0
        override val count get() = uids.size
        override fun uidAt(index: Int) = uids.getOrNull(index)
        override val currentIndex get() = current
        override fun set(slots: List<QueueSync.Slot>, index: Int, positionMs: Long) { calls++; handed += slots.size; uids.clear(); slots.mapTo(uids) { it.uid }; current = index; most = maxOf(most, uids.size) }
        override fun clear() { calls++; uids.clear(); current = 0 }
        override fun remove(from: Int, to: Int) { calls++; uids.subList(from, to).clear(); if (from < current) current -= to - from }
        override fun add(at: Int, slots: List<QueueSync.Slot>) { calls++; handed += slots.size; uids.addAll(at, slots.map { it.uid }); if (at <= current) current += slots.size; most = maxOf(most, uids.size) }
        override fun seekTo(index: Int, positionMs: Long) { calls++; current = index }
    }

    /** The playing order, counting each time one entry is put into its middle or taken out
     *  of it: the step that, taken once for each of many songs, is the square of the queue. */
    private class Order : ArrayList<Int>() {
        var oneAtATime = 0
        override fun add(index: Int, element: Int) {
            if (index < size) oneAtATime++
            super.add(index, element)
        }
        override fun removeAt(index: Int): Int {
            if (index < size - 1) oneAtATime++
            return super.removeAt(index)
        }
    }

    private val order = Order()

    /** Seconds allowed for one edit of 200,000 songs. Hundreds of times what it takes. */
    private val allowed = 20.0

    /** Does an edit, and checks it was done in a few passes over the queue: however many
     *  songs it was for, the playing order was shifted about no more than `shifts` times. */
    private fun assertInProportion(what: String, shifts: Int = 2, block: () -> Unit) {
        val before = order.oneAtATime
        val took = measureNanoTime(block) / 1e9
        val shifted = order.oneAtATime - before
        assertTrue("$what shifted the playing order about $shifted times: once for every song, on a queue of 200,000", shifted <= shifts)
        assertTrue("$what took %.1f s on a queue of 200,000".format(took), took <= allowed)
    }

    private val window = QueueSync.BEFORE + QueueSync.STEP + QueueSync.AFTER

    @Test
    fun thePlayerIsOnlyEverHandedAWindowHoweverLongTheQueue() {
        val playlist = Counting()
        val queue = Queue(order)
        assertInProportion("starting it in order") {
            queue.replace(songs, 123_456, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = false)
            QueueSync.load(queue, playlist, 0)
        }
        // What ExoPlayer, the session, the car and the screens are given: a few hundred, in
        // one call.
        assertEquals(1, playlist.calls)
        assertTrue("${playlist.handed} items", playlist.handed <= window)
        assertInProportion("starting it shuffled") {
            queue.replace(songs, 0, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = true, anyStart = true, random = Random(1))
            QueueSync.load(queue, playlist, 0)
        }
        assertEquals(count, queue.order.size)
        // Playing on through it: the window moves once in every STEP songs, and by a step.
        queue.replace(songs, 0, "Everything", "playlist:big", shuffle = false)
        QueueSync.load(queue, playlist, 0)
        playlist.calls = 0
        playlist.handed = 0
        repeat(1_000) {
            playlist.current++
            queue.moveTo(queue.placeOf(playlist.uids[playlist.current]))
            QueueSync.sync(queue, playlist)
        }
        assertEquals(1_000, queue.cursor)
        assertTrue("${playlist.calls} calls on the player for 1,000 songs played", playlist.calls <= 2 * (1_000 / QueueSync.STEP + 1))
        assertTrue("${playlist.handed} items handed over", playlist.handed <= 1_000 + QueueSync.STEP)
        assertTrue("${playlist.most} items held", playlist.most <= window + QueueSync.STEP)
    }

    @Test
    fun everyEditTakesTimeInProportionToTheQueue() {
        val playlist = Counting()
        val queue = Queue(order)
        fun fresh(at: Int = 50_000) {
            queue.replace(songs, at, "Everything", "playlist:big", songs.indices.toList(), songs.map { it.id }, shuffle = false)
            QueueSync.load(queue, playlist, 0)
        }
        fresh()
        val ahead = queue.playingUids()
        // One at a time: each is one pass over the queue at most.
        assertInProportion("removing a song") { queue.dropUid(queue.playingUids()[queue.cursor + 3]); QueueSync.sync(queue, playlist) }
        assertInProportion("moving a song") {
            val uids = queue.playingUids()
            queue.moveUid(uids[queue.cursor + 200], uids[queue.cursor + 1], queued = true)
            QueueSync.sync(queue, playlist)
        }
        assertInProportion("jumping ahead") { queue.jumpAhead(250); QueueSync.sync(queue, playlist) }
        assertInProportion("shuffle on, then off") {
            queue.setShuffle(true, Random(2)); QueueSync.sync(queue, playlist)
            queue.setShuffle(false, Random(2)); QueueSync.sync(queue, playlist)
        }
        // Many at once: the ones that did work for every song, for every song.
        assertInProportion("clearing the 150,000 songs still to come") { fresh(); queue.clearAhead(queued = false); QueueSync.sync(queue, playlist) }
        assertInProportion("a controller removing 100,000 songs at once") { fresh(); queue.dropUids(ahead.subList(60_000, 160_000).toHashSet()); QueueSync.sync(queue, playlist) }
        val byHand = List(100_000) { Song(id = "q$it") }
        assertInProportion("queueing 100,000 songs by hand") { fresh(); queue.queueUp(byHand, playNext = false); QueueSync.sync(queue, playlist) }
        assertEquals(count + 100_000, queue.order.size)
        assertEquals(100_000, queue.queuedAhead())
        assertInProportion("queueing 100,000 more to play next") { queue.queueUp(byHand, playNext = true); QueueSync.sync(queue, playlist) }
        assertInProportion("shuffle with 200,000 songs queued by hand") { queue.setShuffle(true, Random(3)); QueueSync.sync(queue, playlist) }
        assertEquals(200_000, queue.queuedAhead())
        assertInProportion("jumping past 200,000 songs queued by hand") { queue.jumpAhead(250_000); QueueSync.sync(queue, playlist) }
        assertEquals(200_000, queue.queuedAhead())
        assertInProportion("a controller moving 50,000 songs") { queue.move(queue.cursor + 10, queue.cursor + 50_010, 5); QueueSync.sync(queue, playlist) }
        assertInProportion("the list it came from losing a song", shifts = 0) {
            fresh()
            val now = songs.filterIndexed { index, _ -> index != 90_000 }
            queue.follow(now.map { it.id }, now::getOrNull, Random(4))
            QueueSync.sync(queue, playlist)
        }
        // And through all of it the player held no more than its window (a little over it for
        // a moment, while what is after the song playing has been put right and what is
        // before it hasn't yet).
        assertTrue("${playlist.most} items", playlist.most <= 2 * window)
    }

    @Test
    fun savingCopiesTheQueueOnlyWhenItHasChanged() {
        val small = List(20_000) { songs[it] }
        val queue = Queue()
        queue.replace(small, 100, "Everything", "playlist:big", small.indices.toList(), small.map { it.id }, shuffle = true, random = Random(5))
        val files = QueueFiles.of(folder.root)
        val saved = queue.saved(12.5, 1_700_000_000)
        assertTrue(files.save(saved, token = 1))
        // Where playback has got to, saved every few seconds: nothing of the queue is copied,
        // hashed or written again. Not timed: the queue's file is simply not touched.
        val file = File(folder.root, "queue.json")
        val written = file.lastModified()
        val length = file.length()
        file.setLastModified(written - 60_000)
        assertTrue(files.savePlace(1, 101, 33.0, 1_700_000_100))
        assertEquals(written - 60_000, file.lastModified())
        assertEquals(length, file.length())
        val back = QueueFiles.of(folder.root).load()!!
        assertEquals(20_000, back.songs.size)
        assertEquals(101, back.cursor)
        assertEquals(33.0, back.position, 0.0)
        assertEquals(queue.order, back.order)
        // And taking the copy, when it has changed, is one pass.
        val long = Queue(order).apply { replace(songs, 100, "Everything", shuffle = false) }
        assertInProportion("taking a snapshot of the queue") { long.saved(12.5, 1_700_000_000) }
    }
}
