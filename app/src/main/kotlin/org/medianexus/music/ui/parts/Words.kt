package org.medianexus.music.ui.parts

import android.content.res.Resources
import org.medianexus.music.R
import org.medianexus.music.net.Album
import org.medianexus.music.net.Song

// How lengths and counts are said, as the Windows app says them (`clock`, `count` and
// `length` in src/controller.rs). The words are in the string resources.

/** m:ss, or h:mm:ss past an hour. */
fun clock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val (h, m, r) = Triple(s / 3600, s % 3600 / 60, s % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** A rough running time for a header: "32 min", "2 hr 5 min". */
fun length(res: Resources, seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) res.getString(R.string.length_hours, minutes / 60, minutes % 60) else res.getString(R.string.length_minutes, minutes.coerceAtLeast(1))
}

fun songCount(res: Resources, n: Int): String = res.getQuantityString(R.plurals.song_count, n, n)

fun albumCount(res: Resources, n: Int): String = res.getQuantityString(R.plurals.album_count, n, n)

/** What kind of release an album is, as its header names it (`release_kind`). */
fun releaseKind(album: Album): Int {
    fun has(name: String) = album.releaseTypes.any { it.equals(name, ignoreCase = true) }
    return when {
        has("single") -> R.string.kind_single
        has("ep") -> R.string.kind_ep
        has("compilation") -> R.string.kind_compilation
        else -> R.string.kind_album
    }
}

/** An album on its artist's page, where the year says more than the artist's name again:
 *  "2019, Album". */
fun albumByYear(res: Resources, album: Album): String {
    val kind = res.getString(releaseKind(album))
    return if (album.year > 0) "${album.year}, $kind" else kind
}

/** The facts under an album's title: "2024, 10 songs, 32 min". */
fun albumFacts(res: Resources, album: Album): String = buildList {
    if (album.year > 0) add(album.year.toString())
    add(songCount(res, album.songs.size))
    add(length(res, album.songs.sumOf { it.duration }))
}.joinToString(", ")

/** The facts under a playlist's title: "12 songs, 48 min". */
fun listFacts(res: Resources, songs: List<Song>): String = buildList {
    add(songCount(res, songs.size))
    if (songs.isNotEmpty()) add(length(res, songs.sumOf { it.duration }))
}.joinToString(", ")

fun Album.card(subtitle: String = artistLabel) = CardData(CardKind.ALBUM, id, name, subtitle, coverArt)
