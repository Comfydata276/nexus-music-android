package org.medianexus.music.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.medianexus.music.R
import org.medianexus.music.library.Recommend
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.ui.menu.addToPlaylist
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.theme.Theme

/**
 * "Recommended" under a playlist of the account's own (Windows' offers under a playlist): songs
 * from the library like the ones in it, each with a button to add it; tapped, one plays by
 * itself. Picked when the page opens, and not again while it stays open (so adding one doesn't
 * reshuffle the rest). Nothing at all offline, or when there is nothing to suggest.
 */
fun LazyListScope.recommendations(playlist: Playlist, busy: Boolean = false) {
    item(key = "recommended", contentType = "recommended") { Recommended(playlist, busy) }
}

@Composable
private fun Recommended(playlist: Playlist, busy: Boolean) {
    val ui = LocalUi.current
    val library = ui.account?.library ?: return
    val offline = org.medianexus.music.ui.downloads.reachNow().offline
    // Asked once for the page (by the playlist's id), from what it had then.
    val songs = remember(playlist.id) { playlist.songs }
    val picks by produceState<Recommend.Picks?>(null, playlist.id, offline) {
        // (Offline, what was on screen goes too: nothing can be added.)
        if (offline || songs.isEmpty()) {
            value = null
            return@produceState
        }
        value = try {
            coroutineScope {
                val similar = Recommend.seeds(songs).map { seed -> async { runCatching { library.similarSongs(seed.id, 30) }.getOrDefault(emptyList()) } }.awaitAll()
                val first = Recommend.pick(songs, similar, emptyList())
                val ofGenre = if ((first?.songs?.size ?: 0) < Recommend.SUGGESTED) Recommend.genreOf(songs)?.let { genre -> runCatching { library.randomSongs(40, genre) }.getOrDefault(emptyList()) }.orEmpty() else emptyList()
                Recommend.pick(songs, similar, ofGenre)
            }
        } catch (_: ApiError) {
            null
        }
    }
    val shown = picks ?: return
    var added by remember(playlist.id) { mutableStateOf(emptySet<String>()) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SectionTitle(stringResource(R.string.recommended))
        BasicText(
            stringResource(if (shown.basis == Recommend.Basis.SIMILAR) R.string.recommended_note else R.string.recommended_note_genre),
            Modifier.padding(horizontal = Theme.dimens.pagePad).padding(bottom = 4.dp),
            style = Theme.type.rowDetail.copy(color = Theme.colors.muted),
        )
        for (song in shown.songs) {
            val detail = if (song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
            val done = song.id in added
            TrackRow(
                TrackRowData(song.id, song.title, detail, song.coverArt, duration = clock(song.duration)),
                // A suggestion: hear it, by itself (Windows' `play_suggestion`).
                onClick = { ui.play(listOf(song), 0, song.title) },
                onMore = { ui.menu = SongMenu(song) },
                showDuration = true,
                trailing = {
                    IconButton(
                        if (done) R.drawable.ic_check else R.drawable.ic_plus,
                        if (done) stringResource(R.string.added_to_playlist_short) else stringResource(R.string.add_named_to_playlist, song.title),
                        {
                            // Ticked once the playlist, read after the server took it, holds it:
                            // not as the button is pressed, nor when the warning was answered
                            // "Don't add", nor if the server left it out.
                            if (!done) ui.addToPlaylist(playlist, listOf(song)) { holds -> if (holds != null && song.id in holds) added = added + song.id }
                        },
                        tint = if (done) Theme.colors.accentBright else Theme.colors.muted,
                        // A change to the playlist on its way: dimmed, and nothing to do.
                        enabled = !busy,
                    )
                },
            )
        }
    }
}
