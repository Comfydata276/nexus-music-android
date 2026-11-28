package org.medianexus.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.nexus.NexusShown
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.theme.Theme

/** What the Nexus server's machine shows now; null until it has started. */
@Composable
internal fun nexusShown(graph: Graph): NexusShown? {
    val shown by produceState<NexusShown?>(null, graph) {
        // (It is made once the app has started, off the main thread: looked for until then.)
        while (graph.nexus == null) kotlinx.coroutines.delay(250)
        graph.nexus!!.shown.collect { value = it }
    }
    return shown
}

/**
 * **The account, as the Nexus server's machine shows it** (the backend design's 8.7, 8.10 and
 * section 17): who is signed in, with Sign out; the one row for the server ("Nexus server:
 * connected", "not reachable since 14:02. Your music plays as usual"); what is to be said about
 * the music sign-in; the person's devices, any of which can be forced out; and changing the
 * password. **Every sentence is the machine's**: the phone draws them, in Windows' order, and
 * says what was pressed.
 */
@Composable
internal fun NexusAccount(graph: Graph) {
    val shown = nexusShown(graph) ?: return
    val nexus = graph.nexus ?: return
    val colors = Theme.colors
    SettingRow(shown.who.orEmpty(), shown.row) {
        TextButton(stringResource(R.string.sign_out), onClick = { nexus.press(buildJsonObject { put("sign_out", buildJsonObject {}) }) })
    }
    shown.music?.let { said ->
        BasicText(said, Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = colors.warm))
    }
    // **Where the machine says the local-network permission may be why** (either server's
    // address, whatever asked): a way to give it, here too (6.4).
    if (shown.askPermission || shown.musicAskPermission) {
        val ask = org.medianexus.music.ui.shell.rememberLocalNetworkAsk(graph.localAccess)
        BasicText(stringResource(R.string.local_network_refused), Modifier.padding(vertical = 6.dp), style = Theme.type.rowDetail.copy(color = colors.warm))
        TextButton(stringResource(R.string.allow), onClick = { ask(null) })
    }

    // ---- Your devices -------------------------------------------------------------------
    SettingHeading(stringResource(R.string.your_devices))
    val devices = shown.devices
    if (devices.state == "not_asked") {
        SettingRow(stringResource(R.string.your_devices), stringResource(R.string.your_devices_detail)) {
            TextButton(stringResource(R.string.show), onClick = { nexus.press(buildJsonObject { put("open_devices", buildJsonObject {}) }) })
        }
    } else {
        for (row in devices.rows) {
            SettingRow(row.name, row.seen) {
                if (row.canForceOut) {
                    TextButton(
                        stringResource(if (row.working) R.string.forcing_out else R.string.force_out),
                        onClick = { nexus.press(buildJsonObject { put("force_out", buildJsonObject { put("device", row.id) }) }) },
                        danger = true,
                        enabled = !row.working,
                    )
                }
            }
        }
        if (devices.words.isNotEmpty()) {
            BasicText(devices.words, Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = colors.muted))
        }
        if (devices.state != "working") {
            TextButton(stringResource(R.string.refresh), onClick = { nexus.press(buildJsonObject { put("open_devices", buildJsonObject {}) }) })
        }
    }

    // ---- Change password ----------------------------------------------------------------
    SettingHeading(stringResource(R.string.change_password))
    ChangePassword(graph, shown.password)
}

/**
 * The present password and a new one typed twice. **Kept in memory only, and only for as long as
 * this is on the screen** (the design's R5: the app keeps a session, never a password): not in
 * the saved state Android may write out, and wiped, with its undo history, when it goes, and as
 * soon as it has been handed to the machine.
 */
@Composable
private fun ChangePassword(graph: Graph, said: NexusShown.Said) {
    val nexus = graph.nexus ?: return
    val current = remember { TextFieldState() }
    val new = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    DisposableEffect(Unit) {
        onDispose { listOf(current, new, again).forEach { it.clearText(); it.undoState.clearHistory() } }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val working = said.state == "working"
    LaunchedEffect(said.state) { if (said.state == "changed") open = false }
    if (!open) {
        SettingRow(stringResource(R.string.change_password), said.words.ifEmpty { stringResource(R.string.change_password_detail) }) {
            TextButton(stringResource(R.string.change), onClick = { open = true })
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val password = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
        BasicText(stringResource(R.string.current_password), style = Theme.type.label.copy(color = Theme.colors.text))
        Field(current, stringResource(R.string.current_password), secret = true, keyboardOptions = password, contentType = ContentType.Password, enabled = !working)
        BasicText(stringResource(R.string.new_password), style = Theme.type.label.copy(color = Theme.colors.text))
        Field(new, stringResource(R.string.new_password), secret = true, keyboardOptions = password, contentType = ContentType.NewPassword, enabled = !working)
        BasicText(stringResource(R.string.password_again), style = Theme.type.label.copy(color = Theme.colors.text))
        Field(again, stringResource(R.string.password_again), secret = true, keyboardOptions = password.copy(imeAction = ImeAction.Done), contentType = ContentType.NewPassword, enabled = !working)
        if (said.words.isNotEmpty()) {
            BasicText(said.words, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (said.state == "changed") Theme.colors.muted else Theme.colors.danger))
        }
        TextButton(
            stringResource(if (working) R.string.saving else R.string.change_password),
            onClick = {
                nexus.press(buildJsonObject {
                    put("change_password", buildJsonObject {
                        put("current", current.text.toString())
                        put("new", new.text.toString())
                        put("again", again.text.toString())
                    })
                })
                // (Handed to the machine, which holds it for the one call: wiped here.)
                listOf(current, new, again).forEach { it.clearText(); it.undoState.clearHistory() }
            },
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = !working && current.text.isNotEmpty() && new.text.isNotEmpty() && again.text.isNotEmpty(),
        )
        // Back to the row, with what was typed wiped (a change already sent goes on).
        TextButton(stringResource(R.string.cancel), onClick = {
            listOf(current, new, again).forEach { it.clearText(); it.undoState.clearHistory() }
            open = false
        }, modifier = Modifier.fillMaxWidth(), enabled = !working)
        Spacer(Modifier.height(4.dp))
    }
}
