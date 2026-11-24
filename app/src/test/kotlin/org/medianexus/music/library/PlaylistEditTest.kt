package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.library.PlaylistEdits.apply
import org.medianexus.music.library.PlaylistEdits.moving
import org.medianexus.music.library.PlaylistEdits.positions
import org.medianexus.music.library.PlaylistEdits.restoring
import org.medianexus.music.library.PlaylistEdits.spot
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/** Windows' tests of src/playlists.rs, ported as they are, and what the phone adds. */
class PlaylistEditTest {
    private fun list(text: String) = text.split(' ').filter { it.isNotEmpty() }
    private fun songs(text: String) = list(text).map { Song(id = it, title = it.uppercase(), duration = 60) }

    @Test
    fun removesTheSongsMeantFromAPlaylistThatChanged() {
        val page = list("a b c d e")
        val remove = PlaylistEdit.Remove(listOf(spot(page, 1), spot(page, 3)))
        // Nothing else has touched it.
        assertEquals(list("a c e"), apply(page, remove))
        // Another device reordered it and added a song: the same two songs go, not whatever
        // now sits at positions 1 and 3.
        assertEquals(list("e c a x"), apply(list("e d c b a x"), remove))
        // One of them was already taken out elsewhere.
        assertEquals(list("a c e"), apply(list("a c d e"), remove))
        assertEquals(listOf(2, 4), positions(list("x a b c d e"), listOf(spot(page, 1), spot(page, 3))))
    }

    @Test
    fun tellsCopiesOfTheSameSongApart() {
        val page = list("a b a c a")
        // The second "a".
        val remove = PlaylistEdit.Remove(listOf(spot(page, 2)))
        assertEquals(list("a b c a"), apply(page, remove))
        // If only one copy is left on the server there is no second copy to take out.
        assertEquals(list("b a c"), apply(list("b a c"), remove))
    }

    @Test
    fun movesASongToFollowTheSameNeighbour() {
        val page = list("a b c d e")
        // "d" dragged up to sit after "a".
        val edit = moving(page, 3, 1)
        assertEquals(list("a d b c e"), apply(page, edit))
        // Meanwhile another device added "x" and "y": they're kept, and "d" still follows "a".
        assertEquals(list("x a d b c e y"), apply(list("x a b c d e y"), edit))
        // Moved to the top.
        assertEquals(list("e x a b c d"), apply(list("x a b c d e"), moving(page, 4, 0)))
        // Moved down.
        assertEquals(list("b c d a e"), apply(page, moving(page, 0, 3)))
        // The song was taken out elsewhere: moving it doesn't bring it back.
        assertEquals(list("a b c e"), apply(list("a b c e"), edit))
        // The neighbour was taken out elsewhere: it goes to the end.
        assertEquals(list("b c e d"), apply(list("b c d e"), edit))
    }

    @Test
    fun putsRemovedSongsBackWhereTheyWere() {
        val before = songs("a b c d e")
        val ids = before.map { it.id }
        val removed = listOf(1, 2, 4)
        val after = apply(ids, PlaylistEdit.Remove(removed.map { spot(ids, it) }))
        assertEquals(list("a d"), after)
        val undo = restoring(before, removed)
        assertEquals(ids, apply(after, undo))
        // Something was added elsewhere before the undo: it stays, and the rest goes back
        // beside its old neighbours.
        assertEquals(list("a b c x d e"), apply(list("a x d"), undo))
        // The first song of the list, put back first.
        assertEquals(list("a b"), apply(list("b"), restoring(songs("a b"), listOf(0))))
    }

    // ---- The phone's own --------------------------------------------------------------------

    @Test
    fun aPlaylistAsShownCarriesTheEditWithEachSongsDetails() {
        val playlist = Playlist(id = "p", name = "Mix", songs = songs("a b c"), songCount = 3, duration = 180)
        val removed = PlaylistEdits.applyTo(playlist, PlaylistEdit.Remove(listOf(Spot("b", 0))))
        assertEquals(list("a c"), removed.songs.map { it.id })
        assertEquals(2, removed.songCount)
        assertEquals(120, removed.duration)
        // A song put in brings its own details.
        val added = PlaylistEdits.applyTo(playlist, PlaylistEdit.Append(listOf(Song(id = "z", title = "Zed", duration = 30))))
        assertEquals("Zed", added.songs.last().title)
        assertEquals(210, added.duration)
        // The same song twice is the same details twice.
        val twice = PlaylistEdits.applyTo(playlist, PlaylistEdit.Insert(listOf(Placed(playlist.songs[0], Spot("c", 0)))))
        assertEquals(list("a b c a"), twice.songs.map { it.id })
        assertEquals("Mix again", PlaylistEdits.applyTo(playlist, PlaylistEdit.Rename("Mix again")).name)
    }
}
