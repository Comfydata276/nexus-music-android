package org.medianexus.music.library

import java.util.concurrent.ConcurrentHashMap
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.net.Song
import org.medianexus.music.playback.NATIVE_SUFFIXES
import org.medianexus.music.playback.cacheKey
import org.medianexus.music.playback.streamForm
import org.medianexus.music.store.KeptIndex

/**
 * Which songs can play with the server gone (Windows' `plays_offline`): those downloaded,
 * and those kept whole among the kept songs in the form they would be streamed in now. Everything here is in memory: it is asked
 * of every row of every page, and of every song the car shows offline.
 */
class Availability(
    private val downloads: Catalogue,
    private val kept: KeptIndex,
    /** The streaming limit now (kbps; 0 for the files as they are) and whether kept songs
     *  are kept at all. */
    private val cap: () -> Int,
    private val keeping: () -> Boolean,
) {
    /** Downloaded: also a download that needs a fresh copy (it failed once when played),
     *  which stays on the phone, and is played with no server, until the new one has taken
     *  its place (see `Downloads.demote`). */
    fun isDownloaded(id: String): Boolean = downloads.has(id)

    /** The key a song is kept under in the form it would be streamed in now. */
    fun keyOf(song: Song): String {
        val suffix = song.suffix.lowercase()
        val native = suffix.isEmpty() || suffix in NATIVE_SUFFIXES
        return cacheKey(song.id, streamForm(native, cap(), suffix, song.bitRate))
    }

    fun isKept(song: Song): Boolean = keeping() && kept.isWhole(keyOf(song))

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
