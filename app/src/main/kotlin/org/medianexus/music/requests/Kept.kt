package org.medianexus.music.requests

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.store.writeAtomically

/**
 * One small file of an account's requests, kept as the phone's other stores are:
 *
 * - **written whole**: beside its place, onto the disk, moved into place, the folder synced;
 * - **an unreadable one is never taken for an absent one**. No file is nothing kept, and
 *   neither is an empty one. A file that is there and can't be read is left exactly as it
 *   is: [read] says so (null), and nothing is written over it for as long as that lasts;
 * - **one the app can make nothing of is never written over** (cut short, say, or not
 *   this app's at all). As it is read it is moved aside under a name of its own
 *   ([setAside]: `asked.json` to `asked.damaged-20261126-101500.json`), which never
 *   replaces an earlier one, and [asideAs] says so, for whoever owns the file to tell the
 *   person. Only then is anything written where it was. If it can't be moved it is where
 *   it was, and is treated as one that can't be read. (The rule the Windows app has.)
 */
internal class Kept<T>(private val file: File, private val shape: KSerializer<T>, private val nothing: T, private val note: (String) -> Unit = {}) {
    private var unreadable = false

    /** The name the file was moved aside under, when it was found to be something this app
     *  can make nothing of; null if it never was, in this run. */
    @Volatile var asideAs: String? = null
        private set

    /** Whoever holds this may write nothing more: the file is another owner's now (see
     *  `Accounts`: an account made again while its old self was still leaving). */
    @Volatile var cut = false

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
        // (Nothing in it: nothing kept yet.)
        if (text.isBlank()) return nothing
        return try {
            json.decodeFromString(shape, text)
        } catch (_: IllegalArgumentException) {
            val aside = setAside(file)
            if (aside == null) {
                note("requests: ${file.name} is not what this app writes and couldn't be moved aside, so it is left exactly as it is")
                unreadable = true
                return null
            }
            asideAs = aside.name
            note("requests: ${file.name} was not what this app writes; it has been kept as ${aside.name}")
            nothing
        }
    }

    /** Keeps `value` in its place. False if it couldn't be written, or may not be (the file
     *  there couldn't be read, or couldn't be moved aside). */
    @Synchronized
    fun write(value: T): Boolean {
        if (unreadable || cut) return false
        return try {
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

/**
 * A file of an account's requests **that the library writes and reads itself**, as bytes
 * (`asking.json`: what must be put back on Lidarr), kept by the rules [Kept] keeps the
 * others by, which are the Windows app's for the same file:
 *
 * - nothing there, or an empty file (removed): nothing kept;
 * - there and can't be read: left exactly as it is, and **nothing is written in this run**;
 * - reads and the library can make nothing of it: moved aside under a name of its own
 *   ([setAside]), [asideAs] says so, and only then may it be written afresh; if it can't
 *   be moved it is left, and nothing is written in this run.
 *
 * [write] says truthfully whether what it was given is in place (and, asked to be sure,
 * on the disk): the library changes nothing on Lidarr on the strength of a "no".
 */
internal class KeptAsWritten(private val file: File, private val note: (String) -> Unit = {}) {
    /** Nothing is written in this run: what is there couldn't be read, or couldn't be
     *  moved aside. */
    @Volatile var held = false
        private set

    /** The name what was there was moved aside under, if the library could make nothing
     *  of it; null if that never happened in this run. */
    @Volatile var asideAs: String? = null
        private set

    /** As [Kept.cut]. */
    @Volatile var cut = false

    /** Hands what is written there to `load`, which says whether it could make sense of
     *  it, and does with the file what the rules above say. Once, as the account starts. */
    @Synchronized
    fun takeUp(load: (ByteArray) -> Boolean) {
        val written = try {
            java.nio.file.Files.readAllBytes(file.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            return
        } catch (_: IOException) {
            held = true
            note("requests: ${file.name} is on the phone and couldn't be read, so it is left exactly as it is, and nothing is written over it")
            return
        }
        if (written.all { it.toInt().toChar().isWhitespace() }) {
            if (!file.delete()) held = true
            return
        }
        if (load(written)) return
        val aside = setAside(file)
        if (aside == null) {
            held = true
            note("requests: ${file.name} is not what the library writes and couldn't be moved aside, so it is left exactly as it is")
        } else {
            asideAs = aside.name
            note("requests: ${file.name} was not what the library writes; it has been kept as ${aside.name}")
        }
    }

    /** Puts `bytes` in its place (`surely`: and on the disk, before this returns), or with
     *  none removes what is there. False if it isn't done, or may not be. */
    @Synchronized
    fun write(bytes: ByteArray?, surely: Boolean): Boolean {
        if (held || cut) return false
        return try {
            if (bytes == null) java.nio.file.Files.deleteIfExists(file.toPath()) else writeAtomically(file, bytes, durable = surely)
            true
        } catch (_: IOException) {
            note("requests: ${file.name} couldn't be written")
            false
        }
    }
}

/**
 * Moves a file the app can make nothing of aside, beside where it was, under a name of its
 * own: `asked.json` to `asked.damaged-20261126-101500.json`, with `-2` and so on after the
 * time if that name is taken. **Never over another file.** Says where it went; null if it
 * couldn't be moved, and then it is where it was.
 */
internal fun setAside(file: File, now: LocalDateTime = LocalDateTime.now()): File? {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    val time = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    for (count in 1 until 100) {
        val aside = File(file.parentFile, "$stem.damaged-$time${if (count == 1) "" else "-$count"}$extension")
        try {
            // (Asked to replace nothing: a name that is taken is refused, not written over.)
            java.nio.file.Files.move(file.toPath(), aside.toPath())
            return aside
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            continue
        } catch (_: IOException) {
            return null
        }
    }
    return null
}
