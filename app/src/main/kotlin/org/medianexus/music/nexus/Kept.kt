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
import org.medianexus.music.net.Handed
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.writeAtomically

/**
 * **What the Nexus server's part of the app keeps, where secrets are kept** (the backend
 * design's section 11): sealed by the Keystore, in a folder left out of backups, as the saved
 * sign-in was before. Never the Nexus password; never a music server's password.
 *
 * - **One file, put in place in one step:** what signing in left (`client::Saved`: the Nexus
 *   origin and the backend's id, who the person is to it, the session, the music login with its
 *   number), and the sign-out notes still to be told (`client::Note`, each by its session). Each
 *   is kept **exactly as the shared core's machine wrote it** when it said to keep it
 *   (`client::Keep`), and handed back to it as it was at the next start: the phone reads nothing
 *   of them but what a cold start needs to play ([Parts.music], [Parts.account]). All the
 *   changes the machine asks for at once are written together, so a write cut short leaves the
 *   one before whole.
 * - **The device's own secret, beside it** (8.6), kept through signing out.
 */
class NexusKept(
    private val file: File,
    private val secretFile: File,
    private val box: SecretBox,
    /** The Diagnostics schedule (`client::DiagSchedule`), kept per DEVICE, not per account: it
     *  outlives signing out. Not a secret (times only). */
    private val scheduleFile: File = File(secretFile.parentFile, "nexus-diag-schedule.json"),
) {
    /** What is kept: `saved` (the machine's `Saved`, or none) and `notes`. */
    class Parts(val saved: JsonObject?, val notes: List<JsonObject>) {
        /** The music login, if one is kept. */
        val music: KeptMusic? get() = (saved?.get("music") as? JsonObject)?.let(KeptMusic::of)

        /** Which backend and as whom, if a sign-in is kept. */
        val account: KeptAccount? get() = saved?.let(KeptAccount::of)

        /** Whether a session is kept (not after "start again"). */
        val session: Boolean get() = saved?.get("session").let { it != null && it !is kotlinx.serialization.json.JsonNull }

        fun withSaved(next: JsonObject?) = Parts(next, notes)

        /** A note kept by the backend and session it ends: in place of one for the same. */
        fun withNote(note: JsonObject) = Parts(saved, notes.filter { keyOf(it) != keyOf(note) } + note)

        fun withoutNote(backendId: String, session: String) = Parts(saved, notes.filter { keyOf(it) != (backendId to session) })

        /** Without the note at this place (one that did not read, as the machine was handed it). */
        fun withoutNoteAt(index: Int) = Parts(saved, notes.filterIndexed { at, _ -> at != index })

        fun toJson(): JsonObject = JsonObject(buildMap {
            saved?.let { put("saved", it) }
            put("notes", kotlinx.serialization.json.JsonArray(notes))
        })

        override fun toString(): String = "Parts(saved: ${saved != null}, notes: ${notes.size})"

        companion object {
            val NONE = Parts(null, emptyList())

            /** The backend and the session a note ends (`backend_id`, `revoke.session`), as the
             *  machine wrote them. */
            fun keyOf(note: JsonObject): Pair<String?, String?> =
                (note["backend_id"] as? JsonPrimitive)?.content to ((note["revoke"] as? JsonObject)?.get("session") as? JsonPrimitive)?.content

            fun of(all: JsonObject) = Parts(
                all["saved"] as? JsonObject,
                (all["notes"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** What is kept; [Parts.NONE] if nothing is, or it can't be opened (sealed with a key since
     *  lost: then the person signs in again). */
    fun load(): Parts = synchronized(lock) {
        val plain = openFrom(file, box) ?: return Parts.NONE
        try {
            Parts.of(json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject)
        } catch (_: IllegalArgumentException) {
            Parts.NONE
        } finally {
            plain.fill(0)
        }
    }

    /** Puts `parts` in place of what was kept, in one step. True once it is on the disk. With
     *  nothing in it, the file goes. */
    fun save(parts: Parts): Boolean = synchronized(lock) {
        if (parts.saved == null && parts.notes.isEmpty()) return file.delete() || !file.exists()
        sealTo(file, parts.toJson().toString().toByteArray(Charsets.UTF_8), box)
    }

    /** Whether anything is kept: looked for, not opened (the Play button's receiver asks). */
    fun exists(): Boolean = file.isFile && file.length() > 0

    /** The device's own key, exactly as the machine wrote it (`client::DeviceKey`: the secret,
     *  and the backend it is for); null if none is kept, or it doesn't read (then the machine
     *  makes another when it is next needed). */
    fun deviceSecret(): JsonElement? {
        val plain = openFrom(secretFile, box) ?: return null
        return try {
            json.parseToJsonElement(String(plain, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            plain.fill(0)
        }
    }

    fun keepDeviceSecret(key: JsonElement): Boolean = sealTo(secretFile, key.toString().toByteArray(Charsets.UTF_8), box)

    fun forgetDeviceSecret(): Boolean = secretFile.delete() || !secretFile.exists()

    /** The Diagnostics schedule exactly as the machine wrote it (`Keep::DiagSchedule`); null
     *  if none is kept. One that does not read is handed back as it is: the machine says to
     *  forget it (`Forget::DiagSchedule`) and makes another. */
    fun diagSchedule(): JsonElement? = try {
        if (!scheduleFile.isFile) null else json.parseToJsonElement(scheduleFile.readText(Charsets.UTF_8))
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // (Bytes that are not JSON at all: handed over as a string, which the machine does not
        // read, so it says to forget it.)
        JsonPrimitive("unreadable")
    }

    fun keepDiagSchedule(schedule: JsonElement): Boolean = try {
        scheduleFile.parentFile?.mkdirs()
        writeAtomically(scheduleFile, schedule.toString().toByteArray(Charsets.UTF_8))
        true
    } catch (_: IOException) {
        false
    }

    fun forgetDiagSchedule(): Boolean = scheduleFile.delete() || !scheduleFile.exists()
}

/** A kept music login: what the player needs, and its number as the machine wrote it. */
class KeptMusic(val address: String, val name: String, val handed: Handed) {
    override fun toString(): String = "KeptMusic(..)"

    /** As the machine writes a music login, to hand back to the core. */
    fun toJson(): JsonObject = JsonObject(mapOf(
        "address" to JsonPrimitive(address), "name" to JsonPrimitive(name), "salt" to JsonPrimitive(handed.salt),
        "token" to JsonPrimitive(handed.token), "count" to Nexus.number(handed.count),
    ))

    /** The shared core's `carries_over`, for this login (false if the core can't say). */
    fun carriesOver(beforeAddress: String, beforeUser: String): Boolean = try {
        org.medianexus.music.core.Core.nexusCarriesOver(beforeAddress, beforeUser, toJson())
    } catch (e: org.medianexus.music.core.CoreException) {
        false
    }

    companion object {
        fun of(music: JsonObject): KeptMusic? {
            val address = music.text("address") ?: return null
            val name = music.text("name") ?: return null
            val salt = music.text("salt") ?: return null
            val token = music.text("token") ?: return null
            val count = (music["count"] as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: return null
            return KeptMusic(address, name, Handed(salt, token, count))
        }
    }
}

/** Who a kept sign-in is for: enough to find the person's folder. `account` is the backend's
 *  number for them, as the machine wrote it (never turned into a number here). */
class KeptAccount(val backend: String, val backendId: String, val account: String) {
    companion object {
        fun of(saved: JsonObject): KeptAccount? = KeptAccount(
            saved.text("nexus") ?: return null,
            saved.text("backend_id") ?: return null,
            (saved["account"] as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: return null,
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
         * they are to it (the account's number), in hex. It gives away neither, and is the same for
         * them on this phone.
         */
        fun personOf(account: KeptAccount): String =
            MessageDigest.getInstance("SHA-256").digest("nexus\n${account.backendId}\n${account.account}".toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
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
