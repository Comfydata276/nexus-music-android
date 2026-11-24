package org.medianexus.music.lyrics

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Lyrics
import org.medianexus.music.store.writeAtomically

/** What the lyrics view shows for a song. */
sealed interface LyricsShown {
    /** Nothing kept, and the server is being asked. */
    data object Looking : LyricsShown

    /** The server has none for it (or none is kept, and the server can't be asked). */
    data class None(val offline: Boolean) : LyricsShown

    /** The lines, and when each starts if they are timed (milliseconds; empty otherwise). */
    class Lines(val lines: List<String>, val times: LongArray) : LyricsShown {
        val timed: Boolean get() = times.isNotEmpty()

        /** Whether line `i` is a place to seek to: timed, and inside the song's length
         *  (`songMs`; 0 where it isn't known, and then no line is). */
        fun seekable(i: Int, songMs: Long): Boolean = timed && songMs > 0 && times[i] < songMs

        override fun equals(other: Any?) = other is Lines && other.lines == lines && other.times.contentEquals(times)
        override fun hashCode() = lines.hashCode() * 31 + times.contentHashCode()
    }

    companion object {
        /** Windows' `show_lyrics`: timed only if the server says synced and every line has a
         *  time; and here, only if those times are in order (Windows does nothing about times
         *  that aren't: they are shown as plain lyrics). */
        fun of(found: Lyrics?, offline: Boolean = false): LyricsShown {
            if (found == null || found.line.isEmpty()) return None(offline)
            val timed = found.synced && found.line.all { it.start != null } &&
                found.line.zipWithNext().all { (a, b) -> a.start!! <= b.start!! }
            return Lines(found.line.map { it.value.trim() }, if (timed) LongArray(found.line.size) { found.line[it].start ?: 0 } else LongArray(0))
        }

        /** The line playing at `positionMs`: the last one whose start has passed, read a
         *  little ahead (150 ms, as Windows) so a line lights as it is sung; -1 before the
         *  first. A port of Windows' `follow_lyrics`. */
        fun lineAt(times: LongArray, positionMs: Long): Int {
            val at = positionMs + 150
            var low = 0
            var high = times.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (times[mid] <= at) low = mid + 1 else high = mid
            }
            return low - 1
        }
    }
}

/**
 * Lyrics kept on the phone so they show the instant they're wanted (a port of Windows'
 * src/lyrics.rs, and the lyrics parts of src/playback.rs). They come from the server only
 * (embedded in a song, or in a file beside it); whatever it says is kept, that it has none
 * included, and the server is asked again in the background when a song's lyrics are opened.
 * The songs coming up in the queue have theirs fetched ahead.
 *
 * Per account. Two folders: the account's cache (which Android may clear, like Windows'
 * cache) for everything, and its data folder for the songs that are downloaded, so that a
 * downloaded song has its lyrics with no signal (fetched with the download; for downloads
 * already on the phone, when the server can next be asked).
 */
class LyricsBook(
    private val cacheDir: File,
    private val dataDir: File,
    private val ask: suspend (String) -> Lyrics?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** The server can't be asked now (offline by hand, or out of reach). */
    private val offline: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val shown = LinkedHashMap<String, MutableStateFlow<LyricsShown>>(16, 0.75f, true)
    private val asked = HashSet<String>()
    private val lock = Mutex()
    private var aheadJob: Job? = null
    private var downloadsJob: Job? = null

    /**
     * What to show for a song: one result for it, which the view and fetching ahead both
     * update. Each opening of a song's lyrics (each call) shows what's kept at once and, with
     * the server there, asks it again, showing (and keeping) what it says if that differs.
     * What couldn't be asked is never kept as "none".
     */
    fun forSong(id: String): StateFlow<LyricsShown> {
        val flow = synchronized(shown) {
            shown[id] ?: MutableStateFlow<LyricsShown>(LyricsShown.Looking).also {
                shown[id] = it
                if (shown.size > 16) shown.remove(shown.keys.first())
            }
        }
        scope.launch { load(id, flow) }
        return flow.asStateFlow()
    }

    private suspend fun load(id: String, flow: MutableStateFlow<LyricsShown>) {
        val had = withContext(io) { kept(id) }
        if (had != null) flow.value = LyricsShown.of(had.lyrics)
        if (offline()) {
            if (had == null) flow.value = LyricsShown.None(offline = true)
            return
        }
        runCatching { ask(id) }
            .onSuccess { found(id, it) }
            // The server couldn't be asked: what's kept stays; with nothing kept, say so.
            .onFailure { if (had == null) flow.value = LyricsShown.None(offline = offline()) }
    }

    /** What the server said of a song: kept, and shown wherever it is being looked at (only
     *  if it differs, so the view isn't disturbed for nothing). */
    private suspend fun found(id: String, lyrics: Lyrics?) {
        withContext(io) { keep(id, lyrics) }
        val flow = synchronized(shown) { shown[id] } ?: return
        val now = LyricsShown.of(lyrics)
        if (flow.value != now) flow.value = now
    }

    /**
     * Fetches the lyrics of these songs (the one playing, then the next few) so they're on
     * hand before anyone opens them. Whether the network allows the one at each place is
     * asked immediately before its request (`mayFetch`), not once for the batch. Each song is asked
     * about once a run; one asked lately (a day) that had some isn't asked again, and one
     * that had none is, since lyrics get added to a library after the songs do (Windows'
     * `fetch_lyrics_ahead`).
     */
    fun ahead(ids: List<String>, mayFetch: (Int) -> Boolean) {
        if (ids.isEmpty()) return
        val before = aheadJob
        aheadJob = scope.launch {
            before?.join()
            for ((place, id) in ids.withIndex()) {
                if (id.isEmpty() || synchronized(asked) { id in asked }) continue
                val had = withContext(io) { kept(id) }
                if (had != null && had.lyrics != null && had.ageMs < FRESH_MS) {
                    synchronized(asked) { asked.add(id) }
                    continue
                }
                if (offline() || !mayFetch(place)) continue
                if (!synchronized(asked) { asked.add(id) }) continue
                runCatching { ask(id) }.onSuccess { found(id, it) }
            }
        }
    }

    /**
     * The songs downloaded now: each has its lyrics kept with the downloads' own (in the data
     * folder), fetched if they aren't there yet, one at a time, when the server can be asked
     * (`mayAsk`: and, with downloads on Wi-Fi only, on Wi-Fi). Lyrics of songs no longer
     * downloaded are let go.
     */
    fun downloaded(ids: Set<String>, mayAsk: () -> Boolean) {
        downloadsJob?.cancel()
        downloadsJob = scope.launch {
            lock.withLock {
                val have = withContext(io) { dataDir.list()?.toSet() ?: emptySet() }
                val names = ids.mapTo(HashSet(), ::fileName)
                withContext(io) {
                    for (name in have) if (name.endsWith(".json") && name !in names) File(dataDir, name).delete()
                }
                for (id in ids) {
                    if (fileName(id) in have) continue
                    // What's already kept in the cache will do, if it has an answer.
                    val cached = withContext(io) { read(File(cacheDir, fileName(id))) }
                    if (cached != null) {
                        withContext(io) { write(File(dataDir, fileName(id)), cached.lyrics) }
                        continue
                    }
                    if (offline() || !mayAsk()) continue
                    runCatching { ask(id) }.onSuccess { lyrics ->
                        // Kept with the download, then as anything the server says is: kept
                        // in the cache too, and shown wherever it is being looked at.
                        withContext(io) { write(File(dataDir, fileName(id)), lyrics) }
                        found(id, lyrics)
                    }
                }
            }
        }
    }

    // ---- On the disk (Windows' `kept` and `keep`) -----------------------------------------

    class Kept(val lyrics: Lyrics?, val ageMs: Long)

    /** What's kept for a song, and how long ago the server was asked: the downloads' copy
     *  first, then the cache's. Null if the server has never been asked. */
    fun kept(id: String): Kept? = read(File(dataDir, fileName(id))) ?: read(File(cacheDir, fileName(id)))

    /** Keeps what the server said of a song, that it has none included: in the cache, and
     *  with the downloads if the song is one. */
    fun keep(id: String, lyrics: Lyrics?) {
        write(File(cacheDir, fileName(id)), lyrics)
        val downloaded = File(dataDir, fileName(id))
        if (downloaded.exists()) write(downloaded, lyrics)
    }

    private fun read(file: File): Kept? = try {
        val text = file.readText()
        Kept(json.decodeFromString(Lyrics.serializer().nullable, text), (now() - file.lastModified()).coerceAtLeast(0))
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, lyrics: Lyrics?) {
        try {
            file.parentFile?.mkdirs()
            writeAtomically(file, json.encodeToString(Lyrics.serializer().nullable, lyrics).toByteArray())
        } catch (_: Exception) {
            // Only a copy: it will be asked for again.
        }
    }

    companion object {
        /** A day: lyrics fetched ahead within it aren't fetched again. */
        const val FRESH_MS = 24 * 3600 * 1000L

        private val json = Json { ignoreUnknownKeys = true }

        /** Windows' file name for a song: anything but letters, digits and '-' as '_'. */
        fun fileName(song: String): String =
            song.map { if ((it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-') it else '_' }.joinToString("") + ".json"
    }
}

