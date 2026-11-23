package org.medianexus.music.playback

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import org.medianexus.music.net.Song

/** Repeat, as the Windows app numbers it (and saves it in the settings): 0 off, 1 the whole
 *  queue, 2 the song that's playing. */
object Repeat {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2
}

/**
 * The play queue: a port of the queue in the Windows app's `src/playback.rs`, whose tests
 * are its specification. Plain Kotlin, no Android, so it can be tested on its own.
 *
 * The songs are kept in the order they were given (`songs`); `order` is the order they play
 * in, as places in `songs`; `cursor` is the place in `order` that's playing. Shuffling only
 * changes `order`, so turning it off again finds the list as it was.
 *
 * "Next in queue" is the run of songs straight after the current one that were queued by
 * hand (`Song.queued`): they play before the rest of the list, and stay next through a
 * shuffle or a jump.
 *
 * Every entry also has a number of its own (`uids`), never reused, so the player that plays
 * the queue can tell its entries apart even when one song is in it twice.
 */
class Queue {
    var songs = mutableListOf<Song>()
        private set
    var uids = mutableListOf<Long>()
        private set
    var order = mutableListOf<Int>()
        private set
    var cursor = 0
    /** What's playing, in words ("Slow Rivers", "Liked songs"): the queue's title. */
    var context = ""
    /** The page the queue was started from (its route key), and for each entry of `songs`
     *  its position in that page's song list. */
    var source = ""
    var origins = mutableListOf<Int?>()
        private set
    /** The ids of every song in that page's list, as last seen. */
    var sourceList = mutableListOf<String>()
        private set
    var shuffle = false
    var repeat = Repeat.OFF

    /** Moves on whenever the songs or the order they play in change (not when the player
     *  merely moves along them): two readings of the same number are the same queue, entry
     *  for entry and place for place. */
    var revision = 0L
        private set

    val isEmpty: Boolean get() = order.isEmpty()

    fun current(): Song? = order.getOrNull(cursor)?.let { songs.getOrNull(it) }

    fun currentUid(): Long? = order.getOrNull(cursor)?.let { uids.getOrNull(it) }

    /** Where the song playing sits in the list the queue came from, if it came from one. */
    fun currentOrigin(): Int? = order.getOrNull(cursor)?.let { origins.getOrNull(it) }

    /** The songs in the order they play. */
    fun playingOrder(): List<Song> = order.map { songs[it] }

    /** The entries' numbers in the order they play. */
    fun playingUids(): List<Long> = order.map { uids[it] }

    /** The place in the playing order of the entry with this number, or -1. */
    fun placeOf(uid: Long): Int = order.indexOfFirst { uids[it] == uid }

    /** The place in the playing order that follows when the current song ends by itself. */
    fun following(): Int? = when {
        order.isEmpty() -> null
        repeat == Repeat.ONE -> cursor
        cursor + 1 < order.size -> cursor + 1
        repeat == Repeat.ALL -> 0
        else -> null
    }

    /** How many of the entries straight after the current one were queued by hand: the
     *  length of "Next in queue". */
    fun queuedAhead(): Int {
        var count = 0
        for (place in cursor + 1 until order.size) {
            if (!songs[order[place]].queued) break
            count++
        }
        return count
    }

    private fun newUid() = uidCounter.getAndIncrement()

    /**
     * Becomes `other`, which was made ready (perhaps on another thread) to take this one's
     * place, by taking what it holds: nothing is copied, however long it is, and `other` is
     * spent and must not be used again. Repeat is the player's own setting and stays.
     */
    fun adopt(other: Queue) {
        revision++
        songs = other.songs
        uids = other.uids
        order = other.order
        origins = other.origins
        sourceList = other.sourceList
        cursor = other.cursor
        context = other.context
        source = other.source
        shuffle = other.shuffle
    }

    // ---- Starting afresh ------------------------------------------------------------------

    /**
     * Replaces the queue with these songs, to play from `start`. `anyStart` says no particular
     * song was asked for, so a shuffled list may open with any of them. Nothing in a new queue
     * was queued by hand.
     */
    fun replace(
        list: List<Song>,
        start: Int,
        context: String,
        source: String = "",
        origins: List<Int?>? = null,
        sourceList: List<String> = emptyList(),
        shuffle: Boolean = this.shuffle,
        anyStart: Boolean = false,
        random: Random = Random.Default,
    ) {
        revision++
        songs.clear()
        list.mapTo(songs) { if (it.queued) it.copy(queued = false) else it }
        uids.clear()
        repeat(songs.size) { uids += newUid() }
        order.clear()
        order.addAll(songs.indices)
        this.origins.clear()
        if (origins != null && origins.size == songs.size) this.origins.addAll(origins) else repeat(songs.size) { this.origins += null }
        this.sourceList.clear()
        this.sourceList.addAll(sourceList)
        this.context = context
        this.source = source
        this.shuffle = shuffle
        cursor = if (songs.isEmpty()) 0 else if (shuffle && anyStart) random.nextInt(songs.size) else start.coerceIn(0, songs.size - 1)
        if (shuffle) shuffleOrder(random)
    }

    /** Puts back a queue exactly as it was saved: the songs, their order and the place. */
    fun restore(saved: SavedQueue) {
        revision++
        songs.clear()
        songs.addAll(saved.songs)
        uids.clear()
        repeat(songs.size) { uids += newUid() }
        order.clear()
        order.addAll(saved.order)
        cursor = saved.cursor
        context = saved.context
        source = saved.source
        origins.clear()
        origins.addAll(saved.origins)
        while (origins.size < songs.size) origins += null
        while (origins.size > songs.size) origins.removeAt(origins.size - 1)
        sourceList.clear()
        sourceList.addAll(saved.sourceList)
    }

    fun clear() {
        revision++
        songs.clear()
        uids.clear()
        order.clear()
        origins.clear()
        sourceList.clear()
        cursor = 0
        context = ""
        source = ""
    }

    // ---- Moving through it ----------------------------------------------------------------

    /** The player has moved on to this place in the playing order (by itself, or because
     *  next, previous or a queue entry was pressed). */
    fun moveTo(place: Int) {
        if (place !in order.indices) return
        cursor = place
        trackChanged()
    }

    /** What "next" does, and whether playback carries on afterwards. */
    data class Step(val place: Int, val carriesOn: Boolean)

    /**
     * Where pressing Next goes, as Windows has it (`next_track` in src/playback.rs): the
     * following song; from the last one, round to the top, and playing on from there only
     * if repeat is on (either kind). Null with nothing queued.
     */
    fun next(): Step? = when {
        order.isEmpty() -> null
        cursor + 1 < order.size -> Step(cursor + 1, true)
        else -> Step(0, repeat != Repeat.OFF)
    }

    /** The song that's playing has had its turn: it's no longer waiting in the queue. */
    fun trackChanged() {
        val entry = order.getOrNull(cursor) ?: return
        if (songs[entry].queued) songs[entry] = songs[entry].copy(queued = false)
    }

    // ---- Shuffle --------------------------------------------------------------------------

    /** Puts the current track first and shuffles the rest behind it. */
    internal fun shuffleOrder(random: Random = Random.Default) {
        val current = order.getOrNull(cursor) ?: return
        val rest = songs.indices.filter { it != current }.shuffled(random)
        order.clear()
        order += current
        order += rest
        cursor = 0
    }

    /**
     * Rearranges the order with `rearrange`, then puts what was queued by hand back where it
     * was: straight after the current track, in the order it was queued.
     */
    internal fun rearrangeKeepingQueued(rearrange: Queue.() -> Unit) {
        // Nothing queued, nothing to arrange: an empty queue has no "after the current
        // track" to take a slice of.
        if (order.isEmpty()) return
        val ahead = order.subList(cursor + 1, cursor + 1 + queuedAhead()).toList()
        rearrange()
        val current = order.getOrNull(cursor) ?: return
        if (ahead.isEmpty()) return
        val queued = ahead.toHashSet()
        order.removeAll { it in queued }
        cursor = order.indexOf(current).coerceAtLeast(0)
        for ((place, entry) in ahead.withIndex()) order.add(cursor + 1 + place, entry)
    }

    /**
     * Turns shuffle on or off for what's playing. The current song carries on: turning it on
     * shuffles what follows; turning it off continues from this song in the list's order.
     * Songs queued by hand stay next either way.
     */
    fun setShuffle(on: Boolean, random: Random = Random.Default) {
        if (shuffle == on) return
        shuffle = on
        revision++
        if (on) {
            rearrangeKeepingQueued { shuffleOrder(random) }
        } else {
            val current = order.getOrNull(cursor) ?: return
            rearrangeKeepingQueued {
                order.clear()
                order.addAll(songs.indices)
                cursor = current
            }
        }
    }

    // ---- Editing --------------------------------------------------------------------------

    /** Takes the entry at this place in the playing order out of the queue. */
    fun dropAt(at: Int) {
        if (at !in order.indices) return
        revision++
        val removed = order.removeAt(at)
        songs.removeAt(removed)
        uids.removeAt(removed)
        if (removed < origins.size) origins.removeAt(removed)
        for (i in order.indices) if (order[i] > removed) order[i] -= 1
        if (at < cursor) cursor -= 1
        if (cursor >= order.size) cursor = (order.size - 1).coerceAtLeast(0)
    }

    /**
     * Queues songs by hand. They go into "Next in queue", which plays straight after the
     * current track and ahead of the rest of whatever list is playing: at the top of it with
     * `playNext`, otherwise at the bottom. A song that's already further down the list is
     * queued all the same, and plays again when its turn in the list comes. Into an empty
     * queue they simply become the queue, ready to play.
     */
    fun queueUp(list: List<Song>, playNext: Boolean) {
        if (list.isEmpty()) return
        if (order.isEmpty()) {
            // As on Windows: in the order given, whatever shuffle is set to.
            val keep = shuffle
            replace(list, 0, context = "", shuffle = false)
            shuffle = keep
            return
        }
        revision++
        var at = cursor + 1 + if (playNext) 0 else queuedAhead()
        for (song in list) {
            songs += song.copy(queued = true)
            uids += newUid()
            order.add(at, songs.size - 1)
            at++
        }
        while (origins.size < songs.size) origins += null
    }

    /** Moves the entry `from` places after the current track to `to` places after it, into
     *  "Next in queue" or out of it as `queued` says. */
    fun moveAhead(from: Int, to: Int, queued: Boolean) {
        val base = cursor + 1
        val (f, t) = base + from to base + to
        if (from < 0 || to < 0 || f >= order.size || t >= order.size) return
        revision++
        val entry = order.removeAt(f)
        order.add(t, entry)
        songs[entry] = songs[entry].copy(queued = queued)
    }

    /** Moves entries in the playing order as a player is asked to: `from` until `to` go to
     *  `newIndex`. Whether each was queued by hand stays as it was. */
    fun move(from: Int, to: Int, newIndex: Int) {
        if (from < 0 || to > order.size || from >= to || newIndex < 0) return
        revision++
        val current = order.getOrNull(cursor)
        val moving = order.subList(from, to).toList()
        repeat(to - from) { order.removeAt(from) }
        order.addAll(newIndex.coerceAtMost(order.size), moving)
        cursor = order.indexOf(current).coerceAtLeast(0)
    }

    /** Empties part of what's to come: with `queued`, the songs queued by hand; otherwise
     *  the rest of the list behind them. */
    fun clearAhead(queued: Boolean) {
        val ahead = queuedAhead()
        if (queued) dropPlaces(cursor + 1, cursor + 1 + ahead) else dropPlaces(cursor + 1 + ahead, order.size)
    }

    /** Takes the entries at places `from` up to `to` of the playing order out, in one pass
     *  however many they are. */
    fun dropPlaces(from: Int, to: Int) {
        val start = from.coerceIn(0, order.size)
        val end = to.coerceIn(start, order.size)
        if (start == end) return
        val gone = BooleanArray(songs.size)
        for (place in start until end) gone[order[place]] = true
        drop(gone)
    }

    /** Takes the entries with these numbers out, wherever they are, in one pass. */
    fun dropUids(numbers: Set<Long>) {
        if (numbers.isEmpty()) return
        val gone = BooleanArray(songs.size) { uids[it] in numbers }
        if (gone.any { it }) drop(gone)
    }

    /** Removes the marked entries of `songs`. If the current one goes, the one that followed
     *  it (or failing that the last) is current. */
    private fun drop(gone: BooleanArray) {
        revision++
        // How many places before the cursor survive: where the cursor ends up.
        var before = 0
        for (place in 0 until cursor.coerceAtMost(order.size)) if (!gone[order[place]]) before++
        val moved = IntArray(songs.size)
        var kept = 0
        for (entry in songs.indices) {
            if (gone[entry]) continue
            moved[entry] = kept
            songs[kept] = songs[entry]
            uids[kept] = uids[entry]
            if (entry < origins.size) origins[kept] = origins[entry]
            kept++
        }
        while (songs.size > kept) songs.removeAt(songs.size - 1)
        while (uids.size > kept) uids.removeAt(uids.size - 1)
        while (origins.size > kept) origins.removeAt(origins.size - 1)
        var place = 0
        for (entry in order) {
            if (gone[entry]) continue
            order[place++] = moved[entry]
        }
        while (order.size > place) order.removeAt(order.size - 1)
        cursor = before.coerceAtMost(order.size - 1).coerceAtLeast(0)
    }

    /**
     * Makes the entry `offset` places after the current track the current one. Songs queued
     * by hand that it jumps over aren't lost: they stay next, after it. Returns whether
     * there was such an entry.
     */
    fun jumpAhead(offset: Int): Boolean {
        val at = cursor + 1 + offset
        if (offset < 0 || at >= order.size) return false
        revision++
        val ahead = queuedAhead()
        if (offset < ahead) {
            // One of the queued songs: it comes to the front of them.
            val entry = order.removeAt(at)
            cursor += 1
            order.add(cursor, entry)
        } else {
            val waiting = order.subList(cursor + 1, cursor + 1 + ahead).toList()
            repeat(ahead) { order.removeAt(cursor + 1) }
            cursor = at - ahead
            for ((place, entry) in waiting.withIndex()) order.add(cursor + 1 + place, entry)
        }
        trackChanged()
        return true
    }

    // ---- Editing by an entry's own number ------------------------------------------------------
    //
    // What the phone's queue sheet asks for. It shows the queue as it was a moment ago, and a
    // song can end between a tap and its command arriving: "the third one down" would then be
    // a different song. So the sheet names the entry it means, and the place is found here,
    // now.

    /** How many places after the current song the entry with this number is; -1 if it isn't
     *  ahead (it has started playing, been played, or left the queue). */
    fun aheadOf(uid: Long): Int {
        val place = placeOf(uid)
        return if (place > cursor) place - cursor - 1 else -1
    }

    /** Takes this entry out of what's to come. False (and nothing done) if it is no longer
     *  to come. */
    fun dropUid(uid: Long): Boolean {
        val offset = aheadOf(uid)
        if (offset < 0) return false
        dropAt(cursor + 1 + offset)
        return true
    }

    /**
     * Moves this entry to straight after the entry `after` (null: straight after the song
     * playing), into "Next in queue" or out of it as `queued` says. If `after` has already
     * had its turn, straight after the song playing is the nearest there is to where it was
     * dropped. False if the entry itself is no longer to come.
     */
    fun moveUid(uid: Long, after: Long?, queued: Boolean): Boolean {
        val from = aheadOf(uid)
        if (from < 0) return false
        val anchor = if (after == null || after == uid) -1 else aheadOf(after)
        // Where the anchor will be once the entry has been lifted out, and the place after it.
        val to = if (anchor < 0) 0 else anchor - (if (anchor > from) 1 else 0) + 1
        moveAhead(from, to, queued)
        return true
    }

    /** Makes this entry the current one (see [jumpAhead]). False if it is no longer to come. */
    fun jumpToUid(uid: Long): Boolean {
        val offset = aheadOf(uid)
        return offset >= 0 && jumpAhead(offset)
    }

    /**
     * The list the queue was started from now reads `ids` (`songAt` gives its songs): songs
     * that left it leave the queue, songs that joined it join, and the rest take up its new
     * order, as [followList] has it. Returns false if there was nothing to follow (another
     * list, or no change).
     */
    fun follow(ids: List<String>, songAt: (Int) -> Song?, random: Random = Random.Default): Boolean {
        if (isEmpty || sourceList.isEmpty() || sourceList == ids) return false
        revision++
        val (moved, arrivals) = compareLists(sourceList, ids)
        val lineup = followList(
            Lineup(songs.toList(), origins.toList(), order.toList(), cursor, uids.toList()),
            shuffle, moved, arrivals, ids.indices.toList(), songAt,
        ) { n -> random.nextInt(n.coerceAtLeast(1)) }
        songs.clear(); songs.addAll(lineup.songs)
        uids.clear(); lineup.entries.mapTo(uids) { if (it < 0) newUid() else it }
        origins.clear(); origins.addAll(lineup.origins)
        order.clear(); order.addAll(lineup.order)
        cursor = lineup.cursor
        sourceList.clear(); sourceList.addAll(ids)
        return true
    }

    /** A copy of the queue as it stands, for saving. */
    fun saved(position: Double, stamp: Long): SavedQueue = SavedQueue(
        songs = songs.toList(),
        order = order.toList(),
        cursor = cursor,
        position = position,
        context = context,
        source = source,
        origins = origins.toList(),
        stamp = stamp,
        sourceList = sourceList.toList(),
    )
}

/** Entry numbers, never reused while the app runs. */
private val uidCounter = AtomicLong(1)

// ---- Following the list a queue came from ----------------------------------------------------

/**
 * Compares a list of song ids as it was with how it is now. Returns where each old entry has
 * gone (null if it's no longer there), and which of the new entries are arrivals. A song
 * that's in the list more than once is matched copy for copy.
 */
fun compareLists(old: List<String>, new: List<String>): Pair<List<Int?>, List<Int>> {
    val places = HashMap<String, ArrayDeque<Int>>()
    for ((index, id) in new.withIndex()) places.getOrPut(id) { ArrayDeque() }.addLast(index)
    val moved = old.map { id -> places[id]?.removeFirstOrNull() }
    val arrivals = places.values.flatten().sorted()
    return moved to arrivals
}

/** The queue as [followList] sees it: its songs, where each sits in the list it came from,
 *  the order they play in, and which of those is playing. `entries`, if given, is a number
 *  for each song that goes with it wherever it ends up (the queue's entry numbers); an
 *  arrival gets -1. */
data class Lineup(
    val songs: List<Song>,
    val origins: List<Int?>,
    val order: List<Int>,
    val cursor: Int,
    val entries: List<Long> = emptyList(),
)

/**
 * Works out the queue after the list it came from has changed.
 *
 * Songs that left the list leave the queue, except the one playing. Songs that joined it
 * are added: in their place if the queue plays in the list's order, somewhere among what's
 * still to come if it's shuffled. Songs the list was reordered around take up the new order.
 * Songs that were never part of the list (added to the queue by hand) keep their place after
 * whatever they followed.
 *
 * `moved` and `arrivals` are from [compareLists]; `view` is the new list in the order its
 * page shows it; `songAt` gives the song at a position in the new list; `pick(n)` chooses a
 * number below `n`.
 */
fun followList(
    lineup: Lineup,
    shuffled: Boolean,
    moved: List<Int?>,
    arrivals: List<Int>,
    view: List<Int>,
    songAt: (Int) -> Song?,
    pick: (Int) -> Int,
): Lineup {
    val (songs, origins, order, cursor, entries) = lineup
    val current = order.getOrNull(cursor) ?: return lineup
    // Where each entry now sits in the list, and which entries go.
    val now = origins.map { origin -> origin?.let { moved.getOrNull(it) } }
    fun gone(entry: Int) = origins[entry] != null && now[entry] == null && entry != current
    // The arrangement to keep for entries that aren't in the list: the playing order, or
    // when shuffled the order underneath the shuffle.
    val base = if (shuffled) songs.indices.toList() else order
    val followers = HashMap<Int?, MutableList<Int>>()
    val holder = HashMap<Int, Int>()
    var last: Int? = null
    for (entry in base) {
        if (gone(entry)) continue
        val place = now[entry]
        if (place != null) {
            holder[place] = entry
            last = entry
        } else {
            followers.getOrPut(last) { mutableListOf() } += entry
        }
    }
    // Kept entries are >= 0; an arrival at list place p is stored as -(p + 1).
    val items = mutableListOf<Int>()
    followers.remove(null)?.let { items += it }
    val arrivalSet = arrivals.toHashSet()
    for (place in view) {
        val entry = holder[place]
        if (entry != null) {
            items += entry
            followers.remove(entry)?.let { items += it }
        } else if (place in arrivalSet && songAt(place) != null) {
            items += -(place + 1)
        }
    }
    // The new set of songs, and where each old entry went in it.
    val newSongs = mutableListOf<Song>()
    val newOrigins = mutableListOf<Int?>()
    val newEntries = mutableListOf<Long>()
    val went = arrayOfNulls<Int>(songs.size)
    val added = mutableListOf<Int>()
    for (item in items) {
        if (item >= 0) {
            went[item] = newSongs.size
            newSongs += songs[item]
            newOrigins += now[item]
            newEntries += entries.getOrElse(item) { -1L }
        } else {
            val place = -item - 1
            added += newSongs.size
            newSongs += songAt(place)!!
            newOrigins += place
            newEntries += -1L
        }
    }
    val kept = if (entries.size == songs.size) newEntries else emptyList()
    val playing = went[current] ?: 0
    return if (shuffled) {
        val newOrder = order.mapNotNull { went[it] }.toMutableList()
        val at = newOrder.indexOf(playing).coerceAtLeast(0)
        for (entry in added) {
            // Somewhere after the song that's playing.
            val slot = at + 1 + pick(newOrder.size - at)
            newOrder.add(slot.coerceAtMost(newOrder.size), entry)
        }
        Lineup(newSongs, newOrigins, newOrder, at, kept)
    } else {
        Lineup(newSongs, newOrigins, newSongs.indices.toList(), playing, kept)
    }
}
