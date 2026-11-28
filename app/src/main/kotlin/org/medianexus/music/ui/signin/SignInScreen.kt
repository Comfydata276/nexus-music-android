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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.nexus.NexusShown
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.shell.rememberLocalNetworkAsk
import org.medianexus.music.ui.theme.Theme

/**
 * **Signing in to the Nexus server** (the backend design's 8.5): the server's address, the
 * person's email and password, or the one-time code the admin gave them, then a password of
 * their own chosen (8.4). **A form in the app, and nothing that leaves it** (6.4: Android freezes
 * an app behind a browser). The signing in itself is the Nexus server's machine's
 * (`nexus/Nexus.kt`), so it carries on if this screen is rebuilt part-way; **every sentence it
 * shows about how it went is the machine's**.
 *
 * **The password typed is handed to the machine once and kept nowhere** (R5): not in the state
 * Android may write out, and wiped, with its undo history, as soon as it is handed over and when
 * the screen goes.
 *
 * **Android 17's local-network permission is judged here, for each address on its own** (6.4):
 * the Nexus server's address before it is first connected to (and again, by the machine, after a
 * failure in which no connection was opened); and the music server's address the backend hands
 * back, before it is first connected to and again if it can't be reached (`Session`). Where an
 * address leads inside the house and the permission is not held, the app's screen that explains
 * and asks is shown.
 */
@Composable
fun SignInScreen(graph: Graph) {
    val shown = rememberShown(graph)
    if (shown?.screen == "choose_password") ChoosePassword(graph, shown) else SignInForm(graph, shown)
}

@Composable
private fun rememberShown(graph: Graph): NexusShown? {
    val shown by produceState<NexusShown?>(graph.nexus?.shown?.value, graph) {
        while (graph.nexus == null) kotlinx.coroutines.delay(100)
        graph.nexus!!.shown.collect { value = it }
    }
    return shown
}

@Composable
private fun SignInForm(graph: Graph, shown: NexusShown?) {
    val colors = Theme.colors
    val local = graph.localAccess
    val phone by graph.session.signIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val form = shown?.signIn
    val address = rememberTextFieldState(form?.address.orEmpty())
    val email = rememberTextFieldState(form?.email.orEmpty())
    // What the machine puts back in the form (after a start, or a sign-out), once it has said.
    LaunchedEffect(form?.address, form?.email) {
        if (address.text.isEmpty() && !form?.address.isNullOrEmpty()) address.setTextAndPlaceCursorAtEnd(form!!.address)
        if (email.text.isEmpty() && !form?.email.isNullOrEmpty()) email.setTextAndPlaceCursorAtEnd(form!!.email)
    }
    // Not `rememberTextFieldState`: that is saved with the activity's state, which Android may
    // write out, so the password would sit there in plain text.
    val secret = remember { TextFieldState() }
    DisposableEffect(secret) {
        onDispose {
            secret.clearText()
            secret.undoState.clearHistory()
        }
    }
    var withCode by rememberSaveable { mutableStateOf(false) }
    val working = form?.state == "working"
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = shown != null && !working && address.text.isNotBlank() && secret.text.isNotEmpty() && (withCode || email.text.isNotBlank())
    var explaining by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLocalNetworkAsk(local)
    // The press, made once: the secret goes to the machine and is wiped here.
    val press: () -> Unit = {
        val typed = secret.text.toString()
        val asked = if (withCode) {
            buildJsonObject { put("sign_in_with_code", buildJsonObject { put("address", address.text.toString()); put("code", typed) }) }
        } else {
            buildJsonObject { put("sign_in", buildJsonObject { put("address", address.text.toString()); put("email", email.text.toString()); put("password", typed) }) }
        }
        secret.clearText()
        secret.undoState.clearHistory()
        graph.nexus?.press(asked)
    }
    // What was pressed waits here for the answer to Android's question, in memory only; the
    // sign-in goes ahead whatever the answer. Rebuilt meanwhile, the screen asks for it again.
    var held by remember { mutableStateOf<(() -> Unit)?>(null) }
    val connect: () -> Unit = {
        if (ready) {
            keyboard?.hide()
            scope.launch {
                // **Before it connects**: where the Nexus server's address leads on this network.
                if (local.neededStrictlyFor(address.text.toString())) {
                    held = press
                    explaining = true
                } else {
                    press()
                }
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.base)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.25f), colors.accent.copy(alpha = 0f)),
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
                Box(Modifier.size(48.dp).background(colors.accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_bars, size = 26.dp, tint = Color.White)
                }
                BasicText(stringResource(R.string.sign_in_title), Modifier.semantics { heading() }, style = Theme.type.hero.copy(color = colors.text))
                BasicText(stringResource(R.string.sign_in_intro), style = Theme.type.body.copy(color = colors.muted))
                Spacer(Modifier.height(8.dp))

                Label(stringResource(R.string.server_address))
                Field(
                    address,
                    stringResource(R.string.server_address),
                    placeholder = stringResource(R.string.server_address_hint),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    enabled = !working,
                )
                if (!withCode) {
                    Label(stringResource(R.string.email))
                    Field(
                        email,
                        stringResource(R.string.email),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        contentType = ContentType.EmailAddress,
                        enabled = !working,
                    )
                }
                Label(stringResource(if (withCode) R.string.one_time_code else R.string.password))
                Field(
                    secret,
                    stringResource(if (withCode) R.string.one_time_code else R.string.password),
                    secret = !withCode,
                    keyboardOptions = if (withCode) {
                        KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)
                    } else {
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                    },
                    onKeyboardAction = { connect() },
                    contentType = if (withCode) null else ContentType.Password,
                    enabled = !working,
                )

                // How it went, in the machine's words; then the phone's own about its music.
                val said = form?.words.orEmpty().ifEmpty { if (phone.choicesNotPutAway) stringResource(R.string.sign_in_choices_not_put_away) else "" }
                if (said.isNotEmpty()) {
                    BasicText(said, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (working) colors.muted else colors.danger))
                }
                when {
                    explaining -> {
                        // Why the system is about to ask, said before it does; the sign-in goes
                        // ahead with what was pressed whatever the answer.
                        Explain(stringResource(R.string.local_network_why))
                        TextButton(stringResource(R.string.continue_on), onClick = {
                            explaining = false
                            val go = held
                            held = null
                            if (!ask { go?.invoke() }) go?.invoke()
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                    }
                    phone.musicNeedsPermission -> {
                        Explain(stringResource(R.string.music_local_network_why))
                        TextButton(stringResource(R.string.continue_on), onClick = {
                            if (!ask { graph.session.continueSignIn() }) graph.session.continueSignIn()
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                    }
                    phone.musicMayBeWhy -> {
                        BasicText(stringResource(R.string.music_local_network_refused), style = Theme.type.rowDetail.copy(color = colors.warm))
                        TextButton(stringResource(R.string.allow), onClick = {
                            if (!ask { graph.session.continueSignIn() }) graph.session.continueSignIn()
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                        TextButton(stringResource(R.string.continue_anyway), onClick = graph.session::continueSignIn, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        // **After a failure in which no connection was opened**, the machine says
                        // the permission may be why: allowed, the person signs in again.
                        if (form?.askPermission == true && !working) {
                            BasicText(stringResource(R.string.local_network_refused), style = Theme.type.rowDetail.copy(color = colors.warm))
                            TextButton(stringResource(R.string.allow), onClick = { ask(null) }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            stringResource(if (working) R.string.connecting else R.string.sign_in),
                            onClick = connect,
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                            enabled = ready,
                        )
                        TextButton(
                            stringResource(if (withCode) R.string.use_password else R.string.use_code),
                            onClick = {
                                withCode = !withCode
                                secret.clearText()
                                secret.undoState.clearHistory()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                        )
                    }
                }
                BasicText(stringResource(R.string.password_kept), style = Theme.type.caption.copy(color = colors.muted))
            }
        }
    }
}

/** Signed in with a one-time code: the password the person will sign in with from now, typed
 *  twice (8.4). Kept in memory only, as the sign-in's. */
@Composable
private fun ChoosePassword(graph: Graph, shown: NexusShown) {
    val colors = Theme.colors
    val new = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    DisposableEffect(Unit) {
        onDispose { listOf(new, again).forEach { it.clearText(); it.undoState.clearHistory() } }
    }
    val working = shown.signIn.state == "working"
    Box(Modifier.fillMaxSize().background(colors.base)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(stringResource(R.string.choose_password_title), Modifier.semantics { heading() }, style = Theme.type.hero.copy(color = colors.text))
                shown.who?.let { BasicText(it, style = Theme.type.body.copy(color = colors.muted)) }
                BasicText(stringResource(R.string.choose_password_intro), style = Theme.type.body.copy(color = colors.muted))
                val password = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
                Label(stringResource(R.string.new_password))
                Field(new, stringResource(R.string.new_password), secret = true, keyboardOptions = password, contentType = ContentType.NewPassword, enabled = !working)
                Label(stringResource(R.string.password_again))
                Field(again, stringResource(R.string.password_again), secret = true, keyboardOptions = password.copy(imeAction = ImeAction.Done), contentType = ContentType.NewPassword, enabled = !working)
                if (shown.signIn.words.isNotEmpty()) {
                    BasicText(shown.signIn.words, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (working) colors.muted else colors.danger))
                }
                TextButton(
                    stringResource(if (working) R.string.connecting else R.string.choose),
                    onClick = {
                        graph.nexus?.press(buildJsonObject { put("choose_password", buildJsonObject { put("password", new.text.toString()); put("again", again.text.toString()) }) })
                        listOf(new, again).forEach { it.clearText(); it.undoState.clearHistory() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = !working && new.text.isNotEmpty() && again.text.isNotEmpty(),
                )
                TextButton(stringResource(R.string.sign_out), onClick = { graph.nexus?.press(kotlinx.serialization.json.JsonPrimitive("sign_out")) }, modifier = Modifier.fillMaxWidth(), enabled = !working)
            }
        }
    }
}

@Composable
private fun Explain(text: String) {
    BasicText(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.body.copy(color = Theme.colors.text))
}

@Composable
private fun Label(text: String) {
    // The field below carries the same name for screen readers.
    BasicText(text, style = Theme.type.label.copy(color = Theme.colors.text))
}
