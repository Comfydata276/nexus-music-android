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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.medianexus.music.R
import org.medianexus.music.Session
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
fun SignInScreen(session: Session, savedServer: String, savedUsername: String) {
    val colors = Theme.colors
    val state by session.signIn.collectAsStateWithLifecycle()
    val server = rememberTextFieldState(savedServer)
    val username = rememberTextFieldState(savedUsername)
    val password = rememberTextFieldState()
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = !state.busy && server.text.isNotBlank() && username.text.isNotBlank() && password.text.isNotEmpty()
    val connect = {
        if (ready) {
            keyboard?.hide()
            session.signIn(server.text.toString(), username.text.toString(), password.text.toString())
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

                state.error?.let { error ->
                    BasicText(
                        error,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = Theme.type.rowDetail.copy(color = colors.danger),
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    stringResource(if (state.busy) R.string.connecting else R.string.connect),
                    onClick = connect,
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = ready,
                )
                BasicText(stringResource(R.string.password_kept), style = Theme.type.caption.copy(color = colors.faint))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    // The field below carries the same name for screen readers.
    BasicText(text, style = Theme.type.label.copy(color = Theme.colors.text))
}
