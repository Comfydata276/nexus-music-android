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
 * each time, by one writer; read when the account is taken on.
 *
 * **A list that could not be read is never taken for no list.** Only "there is no such
 * file" is an account with no history yet. Any other failure to read it leaves the file
 * exactly as it is: this run never writes it, and what is here is not the account's history
 * ([isOpen] stays false, so it is neither shared nor merged); the song playing still shows.
 * A file that reads and is no list of this app's (cut short) is kept beside its place
 * (`history.json.damaged`) before anything is written where it was.
 *
 * **What has changed and is not yet on the disk stays due**: a write that failed is made
 * again at the next play, and at the next [take] whether or not that brings anything new.
 */
class RecentlyPlayed(private val file: File, private val scope: CoroutineScope, private val io: CoroutineDispatcher, private val note: (String) -> Unit = {}, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val songs = MutableStateFlow<List<Played>>(emptyList())
    val state: StateFlow<List<Played>> = songs.asStateFlow()
    private val writing = Mutex()
    @Volatile private var opened = false
    /** Counts the changes to the list; [written] is the one the file has. */
    @Volatile private var version = 0L
    @Volatile private var written = 0L
    /** The file read and was no list: it is kept aside before the first write. */
    private var damaged = false

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
            val read = kotlinx.coroutines.withContext(io) {
                try {
                    read(file)
                } catch (_: java.io.IOException) {
                    null
                }
            }
            if (read == null) {
                // Left exactly as it is, and never written in this run ([opened] stays false).
                unreadable = true
                note("recently played: the list on the phone couldn't be read, so it is left exactly as it is and nothing is shared of it")
                return
            }
            damaged = read.second
            val list = read.first
            var early = false
            songs.update { mine ->
                early = mine.isNotEmpty()
                val joined = if (early && list.isNotEmpty() && mine.last().song.id == list.first().song.id) mine + list.drop(1) else mine + list
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

    /** The list is on the phone and could not be read (as [open] found it): it is left as
     *  it is, and nothing of this account is exchanged with other devices until a start
     *  that can read it. Not so for no list at all, nor for one that read and was no list
     *  (kept aside): those are an empty history, and the account's. */
    @Volatile var unreadable = false
        private set

    /**
     * Takes in what other devices have played: what is here **at this moment** and theirs,
     * as one list (the shared core's `merge_history`: a song that started playing while the
     * exchange was out is here, and stays). `songsOf` gives the songs for ids this phone
     * hasn't met; a play whose song can't be had is left out here (it stays in the shared
     * record). **On the disk before it returns**; throws `IOException` if it couldn't be
     * written. Returns whether anything changed. Nothing before the list on the phone has
     * been read. Off the main thread.
     */
    @Throws(java.io.IOException::class)
    suspend fun take(theirs: List<org.medianexus.music.core.Play>, still: () -> Boolean = { true }, songsOf: suspend (List<String>) -> Map<String, Song>): Boolean {
        if (!opened) return false
        fun plays(list: List<Played>) = list.map { org.medianexus.music.core.Play(it.song.id, it.at) }
        val before = songs.value
        val merged = if (theirs.isEmpty()) plays(before) else org.medianexus.music.core.Core.history(plays(before), theirs)
        if (merged == plays(before)) {
            // Nothing new: but what an earlier try could not write is still due.
            sure()
            return false
        }
        val known = HashMap<String, Song>()
        for (played in before) known.putIfAbsent(played.song.id, played.song)
        val unmet = merged.map { it.song }.filter { it !in known }.distinct()
        if (unmet.isNotEmpty()) known.putAll(songsOf(unmet))
        // (Looking songs up took a moment: whoever asked may no longer want this taken in.)
        if (!still()) return false
        var changed = false
        songs.update { now ->
            // (Something may have started playing while the songs were looked up: merged
            // with what is here now, so that isn't lost.)
            for (played in now) known.putIfAbsent(played.song.id, played.song)
            val list = org.medianexus.music.core.Core.history(plays(now), theirs).mapNotNull { play -> known[play.song]?.let { Played(it, play.at) } }
            changed = list != now
            if (changed) list else now
        }
        if (changed) version++
        sure()
        return changed
    }

    /** Whatever of the list is not yet in the file is written, surely, or this throws.
     *  (Newer than any write still waiting its turn, which then has nothing to do.) */
    private suspend fun sure() = writing.withLock {
        if (written == version) return@withLock
        kotlinx.coroutines.withContext(io) { write(durable = true) }
    }

    /** The list as it is now, to the file. (Under [writing], off the main thread.) */
    private fun write(durable: Boolean) {
        val number = version
        val list = songs.value
        if (damaged) {
            java.nio.file.Files.copy(file.toPath(), File(file.path + ".damaged").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            damaged = false
        }
        writeAtomically(file, durable) { out -> out.write(json.encodeToString(LIST, list).toByteArray()) }
        written = number
    }

    private fun save() {
        val number = ++version
        scope.launch(io) {
            writing.withLock {
                // Not before the list on the phone has been read: it would be written over.
                if (!opened || number != version) return@withLock
                try {
                    write(durable = false)
                } catch (_: java.io.IOException) {
                    // Kept in memory, and still due: the next play writes it again, and so
                    // does the next exchange.
                }
            }
        }
    }

    companion object {
        /** Songs remembered for "Recently played" (Windows' `HISTORY_LENGTH`). */
        const val HISTORY_LENGTH = 100

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        private val LIST = ListSerializer(Played.serializer())

        /**
         * The file's list, newest first, and whether what is there reads and is no list
         * (then the list is empty). No file is an empty list. Throws `IOException` if it is
         * there and couldn't be read: asked by reading it, not by a check first, which would
         * take a file that couldn't be looked at for one that isn't there.
         */
        @Throws(java.io.IOException::class)
        fun read(file: File): Pair<List<Played>, Boolean> {
            val text = try {
                String(java.nio.file.Files.readAllBytes(file.toPath()), Charsets.UTF_8)
            } catch (_: java.nio.file.NoSuchFileException) {
                return emptyList<Played>() to false
            }
            return try {
                json.decodeFromString(LIST, text).filter { it.song.id.isNotEmpty() }.take(HISTORY_LENGTH) to false
            } catch (_: IllegalArgumentException) {
                // (Windows reads one from before plays carried a time, which the phone never wrote.)
                emptyList<Played>() to text.isNotBlank()
            }
        }
    }
}
