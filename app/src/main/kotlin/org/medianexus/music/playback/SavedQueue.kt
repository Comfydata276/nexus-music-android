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
import org.medianexus.music.net.Song
import org.medianexus.music.store.writeAtomically

/**
 * The play queue as it was last saved, in the Windows app's form (`SavedQueue` in
 * src/config.rs) so the two can later hand it to each other ("Continue here").
 */
@Serializable
data class SavedQueue(
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
    fun shape(): ULong = queueShape(songs.map { it.id }, order, context, source, origins, sourceList)
}

/** Where playback has got to in the saved queue (`QueuePlace` on Windows). */
@Serializable
data class QueuePlace(
    val shape: ULong = 0u,
    val cursor: Int = 0,
    val position: Double = 0.0,
    val stamp: Long = 0,
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

    /** Which saved queue this is: two of these on the same file are the same queue. */
    val key: String get() = queueFile.path

    fun load(): SavedQueue? {
        val saved = read(queueFile, SavedQueue.serializer())?.takeIf { it.valid } ?: return null
        val shape = saved.shape()
        savedShape = shape
        val place = read(placeFile, QueuePlace.serializer())
        return if (place != null && place.shape == shape && place.cursor in saved.order.indices) {
            saved.copy(cursor = place.cursor, position = place.position, stamp = maxOf(saved.stamp, place.stamp))
        } else {
            saved
        }
    }

    /** Writes the queue if it has changed since last written, and the place always. */
    fun save(queue: SavedQueue) {
        if (queue.songs.isEmpty()) {
            clear()
            return
        }
        val shape = queue.shape()
        try {
            if (shape != savedShape) {
                // Straight into the file: a long queue is too big to build as text first.
                writeAtomically(queueFile) { out -> json.encodeToStream(SavedQueue.serializer(), queue, out) }
                savedShape = shape
            }
            val place = QueuePlace(shape, queue.cursor, queue.position, queue.stamp)
            writeAtomically(placeFile, json.encodeToString(QueuePlace.serializer(), place).toByteArray())
        } catch (_: IOException) {
            // Tried again at the next save.
        } catch (_: SerializationException) {
            // The same (it wraps a failure to write).
        }
    }

    /**
     * Writes only where playback has got to, for the queue last written or read here. The
     * queue itself is not looked at: nothing of it is copied, hashed or written. Does nothing
     * if no queue has been written or read yet (the next full save writes both).
     */
    fun savePlace(cursor: Int, position: Double, stamp: Long) {
        val shape = savedShape ?: return
        try {
            writeAtomically(placeFile, json.encodeToString(QueuePlace.serializer(), QueuePlace(shape, cursor, position, stamp)).toByteArray())
        } catch (_: IOException) {
            // Tried again at the next save.
        }
    }

    fun clear() {
        savedShape = null
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
     *  changed since it was last written) and where playback has got to in it. */
    private class Waiting(val files: QueueFiles, val queue: SavedQueue?, val cursor: Int, val position: Double, val stamp: Long)

    /** The newest of what hasn't been written yet, for each queue. */
    private val waiting = HashMap<String, Waiting>()

    init {
        scope.launch(io) { for (step in steps) step() }
    }

    /** Asks for this snapshot to be written, and returns at once. */
    fun save(files: QueueFiles, queue: SavedQueue) = line(Waiting(files, queue, queue.cursor, queue.position, queue.stamp))

    /**
     * Asks for only the place to be written: the queue is as it was last saved, and playback
     * has moved along it. If a snapshot of the whole queue is still waiting, it is written
     * with this place.
     */
    fun savePlace(files: QueueFiles, cursor: Int, position: Double, stamp: Long) = line(Waiting(files, null, cursor, position, stamp))

    private fun line(next: Waiting) {
        val alreadyInLine = synchronized(waiting) {
            val before = waiting[next.files.key]
            // A place alone never takes the place of a queue still waiting to be written.
            waiting[next.files.key] = if (next.queue == null && before?.queue != null) {
                Waiting(before.files, before.queue, next.cursor, next.position, next.stamp)
            } else {
                next
            }
            before != null
        }
        if (alreadyInLine) return
        steps.trySend {
            // Whatever is newest by the time its turn comes.
            val newest = synchronized(waiting) { waiting.remove(next.files.key) } ?: return@trySend
            if (newest.queue != null) {
                newest.files.save(newest.queue.copy(cursor = newest.cursor, position = newest.position, stamp = newest.stamp))
            } else {
                newest.files.savePlace(newest.cursor, newest.position, newest.stamp)
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
 * One account's saved queue, as the playback service uses it: read once when the account is
 * taken on, then written as the queue changes.
 *
 * Until that first read has finished, what is on disk isn't known, and the player's queue
 * (empty, at that moment) is not the account's queue: nothing is written. "Not read yet" and
 * "read, and there was nothing" are different things; only after the second may an empty
 * queue be saved over what was there.
 */
class KeptQueue(private val files: QueueFiles, private val saver: QueueSaver) {
    /** The saved queue has been read (whether or not there was one). */
    @Volatile
    var restored = false
        private set

    /** Reads the saved queue; null if there is none, or it doesn't hold together. */
    suspend fun restore(): SavedQueue? {
        val saved = saver.load(files)
        restored = true
        return saved
    }

    /** The queue's revision when it was last handed over whole. */
    private var savedRevision = -1L

    /** Saves the queue as `snapshot` gives it; does nothing (and takes no snapshot) before
     *  [restore] has finished. Returns whether it was handed to the writer. */
    fun save(snapshot: () -> SavedQueue): Boolean {
        if (!restored) return false
        savedRevision = -1
        saver.save(files, snapshot())
        return true
    }

    /**
     * As [save], for a queue that says when it has changed (`revision`, see
     * [Queue.revision]): the snapshot is only taken when it has. Otherwise only the place
     * (`cursor`, `position`) is handed over, which copies nothing, however long the queue.
     */
    fun save(revision: Long, cursor: Int, position: Double, stamp: Long, snapshot: () -> SavedQueue): Boolean {
        if (!restored) return false
        if (revision != savedRevision) {
            saver.save(files, snapshot())
            savedRevision = revision
        } else {
            saver.savePlace(files, cursor, position, stamp)
        }
        return true
    }
}
