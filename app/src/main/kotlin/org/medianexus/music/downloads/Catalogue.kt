package org.medianexus.music.downloads

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song
import org.medianexus.music.store.syncDirOrFail
import org.medianexus.music.store.temporaryFor
import org.medianexus.music.store.writeAtomically

/**
 * One downloaded song, as Windows lists it (`Entry` in src/downloads.rs): the song with
 * everything needed to show and play it with the server gone, its file, and what it was
 * saved as.
 */
@Serializable
data class Downloaded(
    val song: Song,
    /** The file's name in the downloads folder. */
    val file: String,
    val bytes: Long,
    /** When it arrived, in seconds since 1970. */
    val at: Long,
    /** What it was saved as: the file's kind ("flac", "mp3"), and the bit rate the server was
     *  asked to convert it to (0 for the file as it is on the server). */
    val suffix: String,
    val bitrate: Int = 0,
    /** The file's modification time when it was marked whole (milliseconds since 1970; 0 for
     *  one marked before this was kept): with its name, which file this entry is of. */
    val modified: Long = 0,
    /** **It needs a fresh copy**: it failed when it was played, for a reason that is the
     *  file's own. It is still a download, still on the phone and still played with no
     *  server (as far as it plays); a new copy is fetched, and takes its place only once it
     *  has been verified. Until then the server's copy is preferred when it can be had. */
    val stale: Boolean = false,
    /** What is wrong with it, in plain words, if it was kept knowing something is: the
     *  server's own copy has the flaw (see `SongFetch`). Empty otherwise. */
    val flaw: String = "",
) {
    /** Its bit rate in kbps, as near as its size and the song's length say (Windows'
     *  `saved_as`). */
    val kbps: Int get() = if (song.duration > 0) (bytes * 8 / song.duration / 1000).toInt() else 0
}

/**
 * An album, a playlist or Liked songs downloaded as a whole: it stays downloaded, so songs it
 * gains are fetched too (Windows' `collections`), and with it what is needed to show it with
 * no signal: its name, who it is by, its cover and its songs in order.
 */
@Serializable
data class DownloadedList(
    /** The list's route key: "album:<id>", "playlist:<id>" or "liked". */
    val key: String,
    val name: String = "",
    /** The artist, for an album; the owner, for a playlist. */
    val by: String = "",
    val coverArt: String = "",
    val year: Int = 0,
    /** Its songs' ids, in the list's own order. */
    val songs: List<String> = emptyList(),
    /** When it was asked for, in seconds since 1970. */
    val at: Long = 0,
) {
    val isAlbum: Boolean get() = key.startsWith("album:")
    val isPlaylist: Boolean get() = key.startsWith("playlist:") || key == "liked"
}

/** A song that couldn't be downloaded, and why, in plain words. */
@Serializable
data class Failed(val song: Song, val reason: String, val network: Boolean = false)

/**
 * What one account has downloaded: the songs (each with its file), the lists downloaded as
 * a whole, and the songs that failed and why. Each in its own small file in the account's
 * downloads folder, and all of it held in memory: whether a song is downloaded is asked of
 * every row on every page, and of every song the car shows offline, and is never a look at
 * the disk (nor a wait for whoever is writing: [has] and [entry] read the last state
 * published, and take no lock).
 *
 * **An entry here is the mark that a download is whole.** It is written only after the file
 * has arrived in full, been verified, reached the disk and been moved to its own name (see
 * [SongFetch]). A file in the folder without an entry is not a download: it is cleared away.
 *
 * **The mark must survive being interrupted**, so:
 *
 * - A mark is one line added to a log (`downloads.log`) and synced, not the whole list
 *   written again. The log is only ever added to at the end of its last *whole* line: a line
 *   cut short (the app killed, or a write that failed, part-way through it) is cut off, when
 *   the log is read and again before every line added, so no mark is ever joined to a
 *   fragment and lost with it.
 * - Now and then the list is written whole (`downloads.json`) and the log emptied. The list
 *   before it is kept (`downloads.backup.json`), and stands in if the list can't be read.
 *   The order is such that whichever step the power goes at, the list on the disk and the log
 *   beside it together still say everything: the new list to a file of its own and synced,
 *   the old list renamed to the backup, the new one renamed into place, the folder synced,
 *   and only then the log emptied (in place, and synced: it is never deleted and made again,
 *   which would put its name at the mercy of the folder).
 * - If the list can't be written whole, that is said (`note`, to Diagnostics) and tried again
 *   later; the log goes on being added to meanwhile, and nothing is lost.
 * - Unmarking comes before deleting: a download is removed only once the line that says so
 *   is on the disk. If that can't be written, it stays a download.
 */
class Catalogue(
    val dir: File,
    /** Something went wrong that a person looking at Diagnostics should be able to see. */
    private val note: (String) -> Unit = {},
    /** How many changes the log holds before the list is written whole (fewer in a test). */
    private val compactAfter: Int = COMPACT_AFTER,
    /** The version of Android the phone runs: what it says it can't decode holds for that. */
    private val androidVersion: Int = android.os.Build.VERSION.SDK_INT,
) {
    private val lock = Any()
    private val songs = LinkedHashMap<String, Downloaded>()
    private val lists = LinkedHashMap<String, DownloadedList>()
    private val failures = LinkedHashMap<String, Failed>()
    private val converts = java.util.concurrent.ConcurrentHashMap<String, Convert>()

    /** Changes in the log since the list was last written whole. */
    private var logged = 0

    /** Writing the list whole last failed, and the number of logged changes at which it is
     *  tried again. */
    private var compactionFailing = false
    private var retryAt = 0

    private val now = MutableStateFlow(Snapshot())

    /** What is downloaded, as it stands, for whoever follows it. */
    val state: StateFlow<Snapshot> = now.asStateFlow()

    /** When this catalogue was read: the moment the start-up sweep counts from (see [sweep]). */
    val opened: Long = System.currentTimeMillis()

    /** The songs (oldest first), the lists, and the failures, at one moment. */
    data class Snapshot(
        val songs: Map<String, Downloaded> = emptyMap(),
        val collections: List<DownloadedList> = emptyList(),
        val failed: Map<String, Failed> = emptyMap(),
        /** Songs asked for by themselves (a song's own Download), which keep their files
         *  whatever becomes of any list they are also in. */
        val singles: Set<String> = emptySet(),
    ) {
        val ids: Set<String> get() = songs.keys
    }

    /** Downloads that were marked whole and whose files were found, when the catalogue was
     *  read, to be missing or not the length recorded: no longer downloads, and to be fetched
     *  again. */
    val wanting: List<Downloaded>

    init {
        dir.mkdirs()
        org.medianexus.music.store.sweepTemporaries(dir)
        readList().forEach { songs[it.song.id] = it }
        // Then what has happened since that was written, in order.
        replayLog()
        // Trust the folder over the list: a file that has gone, or is not the length that was
        // recorded when it was verified, is not a download. It is fetched again (see
        // Downloads), and what is left of it is cleared away by the sweep.
        val found = songs.values.filter { File(dir, it.file).length() != it.bytes || it.bytes <= 0 }
        found.forEach { songs.remove(it.song.id) }
        wanting = found
        read(LISTS_FILE, ListSerializer(DownloadedList.serializer()))?.forEach { lists[it.key] = it }
        read(FAILED_FILE, ListSerializer(Failed.serializer()))?.forEach { failures[it.song.id] = it }
        // (Only what was said on this version of Android: after a system update, asked again.)
        read(CONVERT_FILE, ListSerializer(Convert.serializer()))?.forEach { if (it.android == androidVersion) converts[it.id] = it }
        publish()
    }

    // ---- Songs whose own file this phone can't play ------------------------------------------

    /** A song whose file, as it is on the server, this phone was found not to decode: the
     *  song's id and that file's size (so that another file under the same id has a fresh
     *  chance), and the version of Android that said so (0: one from before that was kept,
     *  which is asked again). */
    @Serializable
    private data class Convert(val id: String, val size: Long = 0, val android: Int = 0)

    /**
     * This phone can't decode the song's file as it is (the phone's definite answer, asked
     * before it was marked, or found by the player when it was played): **remembered, on the
     * disk**, so that it is downloaded, and streamed, as the server converts it, and the same
     * file is never fetched again to fail again, **on this version of Android**: after a
     * system update the question is asked again. It outlives "Remove all": it is a fact
     * about the phone and the file, not a download. (A question that could not be put is
     * not an answer, and is never written here.)
     */
    fun mustConvert(song: Song) {
        synchronized(lock) {
            val now = Convert(song.id, song.size, androidVersion)
            if (converts.put(song.id, now) == now) return
            write(CONVERT_FILE, ListSerializer(Convert.serializer()), converts.values.toList(), durable = true)
        }
    }

    /** Whether the song's file as it is has been found not to play on this phone (in memory:
     *  asked as songs are opened). */
    fun convertsFor(song: Song): Boolean = convertsFor(song.id, song.size)

    fun convertsFor(id: String, size: Long): Boolean {
        val of = converts[id]?.size ?: return false
        return of <= 0 || size <= 0 || of == size
    }

    /** The list as last written whole; the one before it if that can't be read, or isn't
     *  there (the app stopped between the old list being moved aside and the new one put in
     *  its place: the log still holds everything since the old one). */
    private fun readList(): List<Downloaded> {
        val serializer = ListSerializer(Downloaded.serializer())
        val main = File(dir, SONGS_FILE)
        read(SONGS_FILE, serializer)?.let { return it }
        if (main.exists()) note("downloads: the list of downloads couldn't be read; the copy before it is used")
        return read(BACKUP_FILE, serializer) ?: emptyList()
    }

    /** What a file holds; null if it isn't there or can't be read. */
    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T>? = try {
        val file = File(dir, name)
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Applies the log's whole lines, in order, and cuts off anything after the last of them:
     * a line cut short is no mark (that download is fetched again), and must not be there
     * for the next mark to be written onto the end of.
     */
    private fun replayLog() {
        val log = File(dir, LOG_FILE)
        if (!log.isFile) return
        try {
            RandomAccessFile(log, "rw").use { file ->
                val whole = wholeLength(file)
                val bytes = ByteArray(whole.toInt())
                file.seek(0)
                file.readFully(bytes)
                var skipped = 0
                for (line in String(bytes).split('\n')) {
                    if (line.isEmpty()) continue
                    val change = runCatching { json.decodeFromString(Change.serializer(), line) }.getOrNull()
                    if (change == null) {
                        skipped++
                        continue
                    }
                    change.add?.let { songs.remove(it.song.id); songs[it.song.id] = it }
                    change.remove?.let { songs.remove(it) }
                    logged++
                }
                if (skipped > 0) note("downloads: $skipped records in the list's log couldn't be read")
            }
        } catch (_: IOException) {
            note("downloads: the list's log couldn't be read")
        }
    }

    /**
     * How much of the log is whole lines, having cut off (and synced) anything after the
     * last of them.
     */
    private fun wholeLength(log: RandomAccessFile): Long {
        val length = log.length()
        if (length == 0L) return 0
        var end = length
        // Back from the end to the last line break.
        val chunk = ByteArray(4_096)
        var whole = 0L
        search@ while (end > 0) {
            val start = maxOf(0, end - chunk.size)
            log.seek(start)
            log.readFully(chunk, 0, (end - start).toInt())
            for (i in (end - start).toInt() - 1 downTo 0) {
                if (chunk[i] == '\n'.code.toByte()) {
                    whole = start + i + 1
                    break@search
                }
            }
            end = start
        }
        if (whole != length) {
            log.setLength(whole)
            log.fd.sync()
            note("downloads: a record cut short was taken out of the list's log")
        }
        return whole
    }

    fun has(id: String): Boolean = now.value.songs.containsKey(id)

    fun entry(id: String): Downloaded? = now.value.songs[id]

    /** The downloaded copy of a song, if there is one. */
    fun fileOf(id: String): File? = entry(id)?.let { File(dir, it.file) }

    /** The downloaded copy of a song, if it is there and as long as it was when it was
     *  marked whole: anything else is not the download that was verified. */
    fun wholeFileOf(id: String): File? = entry(id)?.let { entry -> File(dir, entry.file).takeIf { entry.bytes > 0 && it.length() == entry.bytes } }

    fun collection(key: String): DownloadedList? = synchronized(lock) { lists[key]?.takeIf { key != SINGLES } }

    /** These songs were asked for by themselves: each keeps its file while that stands,
     *  whatever list it is also in. */
    fun single(ids: Collection<String>) = synchronized(lock) {
        val was = lists[SINGLES]?.songs.orEmpty()
        val now = (was + ids).distinct()
        if (now.size != was.size) {
            lists[SINGLES] = DownloadedList(SINGLES, songs = now)
            writeLists()
            publish()
        }
    }

    /**
     * These songs are no longer wanted for themselves. **On the disk first** (see [forget]):
     * false, and nothing changed, if that could not be written.
     */
    fun unsingle(ids: Collection<String>): Boolean = synchronized(lock) {
        val was = lists[SINGLES]?.songs ?: return@synchronized true
        val gone = ids.toSet()
        val now = was.filter { it !in gone }
        if (now.size == was.size) return@synchronized true
        val next = LinkedHashMap(lists)
        if (now.isEmpty()) next.remove(SINGLES) else next[SINGLES] = DownloadedList(SINGLES, songs = now)
        if (!withdrawn(next)) return@synchronized false
        publish()
        true
    }

    /** Writes what is wanted, as it is to be from now on, durably; only then is it so in
     *  memory. False if it could not be written: then nothing has changed, here or on the
     *  disk. */
    private fun withdrawn(next: LinkedHashMap<String, DownloadedList>): Boolean {
        try {
            writeAtomically(File(dir, LISTS_FILE), json.encodeToString(ListSerializer(DownloadedList.serializer()), next.values.toList()).toByteArray(), durable = true)
        } catch (e: IOException) {
            note("downloads: what is wanted downloaded couldn't be changed on the phone (${e.javaClass.simpleName}): nothing was removed")
            return false
        }
        lists.clear()
        lists.putAll(next)
        return true
    }

    /**
     * The songs something wants kept, other than the list `except`: those asked for by
     * themselves, and those of every other list downloaded whole. A song's file stays while
     * it is one of these.
     */
    fun wantedBesides(except: String?): Set<String> = synchronized(lock) {
        val out = HashSet<String>()
        for ((key, list) in lists) if (key != except) out += list.songs
        out
    }

    /** Adds a whole download. Only to be called once the file is in place (see the class). */
    @Throws(IOException::class)
    fun add(entry: Downloaded) {
        synchronized(lock) {
            // The mark, on the disk before it is in memory: one line, synced.
            append(Change(add = entry))
            val before = songs.remove(entry.song.id)
            songs[entry.song.id] = entry
            compactIfDue()
            if (failures.remove(entry.song.id) != null) writeFailures()
            publish()
            // A copy this one replaces (always under another name: see `SongFetch`): gone
            // only now, the new one marked, and shown, in its place.
            if (before != null && before.file != entry.file) File(dir, before.file).delete()
        }
    }

    /** Forgets downloads, and deletes their files. Returns the ones that went: one whose
     *  unmarking couldn't be written stays a download, file and all. */
    fun remove(ids: Collection<String>): Set<String> {
        synchronized(lock) {
            val went = LinkedHashSet<String>()
            for (id in ids) {
                val gone = songs[id] ?: continue
                // Unmarked first, on the disk, then the file goes: never a mark without its
                // file, and never a file deleted that the list still calls a download.
                try {
                    append(Change(remove = id))
                } catch (_: IOException) {
                    note("downloads: a download couldn't be removed (the list couldn't be written)")
                    continue
                }
                songs.remove(id)
                File(dir, gone.file).delete()
                went += id
            }
            compactIfDue()
            if (failures.keys.removeAll(ids.toSet())) writeFailures()
            if (went.isNotEmpty()) publish()
            return went
        }
    }

    /**
     * This download needs a fresh copy (see [Downloaded.stale]): said on the disk, so that it
     * is still so after the app is next started. Only if it is still the download that failed
     * (`was`). Returns the entry as it now stands; null if it is no longer that download, or
     * the phone would not take the write (then it stays as it was).
     */
    fun stale(was: Downloaded): Downloaded? {
        synchronized(lock) {
            val entry = songs[was.song.id] ?: return null
            if (entry.file != was.file || entry.modified != was.modified) return null
            if (entry.stale) return entry
            val now = entry.copy(stale = true)
            try {
                append(Change(add = now))
            } catch (_: IOException) {
                note("downloads: a download that needs a fresh copy couldn't be marked so (the list couldn't be written)")
                return null
            }
            songs[was.song.id] = now
            compactIfDue()
            publish()
            return now
        }
    }

    /**
     * A download's file is no longer the length that was recorded when it was verified: it
     * is not that download, in the run as at the start of one (see the class's `init`). It is
     * unmarked, what is left of it goes, and the entry that was is returned for it to be
     * fetched again; null if the file is the right length after all (it has been replaced
     * since by a fresh copy), or its unmarking couldn't be written.
     */
    fun dropIfNotWhole(id: String): Downloaded? {
        synchronized(lock) {
            val entry = songs[id] ?: return null
            if (entry.bytes > 0 && File(dir, entry.file).length() == entry.bytes) return null
            return entry.takeIf { remove(listOf(id)).isNotEmpty() }
        }
    }

    /**
     * **Everything goes** (Settings, "Remove all"): every download, every list downloaded
     * whole, every failure, and every file in the folder that is not this record itself
     * (parts of downloads cut short, their records, the covers kept with the downloads,
     * anything left lying), so that the room given back is all the room that was taken.
     *
     * Unmarked first, as ever: the empty list is written whole and the log emptied before a
     * file is deleted. If that can't be written, nothing is deleted, and false is returned.
     */
    fun clear(): Boolean = synchronized(lock) {
        val was = songs.values.toList()
        songs.clear()
        try {
            compact()
        } catch (_: IOException) {
            was.forEach { songs[it.song.id] = it }
            note("downloads: they couldn't all be removed (the list couldn't be written)")
            return@synchronized false
        }
        // The list before this one names every download just removed: it must not be there
        // to be read back if this one ever can't be.
        File(dir, BACKUP_FILE).delete()
        lists.clear()
        failures.clear()
        File(dir, LISTS_FILE).delete()
        File(dir, FAILED_FILE).delete()
        runCatching { syncDirOrFail(dir) }
        fun sweep() = dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                file.deleteRecursively()
            } else if (file.name !in KEPT_THROUGH_A_CLEAR && !(file.name.startsWith(".") && file.name.endsWith(".tmp"))) {
                file.delete()
            }
        }
        sweep()
        // **Looked at afterwards, not taken on trust**: what is still in the folder that
        // should have gone (a file that would not delete; one that landed as the others
        // went) is tried once more, and if any of it is still there, that is said, and the
        // answer is no. What the person was told the downloads take must have been given back.
        var left = leftAfterClear()
        if (left.isNotEmpty()) {
            sweep()
            left = leftAfterClear()
        }
        publish()
        if (left.isNotEmpty()) note("downloads: ${left.size} of the files in the downloads' folder could not be removed")
        left.isEmpty()
    }

    /** What is in the folder that a clear should have taken. */
    private fun leftAfterClear(): List<File> = dir.listFiles().orEmpty().filter { file ->
        file.isDirectory || (file.name !in KEPT_THROUGH_A_CLEAR && !(file.name.startsWith(".") && file.name.endsWith(".tmp")))
    }

    /** Remembers a list as downloaded (or with its songs as they are now), or forgets it. */
    fun keep(collection: DownloadedList) = synchronized(lock) {
        lists[collection.key] = collection
        writeLists()
        publish()
    }

    /**
     * A list is no longer wanted downloaded whole. **On the disk first, and only then so**:
     * false, and it is still wanted, if that could not be written. (It used to be forgotten
     * in memory whatever became of the write: the removal was reported done, and the list
     * came back, and fetched itself again, the next time the app was started.)
     */
    fun forget(key: String): Boolean = synchronized(lock) {
        if (key !in lists) return@synchronized true
        if (!withdrawn(LinkedHashMap(lists).apply { remove(key) })) return@synchronized false
        publish()
        true
    }

    fun failed(failure: Failed) = synchronized(lock) {
        failures.remove(failure.song.id)
        failures[failure.song.id] = failure
        writeFailures()
        publish()
    }

    fun clearFailures(ids: Collection<String>) = synchronized(lock) {
        if (failures.keys.removeAll(ids.toSet())) {
            writeFailures()
            publish()
        }
    }

    /** One change to the list of downloads. */
    @Serializable
    private class Change(val add: Downloaded? = null, val remove: String? = null)

    /**
     * Writes one change after the log's last whole line and syncs it: a whole download is
     * marked with a line of its own, not the whole list written again (5,000 downloads are a
     * few megabytes of list, and a batch would have written it once a song). Now and then,
     * the list is written whole and the log emptied.
     */
    @Throws(IOException::class)
    private fun append(change: Change) {
        val file = File(dir, LOG_FILE)
        if (!file.exists()) {
            // A log that has only just come to be: its name must reach the disk before
            // anything is written in it. A line in a log a power cut could take away whole
            // is no mark, and no unmarking. If the folder can't be synced, nothing is written.
            RandomAccessFile(file, "rw").close()
            try {
                syncDirOrFail(dir)
            } catch (e: IOException) {
                file.delete()
                throw e
            }
        }
        RandomAccessFile(file, "rw").use { log ->
            // Never onto the end of a fragment (one left by an earlier run is cut off when
            // the log is read; one left by a write that failed in this run, here).
            val whole = wholeLength(log)
            log.seek(whole)
            try {
                val line = (json.encodeToString(Change.serializer(), change) + "\n").toByteArray()
                if (CrashPoints.at != null) {
                    // (The rig's kill tests: the line in two halves, to be stopped between.)
                    log.write(line, 0, line.size / 2)
                    CrashPoints.reached("append-half")
                    log.write(line, line.size / 2, line.size - line.size / 2)
                } else {
                    log.write(line)
                }
                log.fd.sync()
                CrashPoints.reached("appended")
            } catch (e: IOException) {
                // Part of the line may have gone in: taken out again.
                runCatching {
                    log.setLength(whole)
                    log.fd.sync()
                }
                throw e
            }
        }
        logged++
    }

    /** Once what is held in memory includes every change logged (never between the two: the
     *  list written then would lack the change just logged, and the log goes with it). */
    private fun compactIfDue() {
        if (logged < maxOf(compactAfter, songs.size / 2) || logged < retryAt) return
        try {
            compact()
            if (compactionFailing) note("downloads: the list of downloads is being written whole again")
            compactionFailing = false
            retryAt = 0
        } catch (e: IOException) {
            // Said once, and tried again after some more changes: the log goes on being added
            // to, so nothing is lost, but it must not grow for ever with nobody knowing.
            if (!compactionFailing) note("downloads: the list of downloads couldn't be written whole (${e.javaClass.simpleName}); its log goes on growing until it can")
            compactionFailing = true
            retryAt = logged + maxOf(compactAfter / 4, 1)
        }
    }

    /** The list written whole, and then the log emptied: in the order the class describes,
     *  so that a stop at any step leaves a list and a log that together say everything. */
    @Throws(IOException::class)
    private fun compact() {
        val main = File(dir, SONGS_FILE)
        val temp = temporaryFor(main)
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(ListSerializer(Downloaded.serializer()), songs.values.toList()).toByteArray())
                out.fd.sync()
            }
            CrashPoints.reached("compact-written")
            if (main.isFile) Files.move(main.toPath(), File(dir, BACKUP_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            CrashPoints.reached("compact-aside")
            Files.move(temp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            CrashPoints.reached("compact-placed")
        } catch (e: IOException) {
            temp.delete()
            throw e
        } catch (e: RuntimeException) {
            temp.delete()
            throw IOException(e)
        }
        // The new list's name on the disk before the log that it replaces is emptied: **if
        // the folder can't be synced, the log is not emptied** (a power cut could then give
        // back the list before, with nothing left to say what had happened since). The new
        // list and the whole log together still say everything; it is tried again later.
        syncDirOrFail(dir)
        RandomAccessFile(File(dir, LOG_FILE), "rw").use { log ->
            log.setLength(0)
            CrashPoints.reached("compact-emptied")
            log.fd.sync()
        }
        logged = 0
    }

    private fun writeLists() = write(LISTS_FILE, ListSerializer(DownloadedList.serializer()), lists.values.toList(), durable = true)

    private fun writeFailures() = write(FAILED_FILE, ListSerializer(Failed.serializer()), failures.values.toList(), durable = false)

    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>, durable: Boolean) {
        try {
            writeAtomically(File(dir, name), json.encodeToString(serializer, value).toByteArray(), durable)
        } catch (e: IOException) {
            // Held in memory meanwhile, and written at the next change.
            note("downloads: $name couldn't be written (${e.javaClass.simpleName})")
        }
    }

    private fun publish() {
        now.value = Snapshot(LinkedHashMap(songs), lists.values.filter { it.key != SINGLES }, LinkedHashMap(failures), lists[SINGLES]?.songs?.toSet().orEmpty())
    }

    /**
     * Clears away the files in the folder that are nobody's: what a download cut short left,
     * or a file whose mark was never written. **Only at start-up, before the downloads accept
     * any work** (see `Downloads`), and under the catalogue's lock, so no mark can be written
     * while it looks. Never deleted:
     *
     * - a file an entry names;
     * - a file, or part, of a song the queue still wants (`busy`, their song ids);
     * - a file as new as this run of the app (`began`: when the catalogue was read), which
     *   can only be this run's own work.
     */
    fun sweep(busy: Set<String>, began: Long = opened) = synchronized(lock) {
        val named = songs.values.mapTo(HashSet()) { it.file }
        val wanted = busy.mapTo(HashSet()) { SongFetch.stem(it) }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in OWN_FILES || file.name in named) return@forEach
            // (An atomic write's own temporary file: swept by sweepTemporaries, never here.)
            if (file.name.startsWith(".") && file.name.endsWith(".tmp")) return@forEach
            val stem = SongFetch.stemOfPart(file.name) ?: file.name.substringBeforeLast('.')
            if (stem in wanted || file.lastModified() >= began) return@forEach
            file.delete()
        }
    }

    companion object {
        const val SONGS_FILE = "downloads.json"
        const val BACKUP_FILE = "downloads.backup.json"
        const val LOG_FILE = "downloads.log"
        private const val COMPACT_AFTER = 200
        const val LISTS_FILE = "collections.json"
        const val FAILED_FILE = "failed.json"
        const val CONVERT_FILE = "convert.json"

        /** The key the songs asked for by themselves are kept under, among the lists (it is
         *  not one, and is never shown as one). */
        const val SINGLES = "songs"
        private val OWN_FILES = setOf(SONGS_FILE, BACKUP_FILE, LOG_FILE, LISTS_FILE, FAILED_FILE, CONVERT_FILE)

        /** What "Remove all" leaves in the folder: the (now empty) list and its log, and
         *  what is known of the phone's decoders. */
        private val KEPT_THROUGH_A_CLEAR = setOf(SONGS_FILE, LOG_FILE, CONVERT_FILE)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
