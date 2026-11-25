package org.medianexus.music.ui.requests

import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.medianexus.music.R
import org.medianexus.music.core.ArtistOffer
import org.medianexus.music.core.Offer
import org.medianexus.music.core.Outside
import org.medianexus.music.core.SongOffer
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.MediaCard
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.search.SearchKind
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Ui
import org.medianexus.music.ui.theme.Theme

/**
 * The look beyond the library under a search, for the account and connection signed in:
 * null unless requests are connected (and offline mode is off). It looks for the words in
 * the box only while the search is on screen and the app is in front; leaving the search,
 * or typing again, stops what was being looked for. Something asked for from the search
 * (or anywhere) has it look again, so that it says "Requested".
 */
@Composable
fun rememberOutsideSearch(here: RequestsHere?, typed: String): OutsideSearch? {
    val ui = LocalUi.current
    if (here == null || !here.connected) return null
    val key = here.key
    val pages = here.pages
    val model = remember(key) {
        OutsideSearch({ query, soFar -> pages.search(query, soFar) }, kept = ui.outsideKept?.takeIf { it.first == key }?.second)
    }
    LaunchedEffect(model, typed) { model.type(typed) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { model.run() } }
    val asked = ui.requestsAsked
    val askedBefore = remember(model) { asked }
    LaunchedEffect(model, asked) { if (asked != askedBefore) model.again() }
    val state by model.state.collectAsStateWithLifecycle()
    // (A look that finished is kept for when the search comes back with the same words.)
    LaunchedEffect(model, state) { if (state.done && state.why == null) ui.outsideKept = key to state }
    return model
}

/**
 * "Not in your library", under the search's own results (Windows' `OffersBlock` and its
 * shelves): the line the library gives above it, then the songs (each with the album it is
 * on, and the button that asks for that album), the artists and the albums, in the order
 * they come, filling in as more is known. Under "All", the first four songs and a row of
 * each; under a chip, that kind in full. `ruled`: there is something of the library's above
 * it, and a line between.
 */
fun LazyListScope.notInYourLibrary(
    look: OutsideSearch.State,
    kind: SearchKind,
    columns: Int,
    ruled: Boolean,
    asker: Asker?,
    onShowAllSongs: () -> Unit,
) {
    if (kind == SearchKind.PLAYLISTS || look.query.isEmpty()) return
    val found = look.found
    val songs = when (kind) {
        SearchKind.ALL -> found?.songs.orEmpty().take(TOP_SONGS)
        SearchKind.SONGS -> found?.songs.orEmpty()
        else -> emptyList()
    }
    val artists = if (kind == SearchKind.ALL || kind == SearchKind.ARTISTS) found?.artists.orEmpty() else emptyList()
    val albums = if (kind == SearchKind.ALL || kind == SearchKind.ALBUMS) found?.albums.orEmpty() else emptyList()
    // Under a chip, with nothing of that kind found beyond the library, nothing is said of it.
    if (kind != SearchKind.ALL && look.done && songs.isEmpty() && artists.isEmpty() && albums.isEmpty()) return
    item(key = "outside-title", contentType = "outside-title") { OutsideHeading(found?.note ?: look.why.orEmpty(), ruled) }
    if (found == null) {
        // Nothing known yet: a quiet stand-in, no words (the library gives none for this
        // moment). A look that couldn't be had says why in the heading's line.
        if (look.looking) item(key = "outside-waiting", contentType = "outside-waiting") { SkeletonRows(rows = 2) }
        return
    }
    if (songs.isNotEmpty()) {
        item(key = "outside-songs", contentType = "title") {
            SectionTitle(stringResource(R.string.songs), onShowAll = if (kind == SearchKind.ALL && found.songs.size > TOP_SONGS) onShowAllSongs else null)
        }
        itemsIndexed(songs, key = { index, (song, _) -> "outside-song/$index/${song.album}/${song.title}" }, contentType = { _, _ -> "offer" }) { _, (song, requested) ->
            val ui = LocalUi.current
            OfferRow(song, requested, coverOf(song, found), onOpen = { ui.openAlbumOf(song, coverOf(song, found)) }, onAsk = { asker?.album(song.album, song.albumTitle, song.artist, song.title, song.kind) })
        }
    }
    if (artists.isNotEmpty()) {
        if (kind == SearchKind.ALL) {
            item(key = "outside-artists", contentType = "shelf") {
                val ui = LocalUi.current
                val res = androidx.compose.ui.platform.LocalResources.current
                Shelf(stringResource(R.string.artists), remember(artists, res) { artists.map { it.card(res) } }, onOpen = { ui.openOutsideArtist(it) }, cardWidth = 128.dp)
            }
        } else {
            outsideGrid("outside-artists", artists, columns, { res -> card(res) }, onAsk = null)
        }
    }
    if (albums.isNotEmpty()) {
        if (kind == SearchKind.ALL) {
            item(key = "outside-albums", contentType = "shelf") {
                val ui = LocalUi.current
                val res = androidx.compose.ui.platform.LocalResources.current
                val cards = remember(albums, res) { albums.map { it.card(res) } }
                Shelf(
                    stringResource(R.string.albums), cards,
                    onOpen = { card -> ui.openOutsideAlbum(card, albums) },
                    onPlay = { card -> albums.firstOrNull { it.album == card.id }?.let { offer -> asker?.album(offer.album, offer.title, offer.artist, kind = offer.kind) } },
                    adds = true,
                    playLabel = { card -> res.getString(R.string.request_named, card.title) },
                    playable = { card -> albums.firstOrNull { it.album == card.id }?.requested == false },
                )
            }
        } else {
            outsideGrid("outside-albums", albums, columns, { res -> card(res) }, onAsk = asker?.let { ask -> { offer: Offer -> ask.album(offer.album, offer.title, offer.artist, kind = offer.kind) } })
        }
    }
}

/** A kind in full under its chip: a few cards to a line, each line an item of its own. */
private fun <T> LazyListScope.outsideGrid(name: String, all: List<T>, columns: Int, card: T.(Resources) -> CardData, onAsk: ((Offer) -> Unit)?) {
    item(key = "$name-title", contentType = "title") { SectionTitle(stringResource(if (name == "outside-artists") R.string.artists else R.string.albums)) }
    val lines = (all.size + columns - 1) / columns
    items(lines, key = { "$name/$it" }, contentType = { "cards" }) { line ->
        val ui = LocalUi.current
        val res = androidx.compose.ui.platform.LocalResources.current
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val end = minOf(all.size, line * columns + columns)
            for (at in line * columns until end) {
                val item = all[at]
                val data = remember(item, res) { item.card(res) }
                val offer = item as? Offer
                MediaCard(
                    data,
                    onClick = { if (offer != null) ui.openOutsideAlbum(data, listOf(offer)) else ui.openOutsideArtist(data) },
                    modifier = Modifier.weight(1f),
                    onPlay = if (offer != null && onAsk != null && !offer.requested) ({ onAsk(offer) }) else null,
                    adds = true,
                    playLabel = res.getString(R.string.request_named, data.title),
                )
            }
            repeat(columns - (end - line * columns)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** How many songs "All" shows before "Show all", as Windows' `TOP_SONGS`. */
private const val TOP_SONGS = 4

/** "Not in your library", and the library's line under it. */
@Composable
private fun OutsideHeading(note: String, ruled: Boolean) {
    val colors = Theme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad).padding(top = if (ruled) 20.dp else 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ruled) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Spacer(Modifier.height(12.dp))
        }
        BasicText(stringResource(R.string.not_in_your_library), Modifier.semantics { heading() }, style = Theme.type.title.copy(color = colors.text))
        if (note.isNotEmpty()) BasicText(note, style = Theme.type.rowDetail.copy(color = colors.muted))
    }
}

/**
 * A song that isn't in the library (Windows' `OfferRow`): the cover of the album it is on,
 * its title, who it's by and what it's on; tapped, that album's page; its button asks for
 * the album (a tick once it has been asked for, which says so if pressed again).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OfferRow(song: SongOffer, requested: Boolean, cover: String, onOpen: () -> Unit, onAsk: () -> Unit) {
    val colors = Theme.colors
    val on = when (song.kind) {
        "Single" -> stringResource(R.string.kind_single)
        "EP" -> stringResource(R.string.offer_on_ep, song.albumTitle)
        else -> song.albumTitle
    }
    val about = listOf(song.artist, on, song.year).filter { it.isNotEmpty() }.joinToString(" • ")
    Row(
        Modifier
            .fillMaxWidth()
            .height(Theme.dimens.rowHeight)
            .combinedClickable(enabled = song.album.isNotEmpty(), onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(cover, Modifier.size(Theme.dimens.rowCover), CoverShape.ROW, R.drawable.ic_disc_3, web = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(song.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(about, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // (A row that only names what couldn't be asked for has nothing to ask.)
        if (song.album.isNotEmpty()) IconButton(
            if (requested) R.drawable.ic_check else R.drawable.ic_plus,
            stringResource(if (requested) R.string.asked_for_named else R.string.request_named, song.albumTitle),
            onAsk,
            tint = if (requested) colors.accentBright else colors.muted,
            iconSize = 20.dp,
        )
    }
}

/** The picture for a song's album, where the look has one: the albums of the first songs
 *  found come with their covers. (A song itself carries none.) */
internal fun coverOf(song: SongOffer, found: Outside?): String = found?.albums?.firstOrNull { it.album == song.album }?.cover.orEmpty()

internal fun ArtistOffer.card(res: Resources) = CardData(CardKind.ARTIST, id, name, about.ifEmpty { res.getString(R.string.kind_artist) }, cover, web = true)

/** An album that isn't in the library, as a card: who it's by, and the year or that it has
 *  been asked for (Windows' `request_card`). */
internal fun Offer.card(res: Resources) = CardData(
    CardKind.ALBUM, album, title,
    when {
        requested -> res.getString(R.string.by_requested, artist)
        year.isEmpty() -> artist
        else -> "$artist • $year"
    },
    cover, web = true,
)

internal fun Ui.openOutsideArtist(card: CardData) = open(Route.OutsideArtist(card.id, card.title, card.cover))

internal fun Ui.openOutsideAlbum(card: CardData, offers: List<Offer>) =
    open(Route.OutsideAlbum(card.id, card.title, offers.firstOrNull { it.album == card.id }?.artist.orEmpty(), card.cover))

internal fun Ui.openAlbumOf(song: SongOffer, cover: String) {
    if (song.album.isEmpty()) return
    open(Route.OutsideAlbum(song.album, song.albumTitle, song.artistName.ifEmpty { song.artist }, cover))
}
