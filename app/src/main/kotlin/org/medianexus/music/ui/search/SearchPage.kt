package org.medianexus.music.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.R
import org.medianexus.music.library.SearchLimits
import org.medianexus.music.net.Found
import org.medianexus.music.ui.lists.CardGrid
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.ChipRow
import org.medianexus.music.ui.parts.FailedStrip
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.albumCount
import org.medianexus.music.ui.parts.card
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.shell.open
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.theme.Theme

/**
 * Search, its own tab on the phone: results as the words are typed, in the Windows
 * sections, narrowed by its chips. With nothing typed, the genres to browse by, as on
 * Windows. (Windows keeps no list of past searches, so neither does this.)
 */
@Composable
fun SearchPage() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val account = ui.account
    val model = remember(account) {
        SearchModel(scope, search = { query ->
            account?.library?.search?.find(query, LIMITS) ?: Found()
        })
    }
    val box = rememberTextFieldState()
    LaunchedEffect(model) { snapshotFlow { box.text.toString() }.collect { model.type(it) } }
    val state by model.state.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    val playlists = rememberPageFeed("playlists") { it.playlists() }.value.orEmpty()
    val keyboard = LocalSoftwareKeyboardController.current
    val kinds = listOf(R.string.search_all, R.string.songs, R.string.artists, R.string.albums, R.string.playlists).map { stringResource(it) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, top = 8.dp, bottom = 4.dp)) {
            Field(
                box,
                stringResource(R.string.search_hint),
                Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.search_hint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                onKeyboardAction = { keyboard?.hide() },
            )
        }
        val found = state.found
        if (state.query.isEmpty() && box.text.isBlank()) {
            Genres()
            return@Column
        }
        ChipRow(kinds, selected = kind, onChoose = { kind = it })
        val view = remember(found, playlists, kind, state.query) {
            found?.let { SearchView.of(state.query, it, playlists, SearchKind.entries[kind]) }
        }
        val liked = likedSongs()
        val error = state.error
        when {
            view == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = model::retry)
            view == null -> SkeletonRows()
            view.isEmpty && !state.searching -> PageStatus(
                PageState.Empty(
                    if (kind == 0) res.getString(R.string.search_nothing, state.query) else res.getString(R.string.search_nothing_of_kind, kinds[kind].lowercase(), state.query),
                ),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (error != null) item(key = "failed") { FailedStrip(error.message.orEmpty(), model::retry) }
                val artists = @Composable {
                    if (view.artists.isNotEmpty()) {
                        Shelf(
                            stringResource(R.string.artists),
                            remember(view.artists) { view.artists.map { CardData(CardKind.ARTIST, it.id, it.name, albumCount(res, it.albumCount), it.coverArt) } },
                            onOpen = { ui.open(it) },
                            cardWidth = 128.dp,
                        )
                    }
                }
                val wide = kind != 0
                if (view.artistsFirst && view.artists.isNotEmpty()) item(key = "artists-first", contentType = "shelf") { artists() }
                if (view.songs.isNotEmpty()) {
                    item(key = "songs-title", contentType = "title") {
                        SectionTitle(stringResource(R.string.songs), onShowAll = if (view.moreSongs) ({ kind = SearchKind.SONGS.ordinal }) else null)
                    }
                    itemsIndexed(view.songs, key = { index, song -> "song/$index/${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                        TrackRow(
                            remember(song) { TrackRowData(song.id, song.title, song.artistLabel + if (song.album.isNotEmpty()) " • ${song.album}" else "", song.coverArt, duration = clock(song.duration)) }
                                .copy(liked = song.id in liked),
                            // What "All" shows is the first few of what was found, so the place is the same.
                            onClick = { ui.play(found?.songs.orEmpty(), index, "“${state.query}”") },
                            onMore = { ui.menu = SongMenu(song) },
                        )
                    }
                }
                if (wide && view.artists.isNotEmpty()) {
                    item(key = "artists-grid") {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            GridIn(view.artists.map { CardData(CardKind.ARTIST, it.id, it.name, albumCount(res, it.albumCount), it.coverArt) })
                        }
                    }
                } else if (!view.artistsFirst && view.artists.isNotEmpty()) {
                    item(key = "artists", contentType = "shelf") { artists() }
                }
                if (view.albums.isNotEmpty()) {
                    item(key = "albums", contentType = "shelf") {
                        if (wide) GridIn(view.albums.map { it.card() }) else Shelf(stringResource(R.string.albums), remember(view.albums) { view.albums.map { it.card() } }, onOpen = { ui.open(it) })
                    }
                }
                if (view.playlists.isNotEmpty()) {
                    item(key = "playlists", contentType = "shelf") {
                        val cards = remember(view.playlists) { view.playlists.map { CardData(CardKind.PLAYLIST, it.id, it.name, songCount(res, it.songCount), it.coverArt) } }
                        if (wide) GridIn(cards) else Shelf(stringResource(R.string.playlists), cards, onOpen = { ui.open(it) })
                    }
                }
            }
        }
    }
}

/** Cards of one kind in full, a few to a line, inside the results. */
@Composable
private fun GridIn(cards: List<CardData>) {
    val ui = LocalUi.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val columns = if (maxWidth >= 520.dp) 4 else if (maxWidth >= 380.dp) 3 else 2
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            for (line in cards.chunked(columns)) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    for (card in line) org.medianexus.music.ui.parts.MediaCard(card, onClick = { ui.open(card) }, modifier = Modifier.weight(1f))
                    repeat(columns - line.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** With nothing typed: every genre, the ones with most albums first ("Browse by genre"). */
@Composable
private fun Genres() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val feed = rememberPageFeed("genres") { it.genres() }
    val genres = feed.value
    val error = feed.page.error
    when {
        genres == null && error != null -> PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
        genres == null -> SkeletonRows()
        genres.isEmpty() -> PageStatus(PageState.Empty(stringResource(R.string.search_intro), icon = R.drawable.ic_search))
        else -> CardGrid(
            remember(genres) {
                genres.sortedWith(compareByDescending<org.medianexus.music.net.Genre> { it.albumCount }.thenBy { it.name })
                    .map { CardData(CardKind.GENRE, it.name, it.name, "${albumCount(res, it.albumCount)}, ${songCount(res, it.songCount)}") }
            },
            onOpen = { ui.open(it) },
            minWidth = 160.dp,
            header = {
                androidx.compose.foundation.text.BasicText(
                    stringResource(R.string.browse_by_genre),
                    Modifier.padding(vertical = 12.dp),
                    style = Theme.type.section.copy(color = Theme.colors.text),
                )
            },
        )
    }
}

/** How much a search asks for, as Windows does (24 artists, 36 albums, 60 songs). */
private val LIMITS = SearchLimits(artists = 24, albums = 36, songs = 60)
