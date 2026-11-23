package org.medianexus.music.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.medianexus.music.LocalAccess
import org.medianexus.music.R
import org.medianexus.music.Session
import org.medianexus.music.ui.shell.rememberLocalNetworkAsk
import org.medianexus.music.net.unencryptedBeyondHome
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.theme.Theme

/**
 * The phone's version of the Windows app's sign-in page (`LoginPage`): the server's address,
 * a username and a password. The sign-in itself runs in the session, so it carries on if the
 * screen is rebuilt part-way.
 */
@Composable
fun SignInScreen(session: Session, localAccess: LocalAccess, savedServer: String, savedUsername: String) {
    val colors = Theme.colors
    val state by session.signIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val server = rememberTextFieldState(savedServer)
    val username = rememberTextFieldState(savedUsername)
    // Not `rememberTextFieldState`: that is saved with the activity's state, which Android
    // may write out, so the password would sit there in plain text. Kept in memory only, it
    // is simply empty if the app is killed and brought back; and it is wiped, with its undo
    // history, when the screen goes away (signing in is one way it does).
    val password = remember { TextFieldState() }
    DisposableEffect(password) {
        onDispose {
            password.clearText()
            password.undoState.clearHistory()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = !state.busy && server.text.isNotBlank() && username.text.isNotBlank() && password.text.isNotEmpty()
    // Android 17: a server on the home network can't be reached without the local-network
    // permission. Before signing in to one that is certainly there, a sentence says why the
    // question is coming, then the system asks; the sign-in goes ahead either way, when the
    // answer comes (kept by LocalAccess, so turning the phone meanwhile loses nothing). One
    // that only may be there (a name, a VPN's range) is connected to straight away; if that
    // fails, the screen says the permission may be why.
    var explaining by rememberSaveable { mutableStateOf(false) }
    val asked by localAccess.ask.collectAsStateWithLifecycle()
    var mayBeWhy by remember { mutableStateOf(false) }
    LaunchedEffect(state.error, state.unreachable, asked) {
        mayBeWhy = state.error != null && state.unreachable && localAccess.mayBeWhy(server.text.toString())
    }
    val go = { session.signIn(server.text.toString(), username.text.toString(), password.text.toString()) }
    val ask = rememberLocalNetworkAsk(localAccess)
    val askThenGo = {
        explaining = false
        // Whatever the answer, the sign-in goes ahead with what was typed.
        val signIn = go
        ask { signIn() }
    }
    val connect: () -> Unit = {
        if (ready) {
            keyboard?.hide()
            scope.launch {
                if (localAccess.neededFor(server.text.toString())) explaining = true else go()
            }
        }
    }
    val accent = colors.accent
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.base)
            // A wash of the brand colour behind the form.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0f)),
                        center = Offset(size.width / 2, 30.dp.toPx()),
                        radius = 450.dp.toPx() * 0.7f,
                    ),
                )
            },
    ) {
        val tall = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = tall)
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(48.dp).background(accent, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(R.drawable.ic_bars, size = 26.dp, tint = Color.White)
                }
                BasicText(
                    stringResource(R.string.sign_in_title),
                    Modifier.semantics { heading() },
                    style = Theme.type.hero.copy(color = colors.text),
                )
                BasicText(stringResource(R.string.sign_in_intro), style = Theme.type.body.copy(color = colors.muted))
                Spacer(Modifier.height(8.dp))

                Label(stringResource(R.string.server_address))
                Field(
                    server,
                    stringResource(R.string.server_address),
                    placeholder = stringResource(R.string.server_address_hint),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !state.busy,
                )
                if (unencryptedBeyondHome(server.text.trim().toString())) {
                    BasicText(stringResource(R.string.sign_in_unencrypted), style = Theme.type.rowDetail.copy(color = colors.warm))
                }

                Label(stringResource(R.string.username))
                Field(
                    username,
                    stringResource(R.string.username),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    contentType = ContentType.Username,
                    enabled = !state.busy,
                )

                Label(stringResource(R.string.password))
                Field(
                    password,
                    stringResource(R.string.password),
                    secret = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    onKeyboardAction = { connect() },
                    contentType = ContentType.Password,
                    enabled = !state.busy,
                )

                val error = state.error ?: if (state.settingsNotSaved) stringResource(R.string.sign_in_not_saved) else null
                error?.let {
                    val shown = if (state.suggestHttp) "$it ${stringResource(R.string.sign_in_try_http)}" else it
                    BasicText(
                        shown,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = Theme.type.rowDetail.copy(color = colors.danger),
                    )
                }
                if (explaining) {
                    // Why the system is about to ask, said before it does.
                    BasicText(
                        stringResource(R.string.local_network_why),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = Theme.type.body.copy(color = colors.text),
                    )
                    TextButton(stringResource(R.string.continue_on), onClick = askThenGo, modifier = Modifier.fillMaxWidth(), primary = true)
                } else if (mayBeWhy && !state.busy) {
                    BasicText(stringResource(R.string.local_network_refused), style = Theme.type.rowDetail.copy(color = colors.warm))
                    // Allowed, it connects again by itself.
                    TextButton(stringResource(R.string.allow), onClick = { ask { granted -> if (granted && ready) go() } }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                if (!explaining) {
                    TextButton(
                        stringResource(if (state.busy) R.string.connecting else R.string.connect),
                        onClick = connect,
                        modifier = Modifier.fillMaxWidth(),
                        primary = true,
                        enabled = ready,
                    )
                }
                BasicText(stringResource(R.string.password_kept), style = Theme.type.caption.copy(color = colors.muted))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    // The field below carries the same name for screen readers.
    BasicText(text, style = Theme.type.label.copy(color = Theme.colors.text))
}
