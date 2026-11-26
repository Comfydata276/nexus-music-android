package org.medianexus.music.playback

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.medianexus.music.AccountGraph
import org.medianexus.music.Graph
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp

/**
 * What only the app knows, for [Linking]: who is signed in and to which server, the relay
 * and its key, what the phone is called, and where the two things linking keeps are kept
 * (the phone's id among the person's devices, made once, and the devices it has seen): one
 * small file in the app's own folder, outside backups, holding nothing secret.
 */
internal class LinkHost(private val context: Context, private val graph: Graph, private val signedIn: () -> AccountGraph?) : Linking.Host {
    private val file = File(context.noBackupFilesDir, "link.json")
    private val json = Json { ignoreUnknownKeys = true }

    private fun kept(): JsonObject = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun keep(change: (JsonObject) -> JsonObject) {
        val next = change(kept())
        runCatching {
            val beside = File(file.parentFile, file.name + ".new")
            beside.writeText(next.toString())
            beside.renameTo(file)
        }
    }

    override fun device(): Pair<String, String> {
        var id = kept()["device"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.length != ID_CHARS) {
            // Sixteen bytes from the system, as 22 characters: made once.
            id = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(java.security.SecureRandom()::nextBytes))
            keep { was -> JsonObject(was + ("device" to kotlinx.serialization.json.JsonPrimitive(id))) }
        }
        val chosen = graph.settings.value.deviceName.trim()
        val name = chosen.ifEmpty {
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()?.ifEmpty { null }
                ?: android.os.Build.MODEL.orEmpty().ifEmpty { "This phone" }
        }
        return id to name.take(MOST_NAME)
    }

    override fun account(): String? = signedIn()?.account?.let { "${it.username}@${it.server}" }

    override fun wanted(): Boolean = account() != null && graph.settings.value.linkDevices && relayFile(context).exists()

    override fun server(): Pair<Core.LinkServer, String>? {
        val now = signedIn() ?: return null
        val place = CoreHttp.originOf(now.account.server) ?: return null
        val (rest, form, username) = now.api.linkAccount()
        return Core.LinkServer(rest, form, username) to place
    }

    /**
     * **The relay and its key are one thing, kept as one** (`link-relay.sealed`, sealed
     * for the account as its password is): the address and the key of one save, read
     * together, so that a key is never sent to an address it was not saved with. (The
     * relay jams use, and its key, are jams' own and are not read here.)
     */
    override fun relay(): Pair<Core.LinkRelay, String>? {
        val now = signedIn()?.account ?: return null
        val kept = vault(context).load(now.server, now.username) ?: return null
        val base = kept.substringBefore('\n').trim().trimEnd('/')
        val key = kept.substringAfter('\n', "")
        val place = CoreHttp.originOf(base) ?: return null
        if (key.isEmpty()) return null
        return Core.LinkRelay(base, key) to place
    }

    override fun relayEdit(): Long = edits.get()

    override fun elsewhere(): Set<String> {
        val now = signedIn() ?: return emptySet()
        val requests = now.requests?.settings?.value
        return setOfNotNull(
            CoreHttp.originOf(now.account.server),
            requests?.address?.takeIf { it.isNotBlank() }?.let(Core::lidarrPlace),
            requests?.service?.takeIf { it.isNotBlank() }?.let(CoreHttp::originOf),
        )
    }

    override fun client(): okhttp3.OkHttpClient = graph.http

    override fun remembered(): JsonElement = kept()["remembered"] as? JsonArray ?: NOTHING_REMEMBERED

    override fun remember(list: JsonElement) = keep { was -> JsonObject(was + ("remembered" to list)) }

    /** Through the app's own notes, as everything else it says: it looks like the rest of
     *  the app, and one said while the app is behind waits until the person is looking. */
    override fun say(text: String) {
        graph.diagnostics.note("linking said: $text")
        graph.toasts.show(text)
    }

    override fun note(text: String) = graph.diagnostics.note(text)

    override fun ownStoppedWords(): String = context.getString(org.medianexus.music.R.string.link_stopped)

    override fun forget(playlist: String) {
        // **Noted, and nothing is deleted.** The phone's kept pages are everybody's (the
        // list of playlists among them, which holds this playlist's description as it
        // holds every playlist's), and nothing of linking's rewrites or removes one. How
        // the record is kept out of the phone's caches is for when linking is switched
        // on for everyone.
        graph.diagnostics.note("linking: asked to forget what is kept of a playlist (not done on a phone yet)")
    }

    companion object {
        private const val ID_CHARS = 22
        private const val MOST_NAME = 24

        /** How many times a relay has been saved or removed in this run. */
        private val edits = java.util.concurrent.atomic.AtomicLong()

        private fun relayFile(context: Context) = File(context.noBackupFilesDir, "link-relay.sealed")

        private fun vault(context: Context) = org.medianexus.music.jam.KeyVault(relayFile(context), org.medianexus.music.store.KeystoreBox())

        /**
         * Keeps a relay and its host key for this account, **as one save**; a session
         * that is using another is ended and begun again ([relayEdit]). False if the
         * phone would not seal or write it. Off the main thread.
         */
        fun keepRelay(context: Context, server: String, username: String, base: String, key: String): Boolean =
            vault(context).save(server, username, base.trim().trimEnd('/') + "\n" + key).also { edits.incrementAndGet() }

        /** Whether a relay is kept for anyone at all (without opening it). */
        fun relayKept(context: Context): Boolean = relayFile(context).exists()

        /** Removes it: linking is then not wanted. */
        fun forgetRelay(context: Context): Boolean = vault(context).delete().also { edits.incrementAndGet() }
    }
}
