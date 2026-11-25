package org.medianexus.music.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.medianexus.music.R
import org.medianexus.music.core.AlbumPage
import org.medianexus.music.core.Release
import org.medianexus.music.core.TrackName
import org.medianexus.music.ui.lists.gridColumns
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.Hero
import org.medianexus.music.ui.parts.HeroData
import org.medianexus.music.ui.parts.ListHeading
import org.medianexus.music.ui.parts.MediaCard
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.theme.Theme

/**
 * Runs a page's model while the page is on screen and the app is in front (leaving it
 * cancels what it asked), and has it ask again when something has been asked of Lidarr.
 */
@Composable
internal fun Running(model: Any, run: suspend () -> Unit, again: () -> Unit) {
    val ui = LocalUi.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { run() } }
    val asked = ui.requestsAsked
    val before = remember(model) { asked }
    LaunchedEffect(model, asked) { if (asked != before) again() }
}

/**
 * An artist as the wider world knows them (Windows' `outside_artist`): their name, picture
 * and the line under it; "Add artist" while there is anything of theirs to ask for; the
 * songs they are most played for, once those are known (asked after the page is shown;
 * until then a quiet stand-in of the same size); then their albums, and their singles and
 * EPs, as the library's own artist page shows albums: one the library has opens as the
 * library's own, one that has arrived says so.
 */
@Composable
fun OutsideArtistPage(route: Route.OutsideArtist) {
    val ui = LocalUi.current
    val here = requestsHere() ?: return
    val pages = here.pages
    val model = remember(here.key, route.id) { OutsideArtistModel({ pages.artist(route.id) }, { name -> pages.popular(route.id, name) }) }
    Running(model, model::run, model::again)
    val state by model.state.collectAsStateWithLifecycle()
    val asker = rememberAsker(here)
    val artist = state.artist
    val res = LocalResources.current
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val name = artist?.name?.ifEmpty { null } ?: route.name
    val albumsTitle = stringResource(R.string.albums)
    val smallerTitle = stringResource(R.string.singles_and_eps)
    // (A song carries no picture: its album's, where the page has that album.)
    val covers = remember(artist) { artist?.let { (it.albums + it.smaller).associate { release -> release.offer.album to release.offer.cover } }.orEmpty() }
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (scrolled) name else "", onBack = ui.navigator::back)
        Refreshable(state.asking && artist != null, model::again) {
            val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
            val columns = gridColumns(width)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero", contentType = "hero") {
                    Hero(HeroData(stringResource(R.string.kind_artist), name, meta = artist?.about.orEmpty(), cover = artist?.cover?.ifEmpty { null } ?: route.cover, round = true, web = true))
                }
                when {
                    artist == null && state.why != null -> item(key = "why", contentType = "said") { Said(state.why.orEmpty(), onRetry = model::again) }
                    artist == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    artist.failed -> item(key = "failed", contentType = "said") { Said(artist.empty, onRetry = model::again) }
                    else -> {
                        if (artist.open > 0) item(key = "add", contentType = "actions") {
                            Row(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = Theme.dimens.pagePad), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(stringResource(R.string.add_artist), { asker.artist(route.id, artist.name, artist.open) }, primary = true)
                            }
                        }
                        // A failure to ask again, with the page on show: said above it.
                        state.why?.let { why -> item(key = "again-failed", contentType = "failed") { org.medianexus.music.ui.parts.FailedStrip(why, model::again) } }
                        popular(state.popular, covers, asker)
                        releases("albums", albumsTitle, artist.albums, columns, asker, res)
                        releases("smaller", smallerTitle, artist.smaller, columns, asker, res)
                        if (artist.albums.isEmpty() && artist.smaller.isEmpty() && artist.empty.isNotEmpty()) {
                            item(key = "empty", contentType = "said") { Said(artist.empty) }
                        }
                    }
                }
            }
        }
    }
}

/** "Popular": the songs the artist is most played for that the library doesn't have, each
 *  as a search shows one. Room is kept for them while they are asked for. */
private fun LazyListScope.popular(popular: org.medianexus.music.core.Popular?, covers: Map<String, String>, asker: Asker) {
    if (popular == null) {
        item(key = "popular-waiting", contentType = "popular-waiting") {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = Theme.dimens.touch))
                SkeletonRows(rows = POPULAR_ROWS)
            }
        }
        return
    }
    if (popular.songs.isEmpty()) return
    item(key = "popular-title", contentType = "title") { SectionTitle(popular.heading) }
    itemsIndexed(popular.songs, key = { index, (song, _) -> "popular/$index/${song.album}/${song.title}" }, contentType = { _, _ -> "offer" }) { _, (song, requested) ->
        val ui = LocalUi.current
        val cover = covers[song.album].orEmpty()
        OfferRow(song, requested, cover, onOpen = { ui.openAlbumOf(song, cover) }, onAsk = { asker.album(song.album, song.albumTitle, song.artist, song.title, song.kind) })
    }
}

/** How many songs "Popular" is given room for, while it is asked for: as many as it shows. */
private const val POPULAR_ROWS = 5

/** An artist's releases under a heading, as the library's artist page shows its albums:
 *  cards a few to a line, each line an item of its own. */
private fun LazyListScope.releases(name: String, title: String, releases: List<Release>, columns: Int, asker: Asker, res: android.content.res.Resources) {
    if (releases.isEmpty()) return
    item(key = "title/$name", contentType = "title") { SectionTitle(title) }
    val lines = (releases.size + columns - 1) / columns
    items(lines, key = { "grid/$name/$it" }, contentType = { "cards" }) { line ->
        val ui = LocalUi.current
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val end = minOf(releases.size, line * columns + columns)
            for (at in line * columns until end) {
                val release = releases[at]
                val mine = release.library
                val card = remember(release) {
                    if (mine != null) CardData(CardKind.ALBUM, mine.id, mine.title, release.caption, mine.cover)
                    else CardData(CardKind.ALBUM, release.offer.album, release.offer.title, release.caption, release.offer.cover, web = true)
                }
                val askable = mine == null && !release.arrived && !release.offer.requested
                MediaCard(
                    card,
                    onClick = {
                        if (mine != null) ui.open(Route.Album(mine.id, mine.title, mine.artist, mine.cover))
                        else ui.open(Route.OutsideAlbum(release.offer.album, release.offer.title, release.offer.artist, release.offer.cover))
                    },
                    modifier = Modifier.weight(1f),
                    onPlay = if (askable) ({ asker.album(release.offer.album, release.offer.title, release.offer.artist, kind = release.offer.kind) }) else null,
                    adds = true,
                    playLabel = res.getString(R.string.request_named, card.title),
                )
            }
            repeat(columns - (end - line * columns)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * An album that isn't in the library (Windows' `outside_album`): its cover, title, who it's
 * by (which opens their page) and the line under it; its button ("Request album", or
 * "Requested", as the library has it); and its songs as the library's album page lists them,
 * but only to read: nothing on it plays, so nothing offers to.
 */
@Composable
fun OutsideAlbumPage(route: Route.OutsideAlbum) {
    val ui = LocalUi.current
    val here = requestsHere() ?: return
    val pages = here.pages
    val model = remember(here.key, route.id) { PageLoad { pages.album(route.id) } }
    Running(model, model::run, model::again)
    val state by model.state.collectAsStateWithLifecycle()
    val asker = rememberAsker(here)
    val page = state.value
    val found = (page as? AlbumPage.Found)?.album
    val offer = found?.offer
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val title = offer?.title?.ifEmpty { null } ?: route.title
    val kind = stringResource(
        when (offer?.kind) {
            "EP" -> R.string.kind_ep
            "Single" -> R.string.kind_single
            else -> R.string.kind_album
        },
    )
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (scrolled) title else "", onBack = ui.navigator::back)
        Refreshable(state.asking && page != null, model::again) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero", contentType = "hero") {
                    val artistId = offer?.artistId.orEmpty()
                    Hero(
                        HeroData(kind, title, offer?.artist?.ifEmpty { null } ?: route.artist, found?.about.orEmpty(), found?.cover?.ifEmpty { null } ?: route.cover, web = true),
                        onSubtitle = if (artistId.isEmpty()) null else ({ ui.open(Route.OutsideArtist(artistId, offer?.artist.orEmpty())) }),
                    )
                }
                when {
                    page == null && state.why != null -> item(key = "why", contentType = "said") { Said(state.why.orEmpty(), onRetry = model::again) }
                    page == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    page is AlbumPage.Unavailable -> item(key = "unavailable", contentType = "said") { Said(page.why) }
                    found != null && offer != null -> {
                        item(key = "action", contentType = "actions") {
                            Row(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = Theme.dimens.pagePad), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(found.action, { asker.album(offer.album, offer.title, offer.artist, kind = offer.kind) }, primary = !offer.requested)
                            }
                        }
                        state.why?.let { why -> item(key = "again-failed", contentType = "failed") { org.medianexus.music.ui.parts.FailedStrip(why, model::again) } }
                        if (found.tracksFailed.isNotEmpty()) item(key = "tracks-failed", contentType = "said") { Said(found.tracksFailed) }
                        val discs = found.tracks.map { it.disc }.distinct().size > 1
                        itemsIndexed(found.tracks, key = { index, track -> "track/$index/${track.disc}/${track.number}" }, contentType = { _, _ -> "track" }) { index, track ->
                            Column {
                                if (discs && (index == 0 || found.tracks[index - 1].disc != track.disc)) ListHeading("${stringResource(R.string.disc)} ${track.disc.coerceAtLeast(1)}")
                                TrackName(track)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One song of an album that isn't in the library, as the library's album page lists its
 *  songs (its number, its title, its length), but only to read. */
@Composable
private fun TrackName(track: TrackName) {
    val colors = Theme.colors
    Row(
        Modifier.fillMaxWidth().height(Theme.dimens.rowHeight).semantics(mergeDescendants = true) {}.padding(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(if (track.number > 0) track.number.toString() else "", Modifier.width(24.dp), style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.End), maxLines = 1)
        BasicText(track.title, Modifier.weight(1f), style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (track.seconds > 0) BasicText(clock(track.seconds), style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1)
    }
}

/**
 * A sentence of the library's in place of what a page couldn't show (why it can't be had,
 * that there is nothing), as Windows shows a page's `empty`: quiet, in the middle; with
 * "Try again" where asking again could change it.
 */
@Composable
internal fun Said(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, Modifier.fillMaxWidth(), style = Theme.type.body.copy(color = Theme.colors.muted, textAlign = TextAlign.Center))
        if (onRetry != null) TextButton(stringResource(R.string.try_again), onRetry)
    }
}
