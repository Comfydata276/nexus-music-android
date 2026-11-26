package org.medianexus.music.ui.requests

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.medianexus.music.R
import org.medianexus.music.core.Pending
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.player.SwipeAway
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.theme.Theme

/**
 * Requests (Windows' page of the same name): what this phone asked for that has arrived
 * lately, as the library's own albums; then "On their way (n)", each album Lidarr has still
 * to get with the library's line under it. One is taken off the list from its "…" or by
 * swiping it away, as the queue's songs are; the library's sentence says how that went.
 * Pulled down it asks again, and while it is on screen, every half minute, as Windows does.
 * Opening it is also when arrivals are looked for.
 */
@Composable
fun RequestsPage() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val here = requestsHere() ?: return
    val pages = here.pages
    val model = remember(here.key) { PageLoad { pages.onTheirWay() } }
    Running(model, model::run, model::again)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(REFRESH_MILLIS)
                model.again()
            }
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val waiting = state.value
    val asker = rememberAsker(here)
    // Being taken off the list (from this page or before it was left): not shown meanwhile.
    // Once Lidarr has taken it off, not shown either, until the page has asked again.
    // (Lidarr's numbers for them: the page's own rows, which belong to this connection.)
    val going by UnderWay.of(here.account).going.collectAsStateWithLifecycle()
    val taken = remember(here.key) { mutableStateListOf<Long>() }
    LaunchedEffect(waiting) { taken.clear() }

    fun takeOff(album: Pending) = asker.takeOff(album.id, album.title, under = state.connection) { done -> if (done) taken += album.id }

    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.requests), onBack = ui.navigator::back)
        Refreshable(state.asking && waiting != null, model::again) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    waiting == null && state.why != null -> item(key = "why", contentType = "said") { Said(state.why.orEmpty(), onRetry = model::again) }
                    waiting == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    else -> {
                        state.why?.let { why -> item(key = "again-failed", contentType = "failed") { org.medianexus.music.ui.parts.FailedStrip(why, model::again) } }
                        if (waiting.arrived.isNotEmpty()) {
                            item(key = "arrived-title", contentType = "title") { SectionTitle(stringResource(R.string.arrived)) }
                            items(waiting.arrived, key = { (asked, _) -> "arrived/${asked.libraryAlbum}/${asked.title}" }, contentType = { "arrived" }) { (asked, about) ->
                                ArrivedRow(asked, about, onOpen = { if (asked.libraryAlbum.isNotEmpty()) ui.open(Route.Album(asked.libraryAlbum, asked.title, asked.artist, asked.cover)) })
                            }
                        }
                        val pending = waiting.pending.filter { (album, _) -> UnderWay.offList(album.id, state.connection) !in going && album.id !in taken }
                        if (waiting.heading.isNotEmpty() && pending.isNotEmpty()) {
                            item(key = "pending-title", contentType = "title") { SectionTitle(waiting.heading) }
                            items(pending, key = { (album, _) -> "pending/${album.id}" }, contentType = { "pending" }) { (album, line) ->
                                SwipeAway("pending/${album.id}", onGone = { takeOff(album) }, Modifier.animateItem(fadeOutSpec = null)) {
                                    PendingRow(album, line, onMenu = {
                                        ui.sheet = Sheet.Drawn { choose ->
                                            MenuEntry(stringResource(R.string.take_off_the_list)) { choose { takeOff(album) } }
                                        }
                                    }, onTakeOff = { takeOff(album) })
                                }
                            }
                        }
                        // Nothing on its way, or why Lidarr couldn't say (which is said under what has
                        // arrived, too: that much is known on the phone).
                        if (waiting.empty.isNotEmpty() && pending.isEmpty() && (waiting.arrived.isEmpty() || waiting.failed)) {
                            item(key = "empty", contentType = "said") { Said(waiting.empty, onRetry = if (waiting.failed) model::again else null) }
                        }
                    }
                }
            }
        }
    }
}

/** How often the page asks again while it is on screen (Windows: every half minute). */
private const val REFRESH_MILLIS = 30_000L

/**
 * An album that has arrived, as a row like those on their way: the library's own cover (the
 * music server's), its title, and the library's line under it whole, over two lines if it
 * needs them ("The Quiet Harbour • for “Low Water”": never cut before the song's name).
 * Opens the library's own album.
 */
@Composable
private fun ArrivedRow(asked: org.medianexus.music.core.Asked, line: String, onOpen: () -> Unit) {
    val colors = Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Theme.dimens.rowHeight)
            .clickable(enabled = asked.libraryAlbum.isNotEmpty(), role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        org.medianexus.music.ui.parts.Cover(asked.cover, Modifier.size(Theme.dimens.rowCover), org.medianexus.music.ui.parts.CoverShape.ROW, R.drawable.ic_disc_3)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(asked.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) BasicText(line, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An album on its way: its title, the library's line under it, and its "…". */
@Composable
private fun PendingRow(album: Pending, line: String, onMenu: () -> Unit, onTakeOff: () -> Unit) {
    val colors = Theme.colors
    val takeOff = stringResource(R.string.take_off_the_list)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Theme.dimens.rowHeight)
            .background(colors.base)
            .semantics(mergeDescendants = true) { customActions = listOf(CustomAccessibilityAction(takeOff) { onTakeOff(); true }) }
            .padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(album.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) BasicText(line, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, album.title), onMenu, iconSize = 20.dp)
    }
}

/** One entry of a sheet drawn here: an icon and its words, as the menus' entries are. */
@Composable
private fun MenuEntry(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        org.medianexus.music.ui.parts.Icon(R.drawable.ic_x, size = 20.dp, tint = Theme.colors.muted)
        BasicText(text, style = Theme.type.body.copy(color = Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize))
    }
}
