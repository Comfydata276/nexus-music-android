package org.medianexus.music.jam

import java.io.File
import org.medianexus.music.store.PasswordVault
import org.medianexus.music.store.SecretBox

/**
 * The relay's host key (rule 4), kept as the password is: sealed with a key held by the
 * Android Keystore, in a file of its own beside the password's (kept out of backups), and
 * sealed together with the account it belongs to, so another account signed in on this phone
 * can't open it. Never in the settings file, never in a log or Diagnostics, sent only to the
 * relay whose link it came with, and removed at sign-out (see [Jams.signedOut]).
 *
 * The password's own vault does the sealing: the key is sealed under "the account" exactly as
 * the password is, so the two can be told apart only by their files.
 */
class KeyVault(file: File, box: SecretBox) {
    private val vault = PasswordVault(file, box)
    private val where = file

    /** Keeps the key for this account. False if the phone wouldn't seal or write it. */
    fun save(server: String, username: String, key: String): Boolean = vault.save(server, username, key)

    /** The key for this account, or null (none kept, another account's, or it can't be opened). */
    fun load(server: String, username: String): String? = vault.load(server, username)

    /** Whether a key is kept for anyone at all (without opening it). */
    fun kept(): Boolean = where.exists()

    /** Removes it. True once it is gone. */
    fun delete(): Boolean = vault.delete()
}
