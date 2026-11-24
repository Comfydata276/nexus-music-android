package org.medianexus.music.sync

import org.medianexus.music.net.Song
import org.medianexus.music.store.Added
import org.medianexus.music.store.AddedNote
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.Settings
import org.medianexus.music.store.Waiting
import org.medianexus.music.store.SettingsStore

/**
 * What goes between the phone's own stores and the shared record, each way, as plain
 * functions: what the phone has to say ([choices], [plays]) and what it takes in ([taken]).
 * Windows' `shared_here` and `take_shared`, with the phone's rules about what it may say
 * (docs/DESIGN.md, "In step with other devices", has the table).
 */
object Sharing {
    /** The themes the Windows app has, by the names it knows them by (`THEMES` in its
     *  src/config.rs). A name outside these is never written to the record. */
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

    /** One list's choices as shared, or null if they are the phone's alone: a key Windows
     *  doesn't have, or an order it has no number for. */
    fun prefs(key: String, prefs: ListPrefs): SharedPrefs? =
        if (!windowsKey(key) || prefs.sort !in 0..WINDOWS_SORT_MOST) null
        else SharedPrefs(prefs.shuffle, prefs.sort.toULong(), prefs.descending, prefs.sortChosen, prefs.at)

    /**
     * The settings' part of what the phone has to share: the pins, the theme and each list's
     * choices **as last agreed with the server**, each with the time it has there. Not what
     * the phone shows: the person's changes since are not in this, and are made to the
     * record itself at the exchange ([Changes.onto]). So nothing here carries a time of the
     * phone's own making, and a phone that has agreed nothing yet has nothing to say.
     */
    fun choices(settings: Settings): Shared {
        val agreed = settings.agreed
        val lists = HashMap<String, SharedPrefs>()
        for ((key, mine) in agreed.lists) prefs(key, mine)?.let { lists[key] = it }
        val theme = agreed.theme in WINDOWS_THEMES
        return Shared(layout = LAYOUT, pins = agreed.pins, pinsAt = agreed.pinsAt, theme = if (theme) agreed.theme else "", themeAt = if (theme) agreed.themeAt else 0, lists = lists)
    }

    /**
     * The latest plays of the log (`log`: every play, oldest first, and `sources`: where
     * those made from a playlist were made from, both from one reading of the log), as they
     * are shared: newest first, each once, with the sources of those that are kept. As
     * Windows: the last [PLAYS_SHARED] lines of the log, sorted.
     */
    fun plays(log: List<Pair<String, Long>>, sources: List<Triple<String, Long, String>>): Pair<List<Play>, List<PlayFrom>> {
        val latest = ArrayList<Play>(minOf(log.size, PLAYS_SHARED))
        for (i in log.size - 1 downTo maxOf(0, log.size - PLAYS_SHARED)) latest += Play(log[i].first, log[i].second)
        val plays = Merge.mergePlays(latest, emptyList())
        val kept = plays.toHashSet()
        return plays to sources.filter { Play(it.first, it.second) in kept }.map { PlayFrom(it.first, it.second, it.third) }
    }

    /**
     * The settings with what came back from the server taken in (Windows' `take_shared`):
     * what is agreed becomes the record's, where the record's is newer; the changes that
     * were made to the record before it was written (`applied`) are forgotten; and what the
     * phone shows is the agreed with the changes still waiting on top. "Still waiting" is
     * read from the settings at this moment: a change made while the exchange was out is
     * among them, and stays, on screen and to be shared.
     */
    fun taken(settings: Settings, theirs: Shared, applied: List<Waiting> = emptyList()): Settings {
        val before = settings.agreed
        var agreed = before
        if (Merge.pinsNewer(theirs.pinsAt, theirs.pins, before.pinsAt, before.pins)) agreed = agreed.copy(pins = theirs.pins, pinsAt = theirs.pinsAt)
        // A theme this phone doesn't have is left for the devices that do.
        if (theirs.theme in WINDOWS_THEMES && Merge.themeNewer(theirs.themeAt, theirs.theme, before.themeAt, before.theme)) {
            agreed = agreed.copy(theme = theirs.theme, themeAt = theirs.themeAt)
        }
        var lists: HashMap<String, ListPrefs>? = null
        for ((key, prefs) in theirs.lists) {
            val mine = before.lists[key]
            val shared = mine?.let { SharedPrefs(it.shuffle, it.sort.coerceAtLeast(0).toULong(), it.descending, it.sortChosen, it.at) }
            if (shared == null || Merge.rank(prefs, shared) > 0) {
                (lists ?: HashMap(before.lists).also { lists = it })[key] =
                    ListPrefs(prefs.shuffle, minOf(prefs.sort, Int.MAX_VALUE.toULong()).toInt(), prefs.descending, prefs.sortChosen, prefs.at)
            }
        }
        lists?.let { agreed = agreed.copy(lists = it) }
        // Each change that was applied is gone, once: one made again meanwhile still waits.
        val waiting = ArrayList(settings.waiting)
        for (change in applied) waiting.remove(change)

        // What the phone shows. Where the record says nothing (no pins and no time on them, a
        // list it has no entry for, no theme), what the phone has of its own stays.
        var out = settings.copy(agreed = agreed, waiting = waiting)
        if (agreed.pins.isNotEmpty() || agreed.pinsAt != 0L) out = out.copy(pinnedPlaylists = Changes.shown(agreed.pins, waiting))
        if (agreed.theme.isNotEmpty()) out = out.copy(theme = waiting.lastOrNull { it.what == Changes.THEME }?.key ?: agreed.theme)
        if (agreed.lists.isNotEmpty()) {
            val shown = HashMap(settings.lists)
            for ((key, prefs) in agreed.lists) {
                val here = settings.lists[key]
                // An order only the phone offers is the phone's own choice for that list: it
                // stays unless the one arriving was chosen after it.
                val own = here != null && here.sort !in 0..WINDOWS_SORT_MOST && prefs.at <= here.at
                val made = Changes.shown(prefs, key, waiting)
                shown[key] = if (own) made.copy(sort = here!!.sort, descending = here.descending, sortChosen = here.sortChosen, at = here.at) else made
            }
            out = out.copy(lists = shown)
        }
        return out
    }

    fun notes(notes: Map<String, Map<String, AddedNote>>): Map<String, Map<String, SharedNote>> =
        notes.mapValues { (_, songs) -> songs.mapValues { (_, note) -> SharedNote(note.times, note.at) } }

    fun added(notes: Map<String, Map<String, SharedNote>>): Map<String, Map<String, AddedNote>> =
        notes.mapValues { (_, songs) -> songs.mapValues { (_, note) -> AddedNote(note.times, note.at) } }
}

/**
 * The phone's side of an exchange, over its own stores ([StepPhone]): the settings (for the
 * pins, the theme and each list's choices), the notes of when songs were added, Recently
 * played, and the log of plays.
 */
class PhoneShared(
    private val settings: SettingsStore,
    /** The account's key on the phone: the shared choices in the settings must be its own. */
    private val account: String,
    private val added: Added,
    private val history: RecentlyPlayed,
    private val log: PlayLog,
    /** Recently played and the log of plays have been read from the phone for this account. */
    private val read: () -> Boolean,
    /** The ids of the account's playlists as the phone keeps them; null if no list of them
     *  is kept. */
    private val playlists: suspend () -> Set<String>?,
    /** The songs with these ids, for the ones that can be found. */
    private val songs: suspend (List<String>) -> Map<String, Song>,
    /** The dates on screen may have changed. */
    private val datesChanged: () -> Unit = {},
) : StepPhone {
    override suspend fun record(): Shared? {
        val now = settings.value
        // Not before the choices in the settings are this account's own, nor before its
        // history and its log have been read from the phone: an empty list that means "not
        // read yet" is never sent as "none". (The notes of when songs were added are read
        // from the phone the first time they are asked for, which is here.)
        if (!Belongs.ready(now, account) || !read()) return null
        // Before the playlists have loaded there's no telling which still exist.
        val known = playlists()?.takeIf { it.isNotEmpty() }
        // (The plays and their sources from one and the same moment of the log.)
        val (plays, sources) = log.sharedWithSources().let { (all, from) -> Sharing.plays(all, from) }
        return Sharing.choices(now).copy(
            added = Sharing.notes(added.shared(known)),
            history = history.state.value.map { Play(it.song.id, it.at) },
            plays = plays,
            playSources = sources,
        )
    }

    /** What is waiting in the settings as the last exchange left it: a change to it since
     *  is one made here, to pass on. */
    @Volatile var settled: List<Waiting> = emptyList()
        private set

    override fun withChanges(merged: Shared, now: Long): Changed {
        val waiting = settings.value.let { if (Belongs.ready(it, account)) it.waiting else emptyList() }
        return Changed(Changes.onto(merged, waiting, now), waiting)
    }

    override suspend fun take(theirs: Shared, applied: List<Waiting>) {
        // Only into settings that are still this account's: an exchange that comes back
        // after the account has gone changes nothing.
        settings.update {
            if (!Belongs.ready(it, account)) return@update it
            // (Noted before the settings change, so whoever follows them sees it was this.)
            Sharing.taken(it, theirs, applied).also { taken -> settled = taken.waiting }
        }
        if (!Belongs.ready(settings.value, account)) return
        if (added.merge(Sharing.added(theirs.added))) datesChanged()
        // Plays counted on other devices go into this one's log, so the stats cover them all.
        // What is taken in must be kept: if the phone won't write it, the exchange has failed
        // as a whole (this throws), and is made again later, when the same plays are offered
        // again. Nothing is counted as taken in that the phone could not keep.
        if (log.absorb(theirs.plays.map { it.song to it.at }, theirs.playSources.map { Triple(it.song, it.at, it.from) }) > 0) log.flushed()
        // Recently played: both devices' plays, in order.
        history.take(theirs.history, songs)
    }
}
