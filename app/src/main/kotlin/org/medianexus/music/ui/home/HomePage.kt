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
import androidx.compose.runtime.remember
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
 * shelves of albums that scroll sideways.
 */
@Composable
fun HomePage() {
    val ui = LocalUi.current
    val recent = rememberPageFeed("home:recent") { it.albums(AlbumList.Recent, 0, HomeModel.ROW) }
    val newest = rememberPageFeed("home:newest") { it.albums(AlbumList.Newest, 0, HomeModel.ROW) }
    val frequent = rememberPageFeed("home:frequent") { it.albums(AlbumList.Frequent, 0, HomeModel.FREQUENT) }
    val random = rememberPageFeed("home:random") { it.albums(AlbumList.Random, 0, HomeModel.ROW) }
    val feeds = listOf(recent, newest, frequent, random)
    val tiles = remember(recent.value, newest.value) { HomeModel.tiles(recent.value.orEmpty(), newest.value.orEmpty()) }
    val shelves = remember(recent.value, newest.value, frequent.value, random.value) {
        HomeModel.shelves(recent.value.orEmpty(), newest.value.orEmpty(), frequent.value.orEmpty(), random.value.orEmpty(), System.currentTimeMillis() / 1000)
    }
    val nothingYet = feeds.all { it.value == null }
    // As on Windows, what was played lately is what the page can't do without; the rest are
    // extras whose failure doesn't blank it.
    val error = recent.page.error
    val refresh = { feeds.forEach { it.refresh() } }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(HomeModel.greeting(LocalTime.now().hour))) {
            IconButton(R.drawable.ic_settings, stringResource(R.string.settings), { ui.open(Route.Settings) }, tint = Theme.colors.text)
        }
        Refreshable(feeds.any { it.refreshing }, refresh) {
            when {
                nothingYet && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = refresh)
                nothingYet -> SkeletonRows()
                shelves.isEmpty() && tiles.isEmpty() && feeds.none { it.page.checking } ->
                    PageStatus(PageState.Empty(stringResource(R.string.home_empty), icon = R.drawable.ic_house))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (error != null) item(key = "failed", contentType = "failed") { FailedStrip(error.message.orEmpty(), refresh) }
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
        BasicText(
            album.name,
            Modifier.weight(1f).padding(horizontal = 10.dp),
            style = Theme.type.cardTitle.copy(color = Theme.colors.text),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tile's wash, as on Windows: white at a twelfth. */
private val TILE = Color(0x14FFFFFF)
