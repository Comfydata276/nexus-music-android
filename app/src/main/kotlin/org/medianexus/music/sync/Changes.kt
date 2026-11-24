package org.medianexus.music.sync

import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Settings
import org.medianexus.music.store.Waiting

/**
 * What the person changes on this phone of the things shared between devices, kept as the
 * change itself: "pin this", "unpin that", "shuffle on for this list", "this sort for this
 * list", "this theme". Not as a new whole value with the time on it.
 *
 * Why. Between devices each item is a whole value and a time, and the newer wins everywhere.
 * A phone that has not yet read what the PCs have (new, offline, sharing just switched on)
 * holds none of their pins; if pinning one playlist there made "the pins" the list of that
 * one, stamped now, every PC's pins would become that one. So too for a list: a shuffle
 * tapped before that list's choices had arrived would be sent with a sort of nothing under
 * one time, and the PC's sort for it would be gone.
 *
 * So a change waits ([Settings.waiting], with the settings, for the account they belong to),
 * and becomes a value with a time on it only at an exchange, **on top of what the server has
 * then** ([onto]): the playlist is added to the pins the server has; the shuffle is set on
 * the list's choices as the server has them, its sort left alone. Only an item the change
 * really altered gets a time. Once that has been written the change is forgotten
 * ([Sharing.taken]). Meanwhile, and whether or not sharing is on, the phone shows what it
 * last agreed with the server ([Settings.agreed]) with the changes on top, which is what the
 * settings' own pins, lists and theme hold.
 */
object Changes {
    const val PIN = "pin"
    const val UNPIN = "unpin"
    const val SHUFFLE = "shuffle"
    const val SORT = "sort"
    const val THEME = "theme"

    /** `change` added to those waiting. An earlier one about the same thing gives way to it:
     *  the last word on a playlist's pin, a list's shuffle, a list's sort or the theme. */
    private fun waiting(before: List<Waiting>, change: Waiting): List<Waiting> {
        fun about(w: Waiting) = when (w.what) {
            PIN, UNPIN -> "pin:${w.key}"
            THEME -> THEME
            else -> "${w.what}:${w.key}"
        }
        return before.filter { about(it) != about(change) } + change
    }

    /** Pins a playlist to the top of Library (at the end of the pins), or unpins it. */
    fun pin(settings: Settings, id: String, on: Boolean): Settings = settings.copy(
        pinnedPlaylists = pins(settings.pinnedPlaylists, id, on),
        waiting = waiting(settings.waiting, Waiting(if (on) PIN else UNPIN, id)),
    )

    private fun pins(pins: List<String>, id: String, on: Boolean): List<String> = when {
        on && id !in pins -> pins + id
        !on && id in pins -> pins.filter { it != id }
        else -> pins
    }

    /** Whether the list `key` plays shuffled. A list the Windows app doesn't have under that
     *  key is the phone's own: its choice is kept, and nothing waits to be shared. */
    fun shuffle(settings: Settings, key: String, on: Boolean, now: Long): Settings {
        if (key.isEmpty()) return settings
        val prefs = (settings.lists[key] ?: ListPrefs()).copy(shuffle = on, at = now)
        return settings.copy(
            lists = settings.lists + (key to prefs),
            waiting = if (Sharing.windowsKey(key)) waiting(settings.waiting, Waiting(SHUFFLE, key, on = on)) else settings.waiting,
        )
    }

    /** The order the list `key` is shown in. An order the Windows app has no number for is
     *  the phone's own, and so is a list it doesn't have: kept, and not shared. */
    fun sort(settings: Settings, key: String, sort: Int, descending: Boolean, now: Long): Settings {
        if (key.isEmpty()) return settings
        val prefs = (settings.lists[key] ?: ListPrefs()).copy(sort = sort, descending = descending, sortChosen = true, at = now)
        val shared = Sharing.windowsKey(key) && sort in 0..Sharing.WINDOWS_SORT_MOST
        return settings.copy(
            lists = settings.lists + (key to prefs),
            // (An order of the phone's own takes the place of one that was waiting to be
            // shared: the list has one order, and it is no longer that one.)
            waiting = if (shared) waiting(settings.waiting, Waiting(SORT, key, sort = sort, descending = descending))
            else settings.waiting.filter { !(it.what == SORT && it.key == key) },
        )
    }

    /** The colour theme. */
    fun theme(settings: Settings, name: String): Settings = settings.copy(
        theme = name,
        waiting = if (name in Sharing.WINDOWS_THEMES) waiting(settings.waiting, Waiting(THEME, name)) else settings.waiting,
    )

    /** A time for an item changed here at `now`: after the time of what it was changed from,
     *  whatever clock put that on, so the change is the newer on every device. */
    private fun after(at: Long, now: Long): Long = maxOf(now, at + 1)

    /**
     * The record with the waiting changes made to it: each on top of the item as `record`
     * has it (the server's, merged with what this phone agreed before), and only an item a
     * change really altered given a time. A change that makes no difference to what is
     * there (pinning what is pinned, the shuffle a list already has) alters nothing.
     */
    fun onto(record: Shared, waiting: List<Waiting>, now: Long): Shared {
        var out = record
        var pins = record.pins
        for (change in waiting) if (change.what == PIN || change.what == UNPIN) pins = pins(pins, change.key, change.what == PIN)
        if (pins != record.pins) out = out.copy(pins = pins, pinsAt = after(record.pinsAt, now))
        var lists: HashMap<String, SharedPrefs>? = null
        for (change in waiting) {
            if (change.what != SHUFFLE && change.what != SORT) continue
            val base = (lists ?: record.lists)[change.key] ?: SharedPrefs()
            val made = when {
                change.what == SHUFFLE -> base.copy(shuffle = change.on)
                change.sort in 0..Sharing.WINDOWS_SORT_MOST -> base.copy(sort = change.sort.toULong(), descending = change.descending, sortChosen = true)
                else -> base
            }
            if (made == base || !Sharing.windowsKey(change.key)) continue
            // (Timed from the item as the record had it, however many changes are made to it.)
            (lists ?: HashMap(record.lists).also { lists = it })[change.key] = made.copy(at = after((record.lists[change.key] ?: SharedPrefs()).at, now))
        }
        lists?.let { out = out.copy(lists = it) }
        val theme = waiting.lastOrNull { it.what == THEME }?.key
        if (theme != null && theme in Sharing.WINDOWS_THEMES && theme != record.theme) out = out.copy(theme = theme, themeAt = after(record.themeAt, now))
        return out
    }

    /** One list's choices with the waiting changes to that list on top, as the phone shows
     *  them. */
    internal fun shown(agreed: ListPrefs, key: String, waiting: List<Waiting>): ListPrefs {
        var out = agreed
        for (change in waiting) {
            if (change.key != key) continue
            if (change.what == SHUFFLE) out = out.copy(shuffle = change.on)
            if (change.what == SORT) out = out.copy(sort = change.sort, descending = change.descending, sortChosen = true)
        }
        return out
    }

    /** The pins with the waiting changes on top, as the phone shows them. */
    internal fun shown(agreed: List<String>, waiting: List<Waiting>): List<String> {
        var pins = agreed
        for (change in waiting) if (change.what == PIN || change.what == UNPIN) pins = pins(pins, change.key, change.what == PIN)
        return pins
    }
}
