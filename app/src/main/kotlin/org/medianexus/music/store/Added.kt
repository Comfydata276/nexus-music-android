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
 * file named and written as Windows' is (`playlist-added.json`: `lists` and `seen`; the
 * notes go between devices inside the sync milestone's own file, under `added`).
 *
 * The server doesn't record this, so the app keeps its own note. The first time it sees a
 * playlist, the songs already in it have no known date. From then on, a song that turns up in
 * the playlist is dated to when the app added it, or first saw it there. "Date added" sorts
 * by these, and by the day the song joined the library where there is no note.
 *
 * **What the phone tells other devices** (docs/DESIGN.md, "In step with other devices"): a
 * note with a time on it (`at` above 0) is shared, and on every device the newer note for a
 * song wins. So the phone puts a time on a note only for what the person did on this phone
 * ([changed], [create], [record]). What it merely notices in a copy of a playlist it has read
 * ([sawCopy]) is kept for its own "Date added" as a note with no time on it, which is never
 * shared and gives way to any dated note from another device; and a dated note is never
 * altered by what is noticed. (Windows dates what it notices with "now", in [observe], and
 * shares that: a PC that looks at a playlist late overwrites the date the song was really
 * added, on every device. That is ported for its tests, and the phone does not call it.)
 *
 * Safe from any thread. Written off the main thread, whole, the newest state each time; read
 * when first needed.
 */
class Added(
    private val file: File,
    private val scope: CoroutineScope?,
    private val io: CoroutineDispatcher,
    /** Where an earlier version of the app kept it (`added.json`): moved to `file` when found. */
    private val formerly: File? = null,
) {
    /** Told when a dated note changed here, by the person's own doing: worth passing on to
     *  other devices. */
    @Volatile var changedHere: () -> Unit = {}

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
        if (!file.isFile && formerly?.isFile == true) formerly.renameTo(file)
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
     * A copy of a playlist has been read, from the server or the phone: the phone's own notes
     * are brought in step with it for "Date added", and nothing is put on record for other
     * devices. A song that has turned up since the first look is dated `now` here, in a note
     * with no time on it; one that has gone takes such a note with it. A note with a time on
     * it (the person's own doing, or another device's word) is left exactly as it is,
     * whatever the copy holds: it changes only by [changed] or by a newer note ([merge]).
     */
    @Synchronized
    fun sawCopy(playlist: String, songs: List<Song>, now: Long) {
        load()
        val counts = LinkedHashMap<String, Int>()
        for (song in songs) counts[song.id] = (counts[song.id] ?: 0) + 1
        val firstLook = seen.add(playlist)
        val notes = lists.getOrPut(playlist) { HashMap() }
        var changed = firstLook
        for (id in notes.keys.filter { it !in counts }) {
            if (notes.getValue(id).at == 0L) {
                notes.remove(id)
                changed = true
            }
        }
        for ((id, count) in counts) {
            val note = notes[id]
            if (note != null && (note.at != 0L || note.times.size == count)) continue
            val times = note?.times.orEmpty()
            notes[id] = AddedNote(
                // On the first look, copies nothing is known about go in front, as the oldest.
                if (firstLook) (List((count - times.size).coerceAtLeast(0)) { 0L } + times).take(count)
                else if (times.size < count) times + List(count - times.size) { now } else times.take(count),
                at = 0,
            )
            changed = true
        }
        if (changed) save()
    }

    /**
     * The person changed a playlist on this phone: it held `before` (song ids, as read from
     * the server just before the change) and holds `after`. Each song there are now more or
     * fewer copies of gets a note dated `now`, as Windows' `observe` and `record` would give
     * it: a copy added is dated now; a song taken out leaves its note, emptied, so other
     * devices hear that it went (unless nothing was known of it). Songs the change didn't
     * touch are not looked at.
     */
    fun changed(playlist: String, before: List<String>, after: List<String>, now: Long) {
        val told = synchronized(this) {
            load()
            val was = before.groupingBy { it }.eachCount()
            val isNow = after.groupingBy { it }.eachCount()
            val notes = lists.getOrPut(playlist) { HashMap() }
            var any = false
            var dated = false
            for (id in was.keys + isNow.keys) {
                val had = was[id] ?: 0
                val has = isNow[id] ?: 0
                if (had == has) continue
                any = true
                val note = notes[id]
                // The note as it stands for the copies that were there: a dated note is not
                // altered by what is noticed (see the class), so it may be out of step.
                // Copies nothing is known of go in front, as the oldest.
                val times = (note?.times.orEmpty()).let { if (it.size < had) List(had - it.size) { 0L } + it else it.take(had) }
                if (has == 0) {
                    if (note == null || note.at == 0L) {
                        notes.remove(id)
                    } else {
                        notes[id] = AddedNote(emptyList(), now)
                        dated = true
                    }
                } else {
                    notes[id] = AddedNote(if (times.size < has) times + List(has - times.size) { now } else times.take(has), now)
                    dated = true
                }
            }
            if (any) save()
            dated
        }
        if (told) changedHere()
    }

    /**
     * Brings the notes for a playlist in step with the songs now in it, as just read from the
     * server (Windows' `observe`). Songs it didn't have before are dated `now`, unless this is
     * the first look at the playlist. Returns whether anything worth sharing changed.
     *
     * Windows' own, ported with its tests. **The phone does not call it for a copy it has
     * merely read** (see the class): that is [sawCopy].
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
        if (ids.isNotEmpty()) changedHere()
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
