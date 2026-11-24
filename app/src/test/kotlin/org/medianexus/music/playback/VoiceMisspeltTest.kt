package org.medianexus.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.library.ListIndex
import org.medianexus.music.library.SearchLimits
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.PageCache

/**
 * A spoken request said (or heard) wrongly plays what the search finds for it: what the
 * index matches is accepted on the index's own terms, and the voice scorer only chooses
 * between the kinds of thing found, never turns a match down. Each request goes through the
 * real index, as the phone's copy of the library or what is on the phone would answer it.
 */
class VoiceMisspeltTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    private val lanterns = Artist(id = "ar1", name = "Paper Lanterns")
    private val juniper = Artist(id = "ar2", name = "Juniper Vale")
    private val zoe = Artist(id = "ar3", name = "Zoë Älvdal")
    private val firstLight = Album(id = "al1", name = "First Light", artist = "Paper Lanterns")
    private val secondWind = Album(id = "al2", name = "Second Wind", artist = "Paper Lanterns")
    private val slowRivers = Album(id = "al3", name = "Slow Rivers", artist = "Juniper Vale")
    private val nordlys = Album(id = "al4", name = "Nordlys", artist = "Zoë Älvdal")
    private val season = Song(id = "s1", title = "Lantern Season", artist = "Paper Lanterns", album = "First Light", albumId = "al1")
    private val tidal = Song(id = "s2", title = "Tidal", artist = "Paper Lanterns", album = "First Light", albumId = "al1")
    private val lullaby = Song(id = "s3", title = "Lullaby", artist = "Juniper Vale", album = "Slow Rivers", albumId = "al3")
    private val northern = Song(id = "s4", title = "Northern Lights", artist = "Zoë Älvdal", album = "Nordlys", albumId = "al4")
    private val index = ListIndex(listOf(season, tidal, lullaby, northern), listOf(firstLight, secondWind, slowRivers, nordlys), listOf(lanterns, juniper, zoe))
    private val playlists = listOf(Playlist(id = "p1", name = "Morning mix"), Playlist(id = "p2", name = "Road trip"))

    /** As the car does it: the index searched for the request's words, then the choice. */
    private fun heard(request: VoiceRequest): VoiceChoice {
        val found = index.find(request.searchWords(), SearchLimits(artists = 10, albums = 10, songs = 25))!!
        return Voice.choose(request, found, playlists, emptyList())
    }

    @Test
    fun aMisspeltSongIsPlayed() {
        // The index finds it first; the voice scorer used to turn it down.
        assertEquals(VoiceChoice.OfSong(season), heard(VoiceRequest("Play Lantern Sesaon")))
        assertEquals(VoiceChoice.OfSong(season), heard(VoiceRequest("lantern sesaon", VoiceRequest.Focus.SONG, title = "Lantern Sesaon")))
        assertEquals(VoiceChoice.OfSong(northern), heard(VoiceRequest("play northen lihgts")))
    }

    @Test
    fun aMisspeltArtistIsPlayed() {
        assertEquals(VoiceChoice.OfArtist(juniper), heard(VoiceRequest("play junipr vale")))
        assertEquals(VoiceChoice.OfArtist(lanterns), heard(VoiceRequest("songs by papr lanterns")))
        assertEquals(VoiceChoice.OfArtist(zoe), heard(VoiceRequest("zoey alvdal", VoiceRequest.Focus.ARTIST, artist = "Zoey Alvdal")))
    }

    @Test
    fun aMisspeltAlbumIsPlayed() {
        assertEquals(VoiceChoice.OfAlbum(secondWind), heard(VoiceRequest("play the album secnd wind")))
        assertEquals(VoiceChoice.OfAlbum(slowRivers), heard(VoiceRequest("slow rivrs", VoiceRequest.Focus.ALBUM, album = "Slow Rivrs")))
    }

    @Test
    fun aMisspeltPlaylistIsPlayed() {
        assertEquals(VoiceChoice.OfPlaylist(playlists[0]), heard(VoiceRequest("play my mornig mix playlist")))
        assertEquals(VoiceChoice.OfPlaylist(playlists[1]), heard(VoiceRequest("raod trip", VoiceRequest.Focus.PLAYLIST, playlist = "Raod trip")))
    }

    @Test
    fun whatNothingMatchesIsStillNothing() {
        assertEquals(VoiceChoice.Nothing, heard(VoiceRequest("play zzqx qxzz")))
        // Found, but not for these words (a search's leftovers): not a match.
        assertEquals(VoiceChoice.Nothing, Voice.choose(VoiceRequest("play zzqx qxzz"), org.medianexus.music.net.Found(artists = listOf(lanterns), albums = listOf(firstLight), songs = listOf(season)), playlists, emptyList()))
    }

    @Test
    fun theCarsOfflineSearchPutsLikedSongsFirst() = runBlocking {
        // Two songs on the phone that match equally; the liked one first, as online.
        val catalogue = Catalogue(folder.newFolder("downloads"))
        val a = Song(id = "d1", title = "Harbour Song", album = "Downs", albumId = "x", artist = "A", suffix = "flac")
        val b = Song(id = "d2", title = "Harbour Song", album = "Downs", albumId = "x", artist = "A", suffix = "flac")
        for (song in listOf(a, b)) {
            java.io.File(catalogue.dir, "${song.id}.flac").writeBytes(ByteArray(4))
            catalogue.add(Downloaded(song, "${song.id}.flac", 4, at = 1, suffix = "flac"))
        }
        val kept = KeptIndex(java.io.File(folder.root, "kept.json"), scope, Dispatchers.IO)
        val api = Subsonic("http://127.0.0.1:9", "ada", "sesame", OkHttpClient(), OfflineSwitch(true))
        val library = Library(api, PageCache(folder.newFolder("pages"), scope, Dispatchers.IO))
        val availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true })
        val state = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) }
        val tree = CarTree(library, CarWords(), CarOffline(now = { true }, availability = availability, downloads = state, liked = { it == "d2" }))
        val songs = tree.search("harbour song").filter { it.group == CarWords().songs }
        assertEquals(listOf("d2", "d1"), songs.map { it.song?.id })
    }
}
