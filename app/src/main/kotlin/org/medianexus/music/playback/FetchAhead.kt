package org.medianexus.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Fetches the next few songs of the queue ahead of time into the kept songs (Windows'
 * `keep_ahead`), so that a tunnel or a dead spot costs nothing: when the player gets there,
 * the song is already on the phone.
 *
 * Each is read whole through the player's own data source, from its first byte to its end,
 * and thrown away as it goes: so it is kept by exactly the rules a song the player reads is
 * kept by (a conversion only whole, and marked so; see `SongReader`), in the form the player
 * will ask for, and for the account signed in. One song at a time, on a thread of its own at
 * low priority. A song that is no longer wanted (the queue changed, offline mode was turned
 * on, the person paused) is let go at once; what had arrived of a conversion is then not
 * kept, and of a file as it is the part that arrived stays for the player to carry on from.
 */
@OptIn(UnstableApi::class)
class FetchAhead(private val sources: DataSource.Factory) {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Nexus:FetchAhead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** The song ids wanted now, in the order they will play. */
    @Volatile private var wanted: List<String> = emptyList()
    @Volatile private var going = false
    private val lock = Any()

    /** Goes up when the song being fetched is to be let go and fetched afresh ([again]). */
    @Volatile private var round = 0

    /** Ids tried in this run of the list that failed: not tried again until the list changes. */
    private val failed = HashSet<String>()

    /** These songs, in this order, are the ones to have (nothing: stop). */
    fun want(ids: List<String>) {
        synchronized(lock) {
            if (ids != wanted) failed.retainAll(ids.toSet())
            wanted = ids
            if (going || ids.isEmpty()) return
            going = true
        }
        worker.execute(::run)
    }

    /**
     * The song being fetched is let go and fetched again from its start: the limit for a
     * stream opened now has changed with the network (Wi-Fi to mobile data, or back), and
     * what is fetched ahead is fetched in the form of the network it is fetched on. What had
     * arrived of a conversion is not kept; of a file as it is, it stays under its own key.
     */
    fun again() {
        synchronized(lock) { round++ }
    }

    private fun run() {
        try {
            while (true) {
                val (next, began) = synchronized(lock) { wanted.firstOrNull { it !in failed }?.let { it to round } } ?: break
                val fetched = fetch(next, began)
                synchronized(lock) {
                    // Let go to be fetched afresh: it stays wanted.
                    if (round != began) return@synchronized
                    if (!fetched) failed += next
                    // Done (kept, or the server had nothing): it leaves the wanted list when
                    // the service next looks, as it is kept now. Until then, not again.
                    if (next in wanted) wanted = wanted - next
                }
            }
        } finally {
            synchronized(lock) { going = false }
            // Wanted again while finishing: go on.
            if (wanted.isNotEmpty()) want(wanted)
        }
    }

    /** Reads one song whole; false if it failed. Let go when it is no longer wanted, or
     *  [again] is asked. */
    private fun fetch(id: String, began: Int): Boolean {
        val source = sources.createDataSource()
        val buffer = ByteArray(64 * 1024)
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse(songUri(id))).build())
            while (id in wanted && round == began && source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { source.close() }
        }
    }

    fun release() {
        wanted = emptyList()
        worker.shutdownNow()
    }

    companion object {
        /** On mobile data, how many songs are fetched ahead whatever the setting: the next one,
         *  so a dead spot at a join costs nothing, while skipping through a queue on a data
         *  plan doesn't fetch three songs for every skip. */
        const val ON_MOBILE_DATA = 1

        /** The songs to fetch ahead: those of the `count` places after the one playing, in
         *  playing order, that aren't on the phone already (`have`), as Windows counts them. */
        fun upcoming(queue: Queue, count: Int, have: (org.medianexus.music.net.Song) -> Boolean): List<String> {
            if (count <= 0 || queue.isEmpty) return emptyList()
            val out = ArrayList<String>()
            val last = minOf(queue.cursor + count, queue.order.size - 1)
            for (place in queue.cursor + 1..last) {
                val song = queue.songs[queue.order[place]]
                if (song.id.isNotEmpty() && song.radioUrl.isEmpty() && !have(song) && song.id !in out) out += song.id
            }
            return out
        }
    }
}
