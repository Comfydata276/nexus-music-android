package org.medianexus.music.playback

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.medianexus.music.library.idAt
import org.medianexus.music.net.Song
import org.medianexus.music.store.writeAtomically

/**
 * The play queue as it was last saved, in the Windows app's form (`SavedQueue` in
 * src/config.rs) so the two can later hand it to each other ("Continue here").
 */
@Serializable
data class SavedQueue(
    /** Written a song at a time as Windows writes them; read back into a table of their own
     *  (see [org.medianexus.music.library.PlacedSongs]), not as one object per song. */
    @Serializable(with = org.medianexus.music.library.PlacedSongsSerializer::class)
    val songs: List<Song> = emptyList(),
    val order: List<Int> = emptyList(),
    val cursor: Int = 0,
    /** Seconds into the current song. */
    val position: Double = 0.0,
    val context: String = "",
    val source: String = "",
    val origins: List<Int?> = emptyList(),
    /** When the queue last changed, in seconds since 1970. */
    val stamp: Long = 0,
    @SerialName("source_list") val sourceList: List<String> = emptyList(),
    /** The phone's own: what was typed in the find box of the list the queue was started
     *  from, which it follows narrowed the same way. (Windows reads past a field it doesn't
     *  know, and isn't asked to follow it; not part of the queue's shape.) */
    @SerialName("list_filter") val listFilter: String = "",
    /** Set while the queue is one taken up from another of the person's devices with nothing
     *  done to it here since: when that device saved it, as the server said (the shared
     *  core's `Here::taken`). Such a queue is that device's still, and is not this phone's
     *  to save on the server; after a restart too. */
    val taken: String? = null,
) {
    /**
     * Whether it holds together: a queue that doesn't (a file cut short, edited by hand) is
     * not put back, as on Windows. The play order must name every song exactly once: with
     * one named twice and another not at all, the player would hold two entries it took for
     * one, and every edit after that would land on the wrong song.
     */
    val valid: Boolean
        get() {
            if (songs.isEmpty() || order.size != songs.size || cursor !in order.indices) return false
            val seen = BooleanArray(songs.size)
            for (place in order) {
                if (place !in songs.indices || seen[place]) return false
                seen[place] = true
            }
            return true
        }

    /** Which queue this is, as Windows works it out (`queue_shape`): where playback had got
     *  to is saved apart, against this. */
    fun shape(): ULong = queueShape(List(songs.size) { songs.idAt(it) }, order, context, source, origins, sourceList)
}

/** Where playback has got to in the saved queue (`QueuePlace` on Windows). */
@Serializable
data class QueuePlace(
    val shape: ULong = 0u,
    val cursor: Int = 0,
    val position: Double = 0.0,
    val stamp: Long = 0,
    /** As [SavedQueue.taken], as it stood when the place was written. */
    val taken: String? = null,
)

/**
 * FNV-1a over the queue, as Windows writes it out so the result doesn't depend on a
 * library's hasher: each piece is followed by a 0xff byte.
 */
fun queueShape(ids: List<String>, order: List<Int>, context: String, source: String, origins: List<Int?>, sourceList: List<String>): ULong {
    var hash = 0xcbf29ce484222325uL
    fun mix(bytes: ByteArray) {
        for (byte in bytes) hash = (hash xor (byte.toULong() and 0xffuL)) * 0x00000100000001b3uL
        hash = (hash xor 0xffuL) * 0x00000100000001b3uL
    }
    fun mix(number: ULong) = mix(ByteArray(8) { i -> (number shr (8 * i)).toByte() })
    for (id in ids) mix(id.toByteArray(Charsets.UTF_8))
    for (place in order) mix(place.toULong())
    mix(context.toByteArray(Charsets.UTF_8))
    mix(source.toByteArray(Charsets.UTF_8))
    for (origin in origins) mix(origin?.toULong() ?: ULong.MAX_VALUE)
    for (id in sourceList) mix(id.toByteArray(Charsets.UTF_8))
    return hash
}

/**
 * The saved queue on disk, as Windows keeps it: the queue itself (thousands of songs, written
 * only when it changes) and, beside it, where playback has got to (a few numbers, written
 * often). Called off the main thread.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class QueueFiles(private val queueFile: File, private val placeFile: File) {
    private var savedShape: ULong? = null

    /** The find text the queue on disk was written with: not part of its shape (Windows
     *  doesn't know it), but a queue played again with another is written again. */
    private var savedFilter = ""

    /** Which hand-over the queue on disk came from (see [KeptQueue]); 0 when it was read
     *  from disk, cleared, or isn't known. A place is only ever written beside the queue it
     *  was handed over with. */
    private var savedToken = 0L

    /** Which saved queue this is: two of these on the same file are the same queue. */
    val key: String get() = queueFile.path

    fun load(): SavedQueue? {
        savedToken = 0
        val saved = read(queueFile, SavedQueue.serializer())?.takeIf { it.valid } ?: return null
        val shape = saved.shape()
        savedShape = shape
        savedFilter = saved.listFilter
        val place = read(placeFile, QueuePlace.serializer())
        return if (place != null && place.shape == shape && place.cursor in saved.order.indices) {
            saved.copy(cursor = place.cursor, position = place.position, stamp = maxOf(saved.stamp, place.stamp), taken = place.taken)
        } else {
            saved
        }
    }

    /**
     * Writes the queue if it has changed since last written, and the place always. `token`
     * names this hand-over of the queue, for the places that follow it ([savePlace]).
     * Returns whether the queue is now on disk: false if it couldn't be written, in which
     * case what was there before still is, whole, with its own place.
     */
    fun save(queue: SavedQueue, token: Long = 0): Boolean {
        if (queue.songs.isEmpty()) {
            clear()
            return !queueFile.exists()
        }
        val shape = queue.shape()
        try {
            if (shape != savedShape || queue.listFilter != savedFilter) {
                // Until the new queue is in place, no place may be written beside the old
                // one on the strength of this hand-over.
                savedToken = 0
                // Straight into the file: a long queue is too big to build as text first.
                writeAtomically(queueFile) { out -> json.encodeToStream(SavedQueue.serializer(), queue, out) }
                savedShape = shape
                savedFilter = queue.listFilter
            }
            savedToken = token
        } catch (_: IOException) {
            return false
        } catch (_: SerializationException) {
            // The same (it wraps a failure to write).
            return false
        }
        // The queue is there. Its place, if this can't be written, is the one saved in the
        // queue itself (an older place file names another queue, and is ignored for that).
        writePlace(QueuePlace(shape, queue.cursor, queue.position, queue.stamp, queue.taken))
        return true
    }

    /**
     * Writes only where playback has got to, in the queue handed over as `token`. The queue
     * itself is not looked at: nothing of it is copied, hashed or written. Does nothing, and
     * says so, unless the queue on disk is that very one: a place written beside any other
     * queue would put that queue back at a song it was never at.
     */
    fun savePlace(token: Long, cursor: Int, position: Double, stamp: Long, taken: String? = null): Boolean {
        val shape = savedShape ?: return false
        if (token == 0L || token != savedToken) return false
        return writePlace(QueuePlace(shape, cursor, position, stamp, taken))
    }

    private fun writePlace(place: QueuePlace): Boolean = try {
        writeAtomically(placeFile, json.encodeToString(QueuePlace.serializer(), place).toByteArray())
        true
    } catch (_: IOException) {
        // Tried again at the next save.
        false
    }

    fun clear() {
        savedShape = null
        savedToken = 0
        queueFile.delete()
        placeFile.delete()
    }

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        file.inputStream().buffered(64 * 1024).use { json.decodeFromStream(serializer, it) }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = false
        }

        /** An account's saved queue, in its data folder. */
        fun of(data: File) = QueueFiles(queueFile(data), File(data, "queue-place.json"))

        fun queueFile(data: File) = File(data, "queue.json")
    }
}

/**
 * The one writer of saved queues, for the whole app. Everything that touches a saved queue's
 * files, reading as well as writing, is a step in one line, done one at a time in the order
 * asked: so two writes can never be under way at once (they would share a temporary file),
 * an older snapshot can never land after a newer one, and a read sees everything that was
 * asked to be written before it. Of several snapshots of one queue waiting to be written,
 * only the last is.
 *
 * It belongs to the app, not to the playback service or an account, so a snapshot handed
 * over as either goes away is still written: the last save is this writer finishing, not a
 * second writer.
 */
class QueueSaver(scope: CoroutineScope, io: CoroutineDispatcher) {
    private val steps = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** What is waiting to be written for one queue: the whole queue (null if it hasn't
     *  changed since it was last handed over) and where playback has got to in it. `token`
     *  names the hand-over of the whole queue that the place belongs to; `done` is told
     *  whether the whole queue was written. */
    private class Waiting(val files: QueueFiles, val queue: SavedQueue?, val token: Long, val cursor: Int, val position: Double, val stamp: Long, val taken: String?, val done: ((Boolean) -> Unit)?)

    /** The newest of what hasn't been written yet, for each queue. */
    private val waiting = HashMap<String, Waiting>()

    init {
        scope.launch(io) { for (step in steps) step() }
    }

    /**
     * Asks for this snapshot to be written, and returns at once. `token` names it, for the
     * places handed over after it ([savePlace]); `done` is told, on the writer's thread,
     * whether it was written (not told if a newer snapshot took its place before its turn).
     */
    fun save(files: QueueFiles, queue: SavedQueue, token: Long = 0, done: ((Boolean) -> Unit)? = null) =
        line(Waiting(files, queue, token, queue.cursor, queue.position, queue.stamp, queue.taken, done))

    /**
     * Asks for only the place to be written: the queue is as it was handed over under
     * `token`, and playback has moved along it. If that snapshot is still waiting, it is
     * written with this place. If the queue on disk turns out not to be that one (its write
     * failed), nothing is written.
     */
    fun savePlace(files: QueueFiles, token: Long, cursor: Int, position: Double, stamp: Long, taken: String? = null) = line(Waiting(files, null, token, cursor, position, stamp, taken, null))

    private fun line(next: Waiting) {
        val alreadyInLine = synchronized(waiting) {
            val before = waiting[next.files.key]
            waiting[next.files.key] = when {
                // A place alone never takes the place of a queue still waiting to be written:
                // it goes with it, if it is that queue's.
                next.queue == null && before?.queue != null ->
                    if (before.token == next.token) Waiting(before.files, before.queue, before.token, next.cursor, next.position, next.stamp, next.taken, before.done) else before
                else -> {
                    // A snapshot that never got its turn was not written.
                    if (before?.queue != null) before.done?.invoke(false)
                    next
                }
            }
            before != null
        }
        if (alreadyInLine) return
        steps.trySend {
            // Whatever is newest by the time its turn comes.
            val newest = synchronized(waiting) { waiting.remove(next.files.key) } ?: return@trySend
            if (newest.queue != null) {
                val written = try {
                    newest.files.save(newest.queue.copy(cursor = newest.cursor, position = newest.position, stamp = newest.stamp, taken = newest.taken), newest.token)
                } catch (e: Throwable) {
                    newest.done?.invoke(false)
                    throw e
                }
                newest.done?.invoke(written)
            } else {
                newest.files.savePlace(newest.token, newest.cursor, newest.position, newest.stamp, newest.taken)
            }
        }
    }

    /** Reads a saved queue, after everything asked to be written so far has been. */
    suspend fun load(files: QueueFiles): SavedQueue? = inLine { files.load() }

    /** Waits until everything asked for so far has been written. */
    suspend fun flush() = inLine { }

    private suspend fun <T> inLine(step: () -> T): T {
        val done = CompletableDeferred<T>()
        steps.trySend {
            try {
                done.complete(step())
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return done.await()
    }
}

/**
 * One account's saved queue, as one life of the playback service uses it: read once when the
 * account is taken on, then written as the queue changes.
 *
 * Nothing is written until the queue it read is the player's queue ([settle]). Before then
 * what the player holds (nothing, while the saved queue is being read and then made ready,
 * which for a long queue takes a while) is not the account's queue, and an account that
 * goes, or a service that is destroyed, in that time must not have "empty" written over what
 * was there. "Read" is not enough: the queue has been read long before it is in the player.
 *
 * It belongs to the service that made it and to that account's turn at it, never to the
 * account: a service made again in the same process starts with a player that is empty
 * again, and with one of these that has not settled.
 */
class KeptQueue(private val files: QueueFiles, private val saver: QueueSaver) {
    /** The saved queue is the player's now (or there was none, or the person has started
     *  something else): from here on what the player holds is what is saved. */
    @Volatile
    var settled = false
        private set

    /** Reads the saved queue; null if there is none, or it doesn't hold together. Saving
     *  stays off until [settle]. */
    suspend fun read(): SavedQueue? = saver.load(files)

    /** The queue that was read has been put into the player, or there was none to put. */
    fun settle() {
        settled = true
    }

    /** The queue's revision when it was last handed over whole, and the name that hand-over
     *  was given. Main thread. */
    private var handedRevision = -1L
    private var token = 0L

    /** The hand-over that is known not to have been written; set by the writer. */
    @Volatile
    private var failedToken = 0L

    /** Saves the queue as `snapshot` gives it; does nothing (and takes no snapshot) before
     *  [settle]. Returns whether it was handed to the writer. */
    fun save(snapshot: () -> SavedQueue): Boolean {
        if (!settled) return false
        handedRevision = -1
        saver.save(files, snapshot())
        return true
    }

    /**
     * As [save], for a queue that says when it has changed (`revision`, see
     * [Queue.revision]): the snapshot is only taken when it has, or when the last one handed
     * over turned out not to have been written (a full disk, say), so that a queue that
     * failed to save is tried again whole. Otherwise only the place (`cursor`, `position`)
     * is handed over, which copies nothing, however long the queue; and the writer puts it
     * beside that queue or not at all ([QueueFiles.savePlace]).
     */
    fun save(revision: Long, cursor: Int, position: Double, stamp: Long, taken: String? = null, snapshot: () -> SavedQueue): Boolean {
        if (!settled) return false
        if (revision != handedRevision || failedToken == token) {
            val mine = tokens.incrementAndGet()
            token = mine
            handedRevision = revision
            saver.save(files, snapshot(), mine) { written -> if (!written) failedToken = mine }
        } else {
            saver.savePlace(files, token, cursor, position, stamp, taken)
        }
        return true
    }

    private companion object {
        /** Names for hand-overs, never reused while the app runs: two lives of the service
         *  never share one. */
        val tokens = java.util.concurrent.atomic.AtomicLong()
    }
}
