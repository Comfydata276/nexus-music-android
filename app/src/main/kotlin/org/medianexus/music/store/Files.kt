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

/** `settings.json` with its extension changed, as Rust's `with_extension` does it:
 *  `settings.backup.json`, `settings.tmp`. */
fun siblingWithExtension(file: File, extension: String): File {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return File(file.parentFile, "$stem.$extension")
}
