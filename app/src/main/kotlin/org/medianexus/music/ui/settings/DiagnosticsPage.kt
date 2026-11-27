package org.medianexus.music.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.BuildConfig
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.theme.Theme

/**
 * What the player has met (see [org.medianexus.music.Diagnostics]), to read here or to share
 * as text through Android's share sheet. Windows writes its like to a file ("Save
 * diagnostics"); a phone has nowhere obvious to put a file, so this is shown and shared.
 */
@Composable
fun DiagnosticsPage(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val changes by graph.diagnostics.changes.collectAsStateWithLifecycle()
    val report = remember(changes) { graph.diagnostics.report(aboutTheApp(graph, context)) }
    val title = stringResource(R.string.share_diagnostics)
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.diagnostics), onBack = onBack) {
            TextButton(stringResource(R.string.share), onClick = { share(context, report, title) })
        }
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BasicText(
                report,
                Modifier.padding(horizontal = Theme.dimens.pagePad).padding(bottom = 32.dp),
                style = Theme.type.rowDetail.copy(color = Theme.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
            )
        }
    }
}

private fun share(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
}

/**
 * What the app is and how it is set up, as the head of the diagnostics: as Windows' file
 * has it, and like it naming no server address and no account.
 */
fun aboutTheApp(graph: Graph, context: Context): List<String> {
    val settings = graph.settings.value
    fun yes(on: Boolean) = if (on) "yes" else "no"
    fun onOff(on: Boolean) = if (on) "on" else "off"
    // Android Auto as this phone has it: whether it is there at all is the first thing to
    // know when the car doesn't show the app.
    val auto = runCatching {
        context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).versionName
    }.getOrNull()
    return listOf(
        "Nexus Music ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        "",
        "Signed in: ${yes(graph.session.state.value is SessionState.SignedIn)}",
        "Server address uses HTTPS: ${yes(settings.server.startsWith("https://"))}",
        "Offline mode: ${onOff(settings.offline)}; kept from reaching the home network: ${yes(graph.localAccess.blocked.value)}",
        "Streaming limit: ${settings.bitrate} kbps (0 is none), on mobile data ${if (settings.mobileBitrate == org.medianexus.music.store.SAME_AS_WIFI) "the same" else "${settings.mobileBitrate} kbps"}; on mobile data now: ${yes(graph.quality.onMobileData())}; kept songs limit: ${settings.cacheLimitMb} MB",
        "Android Auto on this phone: ${auto?.let { "version $it" } ?: "not found"}",
        "Known hosts of media apps listed: ${graph.callers.listed}",
    )
}
