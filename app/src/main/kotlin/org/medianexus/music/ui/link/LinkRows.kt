package org.medianexus.music.ui.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.jam.Links
import org.medianexus.music.playback.LinkHost
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.settings.SettingRow
import org.medianexus.music.ui.theme.Theme

/**
 * **Linking the person's devices, in Settings, Account: two rows, and nothing else of
 * linking has a screen on the phone yet.** Shown only in a build in which linking exists
 * (`link/LinkBuild`; `SettingsPage` asks before it calls this).
 *
 * - "Link my devices": the setting linking goes by. Off, the phone is not in the room
 *   with the person's other devices and asks nothing of the relay or, for linking, of
 *   the music server.
 * - The relay's host link (`https://relay.example/#key`, as the relay prints it): the
 *   relay's address and its key, **kept together, sealed, as one save**
 *   (`LinkHost.keepRelay`). It is never shown again; saved once more, the session at the
 *   old one ends and another begins.
 */
@Composable
internal fun LinkRows(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val colors = Theme.colors
    val title = stringResource(R.string.link_my_devices)
    SettingRow(title, stringResource(R.string.link_my_devices_detail)) {
        Toggle(settings.linkDevices, { on -> graph.settings.update { it.copy(linkDevices = on) } }, title)
    }
    if (!settings.linkDevices) return
    val link = rememberTextFieldState()
    var kept by remember { mutableStateOf(LinkHost.relayKept(context)) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun keep() {
        val text = link.text.toString()
        if (text.isBlank()) return
        when (val read = Links.hostLink(text)) {
            is Links.Read.Wrong -> status = false to res.getString(R.string.link_relay_wrong)
            is Links.Read.Ok -> {
                val account = graph.accounts.state.value?.account ?: return
                link.clearText()
                scope.launch {
                    val done = withContext(Dispatchers.IO) { LinkHost.keepRelay(context, account.server, account.username, read.value.address, read.value.key) }
                    kept = kept || done
                    status = done to if (done) res.getString(R.string.link_relay_kept, Links.hostOf(read.value.address)) else res.getString(R.string.link_relay_not_kept)
                }
            }
        }
    }
    val name = stringResource(R.string.link_relay)
    SettingRow(name, stringResource(R.string.link_relay_detail), nested = true, stacked = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                link, name,
                placeholder = stringResource(if (kept) R.string.link_relay_on_file else R.string.link_relay_hint),
                secret = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                onKeyboardAction = { keep() },
            )
            TextButton(stringResource(R.string.link_relay_keep), ::keep, primary = true, enabled = link.text.isNotBlank())
            status?.let { (good, text) ->
                BasicText(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (good) colors.muted else colors.warm))
            }
        }
    }
}
