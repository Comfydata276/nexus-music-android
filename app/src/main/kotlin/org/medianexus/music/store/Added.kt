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
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.OnePlaylist
import org.medianexus.music.core.OneTold
import org.medianexus.music.net.Song

/** What's known about one song in one playlist: the shared core's `Note` (one time per copy
 *  of the song in the playlist, oldest first, in seconds; 0 for "before the app was keeping
 *  track"; none once the song has been removed; and when this note last changed, 0 for a
 *  note the phone only noticed, which is never shared). */
typealias AddedNote = org.medianexus.music.core.Note

/** Playlist id, then song id. */
typealias AddedNotes = Map<String, Map<String, AddedNote>>

/**
 * When songs were added to playlists: the phone's keeping of the notes, in a file named and
 * written as the Windows app's is (`playlist-added.json`: `lists` and `seen`; the notes go
 * between devices inside the shared record, under `added`).
 *
 * The server doesn't record this, so the app keeps its own note. The first time it sees a
 * playlist, the songs already in it have no known date. From then on, a song that turns up in
 * the playlist is dated to when the app added it, or first saw it there. "Date added" sorts
 * by these, and by the day the song joined the library where there is no note.
 *
 * **Every rule about the notes is the shared core's** (`core/Core.kt`, the Windows
 * repository's `core/src/notes.rs`): what a copy of a playlist that has merely been read
 * changes ([sawCopy]: nothing that is shared), what the person's own change dates
 * ([changed], [record], [create]), which copy of a song has which date ([dates]), what is
 * worth sharing ([shared]) and how notes from other devices are taken in ([take]). What is
 * here is the file, and when it is written.
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
    /** Something for the Diagnostics page. */
    private val note: (String) -> Unit = {},
) {
    /** Told when a dated note changed here, by the person's own doing: worth passing on to
     *  other devices. */
    @Volatile var changedHere: () -> Unit = {}

    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Moves on whenever a date changes here, however it came (noticed, the person's own
     * change, or taken in from another device): a list that is open and sorted by date
     * added is arranged again when it does, and shows the dates as they now are.
     */
    val version: kotlinx.coroutines.flow.StateFlow<Long> = changes

    private var lists = HashMap<String, Map<String, AddedNote>>()
    private var seen = HashSet<String>()
    private var loaded = false
    /** The file is there and couldn't be read: it is not written to in this run. */
    private var unreadable = false
    /** The file holds something that isn't notes: kept aside before it is written over. */
    private var damaged = false
    private val writing = Mutex()
    @Volatile private var dirty = 0L

    /** `mark`: [MARK] in a file written under the present rule for what gets a time (see
     *  the class); 0 in one from a build before it. */
    @Serializable
    private data class Stored(val lists: Map<String, Map<String, AddedNote>> = emptyMap(), val seen: Set<String> = emptySet(), val mark: Int = 0)

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile && formerly?.isFile == true) formerly.renameTo(file)
        // Whether there is a file is told by reading it: only "no such file" means there is
        // none. One that is there and could not be read is **never taken for none**: it is
        // left exactly as it is, and nothing is written to it in this run (the dates noted
        // meanwhile are for this run's screens only).
        val bytes = try {
            java.nio.file.Files.readAllBytes(file.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            return
        } catch (_: java.io.IOException) {
            unreadable = true
            note("dates: the notes of when songs were added couldn't be read from the phone; they are left as they are, and nothing is written to them until they can be")
            return
        }
        val text = bytes.decodeToString()
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()?.takeIf { it.lists.isNotEmpty() || it.seen.isNotEmpty() }
            // The file as written before notes were shared: just the times.
            ?: runCatching { json.decodeFromString(OLD, text) }.getOrNull()?.let { old ->
                Stored(old.mapValues { (_, songs) -> songs.mapValues { (_, times) -> AddedNote(times, 0) } }, old.keys)
            }
        if (stored == null) {
            // It reads, and is no notes this app wrote (cut short, damaged): the notes start
            // again, and the file as it was found is kept beside the new one, once, before
            // anything is written over it.
            damaged = text.isNotBlank() && runCatching { json.decodeFromString(Stored.serializer(), text) }.isFailure
            return
        }
        lists = HashMap(stored.lists)
        seen = HashSet(stored.seen)
        if (stored.mark < MARK) {
            // Written by a build that dated whatever it noticed in a playlist with the moment
            // it looked, and would now share that as the day the song was added. Which of
            // its notes were the person's own doing can't be told from the file, so none
            // keeps its time: all become what the phone merely saw, for its own "Date added",
            // and are never shared. (A note that only said "it went" says nothing then.)
            for (entry in lists.entries) entry.setValue(entry.value.filterValues { it.times.isNotEmpty() }.mapValues { it.value.copy(at = 0) })
            save()
        }
    }

    private fun one(playlist: String) = OnePlaylist(lists[playlist].orEmpty(), playlist in seen)

    /** One playlist's notes as the core left them. Whether anything is different. */
    private fun put(playlist: String, told: OneTold): Boolean {
        val before = one(playlist)
        if (told.notes.isEmpty()) lists.remove(playlist) else lists[playlist] = told.notes
        if (told.seen) seen.add(playlist) else seen.remove(playlist)
        if (before.notes != told.notes) changes.value++
        return before.notes != told.notes || before.seen != told.seen
    }

    /** What the core does with one playlist's notes. If it can't be asked, the notes stay
     *  as they are, and Diagnostics says so. */
    private inline fun asked(what: String, act: () -> Unit) {
        try {
            act()
        } catch (e: CoreException) {
            note("dates: $what couldn't be noted (${e.message})")
        }
    }

    /**
     * A copy of a playlist has been read, from the server or the phone: the phone's own notes
     * are brought in step with it for "Date added", and nothing is put on record for other
     * devices (the core's `Kept::notice`).
     */
    @Synchronized
    fun sawCopy(playlist: String, songs: List<Song>, now: Long) {
        load()
        asked("a playlist as read") { if (put(playlist, Core.notesNotice(one(playlist), songs.map { it.id }, now))) save() }
    }

    /**
     * The person changed a playlist on this phone, and the server has taken the change: it
     * held `before` (song ids, as the change was worked out from) and the change made it
     * `after` (**worked out, not read back**: a later read can show another device's change
     * too, which is not this phone's to date). For a change that took songs out, `removed`
     * says where in `before` they were, so that it is those copies' dates that go. The
     * core's `Kept::changed` and `Kept::edited`.
     */
    fun changed(playlist: String, before: List<String>, after: List<String>, now: Long, removed: List<Int>? = null) {
        var told = false
        synchronized(this) {
            load()
            asked("a change to a playlist") {
                val out = Core.notesChanged(one(playlist), before, after, removed, now)
                if (put(playlist, out)) save()
                told = out.told
            }
        }
        if (told) changedHere()
    }

    /** Notes songs the app has just added to a playlist (the core's `Kept::added`). */
    fun record(playlist: String, ids: List<String>, now: Long) {
        synchronized(this) {
            load()
            asked("songs added to a playlist") { if (put(playlist, Core.notesAdded(one(playlist), ids, fresh = false, now))) save() }
        }
        if (ids.isNotEmpty()) changedHere()
    }

    /** Notes a playlist the app has just created with these songs in it (`Kept::create`). */
    fun create(playlist: String, ids: List<String>, now: Long) {
        synchronized(this) {
            load()
            asked("a new playlist") { if (put(playlist, Core.notesAdded(OnePlaylist(), ids, fresh = true, now))) save() }
        }
        if (ids.isNotEmpty()) changedHere()
    }

    /** A playlist has gone: nothing is kept about it. */
    @Synchronized
    fun forget(playlist: String) {
        load()
        val known = seen.remove(playlist)
        val had = lists.remove(playlist) != null
        if (had) changes.value++
        if (had || known) save()
    }

    /** The time each of these songs was added to the playlist, in the same order; 0 where it
     *  isn't known (the core's `Kept::dates`). */
    @Synchronized
    fun dates(playlist: String, songs: List<Song>): LongArray {
        load()
        val notes = lists[playlist]?.takeIf { it.isNotEmpty() } ?: return LongArray(songs.size)
        return try {
            Core.notesDates(notes, songs.map { it.id }).toLongArray().takeIf { it.size == songs.size } ?: LongArray(songs.size)
        } catch (_: CoreException) {
            LongArray(songs.size)
        }
    }

    /** The notes worth sharing with other devices: the dated ones, for playlists that still
     *  exist (when `playlists` lists them) (the core's `Kept::shared`). */
    @Synchronized
    fun shared(playlists: Set<String>? = null): AddedNotes {
        load()
        return Core.notesShared(HashMap(lists), playlists)
    }

    /**
     * Takes in notes from other devices (the core's `notes::take`), **and has them on the
     * disk before it returns**: an exchange is done only when what it took in is kept.
     * Returns whether anything changed here. Throws `IOException` if the file couldn't be
     * written: then the notes are as they were. Off the main thread.
     */
    @Synchronized
    @Throws(java.io.IOException::class)
    fun take(theirs: AddedNotes): Boolean {
        load()
        if (theirs.isEmpty()) return false
        val out = Core.notesTake(HashMap(lists), theirs)
        if (!out.changed) return false
        if (unreadable) throw java.io.IOException("The notes of when songs were added can't be read on the phone, so nothing is written over them")
        keepDamaged()
        writeAtomically(file, json.encodeToString(Stored.serializer(), Stored(out.lists, HashSet(seen), MARK)).toByteArray())
        lists = HashMap(out.lists)
        changes.value++
        // (What is on the disk is newer than any write still waiting its turn.)
        dirty++
        return true
    }

    /** Written off the caller's thread (always off the main one), the newest state each
     *  time, one write after another: a write that was overtaken is skipped. With no scope
     *  (tests), at once. */
    private fun save() {
        // (Never over a file that couldn't be read.)
        if (unreadable) return
        val stored = Stored(HashMap(lists), HashSet(seen), MARK)
        val number = ++dirty
        val write = {
            try {
                keepDamaged()
                writeAtomically(file, json.encodeToString(Stored.serializer(), stored).toByteArray())
            } catch (_: java.io.IOException) {
                // Kept in memory; the next change writes it again.
            }
        }
        if (scope == null) write() else scope.launch(io) { writing.withLock { if (number == dirty) write() } }
    }

    /** A file that held something other than notes is kept as it was found, beside where
     *  the new notes go, before the first write over it (and only then is it written over). */
    @Throws(java.io.IOException::class)
    private fun keepDamaged() {
        if (!damaged) return
        java.nio.file.Files.copy(file.toPath(), File(file.path + ".damaged").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        damaged = false
    }

    companion object {
        /** The rule a file's notes were given their times under: 1, only the person's own
         *  changes on this phone are dated. */
        private const val MARK = 1

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val OLD = MapSerializer(String.serializer(), MapSerializer(String.serializer(), ListSerializer(Long.serializer())))
    }
}
