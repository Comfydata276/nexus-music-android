package org.medianexus.music.ui.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import org.medianexus.music.jam.RelayLine
import org.medianexus.music.jam.RoomSide
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.settings.SettingRow
import org.medianexus.music.ui.theme.Theme

/**
 * Settings, Jam (Windows' tab of the same name, in its words and order): "Host jams", the
 * relay's host link (its address kept in the settings, its key sealed with the password: see
 * `jam/KeyVault`), "Link my devices" and what this phone is called.
 */
@Composable
fun JamSettings(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    // What the last check of the relay said: Windows' "connected", "error", "checking", "none".
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val colors = Theme.colors

    fun check() {
        val account = graph.accounts.state.value?.account ?: return
        val address = graph.settings.value.jamRelay
        status = true to res.getString(R.string.jam_checking)
        scope.launch {
            val key = withContext(Dispatchers.IO) { graph.relayKey.load(account.server, account.username) }
            if (address.isEmpty() || key == null) {
                status = false to res.getString(R.string.jam_paste_link_to_finish)
                return@launch
            }
            val hello = RoomSide.hello(RelayLine(address, graph.http), key)
            status = when (hello) {
                RoomSide.Hello.Connected -> true to res.getString(R.string.jam_connected_to, Links.hostOf(address))
                RoomSide.Hello.KeyRefused -> false to res.getString(R.string.jam_relay_didnt_accept)
                is RoomSide.Hello.Refusing -> false to (hello.said ?: res.getString(R.string.jam_relay_refusing))
                RoomSide.Hello.NotARelay -> false to res.getString(R.string.jam_not_a_relay, Links.hostOf(address))
                RoomSide.Hello.Unreachable -> false to res.getString(R.string.jam_unreachable)
            }
        }
    }

    LaunchedEffect(settings.jamHost) { if (settings.jamHost) check() }

    Column {
        SettingRow(stringResource(R.string.jam_host_jams), stringResource(R.string.jam_host_jams_detail)) {
            Toggle(settings.jamHost, { on ->
                graph.settings.update { it.copy(jamHost = on) }
                // Made now, so linking can begin (or the jam hosted here end).
                if (!on) graph.jamsIfMade()?.endHosting() else graph.jams
            }, stringResource(R.string.jam_host_jams))
        }
        if (settings.jamHost) {
            val link = rememberTextFieldState()
            fun connect() {
                val text = link.text.toString()
                if (text.isBlank()) return
                when (val read = Links.hostLink(text)) {
                    is Links.Read.Wrong -> status = false to graph.jams.let { org.medianexus.music.jam.JamWords(res).trouble(read.trouble) }
                    is Links.Read.Ok -> {
                        val account = graph.accounts.state.value?.account ?: return
                        link.clearText()
                        scope.launch {
                            val kept = withContext(Dispatchers.IO) { graph.relayKey.save(account.server, account.username, read.value.key) }
                            if (!kept) {
                                status = false to res.getString(R.string.jam_key_not_saved)
                                return@launch
                            }
                            graph.settings.update { it.copy(jamRelay = read.value.address) }
                            graph.jams.accountChanged()
                            check()
                        }
                    }
                }
            }
            SettingRow(stringResource(R.string.jam_host_link), stringResource(R.string.jam_host_link_detail), nested = true, stacked = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        link, stringResource(R.string.jam_host_link),
                        placeholder = if (settings.jamRelay.isNotEmpty()) stringResource(R.string.jam_host_link_on_file, Links.hostOf(settings.jamRelay)) else stringResource(R.string.jam_host_link_hint),
                        secret = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        onKeyboardAction = { connect() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(stringResource(R.string.jam_connect_button), ::connect, primary = true, enabled = link.text.isNotBlank())
                        if (status?.first == false && settings.jamRelay.isNotEmpty()) TextButton(stringResource(R.string.jam_try_again), ::check)
                    }
                    status?.let { (good, text) ->
                        BasicText(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (good) colors.muted else colors.warm))
                    }
                }
            }
            SettingRow(stringResource(R.string.jam_link_my_devices), stringResource(R.string.jam_link_my_devices_detail)) {
                Toggle(settings.linkDevices, { on -> graph.settings.update { it.copy(linkDevices = on) } }, stringResource(R.string.jam_link_my_devices))
            }
            if (settings.linkDevices) {
                val name = rememberTextFieldState(settings.deviceName)
                SettingRow(stringResource(R.string.jam_device_name), stringResource(R.string.jam_device_name_detail), nested = true, stacked = true) {
                    Field(
                        name, stringResource(R.string.jam_device_name),
                        Modifier.fillMaxWidth().padding(end = 8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        onKeyboardAction = {
                            val typed = name.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(24)
                            graph.settings.update { it.copy(deviceName = typed) }
                        },
                    )
                }
            }
        }
    }
}
