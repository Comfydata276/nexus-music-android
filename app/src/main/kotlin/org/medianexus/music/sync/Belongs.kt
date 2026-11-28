package org.medianexus.music.sync

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.medianexus.music.core.AgreedFormerly
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.ListChoices
import org.medianexus.music.core.WaitingFormerly
import org.medianexus.music.store.Agreed
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.OwnSort
import org.medianexus.music.store.Settings
import org.medianexus.music.store.SettingsStore
import org.medianexus.music.store.Waiting
import org.medianexus.music.store.writeAtomically

/**
 * Whose the shared choices are.
 *
 * The pins, each list's shuffle and sort and the theme live in the phone's settings, where
 * every screen reads them, with what was last agreed with the server and the changes still
 * waiting to be shared, as the shared core keeps them ([Settings.choices]). Settings are the phone's, not
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
        // ...and the shared choices as the core keeps them (what was agreed with the
        // server, and what was still waiting to be shared), with the orders that are the
        // phone's own.
        val choices: JsonElement? = null,
        val ownSorts: Map<String, OwnSort> = emptyMap(),
        // (As a build before the shared core put them away.)
        val agreed: Agreed = Agreed(),
        val waiting: List<Waiting> = emptyList(),
        /** [MARK], in a file this build wrote. 1 from a build that kept the choices itself
         *  (`agreed` and `waiting`); 0 from the build before that, which kept the pins and
         *  lists with times of its own making and no more. */
        val mark: Int = 0,
    )

    /** The rules the shared choices are now kept under (see [Settings.stepMark]): the
     *  shared core's. */
    const val MARK = 2

    private fun formerly(agreed: Agreed) = AgreedFormerly(
        agreed.pins, agreed.pinsAt, agreed.theme, agreed.themeAt,
        agreed.lists.mapValues { (_, prefs) -> ListChoices(prefs.shuffle, prefs.sort.coerceAtLeast(0), prefs.descending, prefs.sortChosen, prefs.at) },
    )

    /**
     * What an earlier build left as an account's shared choices, as the core keeps them.
     *
     * - From a build that kept them itself (`mark` 1; 0.4.0, which had keeping in step
     *   switched off and so had agreed nothing): what the person changed there was never a
     *   statement about their other devices. Its theme, and each list's shuffle and sort,
     *   are offered as chosen "long ago": they lose to anything chosen since on any device,
     *   and are what goes into the record only where nobody has said anything. A playlist
     *   pinned there stays a pin still to be shared: it is added to the other devices'
     *   pins, and replaces nothing. (What a build of that kind had really agreed with the
     *   server is kept as it was, with a change on top of it still a change: the core's
     *   layer decides which, [Core.adopt].)
     * - From the build before that (`mark` 0): its pins, list choices and theme, shown as
     *   they were and agreed with nobody. Their times were put on by rules since thrown out
     *   (the whole value stamped "now" from whatever the phone held), so the core is told to
     *   take each as chosen "long ago": offered, and losing to anything chosen since on any
     *   device.
     */
    private fun adopted(mark: Int, account: String, pins: List<String>, lists: Map<String, ListPrefs>, theme: String, agreed: Agreed, waiting: List<Waiting>): JsonElement =
        if (mark >= 1) Core.adopt(
            formerly(agreed),
            // (Only what the phone may say: a list the Windows app has too, an order it has
            // a number for. That build kept nothing else waiting; held to it all the same.)
            waiting.filter { (it.what != "shuffle" && it.what != "sort") || (Chosen.windowsKey(it.key) && (it.what != "sort" || it.sort in 0..Chosen.WINDOWS_SORT_MOST)) }
                .map { WaitingFormerly(it.what, it.key, it.on, it.sort, it.descending) },
            account,
        )
        else Core.adopt(formerly(Agreed(pins = pins, theme = theme, lists = lists.filterKeys(Chosen::windowsKey).filterValues { it.sort in 0..Chosen.WINDOWS_SORT_MOST })), emptyList(), account, demote = true, usualTheme = org.medianexus.music.store.DEFAULT_THEME)

    /** The orders only the phone offers that an earlier build kept among a list's choices
     *  (for a list the Windows app has too): kept apart from here on ([Settings.ownSorts]). */
    private fun ownOf(lists: Map<String, ListPrefs>): Map<String, OwnSort> =
        lists.filter { (key, prefs) -> Chosen.windowsKey(key) && prefs.sortChosen && prefs.sort !in 0..Chosen.WINDOWS_SORT_MOST }.mapValues { (_, prefs) -> OwnSort(prefs.sort, prefs.descending) }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileIn(data: File) = File(data, "in-step.json")

    /** The shared choices in these settings are this account's. */
    fun ready(settings: Settings, account: String): Boolean = settings.sharedFor == account && settings.stepMark >= MARK

    /**
     * Makes the shared choices in the settings this account's (`account`: its storage key;
     * `dataOf` gives an account's own data folder). Off the main thread: it reads and writes
     * a small file, and asks the core. `note` is told what went wrong, for the Diagnostics
     * page.
     *
     * **False if it couldn't be done**, and then nothing has changed: the choices in the
     * settings are still the account's they were, with whatever of them was waiting to be
     * shared. That is when the other account's choices couldn't be put away on the phone
     * (taken on all the same, this account would have shared them as its own, or they would
     * have been lost), or this account's own, put away earlier, are there and couldn't be
     * read (taken for none, they would be written over when it next gives way). Whoever
     * asked must not go on as this account; trying again is the way out.
     *
     * Put-away choices that **read and are not what this app wrote** are not a refusal:
     * they are moved to a name beside their place (`in-step.json.damaged`, which nothing
     * ever writes to), Diagnostics says so, and the account is taken on with no choices.
     */
    fun takeOn(
        settings: SettingsStore,
        account: String,
        dataOf: (String) -> File,
        legacyChoices: Pair<String, File>? = null,
        note: (String) -> Unit = {},
    ): Boolean {
        // First, what a build before this one left in the settings is brought under this
        // one's rules ([MARK]): once, whoever is signed in.
        try {
            settings.update { old ->
                when {
                    old.stepMark >= MARK -> old
                    // Settings that belong to no account (a phone that has never kept in
                    // step): what they hold stays the phone's own, shown and in nobody's
                    // record. They are not given to whichever account opens them first.
                    old.sharedFor.isEmpty() -> old.copy(stepMark = MARK, choices = null, agreed = Agreed(), waiting = emptyList(), ownSorts = ownOf(old.lists))
                    else -> old.copy(
                        stepMark = MARK,
                        ownSorts = ownOf(old.lists),
                        choices = adopted(old.stepMark, old.sharedFor, old.pinnedPlaylists, old.lists, old.theme, old.agreed, old.waiting),
                        agreed = Agreed(), waiting = emptyList(),
                    )
                }
            }
        } catch (e: CoreException) {
            // Left as they were, to be tried again at the next start: until then nothing of
            // them is shared ([ready]).
            note("in step: the choices an earlier version kept couldn't be brought over (${e.message})")
            return true
        }
        val before = settings.value
        if (before.sharedFor == account) return true
        if (before.sharedFor.isEmpty()) {
            settings.update { if (it.sharedFor.isEmpty()) it.copy(sharedFor = account) else it }
            if (settings.value.sharedFor == account) return true
        }
        // This account's own, put away when it last gave way; none kept is none chosen. Read
        // first: if they are there and can't be read, nothing is put away or taken on.
        val choicesFile = legacyChoices?.takeIf { (key, file) -> key == account && file.exists() }?.second
            ?: fileIn(dataOf(account))
        val mine = try {
            val kept = choicesFile
            try {
                json.decodeFromString(Kept.serializer(), String(java.nio.file.Files.readAllBytes(kept.toPath()), Charsets.UTF_8))
            } catch (_: java.nio.file.NoSuchFileException) {
                null
            }
        } catch (_: IOException) {
            note("in step: this account's pins and list choices on the phone couldn't be read, so it wasn't taken on")
            return false
        } catch (_: IllegalArgumentException) {
            // They read, and are not what this app wrote (cut short, say). Never written
            // over: moved to a name beside their place that nothing is ever written to
            // again, and the account goes on with no choices. If they can't be moved, the
            // account is not taken on (they would be written over when it next gives way).
            try {
                val damaged = choicesFile
                var aside = File(damaged.path + ".damaged")
                var n = 2
                while (aside.exists()) aside = File(damaged.path + ".damaged-${n++}")
                java.nio.file.Files.move(damaged.toPath(), aside.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                note("in step: this account's pins and list choices on the phone couldn't be read, so they were kept beside their place (${aside.name}) and the account starts with none")
            } catch (_: IOException) {
                note("in step: this account's pins and list choices on the phone couldn't be read or kept aside, so it wasn't taken on")
                return false
            }
            null
        }?.let { kept ->
            if (kept.mark >= MARK) kept else try {
                kept.copy(choices = adopted(kept.mark, account, kept.pinnedPlaylists, kept.lists, kept.theme, kept.agreed, kept.waiting), ownSorts = ownOf(kept.lists), agreed = Agreed(), waiting = emptyList(), mark = MARK)
            } catch (e: CoreException) {
                note("in step: the choices an earlier version kept for this account couldn't be brought over (${e.message})")
                kept.copy(choices = null, agreed = Agreed(), waiting = emptyList(), mark = MARK)
            }
        } ?: Kept()
        // Put away with the account they belong to, surely: until they are, they stay.
        val leaving = settings.value
        if (leaving.sharedFor != account && leaving.sharedFor.isNotEmpty()) {
            val kept = Kept(leaving.pinnedPlaylists, leaving.lists, leaving.theme, leaving.choices, leaving.ownSorts, mark = MARK)
            try {
                val target = legacyChoices?.takeIf { it.first == leaving.sharedFor }?.second
                    ?: fileIn(dataOf(leaving.sharedFor))
                writeAtomically(target, json.encodeToString(Kept.serializer(), kept).toByteArray(), durable = true)
            } catch (_: IOException) {
                note("in step: one account's pins and list choices couldn't be put away on the phone, so the other wasn't taken on")
                return false
            }
        }
        // ...and this account's own brought out.
        settings.update { now ->
            if (now.sharedFor == account) return@update now
            now.copy(
                sharedFor = account,
                pinnedPlaylists = mine.pinnedPlaylists,
                lists = mine.lists,
                // (The phone's look is left as it is for an account that has none of its own.)
                theme = mine.theme.ifEmpty { now.theme },
                choices = mine.choices,
                ownSorts = mine.ownSorts,
            )
        }
        return true
    }
}
