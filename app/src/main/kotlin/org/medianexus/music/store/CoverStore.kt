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
 * server once, at one size, and kept on disk for the account; whatever shows it (Coil in the
 * app, the car and the system's media controls through [CoverProvider]) cuts it down to the
 * size it needs. Two requests for the same picture at once share one fetch, and a picture
 * that couldn't be fetched is left alone for a minute before it is asked for again.
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
    fun kept(id: String): File? = fileFor(id)?.takeIf { it.isFile && it.length() > 0 }

    /**
     * The file holding a cover, fetched first if it isn't kept yet. Null if there's no such
     * cover or it can't be fetched now. Safe to call from many places at once.
     */
    suspend fun file(id: String): File? {
        val file = fileFor(id) ?: return null
        if (withContext(io) { file.isFile && file.length() > 0 }) return file
        failedAt[id]?.let { at -> if (clock() - at < RETRY_AFTER_MILLIS) return null }
        val shared = inFlight.computeIfAbsent(id) {
            // On the store's scope, so a caller giving up doesn't stop the fetch for another.
            scope.async(io, start = CoroutineStart.LAZY) {
                try {
                    download(id, file)
                } finally {
                    inFlight.remove(id)
                }
            }
        }
        shared.start()
        return shared.await()
    }

    private suspend fun download(id: String, file: File): File? {
        val bytes = try {
            fetch(id, SIZE)
        } catch (_: ApiError) {
            failedAt[id] = clock()
            return null
        }
        if (!looksLikePicture(bytes)) {
            // The server sends a JSON error, or nothing, for a cover it doesn't have.
            failedAt[id] = clock()
            return null
        }
        return try {
            writeAtomically(file, bytes)
            failedAt.remove(id)
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

    internal fun fileFor(id: String): File? = fileName(id)?.let { File(dir, it) }

    companion object {
        /**
         * The one size every cover is fetched at, in pixels. The Windows app's largest (its Now
         * Playing art): enough for the car, which asks for about 512, and for the phone's own
         * Now Playing; rows and cards are cut down from it.
         */
        const val SIZE = 640

        const val RETRY_AFTER_MILLIS = 60_000L

        /** Covers kept at most, on disk, per account. */
        const val DISK_BUDGET = 300L * 1024 * 1024

        /**
         * The file name for a cover id, or null if the id is not one a server gives: only
         * letters, digits, '-' and '_', as Navidrome's are ("al-…_…"). Anything else is refused
         * rather than tidied, so nothing handed to [CoverProvider] can name a file outside the
         * folder.
         */
        fun fileName(id: String): String? =
            if (id.isNotEmpty() && id.length <= 160 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
                "$id.$SIZE"
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
