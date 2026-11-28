package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Who is signed in, and where what's kept for them lives on the phone. */
data class Account(
    /** The music server, and the user there, of the music login the Nexus server handed over. */
    val server: String,
    val username: String,
    /** The person's folder name (`nexus/Kept.kt`, `Folders`): who they are to the Nexus server,
     *  or the folder carried over to them. It gives away neither, and never leaves the app (a
     *  cover's address has `artworkIdOf` instead). */
    val key: String,
    val dirs: AccountDirs,
)

/**
 * Each account's own folders: downloads, kept songs, cached pages and changes waiting for
 * the server go in here, so signing in as someone else never mixes theirs with yours.
 */
data class AccountDirs(
    /** Kept until the account's data is deleted: downloads, held changes, the library copy. */
    val data: File,
    /** The system may clear this when space runs short: cached pages and covers. */
    val cache: File,
)

/** The folder name earlier versions gave an account: the first half of a SHA-256 of the
 *  server and username, in hex. Now only the folder that can be carried over (`Folders`), and
 *  where requests' secrets are kept apart by account. */
fun accountKey(server: String, username: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$server\n$username".toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

/**
 * The account's name in the addresses of its covers (`content://…covers/<this>/<cover id>`),
 * which every app let in to the player is given (a car, the system's media controls). Made
 * at random the first time it is wanted, and kept with the account's data, so the addresses
 * a car keeps go on working; never derived from the account. Not [accountKey]: that is an
 * unkeyed hash of the server and username, the same on every phone, which an app that sees
 * it could match between phones, or hash a guess and confirm it. This says nothing but
 * "the same account as before, on this phone".
 */
fun artworkIdOf(dataDir: File): String {
    val file = File(dataDir, ARTWORK_ID_FILE)
    val kept = try {
        file.readText().trim()
    } catch (_: IOException) {
        ""
    }
    if (kept.length == 32 && kept.all { it in '0'..'9' || it in 'a'..'f' }) return kept
    val made = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    try {
        dataDir.mkdirs()
        writeAtomically(file, made.toByteArray(Charsets.UTF_8))
    } catch (_: IOException) {
        // Not kept: this run's addresses still work, and the next run's are new ones.
    }
    return made
}

private const val ARTWORK_ID_FILE = "artwork-id"

fun accountDirs(filesRoot: File, cacheRoot: File, key: String) =
    AccountDirs(data = File(filesRoot, "accounts/$key"), cache = File(cacheRoot, "accounts/$key"))

/** Seals bytes so only this app on this phone can open them again. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Null if the bytes can't be opened: tampered with, or sealed with a key since lost. */
    fun open(sealed: ByteArray): ByteArray?
}
