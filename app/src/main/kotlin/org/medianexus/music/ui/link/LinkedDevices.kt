package org.medianexus.music.ui.link

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.playback.Linked
import org.medianexus.music.ui.link.ConnectView.Kind
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/**
 * The Connect sheet's "Linked devices (new)" (Windows' `ConnectPanel`, as the new linking
 * fills it): every device but the one being steered, each drawn as [ConnectView.kind] says,
 * then the library's note. Every sentence here is the library's, as handed: what each device
 * is doing ("Looking for it…" before the phone has entered), which of two to update, what
 * became of the last thing asked, and why linking has stopped. A press on a row picks it;
 * Pause pauses it and leaves the sheet up.
 */
@Composable
internal fun LinkedDevices(state: Linked.State, choose: (() -> Unit) -> Unit) {
    val linked = LocalUi.current.graph.linked
    val colors = Theme.colors
    Column(Modifier.fillMaxWidth()) {
        BasicText(
            stringResource(R.string.link_linked_devices),
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
            style = Theme.type.label.copy(color = colors.muted, fontWeight = FontWeight.SemiBold),
        )
        for (device in ConnectView.rows(state)) {
            LinkedRow(device, onChoose = { choose { linked.choose(device.place) } }, onPause = { linked.pause(device.place) })
        }
        if (state.note.isNotEmpty()) {
            BasicText(state.note, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Theme.type.body.copy(color = colors.text))
        }
    }
}

/** One device: a row like the old linking's, drawn back if it is not here, its sentence
 *  under it if it is of another generation, and a Pause beside it if it is playing. */
@Composable
private fun LinkedRow(device: Linked.Device, onChoose: () -> Unit, onPause: () -> Unit) {
    val colors = Theme.colors
    val kind = ConnectView.kind(device)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .alpha(if (kind == Kind.AWAY) 0.5f else 1f)
                    .clickable(enabled = kind == Kind.PICK, role = Role.Button, onClick = onChoose)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${device.name}, ${device.about}" + if (kind == Kind.UPDATE) ". ${device.update}" else ""
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(R.drawable.ic_monitor_speaker, size = 20.dp, tint = colors.muted)
                Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(device.name, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(device.about, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ConnectView.pause(device)) {
                IconButton(R.drawable.ic_pause_fill, stringResource(R.string.link_pause_device, device.name), onPause, iconSize = 20.dp)
            }
        }
        // Of another generation: which of the two to update, under the row's two lines, as
        // Windows has it (a smaller muted line, wrapped).
        if (kind == Kind.UPDATE) {
            BasicText(
                device.update,
                Modifier.padding(start = 44.dp, end = 12.dp, bottom = 8.dp),
                style = Theme.type.caption.copy(color = colors.muted),
            )
        }
    }
}
