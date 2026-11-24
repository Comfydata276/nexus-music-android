package org.medianexus.music.downloads

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song
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
 * downloads folder, written atomically, and all of it held in memory: whether a song is
 * downloaded is asked of every row on every page, and of every song the car shows offline,
 * and is never a look at the disk.
 *
 * **An entry here is the mark that a download is whole.** It is written only after the file
 * has arrived in full, been checked, reached the disk and been moved to its own name (see
 * [SongFetch]). A file in the folder without an entry is not a download: it is cleared away.
 */
class Catalogue(val dir: File) {
    private val lock = Any()
    private val songs = LinkedHashMap<String, Downloaded>()
    private val lists = LinkedHashMap<String, DownloadedList>()
    private val failures = LinkedHashMap<String, Failed>()

    /** Changes in the log since the list was last written whole. */
    private var logged = 0

    private val now = MutableStateFlow(Snapshot())

    /** What is downloaded, as it stands, for whoever follows it. */
    val state: StateFlow<Snapshot> = now.asStateFlow()

    /** The songs (oldest first), the lists, and the failures, at one moment. */
    data class Snapshot(
        val songs: Map<String, Downloaded> = emptyMap(),
        val collections: List<DownloadedList> = emptyList(),
        val failed: Map<String, Failed> = emptyMap(),
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
        read(SONGS_FILE, ListSerializer(Downloaded.serializer())).forEach { songs[it.song.id] = it }
        // Then what has happened since that was written, in order. A line cut short (the app
        // stopped while writing it) is no entry: that download is not marked, and is fetched
        // again.
        val log = File(dir, LOG_FILE)
        if (log.isFile) {
            try {
                log.forEachLine { line ->
                    val change = runCatching { json.decodeFromString(Change.serializer(), line) }.getOrNull() ?: return@forEachLine
                    change.add?.let { songs.remove(it.song.id); songs[it.song.id] = it }
                    change.remove?.let { songs.remove(it) }
                    logged++
                }
            } catch (_: IOException) {
            }
        }
        // Trust the folder over the list: a file that has gone, or is not the length that was
        // recorded when it was verified, is not a download. It is fetched again (see
        // Downloads), and what is left of it is cleared away by the sweep.
        val found = songs.values.filter { File(dir, it.file).length() != it.bytes || it.bytes <= 0 }
        found.forEach { songs.remove(it.song.id) }
        wanting = found
        read(LISTS_FILE, ListSerializer(DownloadedList.serializer())).forEach { lists[it.key] = it }
        read(FAILED_FILE, ListSerializer(Failed.serializer())).forEach { failures[it.song.id] = it }
        publish()
    }

    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> = try {
        val file = File(dir, name)
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    fun has(id: String): Boolean = synchronized(lock) { id in songs }

    fun entry(id: String): Downloaded? = synchronized(lock) { songs[id] }

    /** The downloaded copy of a song, if there is one. */
    fun fileOf(id: String): File? = entry(id)?.let { File(dir, it.file) }

    /** The downloaded copy of a song, if it is there and as long as it was when it was
     *  marked whole: anything else is not the download that was verified. */
    fun wholeFileOf(id: String): File? = entry(id)?.let { entry -> File(dir, entry.file).takeIf { entry.bytes > 0 && it.length() == entry.bytes } }

    fun collection(key: String): DownloadedList? = synchronized(lock) { lists[key] }

    /** Adds a whole download. Only to be called once the file is in place (see the class). */
    @Throws(IOException::class)
    fun add(entry: Downloaded) {
        synchronized(lock) {
            // The mark, on the disk before it is in memory: one line, synced.
            append(Change(add = entry))
            songs.remove(entry.song.id)
            songs[entry.song.id] = entry
            compactIfDue()
            if (failures.remove(entry.song.id) != null) writeFailures()
            publish()
        }
    }

    /** Forgets downloads, and deletes their files. */
    fun remove(ids: Collection<String>) {
        synchronized(lock) {
            var changed = false
            for (id in ids) {
                val gone = songs[id] ?: continue
                // Unmarked first, then the file goes: never a mark without its file.
                runCatching { append(Change(remove = id)) }
                songs.remove(id)
                File(dir, gone.file).delete()
                changed = true
            }
            compactIfDue()
            if (failures.keys.removeAll(ids.toSet())) writeFailures()
            if (changed) publish()
        }
    }

    /** Remembers a list as downloaded (or with its songs as they are now), or forgets it. */
    fun keep(collection: DownloadedList) = synchronized(lock) {
        lists[collection.key] = collection
        writeLists()
        publish()
    }

    fun forget(key: String) = synchronized(lock) {
        if (lists.remove(key) != null) {
            writeLists()
            publish()
        }
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
     * Writes one change to the end of the log and syncs it: a whole download is marked with a
     * line of its own, not the whole list written again (5,000 downloads are a few megabytes
     * of list, and a batch would have written it once a song). Now and then, the list is
     * written whole and the log begun afresh.
     */
    private fun append(change: Change) {
        java.io.FileOutputStream(File(dir, LOG_FILE), true).use { out ->
            out.write((json.encodeToString(Change.serializer(), change) + "\n").toByteArray())
            out.fd.sync()
        }
        logged++
    }

    /** Once what is held in memory includes every change logged (never between the two: the
     *  list written then would lack the change just logged, and the log goes with it). */
    private fun compactIfDue() {
        if (logged >= maxOf(COMPACT_AFTER, songs.size / 2)) runCatching { compact() }
    }

    /** The list written whole, and then the log let go (a crash between the two replays the
     *  log over the list, which comes to the same). */
    private fun compact() {
        write(SONGS_FILE, ListSerializer(Downloaded.serializer()), songs.values.toList())
        File(dir, LOG_FILE).delete()
        logged = 0
    }

    private fun writeLists() = write(LISTS_FILE, ListSerializer(DownloadedList.serializer()), lists.values.toList())

    private fun writeFailures() = write(FAILED_FILE, ListSerializer(Failed.serializer()), failures.values.toList())

    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>) {
        writeAtomically(File(dir, name), json.encodeToString(serializer, value).toByteArray())
    }

    private fun publish() {
        now.value = Snapshot(LinkedHashMap(songs), lists.values.toList(), LinkedHashMap(failures))
    }

    /** Files in the folder that no entry names, and no download under way (`busy`, their
     *  song ids) is writing: what a download cut short left, or a file whose entry was lost.
     *  Deleted. */
    fun sweep(busy: Set<String>) {
        val named = synchronized(lock) { songs.values.mapTo(HashSet()) { it.file } }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in OWN_FILES || file.name in named) return@forEach
            val stem = SongFetch.stemOfPart(file.name) ?: file.name.substringBeforeLast('.')
            // (An atomic write's own temporary file: swept by sweepTemporaries, never here.)
            if (file.name.startsWith(".") && file.name.endsWith(".tmp")) return@forEach
            if (busy.none { SongFetch.stem(it) == stem }) file.delete()
        }
    }

    companion object {
        const val SONGS_FILE = "downloads.json"
        const val LOG_FILE = "downloads.log"
        private const val COMPACT_AFTER = 200
        const val LISTS_FILE = "collections.json"
        const val FAILED_FILE = "failed.json"
        private val OWN_FILES = setOf(SONGS_FILE, LOG_FILE, LISTS_FILE, FAILED_FILE)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
