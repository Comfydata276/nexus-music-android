package org.medianexus.music.sync

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.medianexus.music.store.Agreed
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Settings
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.Waiting
import org.medianexus.music.store.writeAtomically

/**
 * Whose the shared choices are.
 *
 * The pins, each list's shuffle and sort and the theme live in the phone's settings, where
 * every screen reads them, with what was last agreed with the server and the changes still
 * waiting to be shared ([Settings.agreed], [Settings.waiting]). Settings are the phone's, not
 * an account's; but these are shared into an account's record on the server, where that
 * person's PCs take them for that person's own. So they belong to one account at a time
 * ([Settings.sharedFor]), and when another account is taken on they are put away with the
 * account they belong to (`in-step.json`, in its own data) and that account's own are
 * brought out: nothing chosen under one account is ever shared into another's record, and
 * signing back in finds one's choices as they were. (The Windows app keeps them in its one
 * settings file whoever is signed in, and shares them with whoever is.)
 *
 * The theme stays the phone's look: an account that has none of its own takes the look the
 * phone has, and has nothing to say about it to its other devices until a theme is chosen
 * under it or comes from its record.
 *
 * This runs as the account is taken on, before anything of the account exists to read the
 * settings (`Graph.accounts`); the exchange asks [ready] and takes no part until it is so.
 */
object Belongs {
    @Serializable
    private data class Kept(
        // What the phone showed for this account...
        val pinnedPlaylists: List<String> = emptyList(),
        val lists: Map<String, ListPrefs> = emptyMap(),
        val theme: String = "",
        // ...what it had agreed with the server, and what was still waiting to be shared.
        val agreed: Agreed = Agreed(),
        val waiting: List<Waiting> = emptyList(),
        /** [MARK], in a file this build wrote; 0 in one from the build before it, which kept
         *  the pins and lists with times of its own making and no more. */
        val mark: Int = 0,
    )

    /** The rules the shared choices are now kept under (see [Settings.stepMark]). */
    const val MARK = 1

    /**
     * What an earlier build left as an account's pins, list choices and theme, as this build
     * holds them: shown as they were, and agreed with nobody. Their times were put on by
     * rules since thrown out (the whole value stamped "now" from whatever the phone held),
     * so they are given Windows' "long ago": offered, and losing to anything chosen since on
     * any device.
     */
    private fun demoted(pins: List<String>, lists: Map<String, ListPrefs>, theme: String): Agreed = Agreed(
        pins = pins, pinsAt = if (pins.isEmpty()) 0 else LONG_AGO,
        // (The default theme is nobody's choice.)
        theme = if (theme != org.medianexus.music.store.DEFAULT_THEME) theme else "", themeAt = if (theme != org.medianexus.music.store.DEFAULT_THEME) LONG_AGO else 0,
        lists = lists.mapValues { it.value.copy(at = LONG_AGO) },
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
        // First, what a build before this one left in the settings is brought under this
        // one's rules ([MARK]): once, whoever is signed in.
        settings.update { old ->
            when {
                old.stepMark >= MARK -> old
                // Settings that belong to no account (a phone that has never kept in step):
                // what they hold stays the phone's own, shown and in nobody's record. They are
                // not given, with the times they carry, to whichever account opens them first.
                old.sharedFor.isEmpty() -> old.copy(stepMark = MARK, agreed = Agreed(), waiting = emptyList())
                // An account's, from the build that first kept in step: demoted.
                else -> old.copy(stepMark = MARK, agreed = demoted(old.pinnedPlaylists, old.lists, old.theme), waiting = emptyList())
            }
        }
        val before = settings.value
        if (before.sharedFor == account) return
        if (before.sharedFor.isEmpty()) {
            settings.update { if (it.sharedFor.isEmpty()) it.copy(sharedFor = account) else it }
            if (settings.value.sharedFor == account) return
        }
        // Put away with the account they belong to...
        val leaving = settings.value
        if (leaving.sharedFor != account && leaving.sharedFor.isNotEmpty()) {
            val kept = Kept(leaving.pinnedPlaylists, leaving.lists, leaving.theme, leaving.agreed, leaving.waiting, MARK)
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
        }?.let { kept -> if (kept.mark >= MARK) kept else kept.copy(agreed = demoted(kept.pinnedPlaylists, kept.lists, kept.theme), waiting = emptyList(), mark = MARK) } ?: Kept()
        settings.update { now ->
            if (now.sharedFor == account) return@update now
            now.copy(
                sharedFor = account,
                pinnedPlaylists = mine.pinnedPlaylists,
                lists = mine.lists,
                // (The phone's look is left as it is for an account that has none of its own.)
                theme = mine.theme.ifEmpty { now.theme },
                agreed = mine.agreed,
                waiting = mine.waiting,
            )
        }
    }
}
