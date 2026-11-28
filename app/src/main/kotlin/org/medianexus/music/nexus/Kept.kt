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
    /**
     * What is kept: `saved` (the machine's `Saved`, or none) and `notes`. **The notes are held by
     * their places as they were read** (a slot may be anything the file held: what does not read
     * is handed to the machine as it is, which names it by its place, `Forget::UnreadableNote`);
     * a slot forgotten is emptied, never shifted, so a second removal names the slot it meant.
     */
    class Parts(val saved: JsonObject?, val slots: List<JsonElement?>) {
        /** The notes that are notes (objects), in their order. */
        val notes: List<JsonObject> get() = slots.mapNotNull { it as? JsonObject }

        /** Every slot still held, as it was read: what the machine is handed at start. */
        val raw: List<JsonElement> get() = slots.filterNotNull()

        /** The music login, if one is kept. */
        val music: KeptMusic? get() = (saved?.get("music") as? JsonObject)?.let(KeptMusic::of)

        /** Which backend and as whom, if a sign-in is kept. */
        val account: KeptAccount? get() = saved?.let(KeptAccount::of)

        /** Whether a session is kept (not after "start again"). */
        val session: Boolean get() = saved?.get("session").let { it != null && it !is kotlinx.serialization.json.JsonNull }

        fun withSaved(next: JsonObject?) = Parts(next, slots)

        /** A note kept by the backend and session it ends: in place of one for the same. */
        fun withNote(note: JsonObject) = Parts(saved, slots.map { if (it is JsonObject && keyOf(it) == keyOf(note)) null else it } + note)

        fun withoutNote(backendId: String, session: String) = Parts(saved, slots.map { if (it is JsonObject && keyOf(it) == (backendId to session)) null else it })

        /** Without the slot at this place as it was handed to the machine (one that did not read). */
        fun withoutNoteAt(index: Int) = Parts(saved, slots.mapIndexed { at, it -> if (at == index) null else it })

        /** The same, with the emptied slots let go (once what the machine was handed is done with). */
        fun compact() = Parts(saved, slots.filterNotNull())

        fun toJson(): JsonObject = JsonObject(buildMap {
            saved?.let { put("saved", it) }
            put("notes", kotlinx.serialization.json.JsonArray(slots.filterNotNull()))
        })

        val empty: Boolean get() = saved == null && slots.all { it == null }

        override fun toString(): String = "Parts(saved: ${saved != null}, notes: ${slots.count { it != null }})"

        companion object {
            val NONE = Parts(null, emptyList())

            /** The backend and the session a note ends (`backend_id`, `revoke.session`), as the
             *  machine wrote them. */
            fun keyOf(note: JsonObject): Pair<String?, String?> =
                (note["backend_id"] as? JsonPrimitive)?.content to ((note["revoke"] as? JsonObject)?.get("session") as? JsonPrimitive)?.content

            fun of(all: JsonObject) = Parts(
                all["saved"] as? JsonObject,
                (all["notes"] as? kotlinx.serialization.json.JsonArray).orEmpty().toList(),
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** What is kept; [Parts.NONE] if nothing is. **A file there that can't be opened or read**
     *  (sealed with a key since lost, or damaged) **is retired as absent**: removed, so that its
     *  old bytes are not taken up again later; the person signs in again. */
    fun load(): Parts = synchronized(lock) {
        if (!file.exists()) return Parts.NONE
        val plain = openFrom(file, box)
        val parts = try {
            plain?.let { Parts.of(json.parseToJsonElement(String(it, Charsets.UTF_8)).jsonObject) }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            plain?.fill(0)
        }
        if (parts == null) removeDurably(file)
        parts ?: Parts.NONE
    }

    /** Puts `parts` in place of what was kept, in one step, **durably** (the folder synced after
     *  the rename: a power cut does not bring the one before back). True once it is on the disk.
     *  With nothing in it, the file goes, as durably. */
    fun save(parts: Parts): Boolean = synchronized(lock) {
        if (parts.empty) return removeDurably(file)
        sealTo(file, parts.toJson().toString().toByteArray(Charsets.UTF_8), box)
    }

    /** Whether anything is kept: looked for, not opened (the Play button's receiver asks). */
    fun exists(): Boolean = file.isFile && file.length() > 0

    /** The device's own key, exactly as the machine wrote it (`client::DeviceKey`: the secret,
     *  and the backend it is for); null if none is kept, or it doesn't read (then the machine
     *  makes another when it is next needed). */
    fun deviceSecret(): JsonElement? {
        if (!secretFile.exists()) return null
        // (One there that can't be opened or read is handed over as something the machine does
        // not read: it then says to forget it, and it goes.)
        val plain = openFrom(secretFile, box) ?: return UNREADABLE
        return try {
            json.parseToJsonElement(String(plain, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            UNREADABLE
        } finally {
            plain.fill(0)
        }
    }

    fun keepDeviceSecret(key: JsonElement): Boolean = sealTo(secretFile, key.toString().toByteArray(Charsets.UTF_8), box)

    fun forgetDeviceSecret(): Boolean = removeDurably(secretFile)

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
        writeAtomically(scheduleFile, schedule.toString().toByteArray(Charsets.UTF_8), durable = true)
        true
    } catch (_: IOException) {
        false
    }

    fun forgetDiagSchedule(): Boolean = removeDurably(scheduleFile)

    private companion object {
        val UNREADABLE = JsonPrimitive("unreadable")
    }
}

/** Removes `file`, and its name from the folder's list on the disk. True once it is gone. */
private fun removeDurably(file: File): Boolean {
    val removed = file.delete()
    if (!removed && file.exists()) return false
    return !removed || file.parentFile?.let { org.medianexus.music.store.syncDir(it) } != false
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
 * **What is carried over, exactly, and once:** the first person to sign in on this version is
 * recorded at once (only who they are); at THAT person's first music login handed (at the
 * sign-in, or later, when the admin sets it), if it names the same music server (its address as
 * the app tidies it) and the same user as the sign-in this phone had before, that folder becomes
 * theirs: its name is kept for them in [carried], and the earlier sign-in is forgotten so that
 * nobody else is ever given it. Until then nothing of the earlier folder is read, shown, written
 * or cleared. Anyone else, and anyone after, has a folder of their own.
 */
class Folders(private val carried: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The decision, once made: whose it was (the first person to sign in on this version), and
     *  the earlier version's folder if it went to them (null: it went to nobody). `undecided`:
     *  only who is first is known; their first music login has not been handed yet. */
    @Serializable
    private class Carried(val person: String, val folder: String? = null, val undecided: Boolean = false)

    /** What [settle] left. */
    enum class Settled { NOT_KEPT, UNDECIDED, DECIDED }

    private sealed interface Read {
        data object Undecided : Read
        data object Unreadable : Read
        class Decided(val carried: Carried) : Read
    }

    private fun read(): Read {
        if (!carried.exists()) return Read.Undecided
        return try {
            Read.Decided(json.decodeFromString(Carried.serializer(), carried.readText()))
        } catch (_: IOException) {
            Read.Unreadable
        } catch (_: IllegalArgumentException) {
            Read.Unreadable
        }
    }

    /** The folder for `person` ([personOf]): the one carried over to them, or their own. **A
     *  decision that can't be read gives nobody the earlier folder** (closed, not open). */
    fun of(person: String): String = (read() as? Read.Decided)?.carried?.takeIf { it.person == person && !it.undecided }?.folder ?: person

    /**
     * **Section 11's carry-over, settled once, durably.** The first person to sign in on this
     * version (`person`) is recorded at their first kept sign-in, whatever was handed; the
     * comparison is made once, at that person's first music login (`music`, at the sign-in or
     * handed later): if the earlier version left a sign-in (`beforeServer`, `beforeUser`) and the
     * login names the same music server and user (the core's `carries_over`), that folder is
     * theirs for good; otherwise nobody's. Later sign-ins, other people and later logins never
     * change it. [Settled.DECIDED] once the decision is on the disk (or there was nothing to
     * decide, or it is another person's), [Settled.UNDECIDED] while the first person has no
     * music login yet, [Settled.NOT_KEPT] if it could not be kept.
     */
    fun settle(person: String, music: KeptMusic?, beforeServer: String, beforeUser: String, carriesOver: (String, String, KeptMusic) -> Boolean): Settled {
        when (val now = read()) {
            is Read.Decided -> when {
                !now.carried.undecided -> return Settled.DECIDED
                // (Another person: a folder of their own, and the earlier one still waits for the
                // first person's first login.)
                now.carried.person != person -> return Settled.UNDECIDED
            }
            // (Kept as it is, and nobody given the earlier folder: see [of].)
            Read.Unreadable -> return Settled.DECIDED
            Read.Undecided -> if (beforeServer.isEmpty() && beforeUser.isEmpty()) return Settled.DECIDED
        }
        val carried = when {
            music == null -> Carried(person, undecided = true)
            carriesOver(beforeServer, beforeUser, music) -> Carried(person, org.medianexus.music.store.accountKey(beforeServer, beforeUser))
            else -> Carried(person)
        }
        // (Already recorded as undecided for them, and still no login: nothing to write.)
        if (carried.undecided && read() is Read.Decided) return Settled.UNDECIDED
        return try {
            this.carried.parentFile?.mkdirs()
            writeAtomically(this.carried, json.encodeToString(Carried.serializer(), carried).toByteArray(Charsets.UTF_8), durable = true)
            if (carried.undecided) Settled.UNDECIDED else Settled.DECIDED
        } catch (_: IOException) {
            Settled.NOT_KEPT
        }
    }

    /** Gives `folder` to `person`, for good. True once it is on the disk. (For the tests.) */
    internal fun carry(person: String, folder: String): Boolean = try {
        carried.parentFile?.mkdirs()
        writeAtomically(carried, json.encodeToString(Carried.serializer(), Carried(person, folder)).toByteArray(Charsets.UTF_8), durable = true)
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
        writeAtomically(file, box.seal(plain), durable = true)
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
