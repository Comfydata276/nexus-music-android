package org.medianexus.music.ui.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.R
import org.medianexus.music.jam.DeviceUi
import org.medianexus.music.jam.JamUi
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.Slider
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.theme.Theme

/** Now Playing's "Connect to a device" (Windows' devices button in the player bar): lit while
 *  the music is elsewhere. */
@Composable
fun ConnectButton() {
    val ui = LocalUi.current
    val elsewhere by ui.player.elsewhere.collectAsStateWithLifecycle()
    IconButton(
        R.drawable.ic_monitor_speaker,
        stringResource(R.string.jam_connect_to_device),
        { ui.sheet = Sheet.Drawn { choose -> ConnectSheet(choose) } },
        tint = Theme.colors.text,
        active = elsewhere != null,
    )
}

/**
 * Where the music is playing, a way into a jam, and the person's other devices to move it to
 * (Windows' `ConnectPanel`, in a sheet).
 */
@Composable
private fun ConnectSheet(choose: (() -> Unit) -> Unit) {
    val ui = LocalUi.current
    val jams = ui.graph.jams
    val state by jams.ui.collectAsStateWithLifecycle()
    val colors = Theme.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(stringResource(R.string.jam_connect), Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = colors.text))
        Column(Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(Theme.dimens.radius)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(R.drawable.ic_monitor_speaker, size = 22.dp, tint = colors.accentBright)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(state.linked ?: stringResource(R.string.jam_this_phone), style = Theme.type.rowTitle.copy(color = colors.accentBright, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(stringResource(if (state.linked != null) R.string.jam_playing_there else R.string.jam_this_phone), style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1)
                }
                if (state.linked != null) TextButton(stringResource(R.string.jam_play_here), { choose { jams.playHere() } })
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(colors.line))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(jamLine(state), Modifier.weight(1f), style = Theme.type.body.copy(color = colors.muted))
                TextButton(
                    stringResource(
                        when {
                            state.hosting != null -> R.string.jam_manage
                            state.joined != null -> R.string.jam_leave
                            state.canHost -> R.string.jam_start
                            else -> R.string.jam_join_a_jam
                        },
                    ),
                    {
                        if (state.joined != null) choose { jams.leave() } else choose { ui.open(Route.Jam()) }
                    },
                )
            }
        }
        val others = state.devices.filter { !it.active }
        if (others.isNotEmpty()) {
            BasicText(stringResource(R.string.jam_your_devices), Modifier.padding(start = 12.dp, top = 8.dp).semantics { heading() }, style = Theme.type.label.copy(color = colors.muted, fontWeight = FontWeight.SemiBold))
            for (device in others) DeviceRow(device) { choose { jams.pick(device.id) } }
        } else {
            BasicText(
                stringResource(if (state.linking) R.string.jam_devices_none_linked else R.string.jam_devices_none_unlinked),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = Theme.type.body.copy(color = colors.muted),
            )
        }
    }
}

@Composable
private fun jamLine(state: JamUi): String = when {
    state.hosting != null -> when (state.hosting.guests.size) {
        0 -> stringResource(R.string.jam_hosting_waiting)
        1 -> stringResource(R.string.jam_hosting_one)
        else -> stringResource(R.string.jam_hosting_many, state.hosting.guests.size)
    }
    state.joined != null -> stringResource(R.string.jam_in_hosts_jam, state.joined.host)
    else -> stringResource(R.string.jam_listen_with_friends)
}

/** One of the person's devices: dimmed and not to be chosen until it's ready to be steered. */
@Composable
private fun DeviceRow(device: DeviceUi, onChoose: () -> Unit) {
    val colors = Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (device.ready) 1f else 0.5f)
            .clickable(enabled = device.ready, role = Role.Button, onClick = onChoose)
            .semantics(mergeDescendants = true) { contentDescription = "${device.name}, ${device.about}" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(R.drawable.ic_monitor_speaker, size = 20.dp, tint = if (device.active) colors.accentBright else colors.muted)
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(device.name, style = Theme.type.rowTitle.copy(color = if (device.active) colors.accentBright else colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(device.about, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** While another of the person's devices is being steered: its volume, on Now Playing. */
@Composable
fun VolumeThere() {
    val ui = LocalUi.current
    val elsewhere by ui.player.elsewhere.collectAsStateWithLifecycle()
    val there = elsewhere ?: return
    val volume = there.volume ?: return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(R.drawable.ic_volume_1, size = 20.dp, tint = Theme.colors.muted)
        Slider(volume, stringResource(R.string.jam_volume_there, there.where), Modifier.weight(1f), onRelease = { ui.player.setVolumeThere(it) })
        Icon(R.drawable.ic_volume_2, size = 20.dp, tint = Theme.colors.muted)
    }
}
