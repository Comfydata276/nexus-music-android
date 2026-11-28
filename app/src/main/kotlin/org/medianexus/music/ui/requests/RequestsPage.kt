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
import androidx.compose.foundation.lazy.itemsIndexed
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
    LaunchedEffect(ui.graph) { ui.graph.requestsRouter.refresh() }
    val here = requestsHere()
    if (here == null) {
        val route by ui.graph.requestsRouter.route.collectAsStateWithLifecycle()
        val unavailable = route as? org.medianexus.music.requests.RequestsRoute.Unavailable
        Column(Modifier.fillMaxSize()) {
            TitleBar(stringResource(R.string.requests), onBack = ui.navigator::back)
            Said(unavailable?.words ?: stringResource(R.string.nexus_requests_setup), onRetry = ui.graph.requestsRouter::refresh)
        }
        return
    }
    val pages = here.pages
    val model = remember(here.key) { PageLoad { pages.onTheirWay() } }
    Running(here, model, model::run, model::again)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, here.server) {
        if (here.server) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(REFRESH_MILLIS)
                model.again()
            }
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val unreadable by pages.askedUnreadable.collectAsStateWithLifecycle()
    val wayBack by pages.wayBackTrouble.collectAsStateWithLifecycle()
    val waiting = state.value
    val asker = rememberAsker(here)
    // Being taken off the list (from this page or before it was left): not shown meanwhile.
    // Once Lidarr has taken it off, not shown either, until the page has asked again.
    // (Lidarr's numbers for them: the page's own rows, which belong to this connection.)
    val going by UnderWay.of(here.account).going.collectAsStateWithLifecycle()
    val taken = remember(here.key) { mutableStateListOf<Long>() }
    LaunchedEffect(waiting) { taken.clear() }

    // A take-off acts on the row as it was shown: its own entry, and the number of the
    // connection the list was found under then, fixed as the row is drawn, so a menu opened
    // before a reconnect or a new key can't carry the new connection's number when chosen.
    fun takeOff(album: Pending, under: Long) = asker.takeOff(album, under = under) { done ->
        if (done) {
            taken += album.id
            if (here.server) model.again()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.requests), onBack = ui.navigator::back)
        Refreshable(state.asking && waiting != null, model::again) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // What heads the page: why there is no list (or asking again failed), and the
                // warnings, which hold whether or not a list has been read (with no key, an
                // artist's setting may still need putting back on Lidarr).
                for (head in heads(hasList = waiting != null, why = state.why, askedUnreadable = unreadable && !here.server, wayBack = if (here.server) "" else wayBack)) when (head) {
                    is Head.Why -> item(key = "why", contentType = "said") { Said(head.words, onRetry = model::again) }
                    is Head.Failed -> item(key = "again-failed", contentType = "failed") { org.medianexus.music.ui.parts.FailedStrip(head.words, model::again) }
                    // What this phone asked for can't be read: the list is short of it, and says
                    // so, in the library's sentence.
                    Head.AskedUnreadable -> item(key = "asked-unreadable", contentType = "warning") { Warning(remember { org.medianexus.music.core.Words.askedUnreadable }) }
                    // What was written down to put back on Lidarr couldn't be taken up: said here,
                    // in the requests layer's own sentence, for as long as the app runs.
                    is Head.WayBack -> item(key = "way-back-trouble", contentType = "warning") { Warning(head.words) }
                    Head.Loading -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                }
                if (waiting != null) {
                    if (waiting.arrived.isNotEmpty()) {
                        item(key = "arrived-title", contentType = "title") { SectionTitle(stringResource(R.string.arrived)) }
                        val keys = arrivedKeys(waiting.arrived.map { it.first })
                        itemsIndexed(waiting.arrived, key = { at, _ -> keys[at] }, contentType = { _, _ -> "arrived" }) { _, (asked, about) ->
                            ArrivedRow(asked, about, onOpen = { if (asked.libraryAlbum.isNotEmpty()) ui.open(Route.Album(asked.libraryAlbum, asked.title, asked.artist, asked.cover)) })
                        }
                    }
                    val shownUnder = state.connection
                    val pending = waiting.pending.filter { (album, _) -> UnderWay.offList(album.id, shownUnder) !in going && album.id !in taken }
                    if (waiting.heading.isNotEmpty() && pending.isNotEmpty()) {
                        item(key = "pending-title", contentType = "title") { SectionTitle(waiting.heading) }
                        items(pending, key = { (album, _) -> if (album.id == 0L) "asked/${album.album}" else "pending/${album.id}" }, contentType = { "pending" }) { (album, line) ->
                            if (waiting.canRemove(album)) {
                                SwipeAway(swipeKey(album, shownUnder), onGone = { takeOff(album, shownUnder) }, Modifier.animateItem(fadeOutSpec = null)) {
                                    PendingRow(album, line, onMenu = {
                                        ui.sheet = Sheet.Drawn { choose ->
                                            MenuEntry(stringResource(R.string.take_off_the_list)) { choose { takeOff(album, shownUnder) } }
                                        }
                                    }, onTakeOff = { takeOff(album, shownUnder) })
                                }
                            } else {
                                PendingRow(album, line)
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

/** What heads the Requests page, in order, before its lists. */
internal sealed interface Head {
    /** No list, and why: with Try again. */
    data class Why(val words: String) : Head
    /** A list, and asking again failed. */
    data class Failed(val words: String) : Head
    /** What this phone asked for can't be read. */
    data object AskedUnreadable : Head
    /** What was written down to put back on Lidarr couldn't be taken up. */
    data class WayBack(val words: String) : Head
    /** No list yet, and nothing wrong. */
    data object Loading : Head
}

/**
 * What heads the page. The warnings hold whatever became of the list: with no key, or Lidarr
 * out of reach, an artist's setting may still need putting back, and that is said.
 */
internal fun heads(hasList: Boolean, why: String?, askedUnreadable: Boolean, wayBack: String): List<Head> = buildList {
    if (why != null) add(if (hasList) Head.Failed(why) else Head.Why(why))
    if (askedUnreadable) add(Head.AskedUnreadable)
    if (wayBack.isNotEmpty()) add(Head.WayBack(wayBack))
    if (!hasList && why == null) add(Head.Loading)
}

/** A warning at the head of the page, in the warm colour. */
@Composable
private fun Warning(words: String) {
    BasicText(
        words,
        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
        style = Theme.type.rowDetail.copy(color = Theme.colors.warm),
    )
}

/**
 * The Arrived rows' keys, one for each, in their order: the note's own identity (Lidarr's id
 * for the album and when it was asked for), not what it turned out to be. Two notes met by one
 * album (the same artist and title under two ids, or one album asked for again after it came)
 * are two rows, and two keys; a note alike in everything to one above it is numbered. Built
 * by [keyOf], the fields and the count apart, so no two keys are ever the same.
 */
internal fun arrivedKeys(arrived: List<org.medianexus.music.core.Asked>): List<String> {
    val seen = HashMap<String, Int>()
    return arrived.map {
        val own = keyOf("arrived", it.album, it.at, it.title, it.artist)
        val before = seen.getOrDefault(own, 0)
        seen[own] = before + 1
        // The count is a part of its own, after the note's: never words another note could have.
        keyOf(own, before)
    }
}

/**
 * The key a pending row's swipe is bound to: the row itself and the connection its list was
 * found under, as its menu is. After a refresh under a new connection, or Lidarr's number
 * standing for another album, the swipe is a new one, and hands over the new row.
 */
internal fun swipeKey(album: Pending, under: Long): String = keyOf("pending", under, album.id, album.title, album.artist, album.album)

/**
 * Text that stands for these parts and nothing else: each is written with its length in front
 * of it, so no part's words can be read as part of another, and two keys are equal only if
 * every part is.
 */
internal fun keyOf(vararg parts: Any?): String = buildString {
    for (part in parts) {
        val text = part.toString()
        append(text.length).append(':').append(text)
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
private fun PendingRow(album: Pending, line: String, onMenu: (() -> Unit)? = null, onTakeOff: (() -> Unit)? = null) {
    val colors = Theme.colors
    val takeOff = stringResource(R.string.take_off_the_list)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Theme.dimens.rowHeight)
            .background(colors.base)
            .semantics(mergeDescendants = true) {
                if (onTakeOff != null) customActions = listOf(CustomAccessibilityAction(takeOff) { onTakeOff(); true })
            }
            .padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(album.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) BasicText(line, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMenu != null) IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, album.title), onMenu, iconSize = 20.dp)
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
