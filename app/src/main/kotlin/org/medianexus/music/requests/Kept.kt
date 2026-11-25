package org.medianexus.music.requests

import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.store.writeAtomically

/**
 * One small file of an account's requests, kept as the phone's other stores are:
 *
 * - **written whole**: beside its place, onto the disk, moved into place, the folder synced;
 * - **an unreadable one is never taken for an absent one**. No file is nothing kept. A file
 *   that is there and can't be read is left exactly as it is: [read] says so (null), and
 *   nothing is written over it for as long as that lasts. A file that reads and is not
 *   what this app writes (cut short, say) is kept beside its place (`<name>.damaged`)
 *   before anything is written where it was.
 */
internal class Kept<T>(private val file: File, private val shape: KSerializer<T>, private val nothing: T, private val note: (String) -> Unit = {}) {
    private var damaged = false
    private var unreadable = false

    /** What is kept; [nothing] if nothing is; null if it is there and couldn't be read. */
    @Synchronized
    fun read(): T? {
        val text = try {
            String(java.nio.file.Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (_: java.nio.file.NoSuchFileException) {
            unreadable = false
            return nothing
        } catch (_: IOException) {
            if (!unreadable) note("requests: ${file.name} is on the phone and couldn't be read, so it is left exactly as it is")
            unreadable = true
            return null
        }
        unreadable = false
        return try {
            json.decodeFromString(shape, text)
        } catch (_: IllegalArgumentException) {
            damaged = text.isNotBlank()
            nothing
        }
    }

    /** Keeps `value` in its place. False if it couldn't be written, or may not be (the file
     *  there couldn't be read, or what it held couldn't be kept aside first). */
    @Synchronized
    fun write(value: T): Boolean {
        if (unreadable) return false
        return try {
            if (damaged) {
                java.nio.file.Files.copy(file.toPath(), File(file.path + ".damaged").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                damaged = false
                note("requests: ${file.name} was not what this app writes; it was kept aside as ${file.name}.damaged")
            }
            writeAtomically(file, json.encodeToString(shape, value).toByteArray(), durable = true)
            true
        } catch (_: IOException) {
            note("requests: ${file.name} couldn't be written")
            false
        }
    }

    /** Whether anything is kept at all (readable or not). */
    fun exists(): Boolean = file.exists()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
