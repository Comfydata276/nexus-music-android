package org.medianexus.music.nexus

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.medianexus.music.net.Handed
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.writeAtomically

/**
 * **What the Nexus server's part of the app keeps, where secrets are kept** (the backend
 * design's section 11): sealed by the Keystore, in a folder left out of backups, as the saved
 * sign-in was before. Never the Nexus password; never a music server's password.
 *
 * - **One file, put in place in one step:** the session, the music login with its number, the
 *   account (which backend, as whom, and the numbers last seen), and a sign-out the backend is
 *   still to be told of. Each part is kept **exactly as the shared core's machine wrote it** when
 *   it said to keep it (`client::Keep`), and handed back to it as it was at the next start: the
 *   phone reads nothing of them but what a cold start needs to play ([Parts.music],
 *   [Parts.account]). All the changes the machine asks for at once are written together, so a
 *   write cut short leaves the one before whole.
 * - **The device's own secret, beside it** (8.6): made once, never forgotten, so that signing in
 *   again from this phone is the same device to the backend.
 */
class NexusKept(private val file: File, private val secretFile: File, private val box: SecretBox) {
    /** What is kept, part by part, as the machine wrote each. */
    class Parts(val all: JsonObject) {
        /** The music login, if one is kept (`client::Keep::Music`). */
        val music: KeptMusic? get() = (all["music"] as? JsonObject)?.let(KeptMusic::of)

        /** Which backend and as whom (`client::AccountFile`), if kept. */
        val account: KeptAccount? get() = (all["account"] as? JsonObject)?.let(KeptAccount::of)

        val session: Boolean get() = all["session"] != null

        fun with(part: String, value: JsonElement) = Parts(JsonObject(all + (part to value)))
        fun without(part: String) = Parts(JsonObject(all - part))

        override fun toString(): String = "Parts(${all.keys})"

        companion object {
            val NONE = Parts(JsonObject(emptyMap()))
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** What is kept; [Parts.NONE] if nothing is, or it can't be opened (sealed with a key since
     *  lost: then the person signs in again). */
    fun load(): Parts = synchronized(lock) {
        val plain = openFrom(file, box) ?: return Parts.NONE
        try {
            Parts(json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject)
        } catch (_: IllegalArgumentException) {
            Parts.NONE
        } finally {
            plain.fill(0)
        }
    }

    /** Puts `parts` in place of what was kept, in one step. True once it is on the disk. With
     *  nothing in it, the file goes. */
    fun save(parts: Parts): Boolean = synchronized(lock) {
        if (parts.all.isEmpty()) return file.delete() || !file.exists()
        sealTo(file, parts.all.toString().toByteArray(Charsets.UTF_8), box)
    }

    /** Whether anything is kept: looked for, not opened (the Play button's receiver asks). */
    fun exists(): Boolean = file.isFile && file.length() > 0

    /** The device's own secret, as the machine wrote it (hex); null if none is kept. */
    fun deviceSecret(): String? {
        val plain = openFrom(secretFile, box) ?: return null
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun keepDeviceSecret(hex: String): Boolean = sealTo(secretFile, hex.toByteArray(Charsets.UTF_8), box)
}

/** A kept music login: what the player needs, and its number. */
class KeptMusic(val address: String, val name: String, val handed: Handed) {
    override fun toString(): String = "KeptMusic(..)"

    companion object {
        fun of(music: JsonObject): KeptMusic? {
            val address = music.text("address") ?: return null
            val name = music.text("name") ?: return null
            val salt = music.text("salt") ?: return null
            val token = music.text("token") ?: return null
            val count = (music["count"] as? JsonPrimitive)?.longOrNull ?: return null
            return KeptMusic(address, name, Handed(salt, token, count))
        }
    }
}

/** The kept account's who and where: enough to find the person's folder. */
class KeptAccount(val backend: String, val backendId: String, val email: String) {
    companion object {
        fun of(account: JsonObject): KeptAccount? = KeptAccount(
            account.text("backend") ?: return null,
            account.text("backend_id") ?: return null,
            account.text("email") ?: return null,
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * **Whose folder is whose** (the backend design's section 11): what the phone keeps for a person
 * (downloads, settings, the queue) is kept under who they are to the backend, never under the
 * music server's name and a user's name, which two people can share.
 *
 * **What is carried over, exactly, and once:** the first time anyone signs in on this version,
 * if the music login handed to them names the same music server (its address as the app tidies
 * it) and the same user as the sign-in this phone had before, that folder becomes theirs: its
 * name is kept for them in [carried], and the earlier sign-in is forgotten so that nobody else
 * is ever given it. Anyone else, and anyone after, has a folder of their own.
 */
class Folders(private val carried: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Carried(val person: String, val folder: String)

    /** The folder for `person` ([personOf]): the one carried over to them, or their own. */
    fun of(person: String): String = carriedTo(person) ?: person

    private fun carriedTo(person: String): String? = try {
        json.decodeFromString(Carried.serializer(), carried.readText()).takeIf { it.person == person }?.folder
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Gives `folder` to `person`, for good. True once it is on the disk. */
    fun carry(person: String, folder: String): Boolean = try {
        carried.parentFile?.mkdirs()
        writeAtomically(carried, json.encodeToString(Carried.serializer(), Carried(person, folder)).toByteArray(Charsets.UTF_8))
        true
    } catch (_: IOException) {
        false
    }

    companion object {
        /**
         * A person's own folder name: the first half of a SHA-256 of the backend's id and who
         * they are to it, in hex. It gives away neither, and is the same for them on this phone.
         * (Keyed by the email the backend stamps its answers with until the machine's account
         * file carries the account's own number: see the report of P7.)
         */
        fun personOf(account: KeptAccount): String =
            MessageDigest.getInstance("SHA-256").digest("nexus\n${account.backendId}\n${account.email}".toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Seals `plain` into `file`, whole or not at all. `plain` is wiped after. */
private fun sealTo(file: File, plain: ByteArray, box: SecretBox): Boolean = try {
    file.parentFile?.mkdirs()
    try {
        writeAtomically(file, box.seal(plain))
    } finally {
        plain.fill(0)
    }
    true
} catch (_: IOException) {
    false
} catch (_: GeneralSecurityException) {
    false
} catch (_: ProviderException) {
    // The Keystore failing in a way it doesn't declare, as some phones' do.
    false
}

private fun openFrom(file: File, box: SecretBox): ByteArray? {
    val sealed = try {
        file.readBytes()
    } catch (_: IOException) {
        return null
    }
    return box.open(sealed)
}
