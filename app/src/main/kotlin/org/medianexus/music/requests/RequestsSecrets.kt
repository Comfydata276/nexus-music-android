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
 * **Each is sealed together with the place it was given for** ([Bound.origin]: Lidarr's
 * own scheme, host and port for the key, the sign-in service's for the token, as the
 * client that connects reads them). Whoever uses one compares that with where it is about
 * to be sent, so a secret can never be paired with another address by what happens to be
 * in a settings file: not by the app being stopped between two writes, not by one of them
 * failing.
 *
 * Neither is ever in a settings file, a log or Diagnostics. The token is replaced each time
 * the sign-in service replaces it (every renewal does), and the write must outlast a power
 * cut: the one it replaces no longer works.
 */
class RequestsSecrets(dir: File, private val box: SecretBox, server: String, username: String) {
    /** A secret, and the place it was given for. Never printed. */
    class Bound(val origin: String, val secret: String) {
        override fun toString(): String = "Bound"
    }

    private val who = accountKey(server, username)

    /** Whoever holds this may read, write and delete nothing more: what is kept is another
     *  owner's now ([cutOff]). */
    @Volatile private var cut = false

    /**
     * Cuts whoever holds this off from what is kept, **after forgetting both secrets**: for
     * an account that is being made again while its old self has still not finished
     * leaving (see `Accounts`). The key and the token go with the old self, as they do
     * when it leaves in good time, and nothing it does afterwards touches a file.
     */
    @Synchronized
    fun cutOff() {
        gone(keyFile)
        gone(tokenFile)
        cut = true
    }

    private val keyFile = File(dir, "lidarr-key.sealed")
    private val tokenFile = File(dir, "lidarr-token.sealed")

    /** Lidarr's key and where it is for, or null: none kept, another account's, one kept
     *  without its place, or it can't be opened. */
    fun key(): Bound? = open(keyFile)

    fun keepKey(origin: String, key: String): Boolean = seal(keyFile, origin, key)

    fun forgetKey(): Boolean = gone(keyFile)

    /** The sign-in's token and the service it is for, or null. */
    fun token(): Bound? = open(tokenFile)

    fun keepToken(origin: String, token: String): Boolean = seal(tokenFile, origin, token)

    fun forgetToken(): Boolean = gone(tokenFile)

    @Synchronized
    private fun gone(file: File): Boolean = !cut && (file.delete() || !file.exists())

    @Synchronized
    private fun seal(file: File, origin: String, secret: String): Boolean {
        if (cut) return false
        // (A place is one line. One that isn't can't have come from an address.)
        if (origin.isEmpty() || '\n' in origin) return false
        // (Wiped once used, as the password's are: the String it arrived as can't be.)
        val plain = "$who\n$origin\n$secret".toByteArray(Charsets.UTF_8)
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

    @Synchronized
    private fun open(file: File): Bound? {
        if (cut) return null
        val sealed = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val plain = box.open(sealed) ?: return null
        try {
            // The account, the place, the secret: a line each, and the secret is the rest.
            val parts = String(plain, Charsets.UTF_8).split('\n', limit = 3)
            if (parts.size != 3 || parts[0] != who || parts[1].isEmpty() || parts[2].isEmpty()) return null
            return Bound(parts[1], parts[2])
        } finally {
            plain.fill(0)
        }
    }
}
