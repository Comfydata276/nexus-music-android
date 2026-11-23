package org.medianexus.music.playback

/**
 * Counts how long one song has really been listened to, as Windows does (`tick` in
 * src/playback.rs): a play counts after half the song or four minutes, whichever comes
 * first. Only time heard counts: a seek forward is a jump in position, not listening, and
 * isn't added.
 *
 * Fed with the song's position every second or so while it plays. Plain Kotlin, so it can be
 * tested on its own.
 */
class PlayCount {
    var songId: String = ""
        private set
    private var duration = 0.0
    private var listened = 0.0
    private var last = 0.0
    private var counted = false

    /** A new song has started (or the same one again, from the top). */
    fun start(songId: String, durationSeconds: Double, at: Double = 0.0) {
        this.songId = songId
        duration = durationSeconds
        listened = 0.0
        last = at
        counted = false
    }

    /** The player is at `seconds` into the song after a seek: carry on from there without
     *  counting the jump. */
    fun jumped(seconds: Double) {
        last = seconds
    }

    /**
     * The song has got to `seconds` while playing. Returns true once, at the moment the play
     * counts.
     */
    fun at(seconds: Double): Boolean {
        val delta = seconds - last
        if (delta > 0 && delta < 2.5) listened += delta
        last = seconds
        if (counted || songId.isEmpty()) return false
        if (listened >= threshold(duration)) {
            counted = true
            return true
        }
        return false
    }

    companion object {
        /** Half the song or four minutes, whichever comes first. */
        fun threshold(durationSeconds: Double): Double = if (durationSeconds > 0) minOf(durationSeconds / 2, 240.0) else 240.0
    }
}
