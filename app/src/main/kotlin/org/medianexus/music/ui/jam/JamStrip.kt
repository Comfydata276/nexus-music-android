package org.medianexus.music.ui.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.R
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.theme.Theme

/**
 * A strip in the accent colour above the player for as long as the phone is in a jam, hosting
 * one, or steering another of the person's devices, as Windows has along the foot of its
 * window: what's going on, and a tap to leave. Nothing at all while there is no jam.
 */
@Composable
fun JamStrip(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val made by ui.graph.jamsMade.collectAsStateWithLifecycle()
    val jams = made ?: return
    val state by jams.ui.collectAsStateWithLifecycle()
    val joined = state.joined
    val hosting = state.hosting
    val linked = state.linked
    val text = when {
        linked != null -> stringResource(R.string.jam_strip_linked, linked)
        joined != null -> stringResource(R.string.jam_strip_joined, joined.host)
        hosting != null -> when (hosting.guests.size) {
            0 -> stringResource(R.string.jam_strip_hosting_none)
            1 -> stringResource(R.string.jam_strip_hosting_one, hosting.guests[0].name)
            else -> stringResource(R.string.jam_strip_hosting_many, hosting.guests.size)
        }
        else -> return
    }
    val colors = Theme.colors
    val buttons: @Composable () -> Unit = {
        when {
            linked != null -> {
                StripLink(stringResource(R.string.jam_play_here)) { jams.playHere() }
                StripLink(stringResource(R.string.jam_stop_steering)) { jams.leave() }
            }
            joined != null -> {
                StripLink(stringResource(R.string.jam_find_songs)) { ui.open(Route.Jam()) }
                StripLink(stringResource(R.string.jam_leave)) { jams.leave() }
            }
            else -> StripLink(stringResource(R.string.jam_manage)) { ui.open(Route.Jam()) }
        }
    }
    val words: @Composable (Modifier) -> Unit = { inner ->
        Row(inner, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(R.drawable.ic_party_popper, size = 16.dp, tint = Color.White)
            BasicText(
                text,
                Modifier.weight(1f),
                style = Theme.type.rowDetail.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Theme.dimens.radius))
            .background(colors.accent)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (maxWidth >= 600.dp) {
            // Wide (a phone on its side): the words and the buttons on one line, as Windows'.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                words(Modifier.weight(1f).padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
            }
        } else {
            // Narrow: the words in full on a line of their own, the buttons under them at the
            // end; beside them, the words were cut to half a sentence.
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp)) {
                words(Modifier.padding(end = 8.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) { buttons() }
            }
        }
    }
}

/** Something to press on the strip: a pill at least the size of a fingertip. */
@Composable
private fun StripLink(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = Theme.dimens.touch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            Modifier.clip(CircleShape).background(Color(0x24000000)).padding(horizontal = 12.dp, vertical = 6.dp),
            style = Theme.type.rowDetail.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
