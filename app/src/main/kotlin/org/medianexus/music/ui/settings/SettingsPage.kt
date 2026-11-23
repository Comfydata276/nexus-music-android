package org.medianexus.music.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.theme.THEMES
import org.medianexus.music.ui.theme.Theme
import org.medianexus.music.ui.theme.ThemeColors

/** Settings, so far: the theme and the account. The rest of the Windows app's settings come
 *  with the features they belong to. */
@Composable
fun SettingsPage(graph: Graph, onBack: () -> Unit) {
    val colors = Theme.colors
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val session by graph.session.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.settings), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Theme.dimens.pagePad)
                .padding(bottom = 32.dp),
        ) {
            Heading(stringResource(R.string.appearance))
            Setting(stringResource(R.string.theme), stringResource(R.string.theme_detail))
            ThemeSwatches(settings.theme) { name ->
                graph.settings.update { it.copy(theme = name, themeAt = System.currentTimeMillis() / 1000) }
            }

            Heading(stringResource(R.string.account))
            (session as? SessionState.SignedIn)?.let { signedIn ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Setting(
                        signedIn.account.server,
                        stringResource(R.string.signed_in_as, signedIn.account.username),
                        Modifier.weight(1f),
                    )
                    TextButton(stringResource(R.string.sign_out), onClick = graph.session::signOut)
                }
            }
            Box(Modifier.height(1.dp).fillMaxWidth().background(colors.line))
        }
    }
}

@Composable
private fun Heading(text: String) {
    BasicText(
        text,
        Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
        style = Theme.type.heading.copy(color = Theme.colors.text),
    )
}

@Composable
private fun Setting(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(title, style = Theme.type.rowTitle.copy(color = Theme.colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(detail, style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
    }
}

/** The themes to pick from, as on Windows: each one's surface with a dot of its accent, and
 *  its name. Three to a line on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSwatches(chosen: String, onChoose: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        for (option in THEMES) {
            Swatch(option, option.name == chosen, Modifier.weight(1f)) { onChoose(option.name) }
        }
    }
}

@Composable
private fun Swatch(option: ThemeColors, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(Theme.dimens.radius)
    val edge by animateColorAsState(if (selected) colors.accentBright else colors.line, label = "swatch")
    val name = stringResource(R.string.theme_named, option.name)
    Box(
        modifier
            .height(84.dp)
            .clip(shape)
            .background(option.panel)
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(14.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(option.accent))
        BasicText(
            option.name,
            Modifier.align(Alignment.BottomStart),
            style = Theme.type.label.copy(color = Color.White),
            maxLines = 1,
        )
    }
}
