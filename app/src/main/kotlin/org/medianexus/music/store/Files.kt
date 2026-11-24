package org.medianexus.music.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces `file` with `bytes` so that, whatever happens part-way (the app killed, the
 * battery gone), the file afterwards is either the old one or the new one, never half of
 * each: the bytes go to a file beside it, reach the disk, and then take its place.
 */
@Throws(IOException::class)
fun writeAtomically(file: File, bytes: ByteArray) = writeAtomically(file) { it.write(bytes) }

/**
 * As above, for something too big to hold in memory twice over: `write` puts it straight
 * into the file beside. (A 20,000-song queue is 12 MB of JSON; built as text and then as
 * bytes before being written, it was 36 MB more of the app's memory at each save.)
 */
@Throws(IOException::class)
fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val temp = temporaryFor(file)
    try {
        FileOutputStream(temp).use { out ->
            val buffered = out.buffered(64 * 1024)
            write(buffered)
            buffered.flush()
            out.fd.sync()
        }
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
    try {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
        temp.delete()
        throw e
    }
}

/**
 * Makes what has just been done to a folder's list of files (one created, renamed into place,
 * or deleted) outlast a power cut: a file's own bytes reach the disk when the file is synced,
 * but its name is the folder's, and reaches the disk only when the folder is. Without it a
 * rename that "happened" can be undone by the battery going, with whatever was written
 * afterwards on the strength of it still there.
 *
 * False if the system wouldn't do it (nothing here can do more about that than say so).
 */
fun syncDir(dir: File): Boolean = try {
    java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    true
} catch (_: IOException) {
    false
} catch (_: UnsupportedOperationException) {
    false
}

/**
 * A file beside `file` for one write to go to first, that no other write uses: two writes at
 * once (a cover's two sizes, say, or two saves of one page) each have their own, so neither
 * can write into the other's or move the other's half-written bytes into place. Named after
 * the whole name ("al-1.640", not "al-1"), and starting with a dot, so nothing that lists a
 * folder's files mistakes it for one of them for long, and a sweep can find what an
 * interrupted write left ([sweepTemporaries]).
 */
internal fun temporaryFor(file: File): File =
    File(file.parentFile, ".${file.name}.$RUN-${java.lang.Long.toHexString(serial.incrementAndGet())}.tmp")

private val serial = java.util.concurrent.atomic.AtomicLong()

/** This run of the app, in its temporary files' names: what a sweep leaves alone. */
private val RUN = java.lang.Long.toHexString(java.security.SecureRandom().nextLong() and Long.MAX_VALUE)

/** Deletes what writes cut short (the app killed part-way) left in `dir`: an earlier run's
 *  temporary files, never this run's, which may be being written. */
fun sweepTemporaries(dir: File) {
    val ours = ".$RUN-"
    dir.listFiles()?.forEach { if (it.isFile && it.name.startsWith('.') && it.name.endsWith(".tmp") && ours !in it.name) it.delete() }
}

/** `settings.json` with its extension changed, as Rust's `with_extension` does it:
 *  `settings.backup.json`, `settings.damaged.json`. */
fun siblingWithExtension(file: File, extension: String): File {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return File(file.parentFile, "$stem.$extension")
}
