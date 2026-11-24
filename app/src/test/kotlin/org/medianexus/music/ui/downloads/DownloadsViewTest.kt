package org.medianexus.music.ui.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.medianexus.music.downloads.Active
import org.medianexus.music.downloads.DownloadedList
import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.downloads.SongState
import org.medianexus.music.library.OnThePhone
import org.medianexus.music.net.Song

class DownloadsViewTest {
    private fun song(id: String, album: String = "al1", track: Int = 0, genre: String = "") =
        Song(id = id, title = id.uppercase(), album = "Album $album", albumId = album, artist = "Artist", artistId = "ar1", track = track, genre = genre)

    private fun downloaded(vararg songs: Pair<Song, Long>, at: Long = 0) =
        songs.mapIndexed { i, (song, bytes) -> song.id to Downloaded(song, "${song.id}.flac", bytes, at + i, "flac") }.toMap()

    @Test
    fun aListsButtonSaysWhereTheListStands() {
        val ids = setOf("a", "b", "c", "d")
        // Nothing of it yet.
        assertEquals(ListDownload(0, 0, 4, 0f), ListDownload.of(DownloadsState(), ids))
        // Two downloaded, one half-way, one waiting: on its way, 2 of 4, and (2 + 0.5) / 4 along.
        val busy = DownloadsState(
            downloaded = downloaded(song("a") to 10, song("b") to 10),
            active = listOf(Active(song("c"), SongState.Fetching(0.5f)), Active(song("d"), SongState.Waiting)),
        )
        assertEquals(ListDownload(1, 2, 4, 0.625f), ListDownload.of(busy, ids))
        // Only some of it downloaded, nothing on its way: not downloaded (the button downloads
        // the rest), as Windows' button.
        assertEquals(0, ListDownload.of(DownloadsState(downloaded = downloaded(song("a") to 1)), ids).state)
        // All of it: downloaded.
        val all = DownloadsState(downloaded = downloaded(song("a") to 1, song("b") to 1, song("c") to 1, song("d") to 1, song("x") to 1))
        assertEquals(ListDownload(2, 4, 4, 1f), ListDownload.of(all, ids))
        // Another list's song on its way doesn't make this one busy.
        val other = all.copy(active = listOf(Active(song("y"), SongState.Fetching(0.1f))))
        assertEquals(2, ListDownload.of(other, ids).state)
        // A song whose size isn't known counts as nothing arrived yet.
        assertEquals(0.75f, ListDownload.of(busy.copy(active = listOf(Active(song("c"), SongState.Fetching(-1f)))).copy(downloaded = downloaded(song("a") to 1, song("b") to 1, song("d") to 1)), ids).progress)
        assertEquals(ListDownload(0, 0, 0, 0f), ListDownload.of(all, emptySet()))
    }

    @Test
    fun theDownloadsAreGroupedWithTheRoomEachTakes() {
        val one = song("s1", album = "al1", track = 2)
        val two = song("s2", album = "al1", track = 1)
        val three = song("s3", album = "al2")
        val state = DownloadsState(
            downloaded = downloaded(one to 3_000_000, two to 2_000_000, three to 5_000_000, at = 100),
            collections = listOf(
                DownloadedList("liked", songs = listOf("s3", "s1")),
                DownloadedList("playlist:p1", name = "Morning", by = "tester", songs = listOf("s2", "gone")),
                DownloadedList("album:al1", name = "First Light", by = "Paper Lanterns", coverArt = "c1", songs = listOf("s2", "s1")),
            ),
        )
        val view = DownloadsView.of(state, "Liked songs")
        // Liked songs first, then by name, each with what of it is on the phone.
        assertEquals(listOf("liked", "playlist:p1"), view.playlists.map { it.list })
        assertEquals("Liked songs", view.playlists[0].title)
        assertEquals(2 to 8_000_000L, view.playlists[0].songs to view.playlists[0].bytes)
        assertEquals(1 to 2_000_000L, view.playlists[1].songs to view.playlists[1].bytes)
        // Albums with anything downloaded, by name, as kept for one downloaded whole.
        assertEquals(listOf("album:al2", "album:al1"), view.albums.map { it.list })
        assertEquals(DownloadedGroup("album:al1", "First Light", "Paper Lanterns", "c1", 2, 5_000_000), view.albums[1])
        // Every song, the newest first; and the room they all take.
        assertEquals(listOf("s3", "s2", "s1"), view.songs.map { it.id })
        assertEquals(10_000_000L, view.bytes)
    }

    @Test
    fun aListOfTheDownloadsIsReadByItsName() {
        val state = DownloadsState(
            downloaded = downloaded(song("s1", track = 2) to 1, song("s2", track = 1) to 1),
            collections = listOf(DownloadedList("playlist:p1", songs = listOf("s1", "s2"))),
        )
        assertEquals(listOf("s2", "s1"), DownloadedViews.songsFor(state, "downloads")!!.map { it.id })
        assertEquals(listOf("s2", "s1"), DownloadedViews.songsFor(state, "downloads:album:al1")!!.map { it.id })
        assertEquals(listOf("s1", "s2"), DownloadedViews.songsFor(state, "downloads:playlist:p1")!!.map { it.id })
        assertNull(DownloadedViews.songsFor(state, "album:al1"))
        assertNull(DownloadedViews.songsFor(state, "downloads:playlist:none"))
    }

    @Test
    fun theLibraryOnThePhoneIsMadeFromItsSongs() {
        val songs = listOf(song("s1", album = "b", genre = "Folk"), song("s2", album = "a", genre = "Folk"), song("s3", album = "a", genre = "Ambient"))
        assertEquals(listOf("a", "b"), OnThePhone.albums(songs).map { it.id })
        assertEquals(2, OnThePhone.albums(songs).first().songCount)
        assertEquals(listOf("ar1"), OnThePhone.artists(songs).map { it.id })
        assertEquals(listOf("Ambient" to 1, "Folk" to 2), OnThePhone.genres(songs).map { it.name to it.songCount })
        assertEquals(setOf("a", "b"), OnThePhone.albumIds(songs))
    }
}
