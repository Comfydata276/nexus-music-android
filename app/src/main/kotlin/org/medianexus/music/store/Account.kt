package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Who is signed in, and where what's kept for them lives on the phone. */
data class Account(
    val server: String,
    val username: String,
    /** A name for the account that can be part of a file's name. It gives away neither the
     *  server nor the username (see `accountKey`). */
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

/** The account's folder name: the first half of a SHA-256 of the server and username, in
 *  hex. The same account always gets the same folder. */
fun accountKey(server: String, username: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$server\n$username".toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

fun accountDirs(filesRoot: File, cacheRoot: File, key: String) =
    AccountDirs(data = File(filesRoot, "accounts/$key"), cache = File(cacheRoot, "accounts/$key"))

/** Seals bytes so only this app on this phone can open them again. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Null if the bytes can't be opened: tampered with, or sealed with a key since lost. */
    fun open(sealed: ByteArray): ByteArray?
}

/**
 * The account password, kept sealed in one file. What's sealed names the account it belongs
 * to, so a password is only ever handed back for the account it was saved for (as the
 * Windows app checks on platforms without a credential store).
 */
class PasswordVault(private val file: File, private val box: SecretBox) {
    // The plain bytes are wiped once used, so the password isn't left lying in memory in more
    // copies than it has to be (the String it arrives as can't be wiped).
    fun save(server: String, username: String, password: String): Boolean = try {
        val plain = "${accountKey(server, username)}\n$password".toByteArray(Charsets.UTF_8)
        try {
            writeAtomically(file, box.seal(plain))
        } finally {
            plain.fill(0)
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: java.security.GeneralSecurityException) {
        false
    } catch (_: java.security.ProviderException) {
        // The Keystore failing in a way it doesn't declare, as some phones' do.
        false
    }

    fun load(server: String, username: String): String? {
        val sealed = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val plain = box.open(sealed) ?: return null
        try {
            // The account's key, a newline, then the password: only the password becomes text.
            val newline = plain.indexOf('\n'.code.toByte())
            if (newline < 0) return null
            val who = String(plain, 0, newline, Charsets.UTF_8)
            if (who != accountKey(server, username)) return null
            return String(plain, newline + 1, plain.size - newline - 1, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun delete() {
        file.delete()
    }
}
