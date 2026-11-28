package org.medianexus.music.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.theme.Theme

/** The canonical report stored on this phone, with an export of the same UTF-8 text. */
@Composable
fun DiagnosticsPage(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = graph.recording.diagnostics
    val report by diagnostics.page.collectAsStateWithLifecycle()
    LaunchedEffect(diagnostics) { diagnostics.refresh() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) diagnostics.export { context.contentResolver.openOutputStream(uri, "wt") }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.diagnostics_what_sent), onBack = onBack) {
            if (report != null) TextButton(stringResource(R.string.diagnostics_export), onClick = { export.launch("Nexus Music Diagnostics.txt") })
        }
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BasicText(
                report ?: stringResource(R.string.diagnostics_loading_report),
                Modifier.padding(horizontal = Theme.dimens.pagePad).padding(bottom = 32.dp),
                style = Theme.type.rowDetail.copy(color = Theme.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
            )
        }
    }
}
