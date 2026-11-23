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
fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = siblingWithExtension(file, "tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * As above, for something too big to hold in memory twice over: `write` puts it straight
 * into the file beside. (A 20,000-song queue is 12 MB of JSON; built as text and then as
 * bytes before being written, it was 36 MB more of the app's memory at each save.)
 */
@Throws(IOException::class)
fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val temp = siblingWithExtension(file, "tmp")
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
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** `settings.json` with its extension changed, as Rust's `with_extension` does it:
 *  `settings.backup.json`, `settings.tmp`. */
fun siblingWithExtension(file: File, extension: String): File {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return File(file.parentFile, "$stem.$extension")
}
