package org.medianexus.music.requests

import java.io.File
import java.io.IOException
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountKey
import org.medianexus.music.store.writeAtomically

/**
 * Lidarr's key, and the token of the sign-in in front of it: kept **as the saved password is
 * kept**, sealed with a key held by the Android Keystore, in files beside the password's
 * (out of backups, which this app has none of anyway), and sealed together with the account
 * they belong to, so that another account signed in on this phone can't open them. One
 * folder for each account.
 *
 * Neither is ever in a settings file, a log or Diagnostics. The token is replaced each time
 * the sign-in service replaces it (every renewal does), and the write must outlast a power
 * cut: the one it replaces no longer works.
 */
class RequestsSecrets(dir: File, private val box: SecretBox, server: String, username: String) {
    private val who = accountKey(server, username)
    private val keyFile = File(dir, "lidarr-key.sealed")
    private val tokenFile = File(dir, "lidarr-token.sealed")

    /** Lidarr's key, or null: none kept, another account's, or it can't be opened. */
    fun key(): String? = open(keyFile)

    fun keepKey(key: String): Boolean = seal(keyFile, key)

    fun forgetKey(): Boolean = gone(keyFile)

    /** The sign-in's token, or null. */
    fun token(): String? = open(tokenFile)

    fun keepToken(token: String): Boolean = seal(tokenFile, token)

    fun forgetToken(): Boolean = gone(tokenFile)

    private fun gone(file: File): Boolean = file.delete() || !file.exists()

    private fun seal(file: File, secret: String): Boolean {
        // (Wiped once used, as the password's are: the String it arrived as can't be.)
        val plain = "$who\n$secret".toByteArray(Charsets.UTF_8)
        return try {
            writeAtomically(file, box.seal(plain), durable = true)
            true
        } catch (_: IOException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        } catch (_: java.security.ProviderException) {
            // The Keystore failing in a way it doesn't declare, as some phones' do.
            false
        } finally {
            plain.fill(0)
        }
    }

    private fun open(file: File): String? {
        val sealed = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val plain = box.open(sealed) ?: return null
        try {
            val newline = plain.indexOf('\n'.code.toByte())
            if (newline < 0 || String(plain, 0, newline, Charsets.UTF_8) != who) return null
            return String(plain, newline + 1, plain.size - newline - 1, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } finally {
            plain.fill(0)
        }
    }
}
