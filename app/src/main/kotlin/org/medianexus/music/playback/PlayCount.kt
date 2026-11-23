package org.medianexus.music.playback

import org.medianexus.music.net.Song

/**
 * Counts how long one song has really been listened to, as Windows does (`tick` in
 * src/playback.rs): a play counts after half the song or four minutes, whichever comes
 * first. Only time heard counts: a seek forward is a jump in position, not listening.
 *
 * It is fed the song's position together with the time on a clock that only matters while
 * the song plays. What is added between two samples is how far the position moved, but
 * never more than the time that passed: so a sample that arrives late (the main thread was
 * busy for five seconds) still counts all five, and a jump nobody announced counts for no
 * more than the moment it took. Plain Kotlin, so it can be tested on its own.
 */
class PlayCount {
    var songId: String = ""
        private set
    private var duration = 0.0
    private var listened = 0.0
    private var last = 0.0
    private var lastAt = 0L
    private var running = false
    private var counted = false

    /** Seconds heard so far of this play. */
    val heard: Double get() = listened

    /** A fresh play of a song starts (or of the same one again, from the top). */
    fun start(songId: String, durationSeconds: Double, at: Double = 0.0, nowMs: Long = 0) {
        this.songId = songId
        duration = durationSeconds
        listened = 0.0
        counted = false
        resume(at, nowMs)
    }

    /** Playing again after a pause, at `seconds`. */
    fun resume(seconds: Double, nowMs: Long) {
        last = seconds
        lastAt = nowMs
        running = true
    }

    /**
     * The song has got to `seconds` while playing (null if where it got to can't be known
     * any more, as when it has just ended: then the time that passed is what was heard).
     * Returns true once, at the moment the play counts.
     */
    fun at(seconds: Double?, nowMs: Long): Boolean {
        if (running) {
            val passed = ((nowMs - lastAt) / 1000.0).coerceAtLeast(0.0)
            val moved = if (seconds == null) passed else seconds - last
            if (moved > 0) listened += minOf(moved, passed + SLACK)
        }
        if (seconds != null) last = seconds
        lastAt = nowMs
        if (counted || songId.isEmpty()) return false
        if (listened >= threshold(duration)) {
            counted = true
            return true
        }
        return false
    }

    /** Playback stopped at `seconds` (a pause, buffering): what was heard up to here counts,
     *  and nothing more until it resumes. */
    fun pause(seconds: Double?, nowMs: Long): Boolean {
        val now = at(seconds, nowMs)
        running = false
        return now
    }

    /** A seek from `from` to `to`: what was heard before it counts; the jump doesn't. */
    fun jumped(from: Double?, to: Double, nowMs: Long): Boolean {
        val now = at(from, nowMs)
        last = to
        return now
    }

    companion object {
        /** Half the song or four minutes, whichever comes first. */
        fun threshold(durationSeconds: Double): Double = if (durationSeconds > 0) minOf(durationSeconds / 2, 240.0) else 240.0

        /** How much further than the clock the position may run between two samples: the
         *  two aren't read at the same instant. */
        private const val SLACK = 0.25
    }
}

/**
 * Decides what the server is told about listening: "now playing" once when a play starts,
 * and the play itself once it counts. It is told what the player does (starts, pauses,
 * moves on, seeks, reaches the end) and keeps count of the play in between.
 *
 * A play is one go at one entry of the queue. The same entry played again is a new play:
 * repeating one song, or a queue that reached its end and was started again from the top.
 */
class PlayReporter(
    private val nowPlaying: (Song) -> Unit,
    private val played: (songId: String) -> Unit,
) {
    private val count = PlayCount()
    /** The queue entry whose play is being counted. */
    private var entry: Long? = null

    /** The song of this queue entry is playing, at `seconds`. */
    fun playing(uid: Long, song: Song, seconds: Double, nowMs: Long) {
        if (uid == entry) {
            count.resume(seconds, nowMs)
            return
        }
        entry = uid
        count.start(song.id, song.duration.toDouble(), seconds, nowMs)
        nowPlaying(song)
    }

    /** Still playing, now at `seconds`. */
    fun tick(seconds: Double, nowMs: Long) = told(count.at(seconds, nowMs))

    /** Stopped playing (paused, buffering, an error) at `seconds`. */
    fun paused(seconds: Double?, nowMs: Long) = told(count.pause(seconds, nowMs))

    /** A seek within the song. */
    fun sought(from: Double, to: Double, nowMs: Long) = told(count.jumped(from, to, nowMs))

    /**
     * The player left the song it was on, which had got to `seconds` (null if not known):
     * the last of it is counted. `again` says the same entry is starting over (repeat), which
     * is a new play of it.
     */
    fun left(seconds: Double?, nowMs: Long, again: Boolean = false) {
        told(count.pause(seconds, nowMs))
        if (again) entry = null
    }

    /** The queue played to its end: whatever plays next, even this same entry, is new. */
    fun ended(nowMs: Long) = left(null, nowMs, again = true)

    /** Nothing is playing any more (the account went). */
    fun reset() {
        entry = null
        count.start("", 0.0)
        count.pause(null, 0)
    }

    private fun told(counts: Boolean) {
        if (counts) played(count.songId)
    }
}
