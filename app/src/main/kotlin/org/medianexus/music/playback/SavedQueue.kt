package org.medianexus.music.playback

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    /** Whether it holds together: a queue that doesn't (a file cut short, edited by hand) is
     *  not put back. */
    val valid: Boolean
        get() = songs.isNotEmpty() && order.size == songs.size && cursor in order.indices && order.all { it in songs.indices }

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
class QueueFiles(private val queueFile: File, private val placeFile: File) {
    private var savedShape: ULong? = null

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
                writeAtomically(queueFile, json.encodeToString(SavedQueue.serializer(), queue).toByteArray())
                savedShape = shape
            }
            val place = QueuePlace(shape, queue.cursor, queue.position, queue.stamp)
            writeAtomically(placeFile, json.encodeToString(QueuePlace.serializer(), place).toByteArray())
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
        json.decodeFromString(serializer, file.readText())
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
    }
}
