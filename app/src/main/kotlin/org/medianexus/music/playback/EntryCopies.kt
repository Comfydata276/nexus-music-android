package org.medianexus.music.playback

/**
 * The copy each queue entry has begun to play from, by the entry's own number (`uid`: the
 * same song twice in the queue is two entries). Told by the player's own reads as their
 * first bytes arrive ([SongLease.began]); never by fetching ahead, which reads for no entry.
 *
 * Two things go by it. **What the format line says is playing** is the current entry's copy,
 * so the second of two entries of one song, or the same song fetched ahead on another
 * network, can't stand in for the one that is sounding. And **an entry goes on in the form it
 * began in** ([formOf]): Media3 makes a new data source for an entry after its own retries
 * have given up and the service makes it ready again, and that one must not choose afresh (the
 * network may have changed since).
 *
 * Read on the player's loading threads and written there and on the main thread.
 */
class EntryCopies(private val most: Int = 64) {
    private val copies = LinkedHashMap<Long, SongCopy>()

    @Synchronized
    fun began(entry: Long, copy: SongCopy) {
        copies.remove(entry)
        copies[entry] = copy
        while (copies.size > most) copies.remove(copies.keys.first())
    }

    @Synchronized
    operator fun get(entry: Long?): SongCopy? = entry?.let { copies[it] }

    /** The form a stream of this entry began in: what its next read is opened in. */
    @Synchronized
    fun formOf(entry: Long?): StreamForm? = (get(entry) as? SongCopy.Stream)?.form

    /** The entry is to be chosen afresh (the streaming quality was changed by the person,
     *  the phone turned out not to play the file as it is, its copy has gone). */
    @Synchronized
    fun forget(entry: Long?) {
        entry?.let { copies.remove(it) }
    }

    /** Every entry's stream is to be chosen afresh; what is read from a download stays. */
    @Synchronized
    fun forgetStreams() {
        copies.values.removeAll { it is SongCopy.Stream }
    }

    @Synchronized
    fun clear() = copies.clear()
}
