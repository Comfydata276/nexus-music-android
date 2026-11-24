package org.medianexus.music.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    /** Something that can't be taken back, said and asked (Windows' prompt). */
    data class Confirm(val title: String, val detail: String, val confirm: String, val onConfirm: () -> Unit) : Sheet
}

/** The sheet [Ui.sheet] names, over everything, in the colours of the song's menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(sheet: Sheet, onClose: () -> Unit) {
    val ui = LocalUi.current
    val colors = Theme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(stringResource(R.string.downloads), onClick = { choose { ui.open(Route.Downloads) } })
                        if (manual) {
                            TextButton(stringResource(R.string.turn_off_offline), primary = true, onClick = { choose { ui.graph.settings.update { it.copy(offline = false) } } })
                        } else {
                            TextButton(stringResource(R.string.try_again), primary = true, onClick = { choose { ui.graph.watch.check() } })
                        }
                    }
                }
                is Sheet.Confirm -> {
                    Words(sheet.title, sheet.detail)
                    Row(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(stringResource(R.string.cancel), onClick = { choose {} })
                        TextButton(sheet.confirm, danger = true, onClick = { choose(sheet.onConfirm) })
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
    if (!reach.offline || (blocked && !reach.manual)) return
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
