package org.medianexus.music.sync

import org.medianexus.music.net.Song
import org.medianexus.music.store.Added
import org.medianexus.music.store.AddedNote
import org.medianexus.music.store.DEFAULT_THEME
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.Settings
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

    /** A time for sharing: a choice with none is given Windows' "long ago", so it is passed
     *  on but loses to anything chosen since. Never now. */
    private fun timed(at: Long): Long = if (at == 0L) LONG_AGO else at

    /** When the pins were last changed, as shared: none set, and no time, is "never". */
    fun pinsAt(settings: Settings): Long = if (settings.pinsAt == 0L && settings.pinnedPlaylists.isNotEmpty()) LONG_AGO else settings.pinsAt

    /**
     * The theme the phone has to tell this account's other devices of, and when it was
     * chosen: the one showing, if it was chosen under this account or came from its record
     * and is one Windows has; else none. The default theme with no time on it is passed on as
     * that, with no time: it was never chosen.
     */
    fun theme(settings: Settings): Pair<String, Long> = when {
        !settings.themeOwn || settings.theme !in WINDOWS_THEMES -> "" to 0L
        settings.themeAt == 0L && settings.theme != DEFAULT_THEME -> settings.theme to LONG_AGO
        else -> settings.theme to settings.themeAt
    }

    /** One list's choices as shared, or null if they are the phone's alone: a key Windows
     *  doesn't have, or an order it has no number for. */
    fun prefs(key: String, prefs: ListPrefs): SharedPrefs? =
        if (!windowsKey(key) || prefs.sort !in 0..WINDOWS_SORT_MOST) null
        else SharedPrefs(prefs.shuffle, prefs.sort.toULong(), prefs.descending, prefs.sortChosen, timed(prefs.at))

    /** The settings' part of what the phone has to share: the pins, the theme and each
     *  list's choices, each with the time it has. */
    fun choices(settings: Settings): Shared {
        val (theme, themeAt) = theme(settings)
        val lists = HashMap<String, SharedPrefs>()
        for ((key, mine) in settings.lists) prefs(key, mine)?.let { lists[key] = it }
        return Shared(layout = LAYOUT, pins = settings.pinnedPlaylists, pinsAt = pinsAt(settings), theme = theme, themeAt = themeAt, lists = lists)
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
     * The settings with what came back from the server taken in, where it's newer than
     * what's here (Windows' `take_shared`). "Here" is what the settings hold at this moment,
     * not what was sent: a choice made while the exchange was out is newer than anything it
     * brings back, and stays.
     */
    fun taken(settings: Settings, theirs: Shared): Settings {
        var out = settings
        if (Merge.pinsNewer(theirs.pinsAt, theirs.pins, pinsAt(settings), settings.pinnedPlaylists)) {
            out = out.copy(pinnedPlaylists = theirs.pins, pinsAt = theirs.pinsAt)
        }
        // A theme this phone doesn't have is left for the devices that do.
        if (theirs.theme in WINDOWS_THEMES) {
            val (mine, mineAt) = theme(settings)
            if (Merge.themeNewer(theirs.themeAt, theirs.theme, mineAt, mine)) out = out.copy(theme = theirs.theme, themeAt = theirs.themeAt, themeOwn = true)
        }
        var lists: HashMap<String, ListPrefs>? = null
        for ((key, prefs) in theirs.lists) {
            val mine = settings.lists[key]
            // (Compared as it would be shared: with the time it has, or "long ago" for none.)
            val shared = mine?.let { SharedPrefs(it.shuffle, it.sort.coerceAtLeast(0).toULong(), it.descending, it.sortChosen, timed(it.at)) }
            if (shared == null || Merge.rank(prefs, shared) > 0) {
                val taken = ListPrefs(prefs.shuffle, minOf(prefs.sort, Int.MAX_VALUE.toULong()).toInt(), prefs.descending, prefs.sortChosen, prefs.at)
                if (taken != mine) (lists ?: HashMap(settings.lists).also { lists = it })[key] = taken
            }
        }
        lists?.let { out = out.copy(lists = it) }
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
    override var playlist: String
        get() = settings.value.let { if (Belongs.ready(it, account)) it.syncPlaylist else "" }
        // Remembered, so the next start needn't look for it.
        set(id) = settings.update { if (Belongs.ready(it, account) && it.syncPlaylist != id) it.copy(syncPlaylist = id) else it }

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

    /** The choices in the settings as the last exchange left them: a change to them since
     *  is one made here, to pass on. */
    @Volatile var settled: Shared? = null
        private set

    override suspend fun take(theirs: Shared) {
        // Only into settings that are still this account's: an exchange that comes back
        // after the account has gone changes nothing.
        settings.update {
            if (!Belongs.ready(it, account)) return@update it
            // (Noted before the settings change, so whoever follows them sees it was this.)
            Sharing.taken(it, theirs).also { taken -> settled = Sharing.choices(taken) }
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
