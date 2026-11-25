package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
 * - [shared] and [sources]: the plays, and where those made from a playlist were made from;
 *   [sharedWithSources]: the two from one and the same moment.
 * - [state]: the same, as one [Plays] that is never copied, for whatever reads them all.
 *
 * **Kept as Windows keeps it.** A file that is only ever added to, a line at a time, so
 * noting a play costs the same however long the log has grown; past 205,000 plays the oldest
 * go, down to 200,000 (more than anyone plays in ten years), and the file is written whole.
 * With no file at all it starts empty. (Windows, when its log was new, started it from
 * Recently played. On the phone Recently played holds every song that was started, even one
 * skipped at once: taken in, it would count plays that never counted.)
 *
 * **Written safely.** Off the main thread, in the order the plays were noted: whatever write
 * runs next writes everything not yet in the file (each play has a number, and the file is
 * known to hold up to a number), so two writes can never put one play in twice or leave one
 * out. Lines are synced as they are added. **The log knows how long the file is up to its last
 * record known to be written**, and every write first cuts the file back to that length: a
 * write that failed part-way (the disk full), or whose sync failed, is taken back before the
 * next try, rather than written again after itself. A record is a whole line; a last line with
 * no newline is a record if it reads as one (as Windows' reading takes it), and it is then given
 * its newline before the next line is added; one that doesn't read is cut off. The whole file
 * is written atomically when it is written whole.
 *
 * **A log that could not be read is never taken for a log that is not there.** Only "there
 * is no such file" means there is no log; any other failure to read it (asked by reading it,
 * not by a check first) leaves it exactly as it is, and nothing is written to it in that
 * run. What is played meanwhile, and what other devices tell of, is kept in a file beside
 * it (`plays.log.new`), whole, and joined to the log, each play once, the next time the log
 * reads; Diagnostics says so. [flushed] says when a write failed. **The same holds for that
 * file**: if it is there and can't be read, it is left exactly as it is, and this run's
 * plays go to a second (`plays.log.new.2`); each is joined to the log, and goes, in a run
 * that could read both it and the log.
 *
 * **One owner of the file at a time.** A log holds its file from [open] to [close]: the lock
 * its writes are made under is taken as it opens and let go only once it has closed, with
 * everything it held written. So a log opened for the same file (signing out and straight back
 * in to the same account) reads it only after the one before has closed, and sees its last
 * play; and a closed log never writes again. The account closes its log as it is let go
 * (`AccountGraph.close`). What is in memory goes with the account.
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
    /** How many times a play already in the log has been given a source ([absorb]): a whole
     *  write that began before the latest of them has not written it. */
    private var sourcedTimes = 0L
    /** Lines in the file (Windows' `lines`): when the oldest are taken out. */
    private var lines = 0
    /** The file is there but couldn't be read: it is never written over. What this run
     *  holds is written beside it instead ([aside]). */
    private var unreadable = false
    /** Where plays are kept while the log itself can't be read; joined to it when it can. */
    private val aside = File(file.path + ".new")
    /** Where they are kept if what is at [aside] can't be read either: that is left exactly
     *  as it is too, and never written over. */
    private val asideSecond = File(file.path + ".new.2")
    /** Which of the two this run writes to: the first that it could read (or that isn't
     *  there). Null if neither could be read: then there is nowhere, and it is said. */
    private var asideHere: File? = aside
    /** Those of the two that were read, and so go once the log has what they held. */
    private var asidesRead: List<File> = emptyList()
    /** The plays that were beside the log have been put after its own, in memory: once the
     *  file has them, the file beside it goes. */
    private var joining = false
    private var opened = false
    private val ready = CompletableDeferred<Unit>()

    /** The file's lock: held by this log from [open] to [close] (see the class's note). */
    private val owner = lockFor(file)
    /** This log's writes, one at a time. */
    private val writing = Mutex()
    private var started = false
    /** It holds the file (it has opened, and not yet closed). */
    private var holding = false
    private var closed = false
    /** [open] has finished, one way or another. */
    private val settled = CompletableDeferred<Unit>()
    /** Whether the last write, at closing, put everything in the file. */
    private var closing: kotlinx.coroutines.Deferred<Boolean>? = null
    /** The file's length up to the end of its last record known to be written; and whether
     *  that record is a last line still wanting its newline. (Under [writing].) */
    private var good = 0L
    private var newlineWanted = false

    /** For tests: a write waits here, holding [writing], before it is made. */
    internal var beforeWrite: suspend () -> Unit = {}
    /** For tests: how new lines go onto the end of the file. */
    internal var appendBytes: (RandomAccessFile, ByteArray) -> Unit = { raf, bytes ->
        raf.write(bytes)
        raf.fd.sync()
    }

    /**
     * Reads the log (off the main thread). With no file, it starts empty, and is written.
     * Plays noted before it had been read are kept after what is read.
     */
    suspend fun open() {
        synchronized(this) {
            if (started || closed) {
                if (closed) {
                    settled.complete(Unit)
                    ready.complete(Unit)
                }
                return
            }
            started = true
        }
        try {
            // Not until the log before this one for the file (if any) has closed.
            owner.lock()
            try {
                val read = withContext(io) {
                    try {
                        readLog(file)
                    } catch (_: IOException) {
                        unreadable = true
                        note("plays: the log on the phone couldn't be read, so it is left exactly as it is; what is played meanwhile is kept beside it, and joined to it when it can be read")
                        Found(emptyList(), 0, false)
                    }
                }
                // What an earlier run kept beside a log it couldn't read.
                // (One that can't be read is left exactly as it is, like the log: it is
                // never taken for one that isn't there, and never written over.)
                val besides = withContext(io) {
                    listOf(aside, asideSecond).map { kept ->
                        kept to try {
                            Result.success(readLog(kept)?.plays)
                        } catch (e: IOException) {
                            note("plays: what was kept beside the log on the phone couldn't be read; it is left exactly as it is")
                            Result.failure(e)
                        }
                    }
                }
                val beside = besides.flatMap { (_, read) -> read.getOrNull().orEmpty() }
                synchronized(this) {
                    // What was noted before the file had been read goes after what is in it.
                    val early = List(size) { Triple(names[songs[it]]!!, ats[it], froms[it].let { f -> if (f < 0) "" else names[f]!! }) }
                    size = 0
                    base = 0
                    for ((song, at, from) in read?.plays.orEmpty()) put(song, at, from)
                    lines = size
                    written = size.toLong()
                    // Joined to the log, each play once (a play another device told of while
                    // the log couldn't be read may be one the log has); or, if the log still
                    // can't be read, held with this run's, to be written beside it again.
                    if (beside.isNotEmpty()) {
                        val have = HashSet<Pair<String, Long>>(size * 2)
                        for (i in 0 until size) have += names[songs[i]]!! to ats[i]
                        for ((song, at, from) in beside) if (have.add(song to at)) put(song, at, from)
                    }
                    asidesRead = besides.filter { (_, read) -> read.getOrNull() != null }.map { it.first }
                    asideHere = besides.firstOrNull { (_, read) -> read.isSuccess }?.first
                    joining = asidesRead.isNotEmpty() && !unreadable
                    // A log started afresh is written whole.
                    rewrite = read == null
                    good = read?.good ?: 0
                    newlineWanted = read?.newline ?: false
                    for ((song, at, from) in early) put(song, at, from)
                    lines = size
                    opened = true
                    holding = true
                    trim()
                    publish()
                }
            } finally {
                if (!holding) owner.unlock()
            }
        } finally {
            settled.complete(Unit)
            ready.complete(Unit)
        }
        persist()
    }

    /**
     * Closes the log: no play is taken from now on, everything it holds is written, and then
     * the file is let go for the next log (see the class's note). Returns at once; what it
     * gives completes when the file has been let go.
     */
    fun close(): kotlinx.coroutines.Job {
        val wasStarted: Boolean
        synchronized(this) {
            closing?.let { return it }
            closed = true
            wasStarted = started
        }
        val job = writer.async {
            if (wasStarted) settled.await()
            if (!holding) return@async true
            try {
                writing.withLock { writeDue() }
            } finally {
                owner.unlock()
            }
        }
        synchronized(this) { closing = job }
        return job
    }

    /** A play that counted, now (seconds since 1970), and the playlist it was made from (its
     *  route key, "playlist:<id>") if it was from one. Safe from any thread; the line is
     *  written off it. */
    fun add(song: String, at: Long, from: String = "") {
        if (song.isEmpty()) return
        synchronized(this) {
            if (closed) return
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
     * already here (Windows' `absorb`). A play is a song and a time: each is taken in at most
     * once, however often it is offered and whatever else is offered at the same time, and not
     * at all if the log holds it (Windows compares with its newest 5,000 only, and keeps two of
     * a play offered twice when another play of the same second sorts between them). A play
     * older than everything the log still holds (taken out at its limit) is not known, and is
     * taken in again if offered again, as on Windows. `sources` says which playlists some of
     * them were made from (the first it gives for a play); a play already here with none noted
     * is given the one told now, without being counted again. Returns how many were new. Waits
     * for the file to have been read.
     */
    suspend fun absorb(plays: List<Pair<String, Long>>, sources: List<Triple<String, Long, String>>): Int {
        ready.await()
        var changed = false
        val new = synchronized(this) {
            if (closed || !holding) return 0
            val sourceOf = HashMap<Pair<String, Long>, String>()
            for ((song, at, from) in sources) sourceOf.putIfAbsent(song to at, from)
            // What the log holds of the seconds offered (only those can be the same play).
            val offered = plays.mapTo(HashSet()) { it.second } + sources.map { it.second }
            val known = HashSet<Pair<String, Long>>()
            // A play already here with no playlist noted, whose playlist is told now (the
            // device that made it shared the play before it shared where from, or an older
            // version passed the play on without): it is given that source, and is not a new
            // play. (In arrays of their own: what has been handed out keeps the old ones.)
            var sourced = false
            for (i in 0 until size) {
                if (ats[i] !in offered) continue
                val play = names[songs[i]]!! to ats[i]
                known += play
                val from = if (froms[i] < 0) sourceOf[play].orEmpty() else ""
                if (from.isNotEmpty()) {
                    if (!sourced) froms = froms.copyOf()
                    froms[i] = ref(from)
                    sourced = true
                }
            }
            // (Its line in the file has no source: the file is written whole, and by a write
            // that began after this, not one already under way with the play as it was.)
            if (sourced) {
                rewrite = true
                sourcedTimes++
            }
            // Oldest first (Rust's stable sort), each play once.
            val kept = ArrayList<Pair<String, Long>>()
            for (play in plays.sortedBy { it.second }) if (known.add(play)) kept += play
            for ((song, at) in kept) {
                put(song, at, sourceOf[song to at].orEmpty())
                lines++
                trim()
            }
            if (kept.isNotEmpty() || sourced) publish()
            if (sourced) changed = true
            kept.size
        }
        if (new > 0 || changed) persist()
        return new
    }

    /** The plays, oldest first (Windows' `shared`). */
    fun shared(): List<Pair<String, Long>> = sharedOf(current.value)

    /** Where the plays made from a playlist were made from (Windows' `sources`): one row per
     *  song and second, as Windows keeps them (a map by the play), with the playlist noted
     *  last for it, in the order the plays were first noted. */
    fun sources(): List<Triple<String, Long, String>> = sourcesOf(current.value)

    /** [shared] and [sources] from one and the same moment (two calls can see two logs, a
     *  play noted in between): what syncing between devices shares. */
    fun sharedWithSources(): Pair<List<Pair<String, Long>>, List<Triple<String, Long, String>>> {
        val plays = current.value
        return sharedOf(plays) to sourcesOf(plays)
    }

    private fun sharedOf(plays: Plays): List<Pair<String, Long>> = List(plays.size) { plays.song(it) to plays.at(it) }

    private fun sourcesOf(plays: Plays): List<Triple<String, Long, String>> {
        val noted = LinkedHashMap<Pair<String, Long>, String>()
        for (i in 0 until plays.size) {
            val from = plays.from(i)
            if (from.isNotEmpty()) noted[plays.song(i) to plays.at(i)] = from
        }
        return noted.map { (play, from) -> Triple(play.first, play.second, from) }
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
        // The song ids and playlists only the plays taken out used go with them: the names are
        // made again from what is kept (in new arrays: what has been handed out keeps the old).
        val was = names
        val renumbered = IntArray(nameCount) { -1 }
        names = arrayOfNulls(64)
        nameCount = 0
        nameRefs.clear()
        fun again(old: Int): Int {
            if (renumbered[old] < 0) renumbered[old] = ref(was[old]!!)
            return renumbered[old]
        }
        for (i in 0 until size) {
            songs[i] = again(songs[i])
            if (froms[i] >= 0) froms[i] = again(froms[i])
        }
    }

    /** How many song ids and playlists the log holds names for (for tests). */
    internal val namesHeld: Int get() = synchronized(this) { nameCount }

    private fun publish() {
        current.value = Plays(size, songs, ats, froms, names)
    }

    // ---- on the phone ------------------------------------------------------------------------

    /** Brings the file up to what is in memory, in the background, in order. */
    private fun persist() {
        if (!ready.isCompleted) return
        writer.launch {
            writing.withLock {
                beforeWrite()
                // (A closed log's last write was made by closing it.)
                if (!synchronized(this@PlayLog) { closed }) writeDue()
            }
        }
    }

    /** Returns once everything noted so far is in the file; throws if it couldn't be
     *  written (the log keeps it, and tries again at the next play), or can't be (a file
     *  that couldn't be read, or a log that never opened). */
    @Throws(IOException::class)
    suspend fun flushed() {
        ready.await()
        val done = synchronized(this) { closing }
        val ok = done?.await() ?: writing.withLock { withContext(io) { writeDue() } }
        if (!ok) throw IOException("The plays couldn't all be written to the log on the phone")
    }

    /** Writes what is due. False if something is due that couldn't be written. (Under
     *  [writing].) */
    private fun writeDue(): Boolean {
        // What is due, read under the lock; written outside it.
        val whole: Boolean
        val plays: Plays
        val from: Int
        val upTo: Long
        val sourcedThen: Long
        if (synchronized(this) { holding && unreadable }) return writeAside()
        synchronized(this) {
            if (!holding) return false
            plays = current.value
            sourcedThen = sourcedTimes
            whole = rewrite
            upTo = base + plays.size
            if (!whole && written >= upTo) {
                joined()
                return true
            }
            from = if (whole) 0 else (written - base).toInt()
        }
        try {
            if (whole) {
                var length = 0L
                writeAtomically(file, durable = true) { out ->
                    for (i in 0 until plays.size) {
                        val bytes = line(plays.song(i), plays.at(i), plays.from(i)).toByteArray()
                        out.write(bytes)
                        length += bytes.size
                    }
                }
                good = length
                newlineWanted = false
            } else {
                append(plays, from)
            }
        } catch (_: IOException) {
            // Kept in memory; the next write cuts the file back to its last good record and
            // tries again from there.
            if (!failedSaid) note("plays: a play couldn't be written to the log on the phone")
            failedSaid = true
            return false
        }
        synchronized(this) {
            if (whole && sourcedTimes == sourcedThen) rewrite = false
            if (upTo > written) written = upTo
            joined()
        }
        return true
    }

    /** The log has everything this log holds, what was kept beside it included: the file
     *  beside it goes. (Under this log's lock.) */
    private fun joined() {
        if (!joining) return
        asidesRead = asidesRead.filter { kept -> !kept.delete() && kept.exists() }
        if (asidesRead.isEmpty()) joining = false
    }

    /**
     * While the log itself can't be read: everything this run holds (what an earlier such
     * run kept, what has been played since, what other devices told of) written whole to
     * the file beside the log. The log is not touched. False if it couldn't be written.
     */
    private fun writeAside(): Boolean {
        val plays = current.value
        val aside = asideHere ?: run {
            // Neither file beside the log could be read, and neither is written over.
            if (plays.size == 0) return true
            if (!failedSaid) note("plays: there is nowhere on the phone to keep what is played while its log can't be read")
            failedSaid = true
            return false
        }
        if (plays.size == 0 && !aside.exists()) return true
        return try {
            writeAtomically(aside, durable = true) { out ->
                for (i in 0 until plays.size) out.write(line(plays.song(i), plays.at(i), plays.from(i)).toByteArray())
            }
            true
        } catch (_: IOException) {
            if (!failedSaid) note("plays: a play couldn't be written beside the log on the phone")
            failedSaid = true
            false
        }
    }

    @Volatile private var failedSaid = false

    /** Adds the plays from `from` on to the end of the file's last good record. */
    private fun append(plays: Plays, from: Int) {
        val fresh = !file.exists()
        val text = StringBuilder()
        if (newlineWanted) text.append('\n')
        for (i in from until plays.size) text.append(line(plays.song(i), plays.at(i), plays.from(i)))
        val bytes = text.toString().toByteArray()
        RandomAccessFile(file, "rw").use { raf ->
            // Whatever a write that failed left after the last good record goes first.
            if (raf.length() != good) raf.setLength(good)
            raf.seek(good)
            appendBytes(raf, bytes)
        }
        if (fresh) file.parentFile?.let { syncDir(it) }
        good += bytes.size
        newlineWanted = false
    }

    companion object {
        /** More than anyone plays in ten years; the oldest go first after that. */
        const val LIMIT = 200_000
        /** How far past the limit the file may grow before the oldest lines are taken out. */
        const val SLACK = 5_000
        private const val INITIAL = 256

        /** Where the log's writes are done: the process's, not the account's, so a play
         *  noted as the account goes is still written, and its closing finished. */
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

        /** What [readLog] found: the plays, and where the last good record ends. */
        private class Found(val plays: List<Triple<String, Long, String>>, val good: Long, val newline: Boolean)

        /**
         * Reads the file: its records, and the length up to the end of the last of them. A
         * record is a line that reads as one; a last line with no newline is one if it reads
         * as one (it is then still to have its newline), and otherwise is no record and not
         * counted in the length. Null when there is no file.
         */
        private fun readLog(file: File): Found? {
            // (Whether it is there is told by reading it: only "no such file" means it isn't.
            // A check first would take a file that couldn't be looked at for one that isn't
            // there, and the log would then be started afresh over it.)
            val bytes = try {
                java.nio.file.Files.readAllBytes(file.toPath())
            } catch (_: java.nio.file.NoSuchFileException) {
                return null
            }
            var end = bytes.size
            while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
            val plays = String(bytes, 0, end, Charsets.UTF_8).lineSequence().mapNotNull { parse(it.removeSuffix("\r")) }.toMutableList()
            val rest = parse(String(bytes, end, bytes.size - end, Charsets.UTF_8).removeSuffix("\r"))
            if (end < bytes.size && rest != null) {
                plays += rest
                return Found(plays, bytes.size.toLong(), newline = true)
            }
            return Found(plays, end.toLong(), newline = false)
        }
    }
}
