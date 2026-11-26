package org.medianexus.music.ui.lists

import org.medianexus.music.ui.parts.FastScroll
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
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
import org.medianexus.music.ui.shell.likedAlbums
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.shell.open
import org.medianexus.music.ui.menu.openMenu

@Composable
fun AlbumPage(route: Route.Album) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed(route.key) { it.album(route.id) }
    if (fromDownloads(route.key, feed, feed.value?.songs)) {
        org.medianexus.music.ui.downloads.DownloadedListPage(Route.Downloaded(route.key, route.title))
        return
    }
    // Offline with no copy of the page (or a kept page with nothing of it on the phone: kept
    // from before its songs were replaced) and nothing of it downloaded, an album shown
    // because songs of it are kept from being played is what the phone has of it.
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    val album = remember(feed.value, phone, route.id) { org.medianexus.music.library.OnThePhone.albumShown(feed.value, phone, route.id) }
    val hero = if (album == null) {
        HeroData(stringResource(R.string.kind_album), route.title, route.artist, cover = route.cover)
    } else {
        val facts = remember(album, res) { albumFacts(res, album) }
        HeroData(stringResource(releaseKind(album)), album.name, album.artistLabel, facts, album.coverArt)
    }
    // What's liked comes from the one owner of likes, as songs' hearts do: shown at once,
    // put back (and said so) if the server says no.
    val likedAlbums = likedAlbums()
    val liked = album != null && album.id in likedAlbums
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
        downloadable = true,
        onSubtitle = album?.takeIf { it.artistId.isNotEmpty() }?.let { { ui.open(Route.Artist(it.artistId, it.artistLabel)) } },
        liked = if (album == null) null else liked,
        onMore = { page -> ui.openMenu(org.medianexus.music.ui.menu.Collection.Album(route.id, album?.name ?: route.title, page = page)) },
        onLike = {
            val shown = album ?: return@SongListPage
            // On the likes as they stand, not as last drawn: two quick taps are two changes.
            val now = ui.account?.likes?.toggleAlbum(shown) ?: return@SongListPage
            ui.toast(res.getString(if (now) R.string.added_to_liked_albums else R.string.removed_from_liked_albums))
        },
        below = {
            // (Offline, only those with something on the phone: everything shown opens.)
            val here = phone?.let { org.medianexus.music.library.OnThePhone.albumIds(it) }
            val more = others?.value?.albums?.filter { it.id != route.id && (here == null || it.id in here) }.orEmpty()
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
    if (fromDownloads(route.key, feed, feed.value?.songs)) {
        org.medianexus.music.ui.downloads.DownloadedListPage(Route.Downloaded(route.key, route.name))
        return
    }
    val ui = LocalUi.current
    val playlists = ui.account?.playlists
    // The server's copy, with the one change on its way (if any) shown as made (see Playlists).
    val busyNow = (playlists?.busy ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet()) }).collectAsStateWithLifecycle().value
    val busy = route.id in busyNow
    val kept = feed.value
    val playlist = remember(kept, busy) { kept?.let { playlists?.shown(it) ?: it } }
    // Gone from the server (its error 70, met by a change): the page goes.
    val gone = (playlists?.gone ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet()) }).collectAsStateWithLifecycle().value
    androidx.compose.runtime.LaunchedEffect(route.id in gone) { if (route.id in gone) ui.navigator.back() }
    val hero = if (playlist == null) {
        HeroData(stringResource(R.string.kind_playlist), route.name, cover = route.cover, playlist = true)
    } else {
        // The facts (the length above all, a scan of every song) worked out once when the
        // list arrives, not at every redraw.
        val facts = remember(playlist, res) { listFacts(res, playlist.songs) }
        HeroData(stringResource(R.string.kind_playlist), playlist.name, playlist.owner, facts, playlist.coverArt, playlist = true)
    }
    // Changed only if it is known to be the account's own: someone else's plays, and says why
    // it can't be changed. And only with the server there: changes are never kept for later,
    // so without it the menus' changes say why, and there is nothing to drag.
    val mine = kept != null && playlists?.canEdit(kept) == true
    val online = !org.medianexus.music.ui.downloads.reachNow().let { it.offline || !it.sure }
    // Holding songs missing from the library: nothing is taken out or moved (its handles dimmed,
    // and it says why); songs can still be added.
    val missing = kept?.hidesSongs == true
    SongListPage(
        key = route.key,
        hero = hero,
        songs = playlist?.songs,
        context = playlist?.name ?: route.name,
        feed = feed,
        empty = stringResource(if (mine) R.string.playlist_empty_editable else R.string.playlist_empty),
        downloadable = true,
        editable = playlist.takeIf { mine },
        draggable = online,
        busy = busy || missing,
        // (The copy the page is drawing as the menu opens: what its Rename and Delete are for.)
        onMore = { page -> ui.openMenu(org.medianexus.music.ui.menu.Collection.Playlist(route.id, playlist?.name ?: route.name, playlist, page = page)) },
        note = when {
            kept != null && !mine -> stringResource(R.string.not_your_playlist, kept.owner)
            mine && missing -> stringResource(R.string.playlist_missing_songs)
            else -> null
        },
        below = { if (mine && online && playlist != null) recommendations(playlist, busy) },
    )
}

@Composable
fun LikedPage() {
    val res = LocalResources.current
    val feed = rememberPageFeed(Route.Liked.key) { it.liked() }
    if (fromDownloads(Route.Liked.key, feed, feed.value?.songs)) {
        org.medianexus.music.ui.downloads.DownloadedListPage(Route.Downloaded(Route.Liked.key))
        return
    }
    // The server's list with what has just been liked or unliked here on top: the same
    // owner as every heart, so the two never disagree.
    val likes = LocalUi.current.account?.likes
    val shown = likedSongs()
    val songs = remember(feed.value, shown, likes) { likes?.songsOf(feed.value) ?: feed.value?.songs }
    val title = stringResource(R.string.liked_songs)
    SongListPage(
        key = Route.Liked.key,
        hero = HeroData(stringResource(R.string.kind_playlist), title, meta = songs?.let { songCount(res, it.size) }.orEmpty(), liked = true),
        songs = songs,
        context = title,
        feed = feed,
        empty = stringResource(R.string.liked_empty),
        downloadable = true,
    )
}

/**
 * Offline, with no copy of the page kept, or a kept copy with nothing in it on the phone (kept
 * from before its songs were replaced), an album or a list that is downloaded (an album with
 * anything of it downloaded, a list downloaded whole) is shown as the downloads have it,
 * rather than as a page that can't be had or played. `songs` are the kept page's.
 */
@Composable
private fun fromDownloads(key: String, feed: org.medianexus.music.ui.shell.PageFeed<*>, songs: List<org.medianexus.music.net.Song>?): Boolean {
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    val stale = remember(songs, phone) { org.medianexus.music.library.OnThePhone.keptIsStale(songs, phone) }
    if (!stale && (feed.value != null || feed.page.error == null)) return false
    val marks = org.medianexus.music.ui.downloads.rememberDownloadMarks()
    val has by remember(marks, key) {
        androidx.compose.runtime.derivedStateOf {
            val now = marks.now
            marks.offline && (now.collections.any { it.key == key } || (key.startsWith("album:") && now.downloaded.values.any { "album:${it.song.albumId}" == key }))
        }
    }
    return has
}

/** An artist: their albums, then singles and EPs, newest first, as on Windows. The play
 *  button plays every song on them. */
@Composable
fun ArtistPage(route: Route.Artist) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed(route.key) { it.artist(route.id) }
    // Offline, an artist is who the songs on the phone say they are: their albums with
    // something that plays, each of which opens. (The server's page of them, kept or not,
    // lists albums that can't be opened; and with none kept there was no page at all, though
    // Library had just shown the artist.)
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    val kept = feed.value
    val artist = if (phone == null) kept else remember(phone, route.id, kept) {
        org.medianexus.music.library.OnThePhone.artist(phone, route.id, kept?.name ?: route.name)?.copy(coverArt = kept?.coverArt ?: route.cover) ?: kept?.copy(albums = emptyList())
    }
    val hero = HeroData(
        stringResource(R.string.kind_artist),
        artist?.name ?: route.name,
        meta = artist?.let { albumCount(res, it.albums.size) }.orEmpty(),
        cover = artist?.coverArt ?: route.cover,
        round = true,
    )
    val groups = remember(artist) { artist?.let(::grouped).orEmpty() }
    val scope = rememberCoroutineScope()
    // "Popular" and "Fans also like", when the server can say (it asks Last.fm): nothing in
    // their place when it can't, or offline. Asked once the artist's name is known.
    val name = artist?.name.orEmpty()
    val extras by androidx.compose.runtime.produceState<Pair<List<org.medianexus.music.net.Song>, List<Artist>>?>(null, route.id, name, phone == null) {
        val library = ui.account?.library ?: return@produceState
        if (phone != null || name.isEmpty()) return@produceState
        value = kotlinx.coroutines.coroutineScope {
            val top = async { runCatching { library.topSongs(name, 5) }.getOrDefault(emptyList()) }
            val info = async { runCatching { library.artistInfo(route.id) }.getOrNull() }
            top.await() to info.await()?.similar.orEmpty().filter { it.id.isNotEmpty() && !it.id.startsWith('-') }
        }
    }
    ArtistLayout(
        key = route.key,
        hero = hero,
        artist = artist,
        feed = feed,
        groups = remember(groups, res, extras) {
            groups.map { (titleRes, albums) -> res.getString(titleRes) to albums.map { it.card(albumByYear(res, it)) } } +
                listOfNotNull(extras?.second?.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.fans_also_like) to it.map { a -> CardData(org.medianexus.music.ui.parts.CardKind.ARTIST, a.id, a.name, res.getString(R.string.kind_artist), a.coverArt) } })
        },
        popular = extras?.first.orEmpty(),
        onPlay = { shuffle ->
            val library = ui.account?.library ?: return@ArtistLayout
            val name = artist?.name.orEmpty()
            if (phone != null) {
                // Offline: their songs on the phone, by album as the page shows them.
                val songs = org.medianexus.music.library.OnThePhone.songsBy(phone, route.id).sortedWith(compareByDescending<org.medianexus.music.net.Song> { it.year }.thenBy { it.album }.thenBy { it.discNumber }.thenBy { it.track })
                if (songs.isEmpty()) ui.toast(res.getString(R.string.cant_play_offline)) else ui.play(songs, 0, name, route.key, shuffle = shuffle, anyStart = true)
                return@ArtistLayout
            }
            // What the page drew, in the order drawn (albums, then singles and EPs, each newest
            // first), the first forty (Windows' `PlaySource::Albums`); and only if nothing has
            // been chosen since the tap (see Choices).
            val albums = artist?.let(::playedByHeader).orEmpty()
            ui.toast(res.getString(R.string.getting_songs))
            var missing = 0
            ui.player.afterLookup(scope, {
                albums.flatMap { album ->
                    try {
                        library.quick(library.album(album.id)).songs
                    } catch (_: ApiError) {
                        missing++
                        emptyList()
                    }
                }
            }, { ui.toast(res.getString(R.string.moved_while_looking_up)) }) { songs ->
                when {
                    songs.isEmpty() -> ui.toast(res.getString(R.string.page_failed))
                    else -> {
                        ui.play(songs, 0, name, route.key, shuffle = shuffle, anyStart = true)
                        // Not quietly fewer: said so.
                        if (missing > 0) ui.toast(res.getQuantityString(R.plurals.some_albums_missing, missing, missing))
                    }
                }
            }
        },
        res = res,
    )
}

/** What an artist's header plays: the albums as the page draws them (albums, then singles
 *  and EPs, each newest first), the first forty. */
internal fun playedByHeader(artist: Artist): List<Album> = grouped(artist).flatMap { it.second }.take(40)

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
    groups: List<Pair<String, List<CardData>>>,
    onPlay: (shuffle: Boolean?) -> Unit,
    res: android.content.res.Resources,
    popular: List<org.medianexus.music.net.Song> = emptyList(),
) {
    val ui = LocalUi.current
    SongListPage(
        key = key,
        hero = hero,
        // The header's play button plays the albums; the songs listed are their most popular,
        // when the server can say (Windows' "Popular").
        songs = if (artist == null) null else popular,
        songsTitle = stringResource(R.string.popular),
        context = hero.title,
        feed = feed,
        empty = if (artist != null && artist.albums.isEmpty()) stringResource(R.string.artist_empty) else "",
        onMore = if (artist == null) null else ({ _ -> ui.openMenu(org.medianexus.music.ui.menu.Collection.Artist(artist.id.ifEmpty { key.removePrefix("artist:") }, artist.name)) }),
        playOverride = if (artist?.albums.isNullOrEmpty()) null else ({ onPlay(null) }),
        below = { columns ->
            for ((title, cards) in groups) gridSection(title, cards, { ui.open(it) }, columns)
        },
    )
}

/**
 * A heading, then cards two or three to a line, inside a page's list: each line is an item
 * of its own, so only the lines on screen are drawn and their covers asked for (a grid as one
 * item started every cover in it at once).
 */
fun LazyListScope.gridSection(title: String, cards: List<CardData>, onOpen: (CardData) -> Unit, columns: Int) {
    item(key = "title/$title", contentType = "title") { SectionTitle(title) }
    val lines = (cards.size + columns - 1) / columns
    items(lines, key = { "grid/$title/$it" }, contentType = { "cards" }) { line ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (at in line * columns until minOf(cards.size, line * columns + columns)) {
                val card = cards[at]
                MediaCard(card, onClick = { onOpen(card) }, modifier = Modifier.weight(1f))
            }
            repeat(columns - (minOf(cards.size, line * columns + columns) - line * columns)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** How many cards to a line in a page's grid section, for a page this wide. */
fun gridColumns(width: androidx.compose.ui.unit.Dp): Int {
    val inside = width - 32.dp
    return if (inside >= 520.dp) 4 else if (inside >= 380.dp) 3 else 2
}

/**
 * A genre: its albums, in a grid (the Windows app's `GridPage`). The first sixty show as soon
 * as they arrive (kept like any page); the rest are fetched straight after, a page at a time,
 * as Windows does, rather than all before anything is shown.
 */
@Composable
fun GenrePage(route: Route.Genre) {
    val ui = LocalUi.current
    val list = org.medianexus.music.net.AlbumList.ByGenre(route.name)
    val feed = rememberPageFeed("genre-first:${route.name}") { it.albums(list, 0, org.medianexus.music.library.Library.GRID_PAGE) }
    val library = ui.account?.library
    val scope = rememberCoroutineScope()
    val pages = remember(route.name, library) {
        org.medianexus.music.ui.library.AlbumPages(org.medianexus.music.library.Library.GRID_PAGE) { offset, changed ->
            // A page from the phone's copy is put right when the server's comes (on the
            // screen's own thread).
            library?.quick(library.albums(list, offset, org.medianexus.music.library.Library.GRID_PAGE)) { fresh -> scope.launch { changed(fresh) } } ?: emptyList()
        }
    }
    LaunchedEffect(pages, feed.page) { feed.value?.let { pages.first(it, feed.page.fresh) } }
    // The rest, one page after another, while the first is already on screen; only while the
    // app is in front, and from where it had got to when it comes back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pages, feed.value != null, pages.generation, lifecycle) {
        if (feed.value == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { pages.loadAll() }
    }
    // Offline: its albums on the phone, from the songs of it that play.
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    if (phone != null) {
        val cards = remember(phone, route.name) { org.medianexus.music.library.OnThePhone.albums(phone.filter { it.genre.equals(route.name, ignoreCase = true) }).map { it.card() } }
        Column(Modifier.fillMaxSize()) {
            TitleBar(route.name, onBack = ui.navigator::back)
            if (cards.isEmpty()) PageStatus(PageState.Empty(stringResource(R.string.not_on_phone), stringResource(R.string.not_on_phone_detail), R.drawable.ic_wifi_off))
            else CardGrid(cards, card = { it }, key = { it.id }, onOpen = { ui.open(it) })
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(route.name, onBack = ui.navigator::back)
        Refreshable(feed.refreshing, {
            pages.refresh()
            feed.refresh()
        }) {
            val error = feed.page.error
            val albums = pages.albums
            when {
                albums.isEmpty() && feed.value == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
                albums.isEmpty() && feed.value == null -> SkeletonRows()
                albums.isEmpty() && feed.value?.isEmpty() == true -> PageStatus(PageState.Empty(stringResource(R.string.genre_empty)))
                // Come, but not in the grid yet (see AlbumsPart).
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
                    footer = when {
                        pages.failed != null -> ({ org.medianexus.music.ui.parts.FailedStrip(pages.failed?.message.orEmpty(), { scope.launch { pages.retry() } }) })
                        pages.asking -> ({ SkeletonRows(rows = 1) })
                        else -> null
                    },
                )
            }
        }
    }
}

/**
 * Cards in a grid that fills the page: albums, artists, genres. Each card is made from its
 * item as it is drawn (`card`), so a long list, or one that grows a page at a time, isn't
 * turned into cards all at once, or again each time it grows. `key` tells items apart.
 */
@Composable
fun <T> CardGrid(
    items: List<T>,
    card: (T) -> CardData,
    key: (T) -> String,
    onOpen: (CardData) -> Unit,
    modifier: Modifier = Modifier,
    failed: String? = null,
    onRetry: () -> Unit = {},
    minWidth: androidx.compose.ui.unit.Dp = 150.dp,
    /** Near the foot of the grid: ask for more. */
    onNearEnd: (suspend () -> Unit)? = null,
    /** What changes each time more has come. */
    progress: Any? = null,
    header: (@Composable () -> Unit)? = null,
    /** At the foot of the grid: a page on its way, or one that failed. */
    footer: (@Composable () -> Unit)? = null,
    state: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    /** For a long grid, what its fast-scroll bubble says of an item (its letter, its year):
     *  given, the grid has the handle (see [org.medianexus.music.ui.parts.FastScroll]). */
    scrollLabel: ((T) -> String?)? = null,
) {
    if (onNearEnd != null) {
        val near by remember(state) { androidx.compose.runtime.derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 12 } }
        // Asked again whenever a page has come (even one that brought nothing new, which
        // leaves the grid the same size), and only while the app is in front.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(near, items.size, progress) {
            if (near) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { onNearEnd() }
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(minWidth),
            Modifier.fillMaxSize(),
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
            items(items, key = { "card/${key(it)}" }, contentType = { "card" }) { item ->
                val data = remember(item) { card(item) }
                MediaCard(data, onClick = { onOpen(data) })
            }
            if (footer != null) {
                item(key = "footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, contentType = "footer") { footer() }
            }
            item(key = "end", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }
        if (scrollLabel != null) {
            FastScroll(state, first = (if (header != null) 1 else 0) + (if (failed != null) 1 else 0), count = items.size) { scrollLabel(items[it]) }
        }
    }
}
