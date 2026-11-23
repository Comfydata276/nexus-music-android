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
import androidx.compose.runtime.rememberCoroutineScope
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
 * A song's menu, as a sheet from the foot of the screen (the Windows right-click menu,
 * `song_menu` in src/actions.rs), with what works so far: play next, add to queue, like or
 * unlike, go to its album or artist, and take it out of the queue when opened from there.
 * The rest of Windows' menu (playlists, ratings, downloads, radio) arrives with the
 * milestones that build them; nothing is shown that does nothing yet.
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
            Entry(if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart, stringResource(if (liked) R.string.remove_from_liked else R.string.add_to_liked)) {
                choose {
                    ui.account?.likes?.toggle(song)
                    ui.toast(res.getString(if (liked) R.string.removed_from_liked else R.string.added_to_liked))
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
            menu.upcoming?.let { uid ->
                Rule()
                Entry(R.drawable.ic_x, stringResource(R.string.remove_from_queue)) { choose { menu.remove?.invoke() ?: ui.player.remove(uid) } }
            }
        }
    }
}

@Composable
private fun Entry(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, size = 20.dp, tint = Theme.colors.muted)
        BasicText(text, style = Theme.type.body.copy(color = Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Theme.colors.line))
}
