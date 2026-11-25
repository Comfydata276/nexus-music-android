package org.medianexus.music.ui.requests

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.core.Words
import org.medianexus.music.requests.BrowserTab
import org.medianexus.music.requests.RequestsState
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.LinkText
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.settings.SettingRow
import org.medianexus.music.ui.theme.Theme

/** Where the connection stands, as Windows' marker shows it: which colour its dot is. */
private enum class Standing { NONE, WAITING, GOOD, SIGN_IN, BAD }

/**
 * Settings, Requests (Windows' tab of the same name, in its words and order): the switch,
 * "Singles and EPs too", then Lidarr's address and key, where the connection stands in the
 * library's sentence (live), and the one thing to do next: Connect, Sign in with browser
 * (when a sign-in stands in front of Lidarr), Cancel while the browser is open, Try again.
 * Nothing about bulk requests or imports: the phone has none.
 *
 * The sign-in opens the browser over this screen's own activity, which is what lets the app
 * come back in front of it when the sign-in comes home; the shell tells the connection each
 * time the app's screen is in front again (`Requests.cameBack`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestsSettings() {
    val here = requestsHere() ?: return
    val requests = here.requests
    val settings = here.settings
    val state = here.state
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val colors = Theme.colors

    val title = stringResource(R.string.requests_on)
    SettingRow(title, stringResource(R.string.requests_on_detail)) {
        Toggle(settings.on, { on -> scope.launch { requests.switch(on) } }, title)
    }
    if (!settings.on) return
    val singles = stringResource(R.string.requests_singles)
    SettingRow(singles, stringResource(R.string.requests_singles_detail), nested = true) {
        Toggle(settings.singles, { on -> scope.launch { requests.setSingles(on) } }, singles)
    }

    // What is typed is the screen's, for this account and connection, and never saved with
    // the screen's state: a key half-typed is not written anywhere when Android stops the app.
    val address = remember(here.account) { TextFieldState(settings.address) }
    val key = remember(here.account) { TextFieldState() }
    // The key as it may be shown (its last few letters), never the key.
    var hint by remember(here.account) { mutableStateOf("") }
    LaunchedEffect(here.key, state) { hint = requests.keyHint() }
    val edited = address.text.toString().trim() != settings.address || key.text.isNotBlank()
    val canConnect = address.text.isNotBlank() && (key.text.isNotBlank() || hint.isNotEmpty())

    fun signIn() {
        scope.launch {
            val page = requests.signIn() ?: return@launch
            // From this screen's own activity: the tab goes over the app, in its task.
            if (activity == null || !BrowserTab.open(activity, page)) requests.cancelSignIn()
        }
    }

    fun connect() {
        if (!canConnect) return
        val typed = address.text.toString()
        val secret = key.text.toString()
        key.clearText()
        // (So the keyboard doesn't come back up over the box when the browser hands back.)
        focus.clearFocus()
        scope.launch {
            // What was typed stays in the field: an address turned down can be put right.
            val now = requests.connect(typed, secret)
            // As on Windows: Connect pressed, and a sign-in found in front of Lidarr, goes
            // straight on to it.
            if (now is RequestsState.NeedsSignIn && now.why == null && !now.signedOut) signIn()
        }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        // Set in beside a rule, as everything that belongs to the switch above.
        Box(Modifier.padding(start = 5.dp, end = 15.dp).width(2.dp).heightIn(min = 48.dp).background(colors.line))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Labelled(stringResource(R.string.lidarr_address)) {
                Field(
                    address, stringResource(R.string.lidarr_address),
                    Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.lidarr_address_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
            }
            Labelled(stringResource(R.string.lidarr_key)) {
                Field(
                    key, stringResource(R.string.lidarr_key_label),
                    Modifier.fillMaxWidth(),
                    placeholder = hint.ifEmpty { stringResource(R.string.lidarr_key_hint) },
                    secret = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    onKeyboardAction = { connect() },
                )
            }

            // Where things stand, and the one thing to do next (Windows' box under the fields).
            val (standing, says) = remember(state) { says(state) }
            val waiting = state is RequestsState.SigningIn
            val signedInAs = (state as? RequestsState.Connected)?.signedInAs
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.raised, RoundedCornerShape(Theme.dimens.radius))
                    .border(1.dp, colors.line, RoundedCornerShape(Theme.dimens.radius))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (says.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(10.dp).background(
                            when (standing) {
                                Standing.GOOD -> GOOD
                                Standing.BAD -> colors.danger
                                Standing.NONE -> colors.faint
                                else -> colors.warm
                            },
                            CircleShape,
                        ),
                    )
                    BasicText(
                        says,
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = Theme.type.body.copy(color = colors.text, fontWeight = FontWeight.Medium),
                    )
                }
                if (signedInAs != null) {
                    val at = signInHost(settings.service)
                    BasicText(
                        if (signedInAs.isEmpty()) stringResource(R.string.requests_signed_in_at, at) else stringResource(R.string.requests_signed_in_as, signedInAs, at),
                        style = Theme.type.rowDetail.copy(color = colors.muted),
                    )
                }
                if (signedInAs == null && (state is RequestsState.NeedsSignIn || waiting)) {
                    BasicText(stringResource(R.string.requests_sign_in_note), style = Theme.type.rowDetail.copy(color = colors.muted))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (waiting) TextButton(stringResource(R.string.cancel), { scope.launch { requests.cancelSignIn() } })
                    if (!waiting && (edited || state == RequestsState.NotConnected || state == RequestsState.KeyForgotten)) {
                        TextButton(stringResource(R.string.requests_connect), ::connect, primary = true, enabled = canConnect)
                    }
                    if (!edited && state is RequestsState.NeedsSignIn) TextButton(stringResource(R.string.sign_in_with_browser), ::signIn, primary = true)
                    if (!edited && state is RequestsState.Unreachable) TextButton(stringResource(R.string.try_again), { scope.launch { requests.check() } })
                    if (signedInAs != null && !waiting) LinkText(stringResource(R.string.sign_out), { scope.launch { requests.signOut() } }, color = colors.accentBright)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

/** Where the connection stands, in the library's own sentence. */
private fun says(state: RequestsState): Pair<Standing, String> = when (state) {
    RequestsState.Off -> Standing.NONE to ""
    RequestsState.NotConnected -> Standing.NONE to Words.notConnected
    RequestsState.KeyForgotten -> Standing.NONE to Words.keyForgotten
    RequestsState.Connecting -> Standing.WAITING to Words.checking
    is RequestsState.Connected -> Standing.GOOD to Words.connected(state.version)
    is RequestsState.NeedsSignIn -> Standing.SIGN_IN to when {
        state.signedOut -> Words.signedOut(state.host)
        state.why != null -> state.why
        // (Interrupted, the app stopped behind the browser: the library has no sentence of
        // its own for it yet, so it is said as any sign-in still to be made.)
        else -> Words.behindSignIn(state.host)
    }
    is RequestsState.SigningIn -> Standing.WAITING to Words.finishInBrowser(state.host)
    is RequestsState.Unreachable -> Standing.BAD to state.why
    // (What is kept can't be read: the library has no sentence for it.)
    RequestsState.CantRead -> Standing.BAD to ""
}

/**
 * Where the sign-in in front of Lidarr is, as the signed-in line names it ("auth.example.org",
 * with its port if it has one). `NeedsSignIn` and `SigningIn` carry the library's own form of
 * it; a connection that is signed in doesn't, so it is read from the address kept.
 */
private fun signInHost(service: String): String = runCatching {
    val at = java.net.URI(service.trim())
    (at.host ?: return@runCatching service) + if (at.port != -1) ":${at.port}" else ""
}.getOrDefault(service)

@Composable
private fun Labelled(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = Theme.type.label.copy(color = Theme.colors.text))
        field()
    }
}

/** Windows' colour for "connected" (it is no theme's). */
private val GOOD = Color(0xFF4CC38A)
