package org.medianexus.music.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.downloads.DownloadPages
import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.downloads.Held
import org.medianexus.music.downloads.SongState
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.ActionBar
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.DownloadButton
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.LikedTile
import org.medianexus.music.ui.parts.ListHeading
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.theme.Theme

/** Downloads as a page of its own (from a toast, offline Home, the offline sheet). */
@Composable
fun DownloadsPage() {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.downloads), onBack = ui.navigator::back)
        DownloadsContent(titled = false)
    }
}

/**
 * Downloads, as Windows' Downloads page has it (`show_downloads`), grouped for a phone: what
 * is on its way, with each song's progress and the batch's, and Cancel; what couldn't be
 * downloaded, each with its reason in plain words, and Retry failed; then what is downloaded,
 * by playlist, by album and as every song, newest first, with the room each takes and the
 * room they all take. All of it from the downloads' own record, never the server or the kept
 * pages: it is the page to open in a dead spot, and it opens at once from a cold start.
 *
 * Library shows it as one of its chips (`titled`: with the page's own heading, there being no
 * title bar of its own).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsContent(titled: Boolean) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val state = downloadsNow()
    val reach = reachNow()
    val marks = rememberDownloadMarks()
    val account = ui.account
    val liked = likedSongs()
    val likedName = stringResource(R.string.liked_songs)
    // Worked out again only when what is downloaded changes, not as each song arrives (twice
    // a second while it does).
    // (A progress tick is given the grouping already made: see DownloadsViews.)
    val views = remember { DownloadsViews() }
    val view = views.of(state, likedName)
    // Everything waiting and everything that failed are folded to their first few until
    // opened: with thousands of either, the music that is downloaded stays in reach below.
    val allActiveState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val allFailedState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val allActive = allActiveState.value
    val allFailed = allFailedState.value
    val player = ui.player
    val from by player.from.collectAsStateWithLifecycle()
    val queueShuffle by player.shuffle.collectAsStateWithLifecycle()
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val key = DownloadedViews.SOURCE
    val title = stringResource(R.string.downloads)
    val colors = Theme.colors
    val sideways = org.medianexus.music.ui.shell.sidewaysPhone()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // On its side a phone has the height for a line or two: the play button goes beside
        // the words, as an album's page puts it.
        val actions = @Composable { inline: Boolean ->
            ActionBar(
                title = title,
                canPlay = view.songs.isNotEmpty(),
                playing = from.isList(key) && !from.paused,
                onPlay = {
                    if (from.isList(key)) player.playPause() else ui.play(view.songs, 0, title, key, anyStart = true)
                },
                shuffle = if (key == from.source) queueShuffle else settings.lists[key]?.shuffle ?: false,
                onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                inline = inline,
                // While anything is on its way, the batch, and the button cancels it all (as
                // Windows' button does on its Downloads page).
                download = if (!state.busy) null else DownloadButton(1, state.batchDone + state.batchFailed, state.batchTotal, state.progress) {
                    account?.downloads?.cancelAll { done -> ui.toast(res.getString(if (done) R.string.downloads_cancelled else R.string.couldnt_cancel_downloads)) }
                },
            )
        }
        item(key = "head", contentType = "head") {
            Row(Modifier.fillMaxWidth().padding(start = Theme.dimens.pagePad, end = if (sideways) Theme.dimens.pagePad else 0.dp).padding(top = if (titled) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(stringResource(R.string.on_this_phone), style = Theme.type.label.copy(color = colors.text))
                    if (titled) {
                        BasicText(title, Modifier.semantics { heading() }, style = Theme.type.title.copy(color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp))
                    }
                    BasicText(summary(res, state), style = Theme.type.body.copy(color = colors.muted))
                }
                if (sideways) actions(true)
            }
        }
        if (!sideways) item(key = "actions", contentType = "actions") { actions(false) }
        // Why what is waiting isn't moving, or how far the batch has got.
        val held = when (state.held) {
            Held.WIFI -> res.getString(R.string.waiting_for_wifi)
            Held.NETWORK -> res.getString(R.string.waiting_for_network)
            Held.OFFLINE -> res.getString(R.string.downloads_offline_mode)
            Held.SERVER -> res.getString(R.string.cant_reach_server_short)
            Held.ANDROID -> res.getString(R.string.waiting_for_android)
            Held.NOTHING -> null
        }
        if (state.busy) {
            item(key = "busy", contentType = "heading") {
                Column(Modifier.fillMaxWidth()) {
                    ListHeading(stringResource(R.string.downloading_now), action = stringResource(R.string.cancel_all), onAction = {
                        account?.downloads?.cancelAll { done -> ui.toast(res.getString(if (done) R.string.downloads_cancelled else R.string.couldnt_cancel_downloads)) }
                    })
                    BasicText(
                        held ?: res.getString(R.string.downloading_n_of, state.at, state.batchTotal),
                        Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 4.dp),
                        style = Theme.type.label.copy(color = if (held != null) colors.warm else colors.accentBright),
                    )
                    Overall(state.progress)
                }
            }
            val waiting = Folded.of(state.active, allActive)
            if (allActive && waiting.shown.size > Folded.MOST + 1) item(key = "active-fewer", contentType = "more") { More("", stringResource(R.string.show_fewer)) { allActiveState.value = false } }
            itemsIndexed(waiting.shown, key = { _, it -> "active/${it.song.id}" }, contentType = { _, _ -> "song" }) { index, active ->
                val song = active.song
                val row = remember(song) { rowOf(song) }
                TrackRow(
                    row.copy(liked = song.id in liked),
                    // Played as it is streamed (it isn't on the phone yet).
                    onClick = { ui.play(state.active.map { it.song }, index, title) },
                    onMore = { ui.menu = SongMenu(song) },
                    showDuration = true,
                    mark = RowMark(active.state),
                    trailing = {
                        IconButton(R.drawable.ic_x, stringResource(R.string.cancel_download) + ": " + song.title, {
                            account?.downloads?.cancel(listOf(song.id)) { done -> ui.toast(res.getString(if (done) R.string.download_cancelled else R.string.couldnt_cancel_downloads)) }
                        }, iconSize = 20.dp)
                    },
                )
            }
            if (waiting.more > 0) item(key = "active-more", contentType = "more") {
                More(res.getQuantityString(R.plurals.more_waiting, waiting.more, waiting.more), stringResource(R.string.show_all)) { allActiveState.value = true }
            }
        }
        if (state.failed.isNotEmpty()) {
            item(key = "failed", contentType = "heading") {
                Column(Modifier.fillMaxWidth()) {
                    ListHeading(stringResource(R.string.couldnt_download_heading))
                    DownloadPages.trouble(context, state.failed)?.let { trouble ->
                        BasicText(trouble, Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 4.dp), style = Theme.type.body.copy(color = colors.text))
                    }
                    FlowRow(Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(stringResource(R.string.retry_failed), primary = true, onClick = {
                            val downloads = account?.downloads ?: return@TextButton
                            ui.askToDownload { scope.launch { downloads.retryFailed()?.let { ui.toast(DownloadPages.said(context, it)) } } }
                        })
                    }
                }
            }
            val failures = Folded.of(state.failed, allFailed)
            if (allFailed && failures.shown.size > Folded.MOST + 1) item(key = "failed-fewer", contentType = "more") { More("", stringResource(R.string.show_fewer)) { allFailedState.value = false } }
            itemsIndexed(failures.shown, key = { _, it -> "failed/${it.song.id}" }, contentType = { _, _ -> "song" }) { index, failed ->
                val song = failed.song
                val row = remember(song) { rowOf(song) }
                TrackRow(
                    row.copy(liked = song.id in liked),
                    onClick = { ui.play(state.failed.map { it.song }, index, title) },
                    onMore = { ui.menu = SongMenu(song) },
                    mark = RowMark(SongState.Failed(failed.reason), unavailable = marks.offline && !marks.playsNow(song)),
                    detail = failed.reason,
                )
            }
            if (failures.more > 0) item(key = "failed-more", contentType = "more") {
                More(res.getQuantityString(R.plurals.more_failed, failures.more, failures.more), stringResource(R.string.show_all)) { allFailedState.value = true }
            }
        }
        when {
            view.songs.isEmpty() && !state.busy && state.failed.isEmpty() -> item(key = "empty", contentType = "empty") {
                Box(Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
                    PageStatus(PageState.Empty(stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_empty), R.drawable.ic_download))
                }
            }
            else -> {
                groups("playlists", res.getString(R.string.playlists), view.playlists, res) { ui.open(Route.Downloaded(it.list, it.title)) }
                groups("albums", res.getString(R.string.albums), view.albums, res) { ui.open(Route.Downloaded(it.list, it.title)) }
                if (view.songs.isNotEmpty()) {
                    item(key = "songs", contentType = "heading") { ListHeading(stringResource(R.string.downloaded_songs_heading)) }
                    val songs = view.songs
                    itemsIndexed(songs, key = { _, song -> "song/${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                        val row = remember(song) { rowOf(song) }
                        val current = from.isRow(key, index, song.id)
                        TrackRow(
                            row.copy(liked = song.id in liked, current = current, paused = current && from.paused),
                            onClick = { ui.play(songs, index, title, key) },
                            onMore = { ui.menu = SongMenu(song) },
                            showDuration = true,
                            mark = marks.rowMark(song),
                        )
                    }
                }
            }
        }
    }
}

/** The foot (or head) of a folded section: how many more there are, and the way to open
 *  it (or fold it again). */
@Composable
private fun More(words: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Theme.dimens.pagePad, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(words, Modifier.weight(1f), style = Theme.type.body.copy(color = Theme.colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(action, onClick = onAction)
    }
}

/** The batch's progress, as a bar across the page. */
@Composable
private fun Overall(progress: Float) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 6.dp).heightIn(min = 4.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 4.dp).clip(shape).background(colors.press))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).heightIn(min = 4.dp).clip(shape).background(colors.accentBright))
    }
}

/** Playlists or albums, a row each, under a heading. */
private fun LazyListScope.groups(kind: String, heading: String, groups: List<DownloadedGroup>, res: android.content.res.Resources, onOpen: (DownloadedGroup) -> Unit) {
    if (groups.isEmpty()) return
    item(key = "$kind-heading", contentType = "heading") { ListHeading(heading) }
    items(groups, key = { "$kind/${it.list}" }, contentType = { "group" }) { group ->
        GroupRow(group, res.getString(R.string.list_facts_size, songCount(res, group.songs), Downloads.sizeLabel(group.bytes)), onClick = { onOpen(group) })
    }
}

@Composable
private fun GroupRow(group: DownloadedGroup, facts: String, onClick: () -> Unit) {
    val colors = Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val shape = RoundedCornerShape(Theme.dimens.rowCoverRadius)
        if (group.liked) {
            LikedTile(Modifier.size(56.dp), shape = shape)
        } else {
            Cover(group.cover, Modifier.size(56.dp), CoverShape.ROW, if (group.list.startsWith("album:")) R.drawable.ic_disc_3 else R.drawable.ic_list_music)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(group.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.by.isNotEmpty()) BasicText(group.by, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(facts, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun rowOf(song: Song) = TrackRowData(song.id, song.title, song.artistLabel + if (song.album.isNotEmpty()) " • ${song.album}" else "", song.coverArt, duration = clock(song.duration))

/** "12 songs, 340 MB, and 3 more on their way" (Windows' summary line). */
internal fun summary(res: android.content.res.Resources, state: DownloadsState): String {
    val count = state.downloaded.size
    val base = if (count == 0) res.getString(R.string.nothing_downloaded) else res.getQuantityString(R.plurals.downloads_summary, count, count, Downloads.sizeLabel(state.bytes))
    val waiting = state.active.size
    return if (waiting == 0) base else res.getQuantityString(R.plurals.on_their_way, waiting, base, waiting)
}
