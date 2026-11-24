package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song

/** What's known about one song in one playlist (Windows' `Note`): one time per copy of the
 *  song in the playlist, oldest first, in seconds (0 for "before the app was keeping
 *  track"; none once the song has been removed), and when this note last changed (0 for a
 *  note that only says "it was already there", which isn't worth sharing). */
@Serializable
data class AddedNote(val times: List<Long> = emptyList(), val at: Long = 0)

/** Playlist id, then song id. */
typealias AddedNotes = Map<String, Map<String, AddedNote>>

/**
 * When songs were added to playlists, a port of the Windows app's `src/added.rs`, with its
 * file in the same form (`added.json`: `lists` and `seen`), ready to be shared between
 * devices with the sync milestone.
 *
 * The server doesn't record this, so the app keeps its own note. The first time it sees a
 * playlist, the songs already in it have no known date. From then on, a song that turns up in
 * the playlist is dated to when the app added it, or first saw it there. "Date added" sorts
 * by these, and by the day the song joined the library where there is no note.
 *
 * Safe from any thread. Written off the main thread, whole, the newest state each time; read
 * when first needed.
 */
class Added(private val file: File, private val scope: CoroutineScope?, private val io: CoroutineDispatcher) {
    private var lists = HashMap<String, HashMap<String, AddedNote>>()
    private var seen = HashSet<String>()
    private var loaded = false
    private val writing = Mutex()
    @Volatile private var dirty = 0L

    @Serializable
    private data class Stored(val lists: Map<String, Map<String, AddedNote>> = emptyMap(), val seen: Set<String> = emptySet())

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val bytes = try { if (file.isFile) file.readBytes() else return } catch (_: java.io.IOException) { return }
        val text = bytes.decodeToString()
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()?.takeIf { it.lists.isNotEmpty() || it.seen.isNotEmpty() }
            // The file as written before notes were shared: just the times.
            ?: runCatching { json.decodeFromString(OLD, text) }.getOrNull()?.let { old ->
                Stored(old.mapValues { (_, songs) -> songs.mapValues { (_, times) -> AddedNote(times, times.maxOrNull() ?: 0) } }, old.keys)
            }
            ?: return
        lists = HashMap(stored.lists.mapValues { HashMap(it.value) })
        seen = HashSet(stored.seen)
    }

    /**
     * Brings the notes for a playlist in step with the songs now in it, as just read from the
     * server (Windows' `observe`). Songs it didn't have before are dated `now`, unless this is
     * the first look at the playlist. Returns whether anything worth sharing changed.
     */
    @Synchronized
    fun observe(playlist: String, songs: List<Song>, now: Long): Boolean {
        load()
        val counts = LinkedHashMap<String, Int>()
        for (song in songs) counts[song.id] = (counts[song.id] ?: 0) + 1
        val firstLook = seen.add(playlist)
        val notes = lists.getOrPut(playlist) { HashMap() }
        var changed = firstLook
        var shared = false
        // Songs that have gone: a dated note stays, emptied, so other devices hear of it.
        val gone = notes.keys.filter { it !in counts }
        for (id in gone) {
            changed = true
            val note = notes.getValue(id)
            if (note.at == 0L) {
                notes.remove(id)
                continue
            }
            val kept = if (note.times.isNotEmpty()) {
                shared = true
                AddedNote(emptyList(), now)
            } else {
                note
            }
            if (now - kept.at < KEEP_REMOVED) notes[id] = kept else notes.remove(id)
        }
        for ((id, count) in counts) {
            val known = id in notes
            val note = notes[id] ?: AddedNote()
            if (note.times.size == count) {
                notes[id] = note
                continue
            }
            changed = true
            var times = note.times
            times = if (firstLook) {
                // Copies nothing is known about go in front, as the oldest.
                (List((count - times.size).coerceAtLeast(0)) { 0L } + times).take(count)
            } else {
                if (times.size < count) times + List(count - times.size) { now } else times.take(count)
            }
            val at = if (known || !firstLook) {
                shared = true
                now
            } else {
                note.at
            }
            notes[id] = AddedNote(times, at)
        }
        if (changed) save()
        return shared
    }

    /** Notes songs the app has just added to a playlist (Windows' `record`). */
    @Synchronized
    fun record(playlist: String, ids: List<String>, now: Long) {
        load()
        val notes = lists.getOrPut(playlist) { HashMap() }
        for (id in ids) {
            val note = notes[id] ?: AddedNote()
            notes[id] = AddedNote(note.times + now, now)
        }
        save()
    }

    /** Notes a playlist the app has just created with these songs in it (`create`). */
    @Synchronized
    fun create(playlist: String, ids: List<String>, now: Long) {
        load()
        lists.remove(playlist)
        seen.add(playlist)
        record(playlist, ids, now)
    }

    /** A playlist has gone (`forget`). */
    @Synchronized
    fun forget(playlist: String) {
        load()
        val known = seen.remove(playlist)
        if (lists.remove(playlist) != null || known) save()
    }

    /** The time each of these songs was added to the playlist, in the same order; 0 where it
     *  isn't known (`dates`). */
    @Synchronized
    fun dates(playlist: String, songs: List<Song>): LongArray {
        load()
        val notes = lists[playlist]
        val seenCopies = HashMap<String, Int>()
        return LongArray(songs.size) { i ->
            val id = songs[i].id
            val copy = (seenCopies[id] ?: 0) + 1
            seenCopies[id] = copy
            notes?.get(id)?.times?.getOrNull(copy - 1) ?: 0
        }
    }

    /** The notes worth sharing with other devices: the dated ones, for playlists that still
     *  exist (when `playlists` lists them) (`shared`). */
    @Synchronized
    fun shared(playlists: Set<String>? = null): AddedNotes {
        load()
        return lists.filterKeys { playlists == null || it in playlists }
            .mapValues { (_, notes) -> notes.filterValues { it.at > 0 } }
            .filterValues { it.isNotEmpty() }
    }

    /** Takes in notes from other devices: for each song, the newer note wins. Returns whether
     *  anything changed here (`merge`). */
    @Synchronized
    fun merge(theirs: AddedNotes): Boolean {
        load()
        var changed = false
        for ((playlist, notes) in theirs) {
            val mine = lists.getOrPut(playlist) { HashMap() }
            for ((id, note) in notes) {
                val have = mine[id]
                if (have == null || note.at > have.at) {
                    mine[id] = note
                    changed = true
                }
            }
        }
        if (changed) save()
        return changed
    }

    /** Written off the caller's thread (always off the main one), the newest state each
     *  time, one write after another: a write that was overtaken is skipped. With no scope
     *  (tests), at once. */
    private fun save() {
        val stored = Stored(lists.mapValues { HashMap(it.value) }, HashSet(seen))
        val number = ++dirty
        val write = {
            try {
                writeAtomically(file, json.encodeToString(Stored.serializer(), stored).toByteArray())
            } catch (_: java.io.IOException) {
                // Kept in memory; the next change writes it again.
            }
        }
        if (scope == null) write() else scope.launch(io) { writing.withLock { if (number == dirty) write() } }
    }

    companion object {
        /** A removed song's note is kept this long, so other devices hear that it went. */
        const val KEEP_REMOVED = 90 * 24 * 3600L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val OLD = MapSerializer(String.serializer(), MapSerializer(String.serializer(), ListSerializer(Long.serializer())))

        /** Combines two sets of shared notes: for each song, the newer one; word that a song was
         *  removed is dropped once it has had long enough to get round (`merge_notes`). */
        fun mergeNotes(a: AddedNotes, b: AddedNotes, now: Long): AddedNotes {
            val out = HashMap<String, HashMap<String, AddedNote>>()
            for ((playlist, notes) in a) out[playlist] = HashMap(notes)
            for ((playlist, notes) in b) {
                val mine = out.getOrPut(playlist) { HashMap() }
                for ((id, note) in notes) {
                    val have = mine[id]
                    if (have == null || note.at > have.at) mine[id] = note
                }
            }
            for (notes in out.values) notes.values.removeAll { it.times.isEmpty() && now - it.at >= KEEP_REMOVED }
            return out.filterValues { it.isNotEmpty() }
        }
    }
}
