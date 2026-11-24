package org.medianexus.music.ui.jam

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.jam.HostingUi
import org.medianexus.music.jam.JamUi
import org.medianexus.music.jam.Qr
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/** A jam hosted on this phone (Windows' jam window while hosting): its link and a code to
 *  scan for it, who's there, "Guests can steer", and ending it. */
@Composable
internal fun HostingPage(state: JamUi) {
    val hosting = state.hosting ?: return
    val ui = LocalUi.current
    val jams = ui.graph.jams
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val colors = Theme.colors
    val context = LocalContext.current
    val res = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Theme.dimens.pagePad)) {
        item(key = "link") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Code(hosting.link, Modifier.align(Alignment.CenterHorizontally))
                BasicText(stringResource(R.string.jam_friends_open), style = Theme.type.body.copy(color = colors.muted))
                Row(
                    Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(8.dp)).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(hosting.link, Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = res.getString(R.string.jam_link_label) }, style = Theme.type.rowDetail.copy(color = colors.text))
                    IconButton(R.drawable.ic_copy, stringResource(R.string.jam_copy_link), {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(res.getString(R.string.jam_link_label), hosting.link)))
                            ui.toast(res.getString(R.string.jam_link_copied))
                        }
                    })
                }
                TextButton(stringResource(R.string.jam_share_link), {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, hosting.link)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, primary = true)
                hosting.trouble?.let {
                    BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.body.copy(color = colors.warm))
                }
                BasicText(
                    when (hosting.guests.size) {
                        0 -> stringResource(R.string.jam_nobody_yet)
                        1 -> stringResource(R.string.jam_one_guest)
                        else -> stringResource(R.string.jam_guests, hosting.guests.size)
                    },
                    Modifier.padding(top = 8.dp).semantics { heading() },
                    style = Theme.type.label.copy(color = colors.text, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        items(hosting.guests, key = { "g" + it.id }) { guest ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(guest.name, Modifier.weight(1f), style = Theme.type.rowTitle.copy(color = colors.text))
                IconButton(R.drawable.ic_x, stringResource(R.string.jam_remove_guest, guest.name), { jams.removeGuest(guest.id) })
            }
        }
        item(key = "end") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(stringResource(R.string.jam_guests_steer), style = Theme.type.rowTitle.copy(color = colors.text))
                        BasicText(stringResource(R.string.jam_guests_steer_detail), style = Theme.type.rowDetail.copy(color = colors.muted))
                    }
                    Toggle(settings.jamGuestsControl, { on ->
                        ui.graph.settings.update { it.copy(jamGuestsControl = on) }
                        jams.guestsSteerChanged()
                    }, stringResource(R.string.jam_guests_steer))
                }
                TextButton(stringResource(R.string.jam_end), { jams.endJam() }, danger = true)
            }
        }
    }
}

/** The jam's link as a code for a phone's camera: dark on white with a margin, as Windows
 *  draws its own. */
@Composable
private fun Code(link: String, modifier: Modifier = Modifier) {
    val modules = remember(link) { Qr.encode(link) } ?: return
    val label = stringResource(R.string.jam_code_to_scan)
    val margin = 3
    Box(modifier.size(220.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).semantics { contentDescription = label }) {
        Canvas(Modifier.fillMaxSize()) {
            val cells = modules.size + 2 * margin
            val cell = size.width / cells
            for (r in modules.indices) for (c in modules.indices) {
                if (modules[r][c]) drawRect(Color(0xFF121212), Offset((c + margin) * cell, (r + margin) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
