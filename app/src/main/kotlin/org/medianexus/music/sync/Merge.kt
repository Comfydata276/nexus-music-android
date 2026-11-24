package org.medianexus.music.sync

/**
 * Combining what two devices know: a port of `merge`, `merge_history`, `merge_plays` and
 * `merge_sources` in the Windows app's src/sync.rs and `merge_notes` in its src/added.rs,
 * held to cases the Windows code itself wrote (`InStepCasesTest`).
 *
 * Three things a port gets wrong by itself, each met here on purpose:
 * - Rust compares text by its bytes; Kotlin by UTF-16 units ([SharedText.bytewise]).
 * - Rust compares a tuple field by field in order; here that order is spelt out ([rank]).
 * - Rust's sort keeps equal items in the order given, and so does Kotlin's `sortedWith`; the
 *   removals of repeats that follow depend on it.
 */
object Merge {
    private val newestFirst = Comparator<Play> { x, y -> if (x.at != y.at) y.at.compareTo(x.at) else SharedText.bytewise(x.song, y.song) }

    /** Rust's `dedup_by`: drops an item for which `same(it, the one kept before it)`. */
    private inline fun <T> List<T>.dedup(same: (T, T) -> Boolean): List<T> {
        val out = ArrayList<T>(size)
        for (item in this) if (out.isEmpty() || !same(item, out[out.size - 1])) out += item
        return out
    }

    /** Puts plays from two devices into one list, newest first: Recently played. The same
     *  song twice running is one stretch of listening. */
    fun mergeHistory(a: List<Play>, b: List<Play>): List<Play> =
        (a + b).sortedWith(newestFirst).dedup { x, y -> x == y }.dedup { later, earlier -> later.song == earlier.song }.take(HISTORY_LENGTH)

    /** Puts the plays two devices know of into one list, newest first, each play once. */
    fun mergePlays(a: List<Play>, b: List<Play>): List<Play> = (a + b).sortedWith(newestFirst).dedup { x, y -> x == y }.take(PLAYS_SHARED)

    /** Where the plays being passed on were made from, as far as either device knows. */
    private fun mergeSources(a: Shared, b: Shared, plays: List<Play>): List<PlayFrom> {
        val kept = plays.toHashSet()
        return (a.playSources + b.playSources).filter { Play(it.song, it.at) in kept }
            .sortedWith { x, y ->
                if (x.at != y.at) y.at.compareTo(x.at)
                else SharedText.bytewise(x.song, y.song).let { if (it != 0) it else SharedText.bytewise(x.from, y.from) }
            }
            .dedup { later, earlier -> later.song == earlier.song && later.at == earlier.at }
    }

    /** Which of two sets of choices for a list wins: the newer, and equally new, an arbitrary
     *  one that is the same whichever way round they are given (Windows' `list_rank`: time,
     *  shuffle, sort, which way round, whether chosen; false before true). */
    fun rank(a: SharedPrefs, b: SharedPrefs): Int {
        if (a.at != b.at) return a.at.compareTo(b.at)
        if (a.shuffle != b.shuffle) return a.shuffle.compareTo(b.shuffle)
        if (a.sort != b.sort) return a.sort.compareTo(b.sort)
        if (a.descending != b.descending) return a.descending.compareTo(b.descending)
        return a.sortChosen.compareTo(b.sortChosen)
    }

    /** Lists of text compared as Rust compares them: item by item, then the shorter first. */
    private fun bytewise(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = SharedText.bytewise(a[i], b[i])
            if (d != 0) return d
        }
        return a.size.compareTo(b.size)
    }

    /** Whether pins `a`, changed at `aAt`, are newer than pins `b`: by time, then by the pins
     *  themselves, so that two devices always settle on the same ones. */
    fun pinsNewer(aAt: Long, a: List<String>, bAt: Long, b: List<String>): Boolean = if (aAt != bAt) aAt > bAt else bytewise(a, b) > 0

    /** The same for the theme. */
    fun themeNewer(aAt: Long, a: String, bAt: Long, b: String): Boolean = if (aAt != bAt) aAt > bAt else SharedText.bytewise(a, b) > 0

    /** Combines two sets of shared notes: for each song, the newer one (of two equally new,
     *  `a`'s); word that a song was removed is dropped once it has had long enough to get
     *  round. */
    fun mergeNotes(a: Map<String, Map<String, SharedNote>>, b: Map<String, Map<String, SharedNote>>, now: Long): Map<String, Map<String, SharedNote>> {
        val out = HashMap<String, HashMap<String, SharedNote>>()
        for ((playlist, notes) in a) out[playlist] = HashMap(notes)
        for ((playlist, notes) in b) {
            val mine = out.getOrPut(playlist) { HashMap() }
            for ((id, note) in notes) {
                val have = mine[id]
                if (have == null || note.at > have.at) mine[id] = note
            }
        }
        // (As Rust works it out: the difference wraps round rather than overflowing.)
        for (notes in out.values) notes.values.removeAll { it.times.isEmpty() && now - it.at >= KEEP_REMOVED }
        out.values.removeAll { it.isEmpty() }
        return out
    }

    /**
     * Combines what two devices know. For each item the newer wins; equally new, the choice
     * is arbitrary but the same whichever way round they're given, so devices end up
     * agreeing. (Windows' `merge`. Fields neither side's version knows are not its business.)
     */
    fun merge(a: Shared, b: Shared, now: Long): Shared {
        val pinsFromA = !pinsNewer(b.pinsAt, b.pins, a.pinsAt, a.pins)
        val themeFromA = !themeNewer(b.themeAt, b.theme, a.themeAt, a.theme)
        val lists = HashMap(a.lists)
        for ((key, theirs) in b.lists) {
            val mine = lists[key]
            if (mine == null || rank(theirs, mine) > 0) lists[key] = theirs
        }
        val plays = mergePlays(a.plays, b.plays)
        return Shared(
            layout = LAYOUT,
            pins = if (pinsFromA) a.pins else b.pins,
            pinsAt = maxOf(a.pinsAt, b.pinsAt),
            theme = if (themeFromA) a.theme else b.theme,
            themeAt = maxOf(a.themeAt, b.themeAt),
            lists = lists,
            added = mergeNotes(a.added, b.added, now),
            history = mergeHistory(a.history, b.history),
            plays = plays,
            playSources = mergeSources(a, b, plays),
        )
    }
}
