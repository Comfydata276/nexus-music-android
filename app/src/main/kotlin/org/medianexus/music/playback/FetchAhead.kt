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
class FetchAhead(
    private val sources: DataSource.Factory,
    /** The form a song would be fetched in now (as `StreamQuality` chooses it for the network
     *  now); null if it isn't known. */
    private val formNow: (String) -> StreamForm? = { null },
) {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Nexus:FetchAhead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** The song ids wanted now, in the order they will play. */
    @Volatile private var wanted: List<String> = emptyList()
    @Volatile private var going = false
    private val lock = Any()

    /** Ids tried in this run of the list that failed, with the form each failed in: not tried
     *  again in that form until the list changes. In another form (the phone has moved
     *  between Wi-Fi and mobile data, and the two limits differ) it is a new try. */
    private val failed = HashMap<String, StreamForm?>()

    /** How many times the worker has been set going (for the tests: it must not spin). */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var runs = 0

    private fun blocked(id: String): Boolean {
        if (id !in failed) return false
        val was = failed[id] ?: return true
        val now = formNow(id) ?: return true
        return now == was
    }

    /** These songs, in this order, are the ones to have (nothing: stop). */
    fun want(ids: List<String>) {
        synchronized(lock) {
            if (ids != wanted) failed.keys.retainAll(ids.toSet())
            wanted = ids
            // (Not set going for a list of nothing but what has failed: it used to set itself
            // going again, and again, for such a list.)
            if (going || ids.all(::blocked)) return
            going = true
        }
        worker.execute(::run)
    }

    private fun run() {
        runs++
        try {
            while (true) {
                val next = synchronized(lock) { wanted.firstOrNull { !blocked(it) } } ?: break
                val outcome = fetch(next)
                // Let go (the network changed under a form it may no longer be fetched in):
                // still wanted, and fetched again at once in the form for the network now.
                if (outcome is Outcome.LetGo) continue
                synchronized(lock) {
                    if (outcome is Outcome.Failed) failed[next] = outcome.form
                    // Done (kept, or the server had nothing): it leaves the wanted list when
                    // the service next looks, as it is kept now. Until then, not again.
                    if (next in wanted) wanted = wanted - next
                }
            }
        } finally {
            synchronized(lock) { going = false }
            // Wanted again while finishing: go on.
            want(wanted)
        }
    }

    private sealed interface Outcome {
        data object Fetched : Outcome
        data class Failed(val form: StreamForm?) : Outcome
        data object LetGo : Outcome
    }

    /**
     * Reads one song whole, through the player's own data source: so in the form the player
     * would open it in now, and asking the server for nothing a read of the player's may not
     * ask for (see `StreamQuality.mayAsk`). Fetching ahead goes further: a form above the
     * lower of two limits is not even **continued** once the network isn't surely unmetered
     * any more ([SongDataSource.mayGoOn]): it is let go between one read and the next.
     */
    private fun fetch(id: String): Outcome {
        val source = sources.createDataSource()
        val reading = source as? SongDataSource
        val buffer = ByteArray(64 * 1024)
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse(songUri(id))).build())
            while (id in wanted) {
                if (reading?.mayGoOn() == false) return Outcome.LetGo
                if (source.read(buffer, 0, buffer.size) == C.RESULT_END_OF_INPUT) break
            }
            Outcome.Fetched
        } catch (e: StreamFailure) {
            // "Moved": the form it was being fetched in may no longer be asked for here (the
            // network changed): not a failure, and fetched again at once, chosen afresh.
            if (e.moved) Outcome.LetGo else Outcome.Failed(reading?.streamForm)
        } catch (_: IOException) {
            Outcome.Failed(reading?.streamForm)
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
