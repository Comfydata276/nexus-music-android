package org.medianexus.music.sync

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Settings
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.writeAtomically

/**
 * Whose the shared choices are.
 *
 * The pins, each list's shuffle and sort, the time the theme was chosen and the sync
 * playlist's id live in the phone's settings, where every screen reads them. Settings are the
 * phone's, not an account's; but these are shared into an account's record on the server,
 * where that person's PCs take them for that person's own. So they belong to one account at
 * a time ([Settings.sharedFor]), and when another account is taken on they are put away with
 * the account they belong to (`in-step.json`, in its own data) and that account's own are
 * brought out: nothing chosen under one account is ever shared into another's record, and
 * signing back in finds one's choices as they were. (The Windows app keeps them in its one
 * settings file whoever is signed in, and shares them with whoever is.)
 *
 * The theme itself stays the phone's look. Brought out with an account is the theme that was
 * chosen under it; an account with none takes the look the phone has, and has nothing to say
 * about it to its other devices until a theme is chosen under it or comes from its record
 * ([Settings.themeOwn]).
 *
 * This runs as the account is taken on, before anything of the account exists to read the
 * settings (`Graph.accounts`); the exchange asks [ready] and takes no part until it is so.
 */
object Belongs {
    @Serializable
    private data class Kept(
        val pinnedPlaylists: List<String> = emptyList(),
        val pinsAt: Long = 0,
        val lists: Map<String, ListPrefs> = emptyMap(),
        /** The theme chosen under this account ("" for none) and when. */
        val theme: String = "",
        val themeAt: Long = 0,
        val syncPlaylist: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileIn(data: File) = File(data, "in-step.json")

    /** The shared choices in these settings are this account's. */
    fun ready(settings: Settings, account: String): Boolean = settings.sharedFor == account

    /**
     * Makes the shared choices in the settings this account's (`account`: its storage key;
     * `dataOf` gives an account's own data folder). Off the main thread: it reads and writes
     * a small file. `note` is told what went wrong, for the Diagnostics page.
     */
    fun takeOn(settings: SettingsStore, account: String, dataOf: (String) -> File, note: (String) -> Unit = {}) {
        val before = settings.value
        if (before.sharedFor == account) return
        if (before.sharedFor.isEmpty()) {
            // The first account since these choices had an owner: they are its own (an
            // earlier version of the app, which shared nothing, had one set for everyone).
            settings.update { if (it.sharedFor.isEmpty()) it.copy(sharedFor = account) else it }
            if (settings.value.sharedFor == account) return
        }
        // Put away with the account they belong to...
        val leaving = settings.value
        if (leaving.sharedFor != account && leaving.sharedFor.isNotEmpty()) {
            val kept = Kept(leaving.pinnedPlaylists, leaving.pinsAt, leaving.lists, if (leaving.themeOwn) leaving.theme else "", if (leaving.themeOwn) leaving.themeAt else 0, leaving.syncPlaylist)
            try {
                val data = dataOf(leaving.sharedFor)
                data.mkdirs()
                writeAtomically(fileIn(data), json.encodeToString(Kept.serializer(), kept).toByteArray())
            } catch (_: IOException) {
                // They go all the same: left where they are, they would be shared into the
                // next account's record. What was shared of them is still on that account's server.
                note("in step: one account's pins and list choices couldn't be put away on the phone")
            }
        }
        // ...and this account's own brought out; none kept is none chosen.
        val mine = try {
            fileIn(dataOf(account)).takeIf { it.isFile }?.let { json.decodeFromString(Kept.serializer(), it.readText()) }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            note("in step: this account's pins and list choices on the phone couldn't be read")
            null
        } ?: Kept()
        settings.update { now ->
            if (now.sharedFor == account) return@update now
            val own = mine.theme.isNotEmpty()
            now.copy(
                sharedFor = account,
                pinnedPlaylists = mine.pinnedPlaylists,
                pinsAt = mine.pinsAt,
                lists = mine.lists,
                theme = if (own) mine.theme else now.theme,
                themeAt = if (own) mine.themeAt else 0,
                themeOwn = own,
                syncPlaylist = mine.syncPlaylist,
            )
        }
    }
}
