package org.medianexus.music.ui.downloads

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.Reach
import org.medianexus.music.downloads.DownloadPages
import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.downloads.Held
import org.medianexus.music.downloads.SongState
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.holds
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.theme.Theme

private val NOTHING = MutableStateFlow(DownloadsState())

/** The signed-in account's downloads, as they stand. */
@Composable
fun downloadsNow(): DownloadsState {
    val flow = LocalUi.current.account?.downloads?.state ?: NOTHING
    val state by flow.collectAsStateWithLifecycle()
    return state
}

/** Where the app stands with the server (offline by hand, or out of reach). */
@Composable
fun reachNow(): Reach {
    val reach by LocalUi.current.graph.watch.state.collectAsStateWithLifecycle()
    return reach
}

/** A row's download state and whether it can play now, for [TrackRowData]. */
fun TrackRowData.withDownload(state: DownloadsState, offline: Boolean, plays: (Song) -> Boolean, song: Song): TrackRowData {
    val now = state.stateOf(song.id)
    return copy(
        downloaded = now == SongState.Done,
        progress = (now as? SongState.Fetching)?.fraction?.coerceAtLeast(0f) ?: if (now == SongState.Waiting) 0f else -1f,
        unavailable = offline && !plays(song),
    )
}

/**
 * Runs a download the person asked for, having first asked (once, on Android 13 and later)
 * for the notifications permission its progress notification needs. Refused, the downloads
 * run all the same and show their progress in the app.
 */
@Composable
fun rememberDownloadAsk(): (() -> Unit) -> Unit {
    val ui = LocalUi.current
    val context = LocalContext.current
    val waiting = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ui.graph.settings.update { s -> s.copy(downloadNotificationsAsked = true) }
        waiting[0]?.invoke()
        waiting[0] = null
    }
    return { action ->
        val ask = Build.VERSION.SDK_INT >= 33 && !ui.graph.settings.value.downloadNotificationsAsked && !context.holds(Manifest.permission.POST_NOTIFICATIONS)
        if (ask) {
            waiting[0] = action
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: android.content.ActivityNotFoundException) {
                action()
            }
        } else {
            action()
        }
    }
}

/**
 * Downloads, as Windows' Downloads page has it (`show_downloads`): what is on its way, then
 * what is downloaded, newest first; a line saying how much there is; what failed and why,
 * with Retry failed; Cancel all while something is on its way. All of it from the phone,
 * with no server needed. (A plain list for now: the real screen comes next.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsPage() {
    val ui = LocalUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = downloadsNow()
    val reach = reachNow()
    val account = ui.account
    val likedSet = likedSongs()
    val songs = remember(state) { state.active.map { it.song } + DownloadedViews.songs(state) }
    val done = remember(state) { DownloadedViews.songs(state) }
    val colors = Theme.colors
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.downloads), onBack = ui.navigator::back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "summary") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(summary(context, state), style = Theme.type.body.copy(color = colors.muted))
                    val held = when (state.held) {
                        Held.WIFI -> stringResource(R.string.waiting_for_wifi)
                        Held.NETWORK -> stringResource(R.string.waiting_for_network)
                        Held.OFFLINE -> stringResource(R.string.offline_mode)
                        Held.SERVER -> stringResource(R.string.waiting_for_server)
                        Held.NOTHING -> if (state.busy) stringResource(R.string.downloading_n_of, state.at, state.batchTotal) else null
                    }
                    if (held != null) BasicText(held, style = Theme.type.label.copy(color = colors.accentBright))
                    DownloadPages.trouble(context, state.failed)?.let { trouble ->
                        BasicText(trouble, style = Theme.type.rowDetail.copy(color = colors.warm))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.failed.isNotEmpty()) TextButton(stringResource(R.string.retry_failed), onClick = {
                            val downloads = account?.downloads ?: return@TextButton
                            scope.launch { downloads.retryFailed()?.let { ui.toast(DownloadPages.said(context, it)) } }
                        })
                        if (state.busy) TextButton(stringResource(R.string.cancel_all), onClick = {
                            account?.downloads?.cancelAll()
                            ui.toast(context.getString(R.string.downloads_cancelled))
                        })
                        if (done.isNotEmpty() && !state.busy) TextButton(stringResource(R.string.remove_all_downloads), danger = true, onClick = {
                            account?.downloads?.removeAll()
                            ui.toast(context.getString(R.string.downloads_removed))
                        })
                    }
                }
            }
            if (songs.isEmpty()) {
                item(key = "empty") { Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(stringResource(R.string.downloads_empty))) } }
            }
            items(songs, key = { it.id }, contentType = { "song" }) { song ->
                val plays = account?.availability?.let { it::plays } ?: { _: Song -> true }
                val row = remember(song) { TrackRowData(song.id, song.title, song.artistLabel + if (song.album.isNotEmpty()) " • ${song.album}" else "", song.coverArt, duration = clock(song.duration)) }
                TrackRow(
                    row.copy(liked = song.id in likedSet).withDownload(state, reach.offline, plays, song),
                    onClick = {
                        val at = done.indexOfFirst { it.id == song.id }
                        if (at >= 0) ui.play(done, at, context.getString(R.string.downloads), "downloads")
                    },
                    onMore = { ui.menu = SongMenu(song) },
                    showDuration = true,
                )
            }
        }
    }
}

/** "12 songs, 340 MB, and 3 more on their way" (Windows' summary line). */
private fun summary(context: android.content.Context, state: DownloadsState): String {
    val size = Downloads.sizeLabel(state.bytes)
    val count = state.downloaded.size
    val base = when (count) {
        0 -> context.getString(R.string.nothing_downloaded)
        1 -> context.getString(R.string.downloads_summary_one, size)
        else -> context.getString(R.string.downloads_summary, count, size)
    }
    val waiting = state.active.size
    return when {
        waiting == 0 -> base
        waiting == 1 -> "$base, ${context.getString(R.string.on_its_way)}"
        else -> "$base, ${context.getString(R.string.on_their_way, waiting)}"
    }
}
