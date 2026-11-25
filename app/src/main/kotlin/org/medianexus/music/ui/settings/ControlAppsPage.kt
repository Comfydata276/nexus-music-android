package org.medianexus.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.store.PlaybackApp
import org.medianexus.music.ui.parts.LinkText
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.theme.Theme

/**
 * "Apps that can control playback" (Settings, Playback): the apps that have asked to and
 * are not ones Android or this app vouches for, each by its name, its package and who signed
 * it, with a switch. Switching one on lets that app, as it is signed, play, pause and skip
 * and see what is playing, and nothing else (`playback/ControlApps.kt`). Opening the page is
 * looking at it: the dot on its row in Settings goes.
 */
@Composable
fun ControlAppsPage(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val apps = settings.playbackApps
    LaunchedEffect(apps.size) { graph.controlApps.seen() }
    val colors = Theme.colors
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.control_apps), onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "about") {
                BasicText(stringResource(R.string.control_apps_about), Modifier.padding(top = 4.dp), style = Theme.type.rowDetail.copy(color = colors.muted))
            }
            if (apps.isEmpty()) {
                item(key = "none") {
                    BasicText(stringResource(R.string.control_apps_none), Modifier.padding(top = 12.dp), style = Theme.type.body.copy(color = colors.text))
                }
            }
            items(apps, key = { it.packageName + "/" + it.certificate }) { app ->
                ControlApp(app, onAllow = { on -> graph.controlApps.allow(app, on) }, onForget = { graph.controlApps.forget(app) })
            }
        }
    }
}

@Composable
private fun ControlApp(app: PlaybackApp, onAllow: (Boolean) -> Unit, onForget: () -> Unit) {
    val colors = Theme.colors
    val name = app.label.ifEmpty { app.packageName }
    Column(
        Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(Theme.dimens.radius)).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(name, Modifier.semantics { heading() }, style = Theme.type.body.copy(color = colors.text, fontSize = Theme.type.rowTitle.fontSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                BasicText(app.packageName, style = Theme.type.rowDetail.copy(color = colors.muted))
            }
            Toggle(app.allowed, onAllow, stringResource(R.string.control_app_allow, name))
        }
        BasicText(
            stringResource(if (app.withThePhone) R.string.control_app_with_phone else R.string.control_app_by_you),
            style = Theme.type.rowDetail.copy(color = colors.muted),
        )
        BasicText(stringResource(R.string.control_app_signed), style = Theme.type.rowDetail.copy(color = colors.muted))
        // (In pairs with colons between, as Android's own tools and Diagnostics write one,
        // and in a face where every digit is as wide as the next: it is there to be compared.)
        for (digest in app.certificate.split('+')) {
            BasicText(digest.chunked(2).joinToString(":"), style = Theme.type.caption.copy(color = colors.text, fontFamily = FontFamily.Monospace))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LinkText(stringResource(R.string.control_app_forget), onForget)
        }
    }
}
