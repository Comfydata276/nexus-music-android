package org.medianexus.music.downloads

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.medianexus.music.library.Availability
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptIndex

/**
 * Debug builds only: what knowing which songs play offline costs with `count` songs
 * downloaded (`DebugControl --es measure availability`), in a throwaway folder of made-up
 * downloads (empty files): reading the catalogue at start-up, asking whether each song plays
 * (as rows and the car ask), the list of everything that plays (the car's offline tabs and
 * search), and the Downloads tab's albums. Times in milliseconds, the median of five runs.
 */
object DebugMeasure {
    fun availability(cacheDir: File, count: Int = 5_000): String {
        val dir = File(cacheDir, "measure-downloads").apply { deleteRecursively(); mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val songs = (0 until count).map { i ->
                Song(id = "song-$i", title = "Song $i", album = "Album ${i / 12}", albumId = "al-${i / 12}", artist = "Artist ${i / 60}", artistId = "ar-${i / 60}",
                    track = i % 12 + 1, suffix = "flac", duration = 200, genre = "Genre ${i % 7}")
            }
            val first = Catalogue(dir)
            for (song in songs) {
                File(dir, "${song.id}.flac").writeBytes(ByteArray(1))
            }
            // Written once, as the catalogue would have it after `count` downloads.
            first.keep(DownloadedList("playlist:p", "Mix", songs = songs.take(1000).map { it.id }))
            songs.chunked(500).forEach { chunk -> chunk.forEach { song -> first.add(Downloaded(song, "${song.id}.flac", 1, 1, "flac")) } }
            val kept = KeptIndex(File(dir, "kept.json"), scope, Dispatchers.IO)
            fun median(block: () -> Unit): Double = List(5) {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1e6
            }.sorted()[2]
            var catalogue = first
            val load = median { catalogue = Catalogue(dir) }
            val availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true })
            val others = songs.map { it.copy(id = it.id + "-x") }
            val askAll = median { for (song in songs) availability.plays(song); for (song in others) availability.plays(song) }
            val playable = median { availability.playable() }
            val state = DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections)
            val albums = median { DownloadedViews.albums(state) }
            val listed = median { DownloadedViews.songs(state) }
            return "with $count downloads: catalogue read at start-up %.1f ms; whether each of %d songs plays %.1f ms (%.2f µs each); everything that plays %.1f ms; the Downloads tab's albums %.1f ms, its songs %.1f ms; downloads.json %d KB"
                .format(load, songs.size * 2, askAll, askAll * 1000 / (songs.size * 2), playable, albums, listed, File(dir, Catalogue.SONGS_FILE).length() / 1024)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
