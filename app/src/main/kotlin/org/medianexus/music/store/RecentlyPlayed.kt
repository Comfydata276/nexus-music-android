package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song

/** A song in "Recently played", and when it was played (seconds since 1970): Windows'
 *  `Played`, in the same form. */
@Serializable
data class Played(val song: Song, val at: Long)

/**
 * Recently played: the last hundred songs that started playing, newest first, each playable
 * again by itself (Windows' `record_played`, `HISTORY_LENGTH`). Kept per account on the
 * phone, in the Windows app's own file form (`history.json`: a list of `song` and `at`), so
 * the sync milestone can share it as Windows does ([mergeHistory], ported now).
 *
 * The same song starting again straight after itself (a seek, a quality change, the player
 * opening it afresh) isn't a new play. Written off the main thread, whole, the newest state
 * each time; read when the account is taken on.
 */
class RecentlyPlayed(private val file: File, private val scope: CoroutineScope, private val io: CoroutineDispatcher, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val songs = MutableStateFlow<List<Played>>(emptyList())
    val state: StateFlow<List<Played>> = songs.asStateFlow()
    private val writing = Mutex()
    @Volatile private var opened = false
    @Volatile private var version = 0L

    /** Reads the account's list from the phone (off the main thread). Plays noted before it
     *  has been read are put in front of what is read. */
    suspend fun open() = writing.withLock {
        if (opened) return@withLock
        val read = kotlinx.coroutines.withContext(io) { load(file) }
        songs.update { mine -> (mine + read).take(HISTORY_LENGTH) }
        opened = true
    }

    /** A song has started playing. */
    fun played(song: Song) {
        if (song.id.isEmpty()) return
        var changed = false
        songs.update { list ->
            // Reloads of the same song (a seek, a quality change) aren't new plays.
            if (list.firstOrNull()?.song?.id == song.id) {
                changed = false
                list
            } else {
                changed = true
                (listOf(Played(song, now())) + list).take(HISTORY_LENGTH)
            }
        }
        if (changed) save()
    }

    private fun save() {
        val number = ++version
        scope.launch(io) {
            writing.withLock {
                // Not before the list on the phone has been read: it would be written over.
                if (!opened || number != version) return@withLock
                try {
                    writeAtomically(file) { out -> out.write(json.encodeToString(LIST, songs.value).toByteArray()) }
                } catch (_: java.io.IOException) {
                    // Kept in memory; the next play writes it again.
                }
            }
        }
    }

    companion object {
        /** Songs remembered for "Recently played" (Windows' `HISTORY_LENGTH`). */
        const val HISTORY_LENGTH = 100

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        private val LIST = ListSerializer(Played.serializer())

        /** The file's list, newest first; nothing for a file that can't be read (Windows reads
         *  one from before plays carried a time, which the phone never wrote). */
        fun load(file: File): List<Played> = try {
            if (!file.isFile) emptyList() else json.decodeFromString(LIST, file.readText()).filter { it.song.id.isNotEmpty() }.take(HISTORY_LENGTH)
        } catch (_: Exception) {
            emptyList()
        }

        /** Puts plays from two devices into one list, newest first (Windows' `merge_history`):
         *  the same play once, the same song twice running as one stretch of listening, the
         *  newest hundred. */
        fun mergeHistory(a: List<Pair<String, Long>>, b: List<Pair<String, Long>>): List<Pair<String, Long>> {
            val all = (a + b).sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            val out = ArrayList<Pair<String, Long>>()
            for (play in all) {
                val last = out.lastOrNull()
                if (last == play) continue
                if (last != null && last.first == play.first) continue
                out += play
            }
            return out.take(HISTORY_LENGTH)
        }
    }
}
