package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/** The car's ids, what spoken requests come to, play counting and stream forms. */
class CarTest {
    // ---- Ids ------------------------------------------------------------------------------

    @Test
    fun everyPlaceSurvivesARoundTripThroughItsId() {
        val places = listOf(
            CarNode.Root, CarNode.Home, CarNode.Playlists, CarNode.Library, CarNode.Albums, CarNode.Artists, CarNode.Genres, CarNode.Liked,
            CarNode.AlbumLetter("Q"), CarNode.ArtistLetter("#"),
            CarNode.Album("al-1"), CarNode.Artist("ar-2"), CarNode.Playlist("pl 3"),
            // A genre's name can hold anything, the escapes themselves included.
            CarNode.Genre("Drum/Bass ~ 100%2F"),
            CarNode.Track("s1", ListRef.Album("al-1"), 4),
            CarNode.Track("s/2", ListRef.Playlist("p~1"), 0),
            CarNode.Track("s3", ListRef.Liked, -1),
        )
        for (place in places) assertEquals(place, CarIds.parse(CarIds.format(place)))
    }

    @Test
    fun theSamePlaceUnderTwoHeadingsHasTwoIdsAndOneMeaning() {
        val album = CarNode.Album("al-1")
        val recent = CarIds.inGroup("recent", album)
        val new = CarIds.inGroup("new", album)
        assertTrue(recent != new && recent != CarIds.format(album))
        assertEquals(album, CarIds.parse(recent))
        assertEquals(album, CarIds.parse(new))
    }

    @Test
    fun idsThatMeanNothingAreRefused() {
        for (id in listOf("", "nonsense", "album", "album/a/b", "track/x/s1/liked", "track/1//liked", "track/1/s1/album")) {
            assertNull(id, CarIds.parse(id))
        }
    }

    @Test
    fun namesAreFiledUnderTheirFirstLetter() {
        assertEquals("S", CarTree.letterOf("Slow Rivers"))
        // A leading "The" doesn't count, nor do accents or case.
        assertEquals("Q", CarTree.letterOf("The Quiet Harbour"))
        assertEquals("Z", CarTree.letterOf("zoë Älvdal"))
        assertEquals("E", CarTree.letterOf("Écoute"))
        assertEquals("#", CarTree.letterOf("夜の散歩"))
        assertEquals("#", CarTree.letterOf("2 Minutes"))
        assertEquals("#", CarTree.letterOf(""))
    }

    // ---- Spoken requests ------------------------------------------------------------------

    private val lanterns = Artist(id = "ar1", name = "Paper Lanterns")
    private val juniper = Artist(id = "ar2", name = "Juniper Vale")
    private val firstLight = Album(id = "al1", name = "First Light", artist = "Paper Lanterns")
    private val slowRivers = Album(id = "al2", name = "Slow Rivers", artist = "Juniper Vale")
    private val lanternsAlbum = Album(id = "al3", name = "Paper Lanterns", artist = "Someone Else")
    private val riverSong = Song(id = "s1", title = "Slow Rivers", artist = "Juniper Vale", album = "Slow Rivers", albumId = "al2")
    private val otherRiver = Song(id = "s2", title = "Slow Rivers", artist = "Cover Band", album = "Covers")
    private val favourites = Playlist(id = "p1", name = "Road trip")

    private fun choose(request: VoiceRequest, found: Found, playlists: List<Playlist> = listOf(favourites), genres: List<Genre> = emptyList()) =
        Voice.choose(request, found, playlists, genres)

    @Test
    fun anArtistNamedPlaysTheArtistRatherThanAnAlbumOfTheSameName() {
        val found = Found(artists = listOf(lanterns), albums = listOf(lanternsAlbum, firstLight))
        assertEquals(VoiceChoice.OfArtist(lanterns), choose(VoiceRequest("paper lanterns"), found))
        // Unless the Assistant says it's the album that's meant.
        assertEquals(VoiceChoice.OfAlbum(lanternsAlbum), choose(VoiceRequest("paper lanterns", VoiceRequest.Focus.ALBUM, album = "Paper Lanterns"), found))
    }

    @Test
    fun aSongByAnArtistFindsThatArtistsSong() {
        val found = Found(songs = listOf(otherRiver, riverSong))
        assertEquals(VoiceChoice.OfSong(riverSong), choose(VoiceRequest("slow rivers by juniper vale"), found))
        assertEquals(VoiceChoice.OfSong(riverSong), choose(VoiceRequest("Slow Rivers", VoiceRequest.Focus.SONG, artist = "Juniper Vale", title = "Slow Rivers"), found))
    }

    @Test
    fun wordsBeforeTheNameSayWhatKindOfThingIsMeant() {
        val found = Found(artists = listOf(juniper), albums = listOf(slowRivers), songs = listOf(riverSong))
        assertEquals(VoiceChoice.OfAlbum(slowRivers), choose(VoiceRequest("the album slow rivers"), found))
        assertEquals(VoiceChoice.OfArtist(juniper), choose(VoiceRequest("songs by juniper vale"), found))
        assertEquals(VoiceChoice.OfPlaylist(favourites), choose(VoiceRequest("my road trip playlist"), found))
        assertEquals(VoiceRequest.Focus.SONG, Voice.understood("the song slow rivers").focus)
    }

    @Test
    fun accentsAndCaseDoNotMatter() {
        val zoe = Artist(id = "ar3", name = "Zoë Älvdal")
        assertEquals(VoiceChoice.OfArtist(zoe), choose(VoiceRequest("zoe alvdal"), Found(artists = listOf(zoe))))
    }

    @Test
    fun anExactNameBeatsOneThatOnlyContainsIt() {
        val longer = Album(id = "al9", name = "Slow Rivers (Live at the Hall)")
        assertEquals(VoiceChoice.OfAlbum(slowRivers), choose(VoiceRequest("slow rivers", VoiceRequest.Focus.ALBUM), Found(albums = listOf(longer, slowRivers))))
        assertTrue(Voice.score("slow rivers", "Slow Rivers") > Voice.score("slow rivers", longer.name))
        assertTrue(Voice.score("rivers", "Slow Rivers") > Voice.score("rivers", "Harbour Lights"))
    }

    @Test
    fun aGenreOrPlaylistCanBeAskedFor() {
        val genres = listOf(Genre("Ambient"), Genre("Jazz"))
        assertEquals(VoiceChoice.OfGenre("Jazz"), choose(VoiceRequest("jazz", VoiceRequest.Focus.GENRE, genre = "Jazz"), Found(), genres = genres))
        assertEquals(VoiceChoice.OfPlaylist(favourites), choose(VoiceRequest("road trip"), Found()))
    }

    @Test
    fun somethingThatIsNotThereComesToNothing() {
        assertEquals(VoiceChoice.Nothing, choose(VoiceRequest("zzqx no such thing qxzz"), Found(artists = listOf(lanterns), albums = listOf(firstLight))))
        assertTrue(VoiceRequest("").unnamed)
        assertFalse(VoiceRequest("", VoiceRequest.Focus.ARTIST, artist = "Juniper Vale").unnamed)
    }

    @Test
    fun theAssistantsFocusIsRead() {
        assertEquals(VoiceRequest.Focus.ARTIST, VoiceRequest.focusOf("vnd.android.cursor.item/artist"))
        assertEquals(VoiceRequest.Focus.SONG, VoiceRequest.focusOf("vnd.android.cursor.item/audio"))
        assertEquals(VoiceRequest.Focus.ANY, VoiceRequest.focusOf("vnd.android.cursor.item/*"))
        assertEquals(VoiceRequest.Focus.ANY, VoiceRequest.focusOf(null))
        assertEquals("Juniper Vale", VoiceRequest("x", VoiceRequest.Focus.ARTIST, artist = "Juniper Vale").searchWords())
        assertEquals("Slow Rivers", VoiceRequest("play the album Slow Rivers on Nexus Music").searchWords())
    }

    @Test
    fun onlyTheLastFewSearchesAreKept() {
        // They used to be kept for as long as the service ran, every query ever typed.
        val results = SearchResults(most = 3)
        for (query in listOf("a", "b", "c", "d")) results.put(query, listOf(CarEntry(query, query)))
        assertEquals(3, results.size())
        assertNull(results.get("a"))
        assertEquals("d", results.get("d")!!.single().id)
        // One just asked for again is the last to go.
        results.get("b")
        results.put("e", emptyList())
        assertNull(results.get("c"))
        assertEquals("b", results.get("b")!!.single().id)
    }

    // ---- How songs are streamed -----------------------------------------------------------

    @Test
    fun songsStreamAsWindowsStreamsThem() {
        assertEquals(StreamForm(0, "flac"), streamForm(native = true, cap = 0, suffix = "flac"))
        assertEquals(StreamForm(160, "mp3"), streamForm(native = true, cap = 160, suffix = "flac"))
        // A file the phone can't play is converted even with no limit set.
        assertEquals(StreamForm(320, "mp3"), streamForm(native = false, cap = 0, suffix = "wma"))
        assertFalse(StreamForm(0, "flac").converted)
        assertTrue(StreamForm(320, "mp3").converted)
        // Kept under the song and the form, never the address.
        assertEquals("song:abc:0", cacheKey("abc", StreamForm(0, "flac")))
        assertEquals("song:abc:160", cacheKey("abc", StreamForm(160, "mp3")))
    }

    @Test
    fun aConversionOpenedPartWayStartsAtTheSecondBefore() {
        // 128 kbps is 16,000 bytes a second: byte 40,000 is 2.5 seconds in.
        assertEquals(SongDataSource.Start(2, 8_000), SongDataSource.startFor(40_000, 128))
        assertEquals(SongDataSource.Start(0, 0), SongDataSource.startFor(0, 320))
        // An ID3 tag's size is written seven bits to a byte.
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0x08, 0x34)
        assertEquals(1076L, SongDataSource.id3Size(head))
    }

    @Test
    fun aConversionIsGivenALengthItWillNotRunPast() {
        // A 20-second song at 128 kbps came to 321,148 bytes from the test server.
        val given = SongDataSource.conversionLength(20, 128)
        assertTrue(given in 321_148L..345_000L)
        assertEquals(-1L, SongDataSource.conversionLength(0, 128))
    }
}
