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

    /**
     * Reads the account's list from the phone (off the main thread). Plays noted before it
     * had been read go in front of what is read, the same song once at the join (the song
     * playing when the app was stopped, still playing as it starts again, is one play), and
     * the list as merged is saved: nothing played meanwhile was saved, since nothing may be
     * written over the list on the phone before it has been read.
     */
    suspend fun open() {
        val early = writing.withLock {
            if (opened) return
            val read = kotlinx.coroutines.withContext(io) { load(file) }
            var early = false
            songs.update { mine ->
                early = mine.isNotEmpty()
                val joined = if (early && read.isNotEmpty() && mine.last().song.id == read.first().song.id) mine + read.drop(1) else mine + read
                joined.take(HISTORY_LENGTH)
            }
            opened = true
            early
        }
        if (early) save()
    }

    /** Told when a song that started playing here went into the list: worth passing on to
     *  the person's other devices. */
    @Volatile var changedHere: () -> Unit = {}

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
        if (changed) {
            save()
            changedHere()
        }
    }

    /** Whether the list on the phone has been read ([open]): before it has, what is here is
     *  not the account's history, and is neither shared with other devices nor merged. */
    val isOpen: Boolean get() = opened

    /**
     * Takes in what other devices have played (the history part of Windows' `take_shared`):
     * both devices' plays, in order ([org.medianexus.music.sync.Merge.mergeHistory]).
     * `songsOf` gives the songs for ids this phone hasn't met; a play whose song can't be had
     * is left out here, as on Windows (it stays in the shared record). Returns whether
     * anything changed. Nothing before the list on the phone has been read.
     */
    suspend fun take(theirs: List<org.medianexus.music.sync.Play>, songsOf: suspend (List<String>) -> Map<String, Song>): Boolean {
        if (!opened) return false
        fun plays(list: List<Played>) = list.map { org.medianexus.music.sync.Play(it.song.id, it.at) }
        val before = songs.value
        val merged = org.medianexus.music.sync.Merge.mergeHistory(plays(before), theirs)
        if (merged == plays(before)) return false
        val known = HashMap<String, Song>()
        for (played in before) known.putIfAbsent(played.song.id, played.song)
        val unmet = merged.map { it.song }.filter { it !in known }.distinct()
        if (unmet.isNotEmpty()) known.putAll(songsOf(unmet))
        var changed = false
        songs.update { now ->
            // (Something may have started playing while the songs were looked up: merged
            // with what is here now, so that isn't lost.)
            for (played in now) known.putIfAbsent(played.song.id, played.song)
            val list = org.medianexus.music.sync.Merge.mergeHistory(plays(now), theirs).mapNotNull { play -> known[play.song]?.let { Played(it, play.at) } }
            changed = list != now
            if (changed) list else now
        }
        if (changed) save()
        return changed
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
         *  newest hundred. (The one port of it is [org.medianexus.music.sync.Merge].) */
        fun mergeHistory(a: List<Pair<String, Long>>, b: List<Pair<String, Long>>): List<Pair<String, Long>> =
            org.medianexus.music.sync.Merge.mergeHistory(a.map { org.medianexus.music.sync.Play(it.first, it.second) }, b.map { org.medianexus.music.sync.Play(it.first, it.second) })
                .map { it.song to it.at }
    }
}
