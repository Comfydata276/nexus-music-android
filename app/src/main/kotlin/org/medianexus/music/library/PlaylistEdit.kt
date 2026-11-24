package org.medianexus.music.library

import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

// Changing a playlist that something else may have changed too: a port of Windows'
// src/playlists.rs, with its tests (PlaylistEditTest).
//
// The page shows a copy of a playlist as it was when the page loaded. Another device, or
// another app, can add or remove songs after that. So a change made on the page is never sent
// as "song number 7" or as the page's whole list: it is described by the songs themselves, and
// carried out on the playlist as the server has it at that moment.
//
// One difference from Windows: songs put back (Undo of a removal, an Insert) each go after a
// song that stayed, not after the one put back before them, and all go in on one pass along
// the list. So putting back half of a long playlist doesn't look along it once for each song;
// and a song put back can't be sent after another copy of its neighbour that someone else
// added in the meantime, ahead of the one it was beside.

/** One song's place in a list where the same song can appear more than once: the song, and
 *  which of its appearances this is (0 for the first). */
data class Spot(val id: String, val nth: Int)

/** A song put in, straight after `after` (or first, with none), with its details, for showing
 *  it while the change waits its turn. */
data class Placed(val song: Song, val after: Spot?)

/**
 * A change to a playlist, described so that it still means the same thing if the playlist has
 * changed in the meantime. The first three are Windows' `Edit`; the rest are the other changes
 * the phone makes to a playlist, which go to the server by the same way (see `Playlists`).
 */
sealed interface PlaylistEdit {
    /** Take these out. */
    
    data class Remove(val spots: List<Spot>) : PlaylistEdit

    /** Put one song straight after another, or first. */
    
    data class Move(val song: Spot, val after: Spot?) : PlaylistEdit

    /** Put songs in, each straight after a song of the list as it was before any of them
     *  went in (or first); several after the same song go in the order given. */
    
    data class Insert(val songs: List<Placed>) : PlaylistEdit

    /** Add songs at the end (Subsonic's `songIdToAdd`, as Windows adds them). */
    
    data class Append(val songs: List<Song>) : PlaylistEdit

    
    data class Rename(val name: String) : PlaylistEdit

    
    data object Delete : PlaylistEdit

    /** A new playlist with these songs (its change's id is the phone's own name for it until
     *  the server has made it: "new:<random>"). */
    
    data class Create(val name: String, val songs: List<Song>) : PlaylistEdit
}

object PlaylistEdits {
    /** The spot of the song at `index`. */
    fun spot(list: List<String>, index: Int): Spot {
        val id = list[index]
        var nth = 0
        for (i in 0 until index) if (list[i] == id) nth++
        return Spot(id, nth)
    }

    /** Where a spot is in a list, if that appearance of the song is there. */
    private fun place(list: List<String>, spot: Spot): Int? {
        var seen = 0
        for ((index, id) in list.withIndex()) {
            if (id != spot.id) continue
            if (seen == spot.nth) return index
            seen++
        }
        return null
    }

    /** Where to put something that goes after `after`: straight after it, first if there's
     *  nothing it goes after, and last if what it went after has gone. */
    private fun following(list: List<String>, after: Spot?): Int =
        if (after == null) 0 else place(list, after)?.let { it + 1 } ?: list.size

    /** Each song of a list in turn, as the spot it is: one pass along the list names them all
     *  (where [spot] for each would look back along the list each time). */
    private class Spots {
        private val seen = HashMap<String, Int>()

        fun next(id: String): Spot {
            val nth = seen.getOrDefault(id, 0)
            seen[id] = nth + 1
            return Spot(id, nth)
        }
    }

    /** The positions of these songs in the list as it is now, lowest first. A song that's no
     *  longer there (or whose second copy is no longer there) is left out. Like [apply],
     *  [restoring] and [removing], one pass along the list however many songs: half of a
     *  playlist of thousands taken out is a look through it once, not once for each song. */
    fun positions(now: List<String>, spots: List<Spot>): List<Int> {
        val wanted = spots.toHashSet()
        val spot = Spots()
        return now.indices.filter { wanted.isNotEmpty() && wanted.remove(spot.next(now[it])) }
    }

    /** Carries out an edit on the list as it is now (Windows' `apply`). Rename and Delete
     *  leave the songs as they are. */
    fun apply(now: List<String>, edit: PlaylistEdit): List<String> = when (edit) {
        is PlaylistEdit.Remove -> {
            val gone = positions(now, edit.spots).toHashSet()
            now.filterIndexed { index, _ -> index !in gone }
        }
        is PlaylistEdit.Move -> now.toMutableList().apply {
            // A song that has been taken out elsewhere isn't put back by moving it.
            place(this, edit.song)?.let { from ->
                val id = removeAt(from)
                add(following(this, edit.after), id)
            }
        }
        is PlaylistEdit.Insert -> {
            // Each after the song it follows, all on one pass along the list; those whose
            // song has gone, last, in the order given.
            val after = LinkedHashMap<Spot?, MutableList<String>>()
            for (placed in edit.songs) after.getOrPut(placed.after) { ArrayList() } += placed.song.id
            val out = ArrayList<String>(now.size + edit.songs.size)
            after.remove(null)?.let(out::addAll)
            val spot = Spots()
            for (id in now) {
                out += id
                if (after.isNotEmpty()) after.remove(spot.next(id))?.let(out::addAll)
            }
            after.values.forEach(out::addAll)
            out
        }
        is PlaylistEdit.Append -> now + edit.songs.map { it.id }
        is PlaylistEdit.Rename, PlaylistEdit.Delete, is PlaylistEdit.Create -> now
    }

    /** The edit that moves the song at `from` to `to` in `list`. */
    fun moving(list: List<String>, from: Int, to: Int): PlaylistEdit.Move {
        val song = spot(list, from)
        val without = list.toMutableList().apply { removeAt(from) }
        return PlaylistEdit.Move(song, if (to > 0) spot(without, to - 1) else null)
    }

    /** The edit that puts back the songs that were at these places in `before`: each after
     *  the nearest song before it that stayed (or first, with none). */
    fun restoring(before: List<Song>, places: Collection<Int>): PlaylistEdit.Insert {
        val back = places.toHashSet()
        val spot = Spots()
        var stayed: Spot? = null
        val songs = ArrayList<Placed>(back.size)
        for ((index, song) in before.withIndex()) {
            if (index in back) songs += Placed(song, stayed) else stayed = spot.next(song.id)
        }
        return PlaylistEdit.Insert(songs)
    }

    /** The edit that takes out the songs at these rows of `list`. */
    fun removing(list: List<String>, rows: Collection<Int>): PlaylistEdit.Remove {
        val out = rows.toHashSet()
        val spot = Spots()
        return PlaylistEdit.Remove(list.mapIndexedNotNull { index, id -> spot.next(id).takeIf { index in out } })
    }

    /**
     * A playlist as the phone shows it with `edit` carried out: its songs (each song's details
     * from the list itself, or from the edit for one it puts in), how many and how long, and
     * its name. Delete leaves it as it is (whoever shows it decides what a deleted one is).
     */
    fun applyTo(playlist: Playlist, edit: PlaylistEdit): Playlist {
        when (edit) {
            is PlaylistEdit.Rename -> return playlist.copy(name = edit.name)
            PlaylistEdit.Delete, is PlaylistEdit.Create -> return playlist
            else -> {}
        }
        val songs = playlist.songs
        val ids = apply(songs.map { it.id }, edit)
        val known = HashMap<String, Song>(songs.size)
        for (song in songs) known.putIfAbsent(song.id, song)
        when (edit) {
            is PlaylistEdit.Insert -> for (placed in edit.songs) known.putIfAbsent(placed.song.id, placed.song)
            is PlaylistEdit.Append -> for (song in edit.songs) known.putIfAbsent(song.id, song)
            else -> {}
        }
        val now = ids.mapNotNull { known[it] }
        if (now == songs) return playlist
        return playlist.copy(songs = now, songCount = now.size, duration = now.sumOf { it.duration })
    }
}
