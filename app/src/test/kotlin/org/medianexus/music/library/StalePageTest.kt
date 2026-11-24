package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Song

/** Offline, what an album or list page shows when the phone has its songs: the phone's,
 *  whether there is no kept page or a kept page that is stale. */
class StalePageTest {
    private fun song(id: String, track: Int) = Song(id = id, title = "Song $id", album = "First Light", albumId = "al1", artist = "Paper Lanterns", track = track)

    // The phone has two songs of the album, as they are now; the kept page is from before the
    // server read the album again, with other ids for its songs.
    private val phone = listOf(song("new-1", 1), song("new-2", 2))
    private val stale = Album(id = "al1", name = "First Light", songs = listOf(song("old-1", 1), song("old-2", 2), song("old-3", 3)))

    @Test
    fun withNoKeptPageTheSongsOnThePhoneAreShown() {
        assertEquals(listOf("new-1", "new-2"), OnThePhone.albumShown(null, phone, "al1")!!.songs.map { it.id })
        assertNull(OnThePhone.albumShown(null, emptyList(), "al1"))
    }

    @Test
    fun aStaleKeptPageGivesWayToTheSongsOnThePhone() {
        assertTrue(OnThePhone.keptIsStale(stale.songs, phone))
        assertEquals(listOf("new-1", "new-2"), OnThePhone.albumShown(stale, phone, "al1")!!.songs.map { it.id })
        // A kept page with something of it on the phone is the page (each song marked as it plays or not).
        val fresh = stale.copy(songs = stale.songs + song("new-2", 4))
        assertFalse(OnThePhone.keptIsStale(fresh.songs, phone))
        assertSame(fresh, OnThePhone.albumShown(fresh, phone, "al1"))
        // Nothing of it on the phone either: the kept page, as before.
        assertSame(stale, OnThePhone.albumShown(stale, listOf(Song(id = "x", albumId = "other")), "al1"))
        // Online: always the page.
        assertSame(stale, OnThePhone.albumShown(stale, null, "al1"))
        assertFalse(OnThePhone.keptIsStale(stale.songs, null))
    }
}
