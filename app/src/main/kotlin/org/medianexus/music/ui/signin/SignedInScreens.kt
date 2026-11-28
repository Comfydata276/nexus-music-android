package org.medianexus.music.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.SignInState
import org.medianexus.music.nexus.NexusShown
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.settings.NexusAccount
import org.medianexus.music.ui.theme.Theme

/** A live backend session can be signed out even if the phone has no playable account. */
@androidx.annotation.StringRes
internal fun signedInWithoutPlaybackMessage(shown: NexusShown?, phone: SignInState): Int? = when {
    shown?.screen != "signed_in" -> null
    phone.choicesNotPutAway -> R.string.sign_in_choices_not_put_away
    !shown.plays -> R.string.signed_in_no_music
    else -> null
}

/** The account and Sign out remain available while the phone has no playable account. */
@Composable
fun SignedInNoMusic(graph: Graph, shown: NexusShown, @androidx.annotation.StringRes message: Int) {
    val colors = Theme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.base)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(stringResource(R.string.account), Modifier.semantics { heading() }, style = Theme.type.hero.copy(color = colors.text))
            BasicText(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.body.copy(color = colors.text))
            NexusNotice(graph, shown)
            Spacer(Modifier.height(8.dp))
            NexusAccount(graph)
        }
    }
}

/**
 * **Asked to sign in again while the music still plays** (the machine's screen is `sign_in` but
 * a music login is held: a backend made anew, the data put back): its words, and a way to the
 * form. Playback, the car and downloads go on meanwhile.
 */
@Composable
fun SignInAgainBar(shown: NexusShown, onSignIn: () -> Unit) {
    val colors = Theme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(colors.raised, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(shown.signIn.words.ifEmpty { shown.row }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.rowDetail.copy(color = colors.text))
        TextButton(stringResource(R.string.sign_in), onClick = onSignIn, modifier = Modifier.fillMaxWidth(), primary = true)
    }
}

/** **What this server records** (13.6), as the machine says it, until the person says they have
 *  seen it (`notice_seen`). */
@Composable
fun NexusNotice(graph: Graph, shown: NexusShown) {
    val notice = shown.notice ?: return
    val colors = Theme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // (The heading both apps use: "What your Nexus server records", one button, "OK".)
        BasicText(stringResource(R.string.diagnostics_recording_notice), style = Theme.type.rowTitle.copy(color = colors.text))
        BasicText(notice, style = Theme.type.rowDetail.copy(color = colors.text))
        TextButton(stringResource(R.string.ok), onClick = { graph.nexus?.press(buildJsonObject { put("notice_seen", buildJsonObject {}) }) }, modifier = Modifier.fillMaxWidth())
    }
}
