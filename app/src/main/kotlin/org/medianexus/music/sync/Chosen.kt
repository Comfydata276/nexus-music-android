package org.medianexus.music.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.medianexus.music.core.Change
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.Shown
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.OwnSort
import org.medianexus.music.store.Settings

/**
 * The phone's side of the choices shared between devices: the pins, each list's shuffle and
 * sort, and the theme.
 *
 * **The rules are the shared core's** (`core/Core.kt`; the Windows repository's
 * `core/src/step.rs` says them): a change the person makes is kept as the change itself and
 * becomes a value with a time on it only at an exchange, on top of what the server has
 * then; what a device shows is what it last agreed with the server with its waiting changes
 * on top. The core keeps both ([Settings.choices]); nothing here decides anything about
 * them.
 *
 * What is the phone's own, and is here:
 *
 * - **What the phone may say.** A list the Windows app doesn't have under that key
 *   ([windowsKey]), an order it has no number for, a theme it doesn't have: these are the
 *   phone's alone, kept in its settings and never handed to the core as a change.
 * - **What the phone shows** ([Settings.pinnedPlaylists], [Settings.lists],
 *   [Settings.theme]): what the core says is shown, and, where the record has said nothing
 *   yet (no pins and no time on them, no entry for a list, no theme), whatever the phone
 *   has of its own. So a phone that has never kept in step shows its pins as it always did.
 * - **An order only the phone offers** for a list the Windows app has too
 *   ([Settings.ownSorts]): kept apart from that list's shared choices, so that a shuffle
 *   arriving for the list does not displace it; a sort chosen on any device after it does.
 */
object Chosen {
    /** The themes the Windows app has, by the names it knows them by (`THEMES` in its
     *  src/config.rs). A name outside these is never handed to the core. */
    val WINDOWS_THEMES = setOf("Violet", "Green", "Ember", "Ocean", "Rose", "Graphite")

    /** The orders the Windows app has for a list of songs (`TRACK_SORTS`): 0 to this. */
    const val WINDOWS_SORT_MOST = 5

    /**
     * Whether a list's key is one the Windows app has too, meaning the same list: its route
     * keys for a playlist, an album, an artist, a genre, Liked songs, the Songs page, the
     * downloads and a "Made for you" mix. The phone's own keys (a downloaded album or
     * playlist as the downloads have it, a song's radio) stay on the phone, and so does a mix
     * made to rules of one's own: its id is only this device's, and names another mix, or
     * none, on any other.
     */
    fun windowsKey(key: String): Boolean = when {
        key == "liked" || key == "songs" || key == "downloads" -> true
        key.startsWith("mix:rule:") -> false
        else -> listOf("playlist:", "album:", "artist:", "genre:", "mix:").any { key.startsWith(it) && key.length > it.length }
    }

    /** Something the person did, as the phone's screens say it. */
    sealed interface Did {
        /** Pinned a playlist to the top of Library (at the end of the pins), or unpinned it. */
        data class Pin(val id: String, val on: Boolean) : Did

        /** Whether the list `key` plays shuffled. */
        data class Shuffle(val key: String, val on: Boolean) : Did

        /** The order the list `key` is shown in. */
        data class Sort(val key: String, val sort: Int, val descending: Boolean) : Did

        /** The colour theme. */
        data class Theme(val name: String) : Did
    }

    /** The core's choices in these settings; its empty ones where it has had none yet. */
    fun choices(settings: Settings): JsonElement = settings.choices ?: JsonObject(emptyMap())

    /** The change to hand to the core for what the person did, if it is something shared. */
    private fun shared(did: Did): Change? = when (did) {
        is Did.Pin -> if (did.on) Change.Pin(did.id) else Change.Unpin(did.id)
        is Did.Shuffle -> if (windowsKey(did.key)) Change.Shuffle(did.key, did.on) else null
        is Did.Sort -> if (windowsKey(did.key) && did.sort in 0..WINDOWS_SORT_MOST) Change.Sort(did.key, did.sort, did.descending) else null
        is Did.Theme -> if (did.name in WINDOWS_THEMES) Change.Theme(did.name) else null
    }

    /** What the person did, as it shows in the phone's own settings: no rule in it, only
     *  the thing itself (the playlist among the pins or not, the list's shuffle, its order,
     *  the theme). */
    private fun here(settings: Settings, did: Did, now: Long): Settings = when (did) {
        is Did.Pin -> settings.copy(
            pinnedPlaylists = when {
                did.on && did.id !in settings.pinnedPlaylists -> settings.pinnedPlaylists + did.id
                !did.on -> settings.pinnedPlaylists.filter { it != did.id }
                else -> settings.pinnedPlaylists
            },
        )
        is Did.Shuffle -> if (did.key.isEmpty()) settings else settings.copy(lists = settings.lists + (did.key to (settings.lists[did.key] ?: ListPrefs()).copy(shuffle = did.on, at = now)))
        is Did.Sort -> if (did.key.isEmpty()) settings else settings.copy(
            lists = settings.lists + (did.key to (settings.lists[did.key] ?: ListPrefs()).copy(sort = did.sort, descending = did.descending, sortChosen = true, at = now)),
            // An order only the phone offers, for a list the Windows app has too, is kept
            // apart; one the Windows app has takes its place.
            ownSorts = if (windowsKey(did.key) && did.sort !in 0..WINDOWS_SORT_MOST) settings.ownSorts + (did.key to OwnSort(did.sort, did.descending)) else settings.ownSorts - did.key,
        )
        is Did.Theme -> settings.copy(theme = did.name)
    }

    /**
     * The settings with what the person did made to them. If it is something shared, and
     * the shared choices belong to an account ([Settings.sharedFor]), the core keeps it as
     * the change, to be made at the next exchange to whatever the server has then. Off the
     * main thread (the core is asked). `note`: for the Diagnostics page, if the core
     * couldn't be asked: the change then shows and is not shared.
     *
     * **A choice belongs to the sign-in it was made under.** `account` is who was signed in
     * at the moment of the tap (empty for nobody), which may not be whose the settings are
     * by the time it is carried out: taps wait their turn behind the settings' writes, and
     * another account may have been taken on meanwhile. Then it is not made at all: these
     * are another account's pins and choices, and made here it would show among them and
     * go into that account's record with a fresh time.
     */
    fun made(settings: Settings, did: Did, account: String, now: Long, note: (String) -> Unit = {}): Settings {
        if (account != settings.sharedFor && (account.isNotEmpty() || shared(did) != null)) {
            note("in step: a choice made as another account was taken on wasn't kept")
            return settings
        }
        val shown = here(settings, did, now)
        if (account.isEmpty()) return shown
        // An order only the phone offers, for a list the Windows app has too: a sort for
        // that list still waiting to be shared is no longer what the person wants, and is
        // taken back, so that it isn't sent to the other devices after all.
        if (did is Did.Sort && did.key in shown.ownSorts) {
            return try {
                with(shown, Core.sortTakenBack(choices(settings), account, did.key), arrived = false)
            } catch (e: CoreException) {
                note("in step: a sort that was waiting to be shared couldn't be taken back (${e.message})")
                shown
            }
        }
        val change = shared(did) ?: return shown
        return try {
            with(shown, Core.change(choices(settings), account, change).choices)
        } catch (e: CoreException) {
            note("in step: a change made here couldn't be kept for sharing (${e.message})")
            shown
        }
    }

    /**
     * The settings holding these choices of the core's (after a change, or after an
     * exchange took something in), and showing what the core says is shown for the account
     * they belong to. Off the main thread.
     */
    fun with(settings: Settings, choices: JsonElement, arrived: Boolean = true): Settings {
        val account = settings.sharedFor
        // (`arrived` false: the shared order changed by this phone's own doing, taking a
        // sort back; it is not a sort arriving, and the phone's own order does not give way.)
        val before = if (arrived) settings.choices?.let { Core.shown(it, account) } else null
        return show(settings.copy(choices = choices), Core.shown(choices, account), before)
    }

    /** What the core shows, put on top of what the phone has of its own. `before` is what
     *  the core showed until now. */
    private fun show(settings: Settings, shown: Shown, before: Shown?): Settings {
        var own = settings.ownSorts
        val lists = HashMap(settings.lists)
        for ((key, choices) in shown.lists) {
            // What the shared order for this list was until now: nothing chosen, where
            // there was no entry for it.
            val was = before?.let { it.lists[key]?.let { had -> Triple(had.sort, had.descending, had.sortChosen) } ?: Triple(0, false, false) }
            // A sort chosen on some device has arrived for this list (its order as shared is
            // not what it was): the phone's own order for it gives way.
            if (key in own && was != null && was != Triple(choices.sort, choices.descending, choices.sortChosen)) own = own - key
            val prefs = ListPrefs(choices.shuffle, choices.sort, choices.descending, choices.sortChosen, choices.at)
            lists[key] = own[key]?.let { prefs.copy(sort = it.sort, descending = it.descending, sortChosen = true) } ?: prefs
        }
        return settings.copy(
            // Where the record has said nothing about the pins, the phone's own stay.
            pinnedPlaylists = if (shown.pinsSaid) shown.pins else settings.pinnedPlaylists,
            // A theme this phone doesn't have is left for the devices that do.
            theme = if (shown.theme in WINDOWS_THEMES) shown.theme else settings.theme,
            lists = lists,
            ownSorts = own,
        )
    }
}
