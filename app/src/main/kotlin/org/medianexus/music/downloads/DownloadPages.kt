package org.medianexus.music.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.medianexus.music.AccountGraph
import org.medianexus.music.R
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Song

/**
 * Downloading what a page shows: an album, a playlist or Liked songs, whole (it stays
 * downloaded), or one song. Shared by the screens and the test rig's debug control, and the
 * words said afterwards are Windows' (src/downloads.rs).
 */
object DownloadPages {
    /** The list a page's route key names, with its songs: from the phone's copy, or the
     *  server's if there is none. Null if the key isn't a list's, or it can't be had. */
    suspend fun listFor(account: AccountGraph, key: String, likedName: String): Pair<DownloadedList, List<Song>>? {
        val library = account.library
        return try {
            when {
                key == "liked" -> {
                    val songs = library.keptSongs("liked") ?: library.quick(library.liked()).songs
                    DownloadedViews.ofLiked(likedName, songs) to songs
                }
                key.startsWith("album:") -> library.quick(library.album(key.removePrefix("album:"))).let { DownloadedViews.ofAlbum(it) to it.songs }
                key.startsWith("playlist:") -> library.quick(library.playlist(key.removePrefix("playlist:"))).let { DownloadedViews.ofPlaylist(it) to it.songs }
                else -> null
            }
        } catch (_: ApiError) {
            null
        }
    }

    /** The download button on a list's page: what Windows' `toggle_page_download` does, and
     *  what to say about it. */
    suspend fun toggle(context: Context, account: AccountGraph, key: String): String? {
        val downloads = account.downloads ?: return null
        val (list, songs) = listFor(account, key, context.getString(R.string.liked_songs)) ?: return context.getString(R.string.cant_reach_server)
        return when (val done = withContext(Dispatchers.Main.immediate) { downloads.toggle(list, songs) }) {
            Downloads.Toggled.Cancelled -> context.getString(R.string.download_cancelled)
            Downloads.Toggled.Removed -> context.getString(R.string.removed_from_downloads)
            is Downloads.Toggled.Asked -> said(context, done.asked)
        }
    }

    /** Songs on their own (a song's menu). */
    suspend fun songs(context: Context, account: AccountGraph, songs: List<Song>): String? {
        val downloads = account.downloads ?: return null
        return said(context, withContext(Dispatchers.Main.immediate) { downloads.download(songs) })
    }

    /** Windows' words for what became of asking. */
    fun said(context: Context, asked: Asked): String = when (asked) {
        is Asked.Started -> if (asked.count == 1) context.getString(R.string.downloading_song, asked.first.title) else context.getString(R.string.downloading_songs, asked.count)
        Asked.AlreadyDownloaded -> context.getString(R.string.already_downloaded)
        is Asked.NoRoom -> context.getString(R.string.no_room, Downloads.sizeLabel(asked.needed), Downloads.sizeLabel(asked.free))
        Asked.Offline -> context.getString(R.string.downloads_wait_offline)
    }

    /** One line about the songs that couldn't be downloaded (Windows' `trouble`), or null. */
    fun trouble(context: Context, failed: List<Failed>): String? {
        val last = failed.firstOrNull() ?: return null
        val which = when (failed.size) {
            1 -> "“${last.song.title}”"
            2 -> context.getString(R.string.and_one_other, "“${last.song.title}”")
            else -> context.getString(R.string.and_others, "“${last.song.title}”", failed.size - 1)
        }
        return context.getString(R.string.couldnt_be_downloaded, which, last.reason)
    }
}
