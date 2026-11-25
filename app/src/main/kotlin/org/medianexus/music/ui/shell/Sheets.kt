package org.medianexus.music.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.theme.Theme

/** What a sheet over every page says and offers. */
sealed interface Sheet {
    /** A list's download button, with all of it downloaded: what it offers (Windows' button
     *  removes at once; a phone's is easy to brush, so it offers first). */
    data class RemoveList(val title: String, val cover: String, val onRemove: () -> Unit) : Sheet

    /** Where the app stands with the server, and the way out. */
    data object Offline : Sheet

    /** Something said and asked (Windows' prompt): by default something that can't be taken
     *  back, its button in the danger colour; otherwise (`danger` false: asking Lidarr for an
     *  album) the accent's. */
    data class Confirm(val title: String, val detail: String, val confirm: String, val danger: Boolean = true, val onConfirm: () -> Unit) : Sheet

    /** The orders a list can be shown in (Windows' "Sort by" menu): the one in use marked,
     *  with which way it runs. `ownOrder` is what the list's own order is called. */
    data class SortBy(val view: org.medianexus.music.library.ListView, val ownOrder: String, val onChoose: (Int) -> Unit) : Sheet

    /** Everything known about a song (Windows' "Song details"). */
    data class SongDetails(val song: org.medianexus.music.net.Song) : Sheet

    /** A sheet whose content is drawn by whoever opened it (the menus of albums, artists and
     *  playlists, the playlist picker, the rating picker, a question): `content` is given the
     *  way to close the sheet and then do something. */
    class Drawn(val content: @Composable (choose: (() -> Unit) -> Unit) -> Unit) : Sheet
}

/** The sheet [Ui.sheet] names, over everything, in the colours of the song's menu. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SheetHost(sheet: Sheet, onClose: () -> Unit) {
    val colors = Theme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A sheet's choice is made once (each sheet has a host of its own): a second tap on its
    // button, or the keyboard's tick, while it closes does nothing, so one "Create" is one
    // playlist and one "Delete" one deletion.
    val chosen = remember { booleanArrayOf(false) }
    fun choose(action: () -> Unit) {
        if (chosen[0]) return
        chosen[0] = true
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
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            when (sheet) {
                is Sheet.RemoveList -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(sheet.cover, Modifier.size(48.dp), CoverShape.ROW, R.drawable.ic_list_music)
                        BasicText(sheet.title, Modifier.weight(1f).semantics { heading() }, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Rule()
                    Entry(R.drawable.ic_circle_arrow_down, stringResource(R.string.remove_from_downloads)) { choose(sheet.onRemove) }
                }
                Sheet.Offline -> {
                    val ui = LocalUi.current
                    val reach = org.medianexus.music.ui.downloads.reachNow()
                    // Gone while the sheet was up (the server answered): nothing to say.
                    if (!reach.offline) {
                        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
                        return@Column
                    }
                    val manual = reach.manual
                    Words(
                        stringResource(if (manual) R.string.offline_on_title else R.string.unreachable_title),
                        stringResource(if (manual) R.string.offline_mode_detail else R.string.unreachable_detail),
                    )
                    // (A button too wide to sit beside the other, with large type on a narrow
                    // phone, goes under it rather than being cut short.)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(stringResource(R.string.downloads), onClick = { choose { ui.open(Route.Downloads) } })
                        if (manual) {
                            TextButton(stringResource(R.string.turn_off_offline), primary = true, onClick = { choose { ui.graph.settings.update { it.copy(offline = false) } } })
                        } else {
                            TextButton(stringResource(R.string.try_again), primary = true, onClick = { choose { ui.graph.watch.check() } })
                        }
                    }
                }
                is Sheet.SortBy -> {
                    Words(stringResource(R.string.sort_by), "")
                    for (sort in 0 until org.medianexus.music.library.ListView.SORTS) {
                        val chosen = sort == sheet.view.sort
                        val name = if (sort == org.medianexus.music.library.ListView.OWN) sheet.ownOrder else stringResource(org.medianexus.music.ui.lists.sortWord(sort, playlist = false))
                        val way = when {
                            !chosen || sort == org.medianexus.music.library.ListView.OWN -> ""
                            sheet.view.descending -> stringResource(R.string.sort_descending)
                            else -> stringResource(R.string.sort_ascending)
                        }
                        SortEntry(
                            icon = when {
                                !chosen -> null
                                sort == org.medianexus.music.library.ListView.OWN -> R.drawable.ic_check
                                sheet.view.descending -> R.drawable.ic_arrow_down
                                else -> R.drawable.ic_arrow_up
                            },
                            text = name,
                            said = if (way.isEmpty()) name else "$name, $way",
                            selected = chosen,
                        ) { choose { sheet.onChoose(sort) } }
                    }
                }
                is Sheet.SongDetails -> {
                    org.medianexus.music.ui.menu.SongDetailsContent(sheet.song)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(stringResource(R.string.close), onClick = { choose {} })
                    }
                }
                is Sheet.Drawn -> sheet.content { action -> choose(action) }
                is Sheet.Confirm -> {
                    Words(sheet.title, sheet.detail)
                    // (A button too wide to sit beside the other, with large type on a narrow
                    // phone, goes under it rather than being cut short.)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(stringResource(R.string.cancel), onClick = { choose {} })
                        TextButton(sheet.confirm, danger = sheet.danger, primary = !sheet.danger, onClick = { choose(sheet.onConfirm) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Words(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = Theme.colors.text))
        if (detail.isNotEmpty()) BasicText(detail, style = Theme.type.body.copy(color = Theme.colors.muted))
    }
}

@Composable
private fun Entry(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, size = 20.dp, tint = Theme.colors.muted)
        BasicText(text, style = Theme.type.body.copy(color = Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One of the orders in the "Sort by" sheet: the one in use has its mark (a tick, or the
 *  way it runs); the others keep the mark's room, so the words line up. */
@Composable
private fun SortEntry(@DrawableRes icon: Int?, text: String, said: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, size = 20.dp, tint = Theme.colors.accentBright)
        }
        BasicText(text, style = Theme.type.body.copy(color = if (selected) Theme.colors.text else Theme.colors.muted, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Theme.colors.line))
}

/**
 * Whether the app is offline, as Windows' top bar shows it: a pill in the warm colour,
 * "Offline mode" when it was switched on by hand, "Can't reach server" when the server has
 * stopped answering. Nothing at all while the server answers. Tapped, it says what that
 * means and offers the way out (turn offline mode off; try the server again). Every page's
 * title bar carries it, so it never competes with the notices, the home network's bar or the
 * mini player at the foot of the screen.
 */
@Composable
fun OfflinePill(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val reach = org.medianexus.music.ui.downloads.reachNow()
    // (While the home network is out of bounds, its own bar says why nothing answers.)
    val blocked = ui.graph.localAccess.blocked.collectAsStateWithLifecycle().value
    // (Not while it isn't yet known: offline mode has just been switched off and the server
    // is being asked. "Can't reach server" is said once that is what was found.)
    if (!reach.offline || (!reach.manual && !reach.sure) || (blocked && !reach.manual)) return
    val colors = Theme.colors
    val words = stringResource(if (reach.manual) R.string.offline_mode else R.string.cant_reach_server_short)
    val said = stringResource(R.string.offline_named, words)
    Box(
        modifier
            .heightIn(min = Theme.dimens.touch)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button) { ui.sheet = Sheet.Offline }
            .semantics(mergeDescendants = true) {
                contentDescription = said
                role = Role.Button
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.warm.copy(alpha = 0.16f))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(words, style = Theme.type.label.copy(color = colors.warm), maxLines = 1)
        }
    }
}

/** Provides the offline pill to every title bar inside. */
@Composable
fun WithOfflinePill(content: @Composable () -> Unit) {
    CompositionLocalProvider(org.medianexus.music.ui.parts.LocalTitlePill provides { OfflinePill() }, content = content)
}
