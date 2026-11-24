package org.medianexus.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import org.medianexus.music.R
import org.medianexus.music.net.Album
import org.medianexus.music.net.AlbumList
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.FailedStrip
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.card
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.open
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.theme.Theme

/**
 * Home, as the Windows app's: a greeting, quick tiles of what was played lately, then
 * shelves of albums that scroll sideways. Offline it leads with what is downloaded (the
 * playlists and albums on the phone), and the tiles and shelves keep only the albums with
 * something that plays.
 */
@Composable
fun HomePage() {
    val ui = LocalUi.current
    val recent = rememberPageFeed("home:recent") { it.albums(AlbumList.Recent, 0, HomeModel.ROW) }
    val newest = rememberPageFeed("home:newest") { it.albums(AlbumList.Newest, 0, HomeModel.ROW) }
    val frequent = rememberPageFeed("home:frequent") { it.albums(AlbumList.Frequent, 0, HomeModel.FREQUENT) }
    val random = rememberPageFeed("home:random") { it.albums(AlbumList.Random, 0, HomeModel.ROW) }
    val feeds = listOf(recent, newest, frequent, random)
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    val playing = remember(phone) { phone?.let { org.medianexus.music.library.OnThePhone.albumIds(it) } }
    val tiles = remember(recent.value, newest.value, playing) { HomeModel.tiles(recent.value.orEmpty(), newest.value.orEmpty()).playable(playing) }
    val shelves = remember(recent.value, newest.value, frequent.value, random.value, playing) {
        HomeModel.shelves(recent.value.orEmpty(), newest.value.orEmpty(), frequent.value.orEmpty(), random.value.orEmpty(), System.currentTimeMillis() / 1000)
            .map { it.copy(albums = it.albums.playable(playing)) }.filter { it.albums.isNotEmpty() }
    }
    // Offline: what is downloaded, first.
    val res = androidx.compose.ui.platform.LocalResources.current
    val likedName = stringResource(R.string.liked_songs)
    val downloads = if (phone != null) org.medianexus.music.ui.downloads.downloadsNow() else null
    val downloaded = remember(downloads?.downloaded, downloads?.collections, likedName) {
        downloads?.let { state -> org.medianexus.music.ui.downloads.DownloadsView.of(state, likedName).let { it.playlists + it.albums } }.orEmpty()
    }
    val nothingYet = feeds.all { it.value == null } && downloaded.isEmpty()
    // As on Windows, what was played lately is what the page can't do without; the rest are
    // extras whose failure doesn't blank it. Offline, not reaching the server is what offline
    // is: the title bar's pill says so once.
    val error = recent.page.error?.takeIf { phone == null }
    val refresh = { feeds.forEach { it.refresh() } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // The downloads' shelf arrives above what is shown (going offline): a list keeps its
    // first item in place, which would leave the new shelf out of sight above it. Near the
    // top, the page goes to the top to show it.
    LaunchedEffect(downloaded.isNotEmpty()) { if (downloaded.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(HomeModel.greeting(LocalTime.now().hour))) {
            // The last hundred songs played (Windows keeps them beside the queue).
            IconButton(R.drawable.ic_history, stringResource(R.string.recently_played), { ui.open(Route.RecentlyPlayed) }, tint = Theme.colors.text)
            IconButton(R.drawable.ic_settings, stringResource(R.string.settings), { ui.open(Route.Settings) }, tint = Theme.colors.text)
        }
        Refreshable(feeds.any { it.refreshing }, refresh) {
            when {
                nothingYet && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = refresh)
                phone != null && downloaded.isEmpty() && shelves.isEmpty() && tiles.isEmpty() ->
                    PageStatus(PageState.Empty(stringResource(R.string.home_offline_empty), icon = R.drawable.ic_download))
                nothingYet -> SkeletonRows()
                shelves.isEmpty() && tiles.isEmpty() && downloaded.isEmpty() && feeds.none { it.page.checking } ->
                    PageStatus(PageState.Empty(stringResource(R.string.home_empty), icon = R.drawable.ic_house))
                else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (error != null) item(key = "failed", contentType = "failed") { FailedStrip(error.message.orEmpty(), refresh) }
                    if (downloaded.isNotEmpty()) item(key = "downloaded", contentType = "shelf") {
                        Shelf(
                            stringResource(R.string.your_downloads),
                            remember(downloaded, res) {
                                downloaded.map { group ->
                                    org.medianexus.music.ui.parts.CardData(
                                        if (group.list.startsWith("album:")) org.medianexus.music.ui.parts.CardKind.ALBUM else org.medianexus.music.ui.parts.CardKind.PLAYLIST,
                                        group.list, group.title, group.by.ifEmpty { org.medianexus.music.ui.parts.songCount(res, group.songs) }, group.cover,
                                    )
                                }
                            },
                            onOpen = { card -> ui.open(Route.Downloaded(card.id, card.title)) },
                            onShowAll = { ui.open(Route.Downloads) },
                        )
                    }
                    if (tiles.isNotEmpty()) item(key = "tiles", contentType = "tiles") { Tiles(tiles) }
                    items(shelves, key = { it.title }, contentType = { "shelf" }) { shelf ->
                        Shelf(
                            stringResource(shelf.title),
                            remember(shelf.albums) { shelf.albums.map { it.card() } },
                            onOpen = { ui.open(it) },
                            onShowAll = shelf.showAll?.let { sort -> { ui.open(Route.Albums(sort)) } },
                        )
                    }
                }
            }
        }
    }
}

/** The quick tiles: two to a line, two lines, as Windows shows them on a narrow window. */
@Composable
private fun Tiles(albums: List<Album>) {
    val ui = LocalUi.current
    Column(Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in albums.take(4).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (album in line) QuickTile(album, Modifier.weight(1f)) { ui.open(Route.Album(album.id, album.name, album.artistLabel, album.coverArt)) }
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A compact album tile for the top of Home: its cover at the edge, its name beside it. */
@Composable
private fun QuickTile(album: Album, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(TILE)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = album.name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) {
            Cover(album.coverArt, Modifier.fillMaxSize(), placeholder = R.drawable.ic_disc_3, shape = org.medianexus.music.ui.parts.CoverShape.SQUARE)
        }
        TileName(album.name, Modifier.weight(1f).padding(horizontal = 10.dp))
    }
}

/**
 * A tile's name: up to two lines, then an ellipsis, broken only between words, never with a
 * hyphen. A word too long for the tile's width even on a line of its own ("Continuous" in a
 * narrow tile at large type) would otherwise be cut through the middle; the type steps down a
 * little until it fits, or reaches the smallest size that is still readable, as Windows'
 * tiles shrink a long name.
 */
@Composable
private fun TileName(name: String, modifier: Modifier) {
    val style = Theme.type.label
    var size by remember(name, style) { mutableStateOf(style.fontSize) }
    BasicText(
        name,
        modifier,
        style = style.copy(
            color = Theme.colors.text,
            fontSize = size,
            hyphens = androidx.compose.ui.text.style.Hyphens.None,
            lineBreak = androidx.compose.ui.text.style.LineBreak.Heading,
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (size.value > MIN_TILE_SP && splitsAWord(name, layout)) size = (size.value - 1f).sp
        },
    )
}

/** Whether a line ends part-way through a word (not counting where the ellipsis goes). */
internal fun splitsAWord(text: String, layout: androidx.compose.ui.text.TextLayoutResult): Boolean {
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return true
    }
    return false
}

private const val MIN_TILE_SP = 11f

/** The tile's wash, as on Windows: white at a twelfth. */
private val TILE = Color(0x14FFFFFF)

/** Offline (`playing` given), only the albums with something that plays. */
private fun List<Album>.playable(playing: Set<String>?): List<Album> = if (playing == null) this else filter { it.id in playing }
