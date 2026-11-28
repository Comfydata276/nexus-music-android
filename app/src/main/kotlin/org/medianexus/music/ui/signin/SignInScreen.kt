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
fun SignInScreen(graph: Graph, onBack: (() -> Unit)? = null) {
    SignInForm(graph, rememberShown(graph), onBack)
}

@Composable
private fun rememberShown(graph: Graph): NexusShown? {
    val shown by produceState<NexusShown?>(null, graph) {
        while (graph.nexus == null) kotlinx.coroutines.delay(100)
        graph.nexus!!.shown.collect { value = it }
    }
    return shown
}

/**
 * "Come back in so many seconds", as a time on the clock: **kept for the process, not the
 * screen**, so a screen rebuilt part-way counts on from where it was rather than from the start.
 * Nothing typed is kept with it.
 */
internal object SignInWait {
    private var seen: Long? = null
    private var until = 0L

    /** The time the button may be pressed again, for the wait the machine shows now (null:
     *  none). */
    @Synchronized
    fun until(waitS: Long?, now: Long = android.os.SystemClock.elapsedRealtime()): Long {
        if (waitS == null) {
            seen = null
            return 0L
        }
        if (seen != waitS) {
            seen = waitS
            until = now + waitS * 1000
        }
        return until
    }
}

/** What was typed, taken once at the press, for the one flight of a sign-in. */
private class Typed(val withCode: Boolean, val address: String, val email: String, val secret: String, val chosen: String, val again: String)

@Composable
private fun SignInForm(graph: Graph, shown: NexusShown?, onBack: (() -> Unit)?) {
    val colors = Theme.colors
    val local = graph.localAccess
    val phone by graph.session.signIn.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
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
    // write out, so the password (or the code) would sit there in plain text. After the process
    // is gone they are typed again.
    val secret = remember { TextFieldState() }
    // With a one-time code: the password chosen, typed twice, sent with the code in one press.
    val chosen = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    val wipe = { listOf(secret, chosen, again).forEach { it.clearText(); it.undoState.clearHistory() } }
    // **One sign-in at a time from this form**: claimed at the tap itself, before anything is
    // looked at, and let go when the flight has been handed over or given up.
    var claimed by remember { mutableStateOf(false) }
    DisposableEffect(secret) {
        onDispose {
            wipe()
            // (The machine lets go of anything held for this form.)
            graph.nexus?.press(buildJsonObject { put("form_closed", buildJsonObject {}) })
        }
    }
    // "Come back in so many seconds": the button disabled and counting down, from a time kept
    // for the process (see [SignInWait]); then the person presses again (nothing is sent again
    // by itself).
    var waitLeft by remember { mutableStateOf(0L) }
    LaunchedEffect(form?.waitS, form?.state) {
        val until = SignInWait.until(form?.waitS)
        while (true) {
            val left = (until - android.os.SystemClock.elapsedRealtime() + 999) / 1000
            waitLeft = left.coerceAtLeast(0)
            if (left <= 0) break
            kotlinx.coroutines.delay(250)
        }
    }
    var withCode by rememberSaveable { mutableStateOf(false) }
    val working = form?.state == "working" || claimed
    val keyboard = LocalSoftwareKeyboardController.current
    val storageBlocked = form?.state == "not_kept"
    val ready = shown != null && !storageBlocked && !working && waitLeft == 0L && address.text.isNotBlank() && secret.text.isNotEmpty() &&
        (if (withCode) chosen.text.isNotEmpty() && again.text.isNotEmpty() else email.text.isNotBlank())
    // (Not saved: after the screen is rebuilt the person presses again, and is asked again.)
    var explaining by remember { mutableStateOf(false) }
    val ask = rememberLocalNetworkAsk(local)
    // The press, made once, with what was typed as it was at the tap, where the address leads
    // and whether the permission is held (the machine says to ask, and asks nothing, if it leads
    // inside without it).
    val press: (Typed) -> Unit = { typed ->
        scope.launch {
            try {
                val (inside, permission) = local.lookAt(typed.address)
                val look = org.medianexus.music.nexus.Nexus.look(inside, permission)
                val asked = if (typed.withCode) {
                    buildJsonObject {
                        put("sign_in_with_code", buildJsonObject {
                            put("address", typed.address); put("code", typed.secret)
                            put("password", typed.chosen); put("again", typed.again); put("look", look)
                        })
                    }
                } else {
                    buildJsonObject {
                        put("sign_in", buildJsonObject { put("address", typed.address); put("email", typed.email); put("password", typed.secret); put("look", look) })
                    }
                }
                graph.nexus?.press(asked)
            } finally {
                claimed = false
            }
        }
    }
    // What was pressed waits here for the answer to Android's question, in memory only.
    var held by remember { mutableStateOf<Typed?>(null) }
    val connect: () -> Unit = {
        if (ready && !claimed) {
            claimed = true
            keyboard?.hide()
            val typed = Typed(withCode, address.text.toString(), email.text.toString(), secret.text.toString(), chosen.text.toString(), again.text.toString())
            wipe()
            scope.launch {
                try {
                    // **Before it connects**: where the Nexus server's address leads on this network.
                    if (local.neededStrictlyFor(typed.address)) {
                        held = typed
                        explaining = true
                        claimed = false
                    } else {
                        press(typed)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    claimed = false
                    throw e
                }
            }
        }
    }
    // Back from the code form is to the password form, not out of the app.
    androidx.activity.compose.BackHandler(enabled = withCode && !working) {
        withCode = false
        wipe()
    }
    if (onBack != null) androidx.activity.compose.BackHandler(enabled = !withCode, onBack = onBack)
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
                // **The code form is a first sign-in**, with a heading and words of its own.
                BasicText(stringResource(if (withCode) R.string.code_title else R.string.sign_in_title), Modifier.semantics { heading() }, style = Theme.type.hero.copy(color = colors.text))
                BasicText(stringResource(if (withCode) R.string.code_intro else R.string.sign_in_intro), style = Theme.type.body.copy(color = colors.muted))
                // **Offline mode can be switched off from here** (nothing is asked while it is on).
                if (settings.offline) {
                    BasicText(stringResource(R.string.offline_mode_on), style = Theme.type.rowDetail.copy(color = colors.warm))
                    TextButton(stringResource(R.string.switch_off_offline), onClick = { graph.settings.update { it.copy(offline = false) } }, modifier = Modifier.fillMaxWidth())
                }
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
                    Label(stringResource(R.string.password))
                    Field(
                        secret,
                        stringResource(R.string.password),
                        secret = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        onKeyboardAction = { connect() },
                        contentType = ContentType.Password,
                        enabled = !working,
                    )
                } else {
                    Label(stringResource(R.string.one_time_code))
                    // Shown as typed, and never copied or cut out of the field (paste works).
                    Field(
                        secret,
                        stringResource(R.string.one_time_code),
                        placeholder = stringResource(R.string.one_time_code_hint),
                        uncopyable = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        enabled = !working,
                    )
                    // The password chosen goes with the code, in the one press (8.4); its rule sits
                    // with its field.
                    val password = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
                    Label(stringResource(R.string.choose_password))
                    Field(chosen, stringResource(R.string.choose_password), secret = true, keyboardOptions = password, contentType = ContentType.NewPassword, enabled = !working)
                    BasicText(stringResource(R.string.choose_password_hint), style = Theme.type.rowDetail.copy(color = colors.muted))
                    Label(stringResource(R.string.same_again))
                    Field(again, stringResource(R.string.same_again), secret = true, keyboardOptions = password.copy(imeAction = ImeAction.Done), onKeyboardAction = { connect() }, contentType = ContentType.NewPassword, enabled = !working)
                }

                // How it went, in the machine's words; then the phone's own about its music.
                val said = when {
                    form?.state == "not_opened" -> stringResource(R.string.sign_in_could_not_open)
                    form?.state == "not_kept" -> stringResource(R.string.sign_in_storage_failed)
                    phone.choicesNotPutAway && form?.words.isNullOrEmpty() -> stringResource(R.string.sign_in_choices_not_put_away)
                    else -> form?.words.orEmpty()
                }
                if (said.isNotEmpty()) {
                    BasicText(said, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = if (form?.state == "working") colors.muted else colors.danger))
                }
                when {
                    explaining -> {
                        // Why the system is about to ask, said before it does. Answered, the
                        // sign-in goes ahead with what was pressed; sent to the system's settings
                        // instead, the person comes back and presses again (or carries on).
                        Explain(stringResource(R.string.local_network_why))
                        TextButton(stringResource(R.string.continue_on), onClick = {
                            val go = held
                            val asked = ask { go?.let(press) ; held = null; explaining = false }
                            if (!asked && !local.cannotAsk) {
                                held = null
                                explaining = false
                                go?.let(press)
                            }
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                        TextButton(stringResource(R.string.continue_anyway), onClick = {
                            val go = held
                            held = null
                            explaining = false
                            go?.let(press)
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    phone.musicNeedsPermission -> {
                        Explain(stringResource(R.string.music_local_network_why))
                        TextButton(stringResource(R.string.continue_on), onClick = {
                            if (!ask { graph.session.continueSignIn() } && !local.cannotAsk) graph.session.continueSignIn()
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                        TextButton(stringResource(R.string.continue_anyway), onClick = graph.session::continueSignIn, modifier = Modifier.fillMaxWidth())
                    }
                    phone.musicMayBeWhy -> {
                        BasicText(stringResource(R.string.music_local_network_refused), style = Theme.type.rowDetail.copy(color = colors.warm))
                        TextButton(stringResource(R.string.allow), onClick = {
                            if (!ask { graph.session.continueSignIn() } && !local.cannotAsk) graph.session.continueSignIn()
                        }, modifier = Modifier.fillMaxWidth(), primary = true)
                        TextButton(stringResource(R.string.continue_anyway), onClick = graph.session::continueSignIn, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        // **After a failure in which no connection was opened**, the machine says
                        // the permission may be why: allowed, the person signs in again.
                        if ((form?.askPermission == true || shown?.askPermission == true) && !working) {
                            BasicText(stringResource(R.string.local_network_refused), style = Theme.type.rowDetail.copy(color = colors.warm))
                            TextButton(stringResource(R.string.allow), onClick = { ask(null) }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            when {
                                waitLeft > 0 -> stringResource(R.string.sign_in_in_seconds, waitLeft.toInt())
                                working -> stringResource(R.string.signing_in)
                                else -> stringResource(R.string.sign_in)
                            },
                            onClick = connect,
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                            enabled = ready,
                        )
                        TextButton(
                            stringResource(if (withCode) R.string.use_password else R.string.use_code),
                            onClick = {
                                withCode = !withCode
                                wipe()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                        )
                        // Signed in already, with music that plays: back to it.
                        if (onBack != null) TextButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.fillMaxWidth())
                    }
                }
                BasicText(stringResource(R.string.password_kept), style = Theme.type.caption.copy(color = colors.muted))
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
