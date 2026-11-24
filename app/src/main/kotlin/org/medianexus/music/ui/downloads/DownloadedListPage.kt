package org.medianexus.music.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.R
import kotlinx.coroutines.launch
import org.medianexus.music.downloads.DownloadPages
import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.ActionBar
import org.medianexus.music.ui.parts.DownloadButton
import org.medianexus.music.ui.parts.Hero
import org.medianexus.music.ui.parts.HeroData
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.shell.sidewaysPhone

/**
 * An album, playlist or Liked songs as the downloads have it (`DownloadedViews`): its
 * downloaded songs in its own order, under its name and cover as they were kept. Opened from
 * the Downloads page, it needs neither the server nor the kept pages, so it opens in a dead
 * spot as the car's Downloads tab does. Playing it plays what is downloaded of it, under a
 * name of its own (as the car does), and its download button offers to remove it.
 */
@Composable
fun DownloadedListPage(route: Route.Downloaded) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val state = downloadsNow()
    val marks = rememberDownloadMarks()
    val liked = likedSongs()
    val likedName = stringResource(R.string.liked_songs)
    val isAlbum = route.list.startsWith("album:")
    val album = remember(state.downloaded, state.collections, route.list) { if (isAlbum) DownloadedViews.album(state, route.list.removePrefix("album:")) else null }
    val playlist = remember(state.downloaded, state.collections, route.list) { if (!isAlbum) DownloadedViews.playlist(state, route.list) else null }
    val songs: List<Song> = album?.songs ?: playlist?.songs.orEmpty()
    val name = when {
        route.list == "liked" -> likedName
        album != null -> album.name
        playlist != null -> playlist.list.name
        else -> route.title
    }
    val bytes = remember(songs, state.downloaded) { songs.sumOf { state.downloaded[it.id]?.bytes ?: 0L } }
    val hero = HeroData(
        label = stringResource(if (isAlbum) R.string.kind_album else R.string.kind_playlist),
        title = name,
        subtitle = album?.artist ?: playlist?.list?.by.orEmpty(),
        meta = res.getString(R.string.list_facts_size, songCount(res, songs.size), Downloads.sizeLabel(bytes)),
        cover = album?.coverArt ?: playlist?.list?.coverArt.orEmpty(),
        liked = route.list == "liked",
        playlist = !isAlbum,
    )
    val key = "${DownloadedViews.SOURCE}:${route.list}"
    val player = ui.player
    val from by player.from.collectAsStateWithLifecycle()
    val queueShuffle by player.shuffle.collectAsStateWithLifecycle()
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val ids = remember(songs) { songs.mapTo(HashSet()) { it.id } }
    val listDownload by remember(marks, ids) { derivedStateOf { ListDownload.of(marks.now, ids) } }
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (scrolled) name else "", onBack = ui.navigator::back)
        if (songs.isEmpty()) {
            // All of it removed (here, or from another page) since it was opened.
            PageStatus(PageState.Empty(stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_empty), R.drawable.ic_download))
            return@Column
        }
        val sideways = sidewaysPhone()
        val actions = @Composable { inline: Boolean ->
            val now = listDownload
            ActionBar(
                title = name,
                canPlay = true,
                playing = from.isList(key) && !from.paused,
                onPlay = { if (from.isList(key)) player.playPause() else ui.play(songs, 0, name, key, anyStart = true) },
                shuffle = if (key == from.source) queueShuffle else settings.lists[key]?.shuffle ?: false,
                onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                inline = inline,
                // Everything here is downloaded (or on its way back after a retry): the
                // button offers to remove it, as on a list's own page, and by the same path
                // (`Downloads.removeGroup`): a song another downloaded list still needs
                // stays, and that list stays downloaded whole.
                download = DownloadButton(if (now.state == 1) 1 else 2, now.done, now.total, now.progress) {
                    val account = ui.account ?: return@DownloadButton
                    val shown = songs.map { it.id }
                    val go = {
                        account.scope.launch { DownloadPages.removeGroup(context, account, route.list, shown)?.let(ui::toast) }
                        Unit
                    }
                    if (now.state == 1) go() else ui.sheet = Sheet.RemoveList(name, hero.cover) { go() }
                },
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero", contentType = "hero") { Hero(hero, actions = if (sideways) ({ actions(true) }) else null) }
            if (!sideways) item(key = "actions", contentType = "actions") { actions(false) }
            itemsIndexed(songs, key = { index, song -> "$index/${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                val base = remember(song, index, isAlbum) {
                    val detail = if (!isAlbum && song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
                    TrackRowData(song.id, song.title, detail, song.coverArt, if (isAlbum && song.track > 0) song.track.toString() else (index + 1).toString(), clock(song.duration))
                }
                val current = from.isRow(key, index, song.id)
                TrackRow(
                    base.copy(liked = song.id in liked, current = current, paused = current && from.paused),
                    onClick = { ui.play(songs, index, name, key) },
                    onMore = { ui.menu = SongMenu(song) },
                    showCover = !isAlbum,
                    showNumber = isAlbum,
                    showDuration = true,
                    mark = marks.rowMark(song),
                )
            }
            item(key = "end", contentType = "end") { Box(Modifier.fillMaxWidth().heightIn(min = 8.dp)) }
        }
    }
}
