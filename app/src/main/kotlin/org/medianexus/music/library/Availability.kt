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
 * unless this session found the download wouldn't play, and those kept whole among the kept
 * songs in the form they would be streamed in now. Everything here is in memory: it is asked
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
    /**
     * Downloaded files found this session not to play: set aside, never deleted (Windows'
     * `bad_downloads`), and the song streamed instead for as long as the app runs. What is set
     * aside is **that file**, not the song: its name and the modification time it had when it
     * was marked whole (both in the catalogue's entry, so asking is still never a look at the
     * disk). A song downloaded again is another file, with a fresh chance.
     */
    private val bad = ConcurrentHashMap<String, Stamp>()

    private data class Stamp(val file: String, val modified: Long, val at: Long)

    private fun stampOf(id: String): Stamp? = downloads.entry(id)?.let { Stamp(it.file, it.modified, it.at) }

    fun isDownloaded(id: String): Boolean = downloads.has(id) && !isSetAside(id)

    /**
     * The downloaded file `file` (as it was at `modified`) of this song couldn't be read or
     * parsed: it is passed over until the app is next started. Returns whether it has now
     * been set aside: not if it already was, and not if the song's download is by now a
     * different file from the one that failed.
     */
    fun setAside(id: String, file: java.io.File, modified: Long): Boolean {
        val entry = downloads.entry(id) ?: return false
        if (entry.file != file.name || (entry.modified != 0L && entry.modified != modified)) return false
        val stamp = Stamp(entry.file, entry.modified, entry.at)
        return bad.put(id, stamp) != stamp
    }

    fun isSetAside(id: String): Boolean {
        val stamp = bad[id] ?: return false
        return stamp == stampOf(id)
    }

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
        for (entry in downloads.state.value.songs.values) if (!isSetAside(entry.song.id) && seen.add(entry.song.id)) out += entry.song
        if (keeping()) for (song in kept.songs()) if (song.id !in seen && isKept(song) && seen.add(song.id)) out += song
        return out
    }
}
