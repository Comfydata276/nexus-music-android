package org.medianexus.music.ui.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.theme.Theme

/**
 * A song's menu, as a sheet from the foot of the screen: the Windows right-click menu
 * (`song_menu` in src/actions.rs), in its order and words. Play next, Add to queue; Add to
 * playlist, like or unlike, Rate, download (or cancel or remove its download, as it stands);
 * Go to album, Go to each artist credited, Song details, Start radio; and Remove from this
 * playlist (from a playlist the account can change) or Remove from queue (from the queue).
 * A radio station isn't a song in the library: only playing it applies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(menu: SongMenu, onClose: () -> Unit) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val colors = Theme.colors
    val song = menu.song
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val liked = song.id in likedSongs()
    // Closes the sheet, then does what was chosen.
    fun choose(action: () -> Unit) {
        action()
        scope.launch { state.hide() }.invokeOnCompletion { onClose() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = colors.overlay,
        contentColor = colors.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.faint) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(song.coverArt, Modifier.size(48.dp), CoverShape.ROW)
                Column(Modifier.weight(1f)) {
                    BasicText(song.title, Modifier.semantics { heading() }, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText(song.artistLabel, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Rule()
            Entry(R.drawable.ic_list_start, stringResource(R.string.play_next)) {
                choose { ui.player.playNext(listOf(song)); ui.toast(res.getString(R.string.playing_next)) }
            }
            Entry(R.drawable.ic_list_end, stringResource(R.string.add_to_queue)) {
                choose { ui.player.addToQueue(listOf(song)); ui.toast(res.getString(R.string.added_to_queue)) }
            }
            Rule()
            val library = song.radioUrl.isEmpty() && song.id.isNotEmpty()
            if (library) {
                Entry(R.drawable.ic_list_plus, stringResource(R.string.add_to_playlist)) { choose { ui.pickPlaylist(listOf(song)) } }
                Entry(if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart, stringResource(if (liked) R.string.remove_from_liked else R.string.add_to_liked)) {
                    choose {
                        ui.account?.likes?.toggle(song)
                        ui.toast(res.getString(if (liked) R.string.removed_from_liked else R.string.added_to_liked))
                    }
                }
                // What the menu says of the rating is the newest the app knows.
                val seen by (ui.account?.freshest?.version ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsStateWithLifecycle()
                val rating = remember(song, seen) { ui.account?.ratings?.of(song) ?: song.userRating }
                Entry(R.drawable.ic_star, ratingLabel(rating)) { choose { ui.pickRating(song) } }
            }
            // Download, or what can be done about one under way or done (Windows' song menu:
            // its words, its icons). Asking for the notifications permission at the first
            // download is the shell's, which is still there when the answer comes; this sheet
            // has closed by then.
            val downloadState = org.medianexus.music.ui.downloads.downloadsNow().stateOf(song.id)
            val context = androidx.compose.ui.platform.LocalContext.current
            if (song.radioUrl.isEmpty() && ui.account?.downloads != null) {
                when (downloadState) {
                    org.medianexus.music.downloads.SongState.Done -> Entry(R.drawable.ic_circle_arrow_down, stringResource(R.string.remove_download)) {
                        choose { ui.account?.downloads?.remove(listOf(song.id)) { done -> ui.toast(res.getString(if (done) R.string.download_removed else R.string.couldnt_change_downloads)) } }
                    }
                    org.medianexus.music.downloads.SongState.Waiting, is org.medianexus.music.downloads.SongState.Fetching -> Entry(R.drawable.ic_x, stringResource(R.string.cancel_download)) {
                        choose { ui.account?.downloads?.cancel(listOf(song.id)) { done -> ui.toast(res.getString(if (done) R.string.download_cancelled else R.string.couldnt_cancel_downloads)) } }
                    }
                    else -> Entry(R.drawable.ic_download, stringResource(R.string.download)) {
                        val account = ui.account
                        choose {
                            if (account != null) ui.askToDownload { ui.graph.scope.launch { org.medianexus.music.downloads.DownloadPages.songs(context.applicationContext, account, listOf(song))?.let(ui::toast) } }
                        }
                    }
                }
            }
            val credited = song.artists.filter { it.id.isNotEmpty() }
            if (song.albumId.isNotEmpty() || credited.isNotEmpty() || song.artistId.isNotEmpty()) Rule()
            if (song.albumId.isNotEmpty()) {
                Entry(R.drawable.ic_disc_album, stringResource(R.string.go_to_album)) {
                    choose { ui.open(Route.Album(song.albumId, song.album, song.artistLabel, song.coverArt)) }
                }
            }
            // Everyone credited, each with a way to their page; or the one artist.
            if (credited.size > 1) {
                for (credit in credited) {
                    Entry(R.drawable.ic_user_round, stringResource(R.string.go_to_named, credit.name)) {
                        choose { ui.open(Route.Artist(credit.id, credit.name)) }
                    }
                }
            } else if (song.artistId.isNotEmpty()) {
                Entry(R.drawable.ic_user_round, stringResource(R.string.go_to_artist)) {
                    choose { ui.open(Route.Artist(song.artistId, song.artist)) }
                }
            }
            if (library) {
                Entry(R.drawable.ic_info, stringResource(R.string.song_details)) {
                    choose { ui.sheet = org.medianexus.music.ui.shell.Sheet.SongDetails(song) }
                }
                Entry(R.drawable.ic_radio, stringResource(R.string.start_radio)) { choose { ui.startRadio(song) } }
            }
            menu.inPlaylist?.let { row ->
                Rule()
                Entry(R.drawable.ic_trash_2, stringResource(R.string.remove_from_playlist)) { choose { ui.removeFromPlaylist(row.playlist, listOf(row.index)) } }
            }
            menu.upcoming?.let { uid ->
                Rule()
                Entry(R.drawable.ic_x, stringResource(R.string.remove_from_queue)) { choose { menu.remove?.invoke() ?: ui.player.remove(uid) } }
            }
        }
    }
}
