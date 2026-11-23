package org.medianexus.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.R
import org.medianexus.music.net.Album
import org.medianexus.music.net.AlbumList
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.library.Library
import org.medianexus.music.ui.lists.CardGrid
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.ChipRow
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.FailedStrip
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.LikedTile
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.albumCount
import org.medianexus.music.ui.parts.card
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.PageFeed
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.shell.open
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.theme.Theme

/** The Albums sorts, as Windows offers them (`ALBUM_SORTS`): names and lists. */
val ALBUM_SORTS: List<Pair<Int, AlbumList>> = listOf(
    R.string.sort_recently_added to AlbumList.Newest,
    R.string.sort_a_to_z to AlbumList.AlphabeticalByName,
    R.string.sort_by_artist to AlbumList.AlphabeticalByArtist,
    R.string.recently_played to AlbumList.Recent,
    R.string.most_played to AlbumList.Frequent,
    R.string.sort_newest_releases to AlbumList.ByYearDescending,
    R.string.liked to AlbumList.Starred,
)

/**
 * Library: playlists, albums, artists and genres, chosen with chips (the phone's version of
 * the Windows sidebar's sections). Liked songs heads the playlists, then the pinned ones in
 * the order they were pinned, then the rest by name.
 */
@Composable
fun LibraryPage() {
    val ui = LocalUi.current
    // Both kept with the page's saved state, here rather than in the part they choose: a
    // part that isn't showing keeps nothing, so the sort would be forgotten on looking at
    // another chip, and both when Android stops the app in the background.
    var part by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.tab_library)) {
            IconButton(R.drawable.ic_settings, stringResource(R.string.settings), { ui.open(Route.Settings) }, tint = Theme.colors.text)
        }
        ChipRow(
            listOf(stringResource(R.string.playlists), stringResource(R.string.albums), stringResource(R.string.artists), stringResource(R.string.genres)),
            selected = part,
            onChoose = { part = it },
        )
        when (part) {
            0 -> Playlists()
            1 -> AlbumsPart(sort to { it: Int -> sort = it })
            2 -> Artists()
            else -> Genres()
        }
    }
}

/** Every album in one sort, from Home's "Show all". */
@Composable
fun AlbumsPage(route: Route.Albums) {
    val ui = LocalUi.current
    var sort by rememberSaveable { mutableIntStateOf(route.sort.coerceIn(0, ALBUM_SORTS.lastIndex)) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.albums), onBack = ui.navigator::back)
        AlbumsPart(sort to { it: Int -> sort = it })
    }
}

@Composable
private fun Playlists() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed("playlists") { it.playlists() }
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val liked = likedSongs()
    val pinned = settings.pinnedPlaylists
    val playlists = remember(feed.value, pinned) {
        feed.value?.sortedWith(compareBy<Playlist> { pinned.indexOf(it.id).let { at -> if (at < 0) Int.MAX_VALUE else at } }.thenBy { it.name.lowercase() })
    }
    Refreshable(feed.refreshing, feed::refresh) {
        val error = feed.page.error
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (error != null && playlists != null) item(key = "failed") { FailedStrip(error.message.orEmpty(), feed::refresh) }
            item(key = "liked", contentType = "row") {
                ListRow(stringResource(R.string.liked_songs), songCount(res, liked.size), onClick = { ui.open(Route.Liked) }) {
                    LikedTile(Modifier.size(56.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(Theme.dimens.rowCoverRadius))
                }
            }
            when {
                playlists == null && error != null -> item(key = "error") { PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh) }
                playlists == null -> item(key = "loading") { SkeletonRows() }
                playlists.isEmpty() -> item(key = "empty") {
                    PageStatus(PageState.Empty(stringResource(R.string.no_playlists), stringResource(R.string.no_playlists_detail), R.drawable.ic_list_music))
                }
                else -> items(playlists, key = { it.id }, contentType = { "row" }) { playlist ->
                    ListRow(
                        playlist.name,
                        songCount(res, playlist.songCount),
                        pinned = playlist.id in pinned,
                        onClick = { ui.open(Route.Playlist(playlist.id, playlist.name, playlist.coverArt)) },
                    ) {
                        Cover(playlist.coverArt, Modifier.size(56.dp), CoverShape.ROW, R.drawable.ic_list_music)
                    }
                }
            }
        }
    }
}

/** A playlist (or Liked songs) in Library's list: its picture, name and count. */
@Composable
private fun ListRow(title: String, detail: String, onClick: () -> Unit, pinned: Boolean = false, picture: @Composable () -> Unit) {
    val colors = Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        picture()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pinned) Icon(R.drawable.ic_pin, size = 13.dp, tint = colors.accentBright, contentDescription = stringResource(R.string.pinned))
                BasicText(detail, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1)
            }
        }
    }
}

/**
 * Albums, in the sort chosen, sixty at a time as the grid scrolls (as Windows asks for them).
 * The first sixty are kept like any page; the rest are fetched as they're reached (see
 * [AlbumPages]). How far down the grid was, and how many albums that took, are kept with the
 * page's saved state, so coming back after Android has stopped the app finds the same place.
 */
@Composable
private fun AlbumsPart(sorting: Pair<Int, (Int) -> Unit>) {
    val ui = LocalUi.current
    val (sort, choose) = sorting
    val list = ALBUM_SORTS[sort.coerceIn(0, ALBUM_SORTS.lastIndex)].second
    val feed = rememberPageFeed("albums:$sort") { it.albums(list, 0, Library.GRID_PAGE) }
    val library = ui.account?.library
    val scope = rememberCoroutineScope()
    val pages = remember(sort, library) {
        AlbumPages(Library.GRID_PAGE) { offset, changed ->
            // Kept like any page, so going back (or coming back after the app was stopped)
            // reads them from the phone; put right when the server's copy comes.
            library?.quick(library.albums(list, offset, Library.GRID_PAGE)) { fresh -> scope.launch { changed(fresh) } } ?: emptyList()
        }
    }
    LaunchedEffect(pages, feed.page) { feed.value?.let { pages.first(it, feed.page.fresh) } }
    // A place of its own for each sort: one sort's place means nothing in another's list.
    val grid = rememberSaveable(sort, saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    // Where the grid was, and how many albums were loaded to get there: kept with the page's
    // saved state. Each time the grid is made (after Android stopped the app, too) it first
    // loads as far as that and goes there; so "restoring" is not saved, only the place.
    var loaded by rememberSaveable(sort) { mutableIntStateOf(0) }
    var restoring by remember(sort) { mutableStateOf(true) }
    val wantFirst = rememberSaveable(sort) { intArrayOf(-1, 0) }
    LaunchedEffect(pages, pages.albums.size) { if (!restoring) loaded = pages.albums.size }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (!restoring) {
                wantFirst[0] = index
                wantFirst[1] = offset
            }
        }
    }
    // Brought back (after the app was stopped, or to this chip): load as far as it had got,
    // then go there. Only while the app is in front.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pages, feed.value != null, lifecycle) {
        if (feed.value == null || !restoring) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (!restoring) return@repeatOnLifecycle
            pages.loadUntil(loaded)
            if (wantFirst[0] > 0) grid.scrollToItem(wantFirst[0], wantFirst[1])
            restoring = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        ChipRow(ALBUM_SORTS.map { stringResource(it.first) }, selected = sort, onChoose = choose)
        Refreshable(feed.refreshing, {
            pages.refresh()
            feed.refresh()
        }) {
            val error = feed.page.error
            val albums = pages.albums
            when {
                albums.isEmpty() && feed.value == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
                albums.isEmpty() && feed.value == null -> SkeletonRows()
                albums.isEmpty() && feed.value?.isEmpty() == true -> PageStatus(PageState.Empty(stringResource(if (list == AlbumList.Starred) R.string.no_liked_albums else R.string.no_albums), icon = R.drawable.ic_disc_3))
                // The page has come but isn't in the grid yet (a frame later): no grid until it
                // is. A grid drawn empty holds on to its foot, and the albums arriving above it
                // leave it scrolled to the end of the first page.
                albums.isEmpty() -> SkeletonRows()
                else -> CardGrid(
                    albums,
                    card = { it.card() },
                    key = { it.id },
                    onOpen = { ui.open(it) },
                    failed = error?.message,
                    onRetry = {
                        pages.refresh()
                        feed.refresh()
                    },
                    onNearEnd = { pages.more() },
                    progress = pages.fetched,
                    state = grid,
                    footer = when {
                        pages.failed != null -> ({ FailedStrip(pages.failed?.message.orEmpty(), { scope.launch { pages.retry() } }) })
                        pages.asking -> ({ SkeletonRows(rows = 1) })
                        else -> null
                    },
                )
            }
        }
    }
}

@Composable
private fun Artists() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed("artists") { it.artists() }
    val artists = feed.value
    Refreshable(feed.refreshing, feed::refresh) {
        val error = feed.page.error
        when {
            artists == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
            artists == null -> SkeletonRows()
            artists.isEmpty() -> PageStatus(PageState.Empty(stringResource(R.string.no_artists), icon = R.drawable.ic_user_round))
            else -> CardGrid(
                artists,
                card = { CardData(CardKind.ARTIST, it.id, it.name, albumCount(res, it.albumCount), it.coverArt) },
                key = { it.id },
                onOpen = { ui.open(it) },
                failed = error?.message,
                onRetry = feed::refresh,
            )
        }
    }
}

@Composable
private fun Genres() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed("genres") { it.genres() }
    val genres = feed.value
    Refreshable(feed.refreshing, feed::refresh) {
        val error = feed.page.error
        when {
            genres == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
            genres == null -> SkeletonRows()
            genres.isEmpty() -> PageStatus(PageState.Empty(stringResource(R.string.no_genres), icon = R.drawable.ic_tags))
            else -> CardGrid(
                genres,
                card = { CardData(CardKind.GENRE, it.name, it.name, "${albumCount(res, it.albumCount)}, ${songCount(res, it.songCount)}") },
                key = { it.name },
                onOpen = { ui.open(it) },
                failed = error?.message,
                onRetry = feed::refresh,
                minWidth = 160.dp,
            )
        }
    }
}

