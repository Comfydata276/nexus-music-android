package org.medianexus.music.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Artist
import org.medianexus.music.net.StarTarget
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.HeroData
import org.medianexus.music.ui.parts.MediaCard
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.albumByYear
import org.medianexus.music.ui.parts.albumCount
import org.medianexus.music.ui.parts.albumFacts
import org.medianexus.music.ui.parts.card
import org.medianexus.music.ui.parts.listFacts
import org.medianexus.music.ui.parts.releaseKind
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.shell.open

@Composable
fun AlbumPage(route: Route.Album) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed(route.key) { it.album(route.id) }
    val album = feed.value
    val hero = if (album == null) {
        HeroData(stringResource(R.string.kind_album), route.title, route.artist, cover = route.cover)
    } else {
        HeroData(stringResource(releaseKind(album)), album.name, album.artistLabel, albumFacts(res, album), album.coverArt)
    }
    // Liking an album shows at once and is put back if the server says no, as songs are.
    var starred by remember(album?.id) { mutableStateOf<Boolean?>(null) }
    val liked = starred ?: (album?.starred != null)
    val scope = rememberCoroutineScope()
    // Other releases by the same artist, as Windows adds below an album ("More by …").
    val artistId = album?.artistId.orEmpty()
    val others = if (artistId.isEmpty()) null else rememberPageFeed("artist:$artistId") { it.artist(artistId) }
    SongListPage(
        key = route.key,
        hero = hero,
        songs = album?.songs,
        context = album?.name ?: route.title,
        feed = feed,
        empty = stringResource(R.string.album_empty),
        look = ListLook(album = true),
        onSubtitle = album?.takeIf { it.artistId.isNotEmpty() }?.let { { ui.open(Route.Artist(it.artistId, it.artistLabel)) } },
        liked = if (album == null) null else liked,
        onLike = {
            val api = ui.account?.api ?: return@SongListPage
            val id = album?.id ?: return@SongListPage
            val want = !liked
            starred = want
            ui.toast(res.getString(if (want) R.string.added_to_liked_albums else R.string.removed_from_liked_albums))
            scope.launch {
                try {
                    api.setStarred(StarTarget.ALBUM, id, want)
                    ui.account?.pages?.forget("liked")
                } catch (e: ApiError) {
                    starred = !want
                    ui.toast(res.getString(R.string.didnt_save, e.message.orEmpty()).trim())
                }
            }
        },
        below = {
            val more = others?.value?.albums?.filter { it.id != route.id }.orEmpty()
            if (more.isNotEmpty()) {
                item(key = "more", contentType = "shelf") {
                    Shelf(
                        stringResource(R.string.more_by, album?.artistLabel.orEmpty()),
                        remember(more) { more.map { it.card(albumByYear(res, it)) } },
                        onOpen = { ui.open(it) },
                        onShowAll = { ui.open(Route.Artist(artistId, album?.artistLabel.orEmpty())) },
                    )
                }
            }
        },
    )
}

@Composable
fun PlaylistPage(route: Route.Playlist) {
    val res = LocalResources.current
    val feed = rememberPageFeed(route.key) { it.playlist(route.id) }
    val playlist = feed.value
    val hero = if (playlist == null) {
        HeroData(stringResource(R.string.kind_playlist), route.name, cover = route.cover, playlist = true)
    } else {
        HeroData(stringResource(R.string.kind_playlist), playlist.name, playlist.owner, listFacts(res, playlist.songs), playlist.coverArt, playlist = true)
    }
    SongListPage(
        key = route.key,
        hero = hero,
        songs = playlist?.songs,
        context = playlist?.name ?: route.name,
        feed = feed,
        empty = stringResource(R.string.playlist_empty),
    )
}

@Composable
fun LikedPage() {
    val res = LocalResources.current
    val feed = rememberPageFeed(Route.Liked.key) { it.liked() }
    val songs = feed.value?.songs
    val title = stringResource(R.string.liked_songs)
    SongListPage(
        key = Route.Liked.key,
        hero = HeroData(stringResource(R.string.kind_playlist), title, meta = songs?.let { songCount(res, it.size) }.orEmpty(), liked = true),
        songs = songs,
        context = title,
        feed = feed,
        empty = stringResource(R.string.liked_empty),
    )
}

/** An artist: their albums, then singles and EPs, newest first, as on Windows. The play
 *  button plays every song on them. */
@Composable
fun ArtistPage(route: Route.Artist) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed(route.key) { it.artist(route.id) }
    val artist = feed.value
    val hero = HeroData(
        stringResource(R.string.kind_artist),
        artist?.name ?: route.name,
        meta = artist?.let { albumCount(res, it.albums.size) }.orEmpty(),
        cover = artist?.coverArt ?: route.cover,
        round = true,
    )
    val groups = remember(artist) { artist?.let(::grouped).orEmpty() }
    val scope = rememberCoroutineScope()
    ArtistLayout(
        key = route.key,
        hero = hero,
        artist = artist,
        feed = feed,
        groups = groups.map { (titleRes, albums) -> stringResource(titleRes) to albums },
        onPlay = { shuffle ->
            val library = ui.account?.library ?: return@ArtistLayout
            val albums = artist?.albums.orEmpty()
            scope.launch {
                // Every song on their albums, as Windows plays an artist (the first forty).
                val songs = albums.take(40).flatMap { album ->
                    try {
                        library.quick(library.album(album.id)).songs
                    } catch (_: ApiError) {
                        emptyList()
                    }
                }
                if (songs.isEmpty()) {
                    ui.toast(res.getString(R.string.page_failed))
                } else {
                    ui.play(songs, 0, artist?.name.orEmpty(), route.key, shuffle = shuffle, anyStart = true)
                }
            }
        },
        res = res,
    )
}

/** Albums first, then singles and EPs; each newest first. */
private fun grouped(artist: Artist): List<Pair<Int, List<Album>>> {
    val sorted = artist.albums.sortedWith(compareByDescending<Album> { it.year }.thenBy { it.name })
    val (singles, full) = sorted.partition { releaseKind(it) == R.string.kind_single || releaseKind(it) == R.string.kind_ep }
    return listOfNotNull(
        full.takeIf { it.isNotEmpty() }?.let { R.string.albums to it },
        singles.takeIf { it.isNotEmpty() }?.let { R.string.singles_and_eps to it },
    )
}

@Composable
private fun ArtistLayout(
    key: String,
    hero: HeroData,
    artist: Artist?,
    feed: org.medianexus.music.ui.shell.PageFeed<Artist>,
    groups: List<Pair<String, List<Album>>>,
    onPlay: (shuffle: Boolean?) -> Unit,
    res: android.content.res.Resources,
) {
    val ui = LocalUi.current
    SongListPage(
        key = key,
        hero = hero,
        // The header's play button plays the albums; the page lists no songs of its own.
        songs = if (artist == null) null else emptyList(),
        context = hero.title,
        feed = feed,
        empty = if (artist != null && artist.albums.isEmpty()) stringResource(R.string.artist_empty) else "",
        playOverride = if (artist?.albums.isNullOrEmpty()) null else ({ onPlay(null) }),
        below = {
            for ((title, albums) in groups) {
                gridSection(title, albums.map { it.card(albumByYear(res, it)) }) { ui.open(it) }
            }
        },
    )
}

/** A heading, then cards two or three to a line, inside a page's list. */
fun LazyListScope.gridSection(title: String, cards: List<CardData>, onOpen: (CardData) -> Unit) {
    item(key = "title/$title", contentType = "title") { SectionTitle(title) }
    item(key = "grid/$title", contentType = "grid") {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val columns = if (maxWidth >= 520.dp) 4 else if (maxWidth >= 380.dp) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in cards.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (card in line) MediaCard(card, onClick = { onOpen(card) }, modifier = Modifier.weight(1f))
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A genre: its albums, in a grid (the Windows app's `GridPage`). */
@Composable
fun GenrePage(route: Route.Genre) {
    val ui = LocalUi.current
    val feed = rememberPageFeed(route.key) { it.genre(route.name) }
    val albums = feed.value
    Column(Modifier.fillMaxSize()) {
        TitleBar(route.name, onBack = ui.navigator::back)
        Refreshable(feed.refreshing, feed::refresh) {
            val error = feed.page.error
            when {
                albums == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
                albums == null -> SkeletonRows()
                albums.isEmpty() -> PageStatus(PageState.Empty(stringResource(R.string.genre_empty)))
                else -> CardGrid(albums.map { it.card() }, onOpen = { ui.open(it) }, failed = error?.message, onRetry = feed::refresh)
            }
        }
    }
}

/** Cards in a grid that fills the page: albums, artists. */
@Composable
fun CardGrid(
    cards: List<CardData>,
    onOpen: (CardData) -> Unit,
    modifier: Modifier = Modifier,
    failed: String? = null,
    onRetry: () -> Unit = {},
    minWidth: androidx.compose.ui.unit.Dp = 150.dp,
    onNearEnd: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    if (onNearEnd != null) {
        val near by remember { androidx.compose.runtime.derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 12 } }
        LaunchedEffect(near, cards.size) { if (near) onNearEnd() }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(minWidth),
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
        }
        if (failed != null) {
            item(key = "failed", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, contentType = "failed") {
                org.medianexus.music.ui.parts.FailedStrip(failed, onRetry)
            }
        }
        items(cards, key = { "${it.kind}/${it.id}" }, contentType = { it.kind }) { card ->
            MediaCard(card, onClick = { onOpen(card) })
        }
        item(key = "end", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}
