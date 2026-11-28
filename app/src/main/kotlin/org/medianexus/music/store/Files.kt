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
fun writeAtomically(file: File, bytes: ByteArray, durable: Boolean = false) = writeAtomically(file, durable) { it.write(bytes) }

/**
 * As above, for something too big to hold in memory twice over: `write` puts it straight
 * into the file beside. (A 20,000-song queue is 12 MB of JSON; built as text and then as
 * bytes before being written, it was 36 MB more of the app's memory at each save.)
 *
 * `durable`: the new file must still be the one there after a power cut, because something
 * is about to rely on it (a record that a download is whole, a change waiting for the
 * server). The bytes reach the disk before the rename either way; with `durable` the folder
 * does too, after it ([syncDir]): without that the rename itself can be lost, and the old
 * file is back. If the folder can't be synced, a durable write **throws**: the new file is in
 * place, but nothing may be done on the strength of it. A cache (a page, a cover) doesn't
 * ask for it: the old one would do.
 */
@Throws(IOException::class)
fun writeAtomically(file: File, durable: Boolean = false, write: (java.io.OutputStream) -> Unit) {
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
        // (Refused, after the temp file is gone, if the file is one a sign-out has fenced.)
        Fence.putting(file) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    } catch (e: IOException) {
        temp.delete()
        throw e
    }
    // (A write asked to be durable that isn't is a write that failed: whoever asked is about
    // to rely on it.)
    if (durable) file.parentFile?.let { syncDirOrFail(it) }
}

/**
 * Makes what has just been done to a folder's list of files (one created, renamed into place,
 * or deleted) outlast a power cut: a file's own bytes reach the disk when the file is synced,
 * but its name is the folder's, and reaches the disk only when the folder is. Without it a
 * rename that "happened" can be undone by the battery going, with whatever was written
 * afterwards on the strength of it still there.
 *
 * False if the system wouldn't do it. **Whatever is about to be done on the strength of it
 * must then not be done** (a log emptied because the list that replaces it is in place, a
 * download marked because its file is): use [syncDirOrFail] where that is so.
 */
fun syncDir(dir: File): Boolean = folderSync(dir)

/** [syncDir], for a step that must not go on without it: throws if the folder couldn't be
 *  synced. */
@Throws(IOException::class)
fun syncDirOrFail(dir: File) {
    if (!syncDir(dir)) throw IOException("The folder couldn't be synced")
}

/** How a folder is synced (a test puts a failing one here, to see what then isn't done). */
@Volatile internal var folderSync: (File) -> Boolean = ::forceFolder

internal fun forceFolder(dir: File): Boolean = try {
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

/**
 * **What earlier versions kept of a music server's password, and the jam relay's key, removed
 * unread** (the backend design's section 11): the files themselves and what an interrupted write
 * of either left beside them (`.password.sealed.<run>-<n>.tmp`). Never opened. True once none is
 * left and the folder's list has reached the disk; false if one could not be removed (it is tried
 * again at the next start).
 */
fun sweepLegacySecrets(dir: File): Boolean {
    var gone = true
    var removed = false
    for (file in dir.listFiles().orEmpty()) {
        val name = file.name
        val legacy = LEGACY_SECRETS.any { name == it || (name.startsWith(".$it.") && name.endsWith(".tmp")) }
        if (!legacy) continue
        if (file.delete()) removed = true else if (file.exists()) gone = false
    }
    return gone && (!removed || syncDir(dir))
}

/** The earlier versions' secret files, by name. */
private val LEGACY_SECRETS = listOf("password.sealed", "relay-key.sealed")

/**
 * **What a sign-out removes stays removed** (the backend design's section 11): the queue and
 * Recently played of an account signed out of are deleted, and no writer still running for it
 * (the queue's saver, which outlives the account; a history write already under way; a service
 * saving the queue as it lets go) can put either back. A file is fenced by its folder and name;
 * every file put in place by [writeAtomically] is checked at its rename, under the same lock as
 * the deletion, so a rename is either before the deletion (and deleted by it) or refused.
 */
object Fence {
    private val closed = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun keyOf(dir: File): String = dir.absolutePath

    private fun lockOf(dir: File): Any = locks.getOrPut(keyOf(dir)) { Any() }

    /** Puts `file` in place by `move`, unless it is fenced: then nothing, and this throws. */
    @Throws(IOException::class)
    internal fun putting(file: File, move: () -> Unit) {
        val dir = file.absoluteFile.parentFile ?: return move()
        synchronized(lockOf(dir)) {
            if (closed[keyOf(dir)]?.contains(file.name) == true) throw IOException("Signed out: not put back")
            move()
        }
    }

    /** Fences these files of `dir` and deletes them, in one step under the fence. True if none
     *  is left (and the folder's list has reached the disk). */
    fun close(dir: File, names: Collection<String>): Boolean = synchronized(lockOf(dir)) {
        closed.merge(keyOf(dir), names.toSet()) { a, b -> a + b }
        var gone = true
        var removed = false
        for (name in names) {
            val one = File(dir, name)
            if (one.delete()) removed = true else if (one.exists()) gone = false
        }
        gone && (!removed || syncDir(dir))
    }

    /** The folder is an account's that is signed in again: its files may be written. */
    fun open(dir: File) {
        synchronized(lockOf(dir)) { closed.remove(keyOf(dir)) }
    }
}
