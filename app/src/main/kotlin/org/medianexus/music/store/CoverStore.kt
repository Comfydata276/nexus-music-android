package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.medianexus.music.net.ApiError

/**
 * Cover art, as the Windows app keeps it (src/covers.rs): each picture is fetched from the
 * server once at a size and kept on disk for the account; whatever shows it (Coil in the
 * app, the car and the system's media controls through [CoverProvider]) cuts it down to the
 * size it needs. Two requests for the same picture at once share one fetch, and a picture
 * that couldn't be fetched is left alone for a minute before it is asked for again.
 *
 * Two sizes, as Windows has more than one: [SIZE] for everything (rows, cards, the car), and
 * [LARGE] for Now Playing only, where a whole phone's width of cover would look soft at 640.
 *
 * `fetch` gets the picture's bytes from the server (the signed-in account's client).
 */
class CoverStore(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val fetch: suspend (id: String, size: Int) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The kept file for a cover, if it's on the phone already. Doesn't fetch. */
    fun kept(id: String, size: Int = SIZE): File? = fileFor(id, size)?.takeIf { it.isFile && it.length() > 0 }

    /**
     * The file holding a cover at `size` ([SIZE] or [LARGE]), fetched first if it isn't kept
     * yet. Null if there's no such cover or it can't be fetched now. Safe to call from many
     * places at once.
     */
    suspend fun file(id: String, size: Int = SIZE): File? {
        val file = fileFor(id, size) ?: return null
        if (withContext(io) { file.isFile && file.length() > 0 }) return file
        val key = file.name
        failedAt[key]?.let { at -> if (clock() - at < RETRY_AFTER_MILLIS) return null }
        val shared = inFlight.computeIfAbsent(key) {
            // On the store's scope, so a caller giving up doesn't stop the fetch for another.
            scope.async(io, start = CoroutineStart.LAZY) {
                try {
                    download(id, size, file)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        shared.start()
        return shared.await()
    }

    private suspend fun download(id: String, size: Int, file: File): File? {
        val key = file.name
        val bytes = try {
            fetch(id, size)
        } catch (_: ApiError) {
            failedAt[key] = clock()
            return null
        }
        if (!looksLikePicture(bytes)) {
            // The server sends a JSON error, or nothing, for a cover it doesn't have.
            failedAt[key] = clock()
            return null
        }
        return try {
            writeAtomically(file, bytes)
            failedAt.remove(key)
            file
        } catch (_: IOException) {
            null
        }
    }

    /** The server can be reached again: try the covers that failed straight away. */
    fun retryFailed() = failedAt.clear()

    /** Deletes the least lately written covers once they take more than `budget` bytes, down
     *  to three quarters of it, as Windows does. */
    suspend fun trim(budget: Long = DISK_BUDGET) = withContext(io) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext
        var total = files.sumOf { it.length() }
        if (total <= budget) return@withContext
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= budget * 3 / 4) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    internal fun fileFor(id: String, size: Int = SIZE): File? = fileName(id, size)?.let { File(dir, it) }

    companion object {
        /**
         * The size covers are fetched at, in pixels: the Windows app's largest (its Now Playing
         * art). Enough for the car, which asks for about 512; rows and cards are cut down from
         * it.
         */
        const val SIZE = 640

        /**
         * Now Playing's size: its cover is nearly the phone's width, over a thousand pixels on
         * a large phone. Fetched only for that screen, which shows the [SIZE] copy until it
         * arrives. Navidrome never makes a picture larger than its original.
         */
        const val LARGE = 1200

        const val RETRY_AFTER_MILLIS = 60_000L

        /** Covers kept at most, on disk, per account. */
        const val DISK_BUDGET = 300L * 1024 * 1024

        /**
         * The file name for a cover id, or null if the id is not one a server gives: only
         * letters, digits, '-' and '_', as Navidrome's are ("al-…_…"). Anything else is refused
         * rather than tidied, so nothing handed to [CoverProvider] can name a file outside the
         * folder.
         */
        fun fileName(id: String, size: Int = SIZE): String? =
            if (id.isNotEmpty() && id.length <= 160 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } && (size == SIZE || size == LARGE)) {
                "$id.$size"
            } else {
                null
            }

        /** JPEG, PNG, GIF, WebP, or BMP, by their first bytes. */
        internal fun looksLikePicture(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            fun at(i: Int) = bytes[i].toInt() and 0xff
            return (at(0) == 0xff && at(1) == 0xd8) ||
                (at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code) ||
                (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) ||
                (at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code) ||
                (at(0) == 'B'.code && at(1) == 'M'.code)
        }
    }
}
