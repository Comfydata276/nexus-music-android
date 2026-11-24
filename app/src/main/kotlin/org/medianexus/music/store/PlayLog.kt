package org.medianexus.music.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The plays as they stand, oldest first: what "Your stats" and the mixes read. Each play is a
 * song's id, when it was played (seconds since 1970), and, for a play made from a playlist,
 * that page's route key ("playlist:<id>"; empty otherwise).
 *
 * Never changed once handed out: it shares the log's arrays, which are only ever written past
 * the places it reads (as `SongTable.Builder.snapshot`), so 200,000 plays are not copied at
 * every play. A song's id is the same `String` every time it comes up, so a lookup by it
 * hashes it once.
 */
class Plays internal constructor(
    val size: Int,
    private val songs: IntArray,
    private val ats: LongArray,
    private val froms: IntArray,
    private val nameTable: Array<String?>,
) {
    /** (Read once: the log's array of names may grow past it, but never changes below it.) */
    private val nameCount = nameTable.size

    fun song(i: Int): String = nameTable[songs[i]]!!
    fun at(i: Int): Long = ats[i]
    fun from(i: Int): String = froms[i].let { if (it < 0) "" else nameTable[it]!! }

    // The same by number, for counting without a map of text: every song id and route key
    // the log names has a number of its own ([name]), below [names].
    fun songRef(i: Int): Int = songs[i]
    /** -1 for a play made from no playlist. */
    fun fromRef(i: Int): Int = froms[i]
    /** How many numbers there are (it may count names past the last play handed out). */
    val names: Int get() = nameCount
    fun name(ref: Int): String = nameTable[ref] ?: ""

    /** When the first play was, or null for none. The log is oldest first, but plays taken in
     *  from another device can be older than the newest here, so it is looked for. */
    fun first(): Long? {
        if (size == 0) return null
        var least = ats[0]
        for (i in 1 until size) if (ats[i] < least) least = ats[i]
        return least
    }

    companion object {
        val EMPTY = Plays(0, IntArray(0), LongArray(0), IntArray(0), emptyArray())

        /** These plays (song, seconds, where from), in this order. */
        fun of(plays: List<Triple<String, Long, String>>): Plays {
            val refs = HashMap<String, Int>()
            val names = ArrayList<String>()
            fun ref(name: String) = refs.getOrPut(name) { names += name; names.size - 1 }
            val songs = IntArray(plays.size) { ref(plays[it].first) }
            val froms = IntArray(plays.size) { plays[it].third.let { from -> if (from.isEmpty()) -1 else ref(from) } }
            return Plays(plays.size, songs, LongArray(plays.size) { plays[it].second }, froms, names.toTypedArray())
        }
    }
}

/**
 * Every play that counted, with when it happened, kept on the phone for the account: a port
 * of the Windows app's `src/plays.rs`, in its file form (`plays.log`, one line a play:
 * `["<song id>",<seconds>]`, or `["<song id>",<seconds>,"playlist:<id>"]` for one made from a
 * playlist), so the sync milestone can share it as Windows does.
 *
 * The server counts how often each song has been played but not when, and Recently played
 * only goes back a hundred songs. Neither can say what was played most this month. This log
 * can, from the day it starts: one line per play that counted, by the same rule, at the same
 * moment, as a play is reported to the server (`PlayReporter`: half the song or four
 * minutes; a radio station never), whether or not plays are reported.
 *
 * **Its interface** (what syncing between devices builds on; Windows' names):
 * - [add]: a play counted here.
 * - [absorb]: plays heard of from other devices, each taken in once; returns how many were new.
 * - [shared] and [sources]: the plays, and where those made from a playlist were made from.
 * - [state]: the same, as one [Plays] that is never copied, for whatever reads them all.
 *
 * **Kept as Windows keeps it.** A file that is only ever added to, a line at a time, so
 * noting a play costs the same however long the log has grown; past 205,000 plays the oldest
 * go, down to 200,000 (more than anyone plays in ten years), and the file is written whole.
 * With no file at all it is started from Recently played, so it isn't empty on day one.
 *
 * **Written safely.** Off the main thread, in the order the plays were noted: whatever write
 * runs next writes everything not yet in the file (each play has a number, and the file is
 * known to hold up to a number), so two writes can never put one play in twice or leave one
 * out. Lines are synced as they are added. A line cut short (the app killed as it was being
 * written) is passed over when the file is read, as on Windows, and cut off before the next
 * line is added (Windows would join the next play to it, and lose both). The whole file is
 * written atomically when it is written whole. A file that exists but can't be read is never
 * written over: the plays of this run are kept in memory only, and said in Diagnostics.
 *
 * **One writer per file for the process**, as for held changes: signing out and straight
 * back in to the same account makes a second log for the file while the first may still be
 * writing, and the second reads the file only once the first has finished with it. Writes
 * carry on after the account has gone (they are of plays it made). What is in memory goes
 * with the account (this object belongs to its `AccountGraph`).
 */
class PlayLog(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val note: (String) -> Unit = {},
) {
    private val current = MutableStateFlow(Plays.EMPTY)

    /** The plays, oldest first; empty until the file has been read ([open]). */
    val state: StateFlow<Plays> = current.asStateFlow()

    // The log in memory, as arrays that only ever grow at the end (see [Plays]).
    private var size = 0
    private var songs = IntArray(INITIAL)
    private var ats = LongArray(INITIAL)
    private var froms = IntArray(INITIAL)
    private var names = arrayOfNulls<String>(64)
    private var nameCount = 0
    private val nameRefs = HashMap<String, Int>()

    /** The number of the play at place 0, so that place `i` is play `base + i`. */
    private var base = 0L
    /** The file holds every play up to (not including) this number; and if [rewrite] is set
     *  it is to be written whole, whatever it holds. */
    private var written = 0L
    private var rewrite = false
    /** Lines in the file (Windows' `lines`): when the oldest are taken out. */
    private var lines = 0
    /** The file is there but couldn't be read: it is never written over. */
    private var unreadable = false
    private var opened = false
    private val ready = CompletableDeferred<Unit>()

    /**
     * Reads the log (off the main thread). With no file, it is started from `recent`, what
     * Recently played remembers (song and seconds, in any order), and written. Plays noted
     * before it had been read are kept after what is read.
     */
    suspend fun open(recent: suspend () -> List<Pair<String, Long>> = { emptyList() }) {
        if (ready.isCompleted) return
        val lock = lockFor(file)
        val read = lock.withLock {
            withContext(io) {
                try {
                    if (!file.exists()) null else parseAll(file.readBytes().decodeToString())
                } catch (_: IOException) {
                    unreadable = true
                    note("plays: the log on the phone couldn't be read; this run's plays are kept in memory only")
                    emptyList()
                }
            }
        }
        val started = if (read == null) recent().sortedBy { it.second }.map { Triple(it.first, it.second, "") } else null
        synchronized(this) {
            if (opened) return@synchronized
            // What was noted before the file had been read goes after what is in it.
            val early = List(size) { Triple(names[songs[it]]!!, ats[it], froms[it].let { f -> if (f < 0) "" else names[f]!! }) }
            size = 0
            base = 0
            for ((song, at, from) in read ?: started!!) put(song, at, from)
            lines = size
            written = size.toLong()
            // A log started afresh is written whole; so is one with plays from before it was read.
            rewrite = read == null
            for ((song, at, from) in early) put(song, at, from)
            lines = size
            opened = true
            trim()
            publish()
        }
        ready.complete(Unit)
        persist()
    }

    /** A play that counted, now (seconds since 1970), and the playlist it was made from (its
     *  route key, "playlist:<id>") if it was from one. Safe from any thread; the line is
     *  written off it. */
    fun add(song: String, at: Long, from: String = "") {
        if (song.isEmpty()) return
        synchronized(this) {
            put(song, at, from)
            if (opened) {
                lines++
                trim()
            }
            publish()
        }
        persist()
    }

    /**
     * Takes in plays made on other devices (see the sync milestone), leaving out the ones
     * already here: Windows' `absorb`, exactly. `sources` says which playlists some of them
     * were made from. Returns how many were new. Waits for the file to have been read.
     */
    suspend fun absorb(plays: List<Pair<String, Long>>, sources: List<Triple<String, Long, String>>): Int {
        ready.await()
        val new = synchronized(this) {
            // Anything another device has to tell is recent, so the newest part is enough to
            // compare with.
            val known = HashSet<Pair<String, Long>>()
            for (i in size - 1 downTo maxOf(0, size - ABSORB_WINDOW)) known += names[songs[i]]!! to ats[i]
            // Oldest first, and the same play twice running once (Rust's stable sort, then
            // `dedup`).
            val new = plays.filter { it !in known }.sortedBy { it.second }
            val kept = ArrayList<Pair<String, Long>>(new.size)
            for (play in new) if (kept.lastOrNull() != play) kept += play
            for ((song, at) in kept) {
                val from = sources.firstOrNull { it.first == song && it.second == at }?.third.orEmpty()
                put(song, at, from)
                lines++
                trim()
            }
            if (kept.isNotEmpty()) publish()
            kept.size
        }
        if (new > 0) persist()
        return new
    }

    /** The plays, oldest first (Windows' `shared`). */
    fun shared(): List<Pair<String, Long>> {
        val plays = current.value
        return List(plays.size) { plays.song(it) to plays.at(it) }
    }

    /** The plays that were made from a playlist, each with that playlist's route key, oldest
     *  first (Windows' `sources`). */
    fun sources(): List<Triple<String, Long, String>> {
        val plays = current.value
        val out = ArrayList<Triple<String, Long, String>>()
        for (i in 0 until plays.size) {
            val from = plays.from(i)
            if (from.isNotEmpty()) out += Triple(plays.song(i), plays.at(i), from)
        }
        return out
    }

    // ---- in memory (under the object's lock) -----------------------------------------------

    private fun ref(name: String): Int = nameRefs.getOrPut(name) {
        if (nameCount == names.size) names = names.copyOf(nameCount * 2)
        names[nameCount] = name
        nameCount++
    }

    private fun put(song: String, at: Long, from: String) {
        if (size == songs.size) {
            // New arrays: what has been handed out keeps reading the old ones.
            songs = songs.copyOf(size * 2)
            ats = ats.copyOf(size * 2)
            froms = froms.copyOf(size * 2)
        }
        songs[size] = ref(song)
        ats[size] = at
        froms[size] = if (from.isEmpty()) -1 else ref(from)
        size++
    }

    /** Takes the oldest plays out once the log is well past its limit (Windows' `trim`). */
    private fun trim() {
        if (lines <= LIMIT + SLACK) return
        val extra = (size - LIMIT).coerceAtLeast(0)
        val keep = size - extra
        songs = songs.copyOfRange(extra, extra + maxOf(keep, INITIAL))
        ats = ats.copyOfRange(extra, extra + maxOf(keep, INITIAL))
        froms = froms.copyOfRange(extra, extra + maxOf(keep, INITIAL))
        size = keep
        base += extra
        rewrite = true
        lines = size
    }

    private fun publish() {
        current.value = Plays(size, songs, ats, froms, names)
    }

    // ---- on the phone ------------------------------------------------------------------------

    /** Brings the file up to what is in memory, in the background, in order. */
    private fun persist() {
        if (!ready.isCompleted) return
        writer.launch {
            lockFor(file).withLock { writeDue() }
        }
    }

    /** Returns once everything noted so far is in the file (for tests, and whatever must
     *  know). */
    suspend fun flushed() {
        ready.await()
        writer.launch { lockFor(file).withLock { writeDue() } }.join()
    }

    private fun writeDue() {
        // What is due, read under the lock; written outside it.
        val whole: Boolean
        val plays: Plays
        val from: Int
        val upTo: Long
        synchronized(this) {
            if (unreadable) return
            plays = current.value
            whole = rewrite
            upTo = base + plays.size
            if (!whole && written >= upTo) return
            from = if (whole) 0 else (written - base).toInt()
        }
        try {
            if (whole) {
                writeAtomically(file, durable = true) { out ->
                    for (i in 0 until plays.size) out.write(line(plays.song(i), plays.at(i), plays.from(i)).toByteArray())
                }
            } else {
                append(plays, from)
            }
        } catch (_: IOException) {
            // Kept in memory; the next play tries again from where the file stopped.
            if (!failedSaid) note("plays: a play couldn't be written to the log on the phone")
            failedSaid = true
            return
        }
        synchronized(this) {
            if (whole) rewrite = false
            if (upTo > written) written = upTo
        }
    }

    @Volatile private var failedSaid = false

    private fun append(plays: Plays, from: Int) {
        val fresh = !file.exists()
        if (!fresh) cutShort(file)
        FileOutputStream(file, true).use { out ->
            val text = StringBuilder()
            for (i in from until plays.size) text.append(line(plays.song(i), plays.at(i), plays.from(i)))
            out.write(text.toString().toByteArray())
            out.fd.sync()
        }
        if (fresh) file.parentFile?.let { syncDir(it) }
    }

    companion object {
        /** More than anyone plays in ten years; the oldest go first after that. */
        const val LIMIT = 200_000
        /** How far past the limit the file may grow before the oldest lines are taken out. */
        const val SLACK = 5_000
        /** How many of the newest plays here a play from another device is compared with. */
        private const val ABSORB_WINDOW = 5_000
        private const val INITIAL = 256

        /** Where the log's writes are done: the process's, not the account's, so a play
         *  noted as the account goes is still written. */
        private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val locks = HashMap<String, Mutex>()

        private fun lockFor(file: File): Mutex = synchronized(locks) { locks.getOrPut(file.absolutePath) { Mutex() } }

        private val json = Json

        /** A line of the log, as Windows writes it (serde's form of a tuple). */
        fun line(song: String, at: Long, from: String): String {
            val parts = if (from.isEmpty()) listOf(JsonPrimitive(song), JsonPrimitive(at)) else listOf(JsonPrimitive(song), JsonPrimitive(at), JsonPrimitive(from))
            return JsonArray(parts).toString() + "\n"
        }

        /** A line of the log: the song, when, and where it was played from if that was noted;
         *  null for a line that isn't one (cut short, say). */
        fun parse(line: String): Triple<String, Long, String>? {
            val parts = try {
                json.parseToJsonElement(line).jsonArray
            } catch (_: Exception) {
                return null
            }
            if (parts.size != 2 && parts.size != 3) return null
            val song = (parts[0] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            val at = (parts[1] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            val from = if (parts.size == 3) (parts[2] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null else ""
            return Triple(song, at, from)
        }

        private fun parseAll(text: String): List<Triple<String, Long, String>> = text.lineSequence().mapNotNull { parse(it.removeSuffix("\r")) }.toList()

        /** Cuts off a last line that was never finished, so the next line doesn't join it. */
        private fun cutShort(file: File) {
            RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                if (length == 0L) return
                raf.seek(length - 1)
                if (raf.read() == '\n'.code) return
                // Back to the end of the last whole line.
                var at = length - 1
                val chunk = ByteArray(4096)
                while (at > 0) {
                    val start = maxOf(0L, at - chunk.size)
                    val n = (at - start).toInt()
                    raf.seek(start)
                    raf.readFully(chunk, 0, n)
                    for (k in n - 1 downTo 0) {
                        if (chunk[k] == '\n'.code.toByte()) {
                            raf.setLength(start + k + 1)
                            return
                        }
                    }
                    at = start
                }
                raf.setLength(0)
            }
        }
    }
}
