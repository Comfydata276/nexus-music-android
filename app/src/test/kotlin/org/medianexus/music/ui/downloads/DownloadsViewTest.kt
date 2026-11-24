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

    // ---- The phone's screens offline (offline hardening 2, item 7) -----------------------------

    @Test
    fun aDownloadedPlaylistIsListedWhetherOrNotTheServersListIsKeptOrHasIt() {
        fun playlist(id: String, count: Int = 3) = org.medianexus.music.net.Playlist(id = id, name = "List $id", songCount = count)
        val downloaded = listOf(playlist("mine", count = 2))
        // The server's list of playlists is not on the phone at all (Android cleared it).
        assertEquals(listOf("mine"), OnThePhone.playlists(null, downloaded) { false }.map { it.id })
        // It is kept, from before the playlist was made: the downloaded one is still listed,
        // and of the kept ones only those with something that plays.
        val kept = listOf(playlist("a"), playlist("b"), playlist("c"))
        assertEquals(listOf("mine", "b"), OnThePhone.playlists(kept, downloaded) { it.id == "b" }.map { it.id })
        // Kept and downloaded: once, in the server's order, counted as what is on the phone.
        val both = OnThePhone.playlists(listOf(playlist("a"), playlist("mine", count = 40), playlist("b")), downloaded) { it.id == "a" }
        assertEquals(listOf("a", "mine"), both.map { it.id })
        assertEquals(2, both.last().songCount)
    }

    @Test
    fun anArtistAndAnAlbumOfflineAreWhatTheSongsOnThePhoneSay() {
        val songs = listOf(
            song("a", album = "al1", track = 2).copy(year = 2019), song("b", album = "al1", track = 1).copy(year = 2019), song("c", album = "al2", track = 1),
            song("d", album = "al9").copy(artistId = "ar2", artist = "Someone else", artists = listOf(org.medianexus.music.net.Credit(id = "ar1", name = "Artist"))),
            song("e", album = "al8").copy(artistId = "ar3", artist = "Nobody"),
        )
        // Everything of theirs, credited or outright, by album: each of which is on the phone.
        val artist = OnThePhone.artist(songs, "ar1")!!
        assertEquals("Artist", artist.name)
        assertEquals(setOf("al1", "al2", "al9"), artist.albums.map { it.id }.toSet())
        assertEquals(listOf("a", "b", "c", "d"), OnThePhone.songsBy(songs, "ar1").map { it.id })
        assertNull(OnThePhone.artist(songs, "ar7"))
        // An album: its songs on the phone, in its own order.
        val album = OnThePhone.album(songs, "al1")!!
        assertEquals(listOf("b", "a"), album.songs.map { it.id })
        assertEquals("Album al1", album.name)
        assertNull(OnThePhone.album(songs, "al7"))
    }

    // ---- The Downloads page with thousands waiting or failed (offline hardening 2, item 7) ------

    @Test
    fun aSectionOfThousandsIsFoldedToItsFirstFewWithACount() {
        val waiting = (1..5_000).map { Active(song("s$it"), SongState.Waiting) }
        val folded = Folded.of(waiting, open = false)
        assertEquals(Folded.MOST, folded.shown.size)
        assertEquals(5_000 - Folded.MOST, folded.more)
        assertEquals("s1", folded.shown.first().song.id)
        // Opened: all of it.
        assertEquals(5_000 to 0, Folded.of(waiting, open = true).let { it.shown.size to it.more })
        // A handful is shown as it is; and one over the handful too ("and 1 more" would take
        // the room the row does).
        assertEquals(3 to 0, Folded.of(waiting.take(3), open = false).let { it.shown.size to it.more })
        assertEquals(Folded.MOST + 1 to 0, Folded.of(waiting.take(Folded.MOST + 1), open = false).let { it.shown.size to it.more })
        assertEquals(Folded.MOST to 2, Folded.of(waiting.take(Folded.MOST + 2), open = false).let { it.shown.size to it.more })
    }

    @Test
    fun aProgressTickIsGivenTheGroupingAlreadyMade() {
        val views = DownloadsViews()
        val songs = downloaded(song("a") to 10, song("b") to 20)
        val lists = listOf(DownloadedList("album:al1", "Album", songs = listOf("a", "b")))
        val first = DownloadsState(downloaded = songs, collections = lists, active = listOf(Active(song("c"), SongState.Fetching(0.1f))))
        val view = views.of(first, "Liked songs")
        // The same downloads, a song a little further on: the very grouping made before.
        org.junit.Assert.assertSame(view, views.of(first.copy(active = listOf(Active(song("c"), SongState.Fetching(0.6f)))), "Liked songs"))
        org.junit.Assert.assertSame(view, views.of(first.copy(active = emptyList(), batchDone = 1), "Liked songs"))
        // The room they take measured anew is told, with the same grouping.
        val measured = views.of(first.copy(occupied = 4_096), "Liked songs")
        assertEquals(4_096L, measured.bytes)
        org.junit.Assert.assertSame(view.albums, measured.albums)
        // A song arrives: made again.
        val more = views.of(first.copy(downloaded = songs + downloaded(song("c") to 5)), "Liked songs")
        assertEquals(3, more.songs.size)
    }
}
