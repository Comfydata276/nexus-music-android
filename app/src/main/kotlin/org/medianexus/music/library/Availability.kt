package org.medianexus.music.library

import java.util.concurrent.ConcurrentHashMap
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.net.Song
import org.medianexus.music.playback.StreamQuality
import org.medianexus.music.store.KeptIndex

/**
 * Which songs can play with the server gone (Windows' `plays_offline`): those downloaded,
 * and those kept whole among the kept songs in the form they would be streamed in now. Everything here is in memory: it is asked
 * of every row of every page, and of every song the car shows offline.
 */
class Availability(
    private val downloads: Catalogue,
    private val kept: KeptIndex,
    /** What songs are streamed as now (the one place that says), and whether kept songs
     *  are kept at all. */
    private val quality: StreamQuality,
    private val keeping: () -> Boolean,
) {
    /** Downloaded: also a download that needs a fresh copy (it failed once when played),
     *  which stays on the phone, and is played with no server, until the new one has taken
     *  its place (see `Downloads.demote`). */
    fun isDownloaded(id: String): Boolean = downloads.has(id)

    /** The key a song is kept under in the form it would be streamed in now (on the network
     *  the phone is on now). */
    fun keyOf(song: Song): String = quality.keyOf(song)

    /** Kept whole in the form it would be streamed in now, or in the other network's (one
     *  fetched ahead on the Wi-Fi plays on mobile data as it was fetched, and the other way
     *  about: see `StreamQuality.formToOpen`). */
    fun isKept(song: Song): Boolean = keeping() && quality.keysOf(song).any(kept::isWhole)

    /** Whether the song plays with no server. */
    fun plays(song: Song): Boolean = song.id.isNotEmpty() && song.radioUrl.isEmpty() && (isDownloaded(song.id) || isKept(song))

    /** Every song that plays with no server: the downloaded ones, then the kept ones. */
    fun playable(): List<Song> {
        val seen = HashSet<String>()
        val out = ArrayList<Song>()
        for (entry in downloads.state.value.songs.values) if (seen.add(entry.song.id)) out += entry.song
        if (keeping()) for (song in kept.songs()) if (song.id !in seen && isKept(song) && seen.add(song.id)) out += song
        return out
    }
}
