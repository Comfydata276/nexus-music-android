package org.medianexus.music.ui.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.jam.Found
import org.medianexus.music.jam.JamUi
import org.medianexus.music.jam.JoinedUi
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.theme.Theme

/**
 * The jam page (Windows' jam window and its page): joining a jam from its link, starting one,
 * and while the phone is in one, who's there and a search of the host's library to add from.
 * `link` is one that arrived from another app (shared to this one, or opened), put in the box.
 */
@Composable
fun JamPage(link: String = "", onBack: (() -> Unit)? = null) {
    val ui = LocalUi.current
    val jams = ui.graph.jams
    val state by jams.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { jams.opened() }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(if (state.hosting != null) R.string.jam_your_jam else R.string.jam), onBack = onBack)
        val joined = state.joined
        val hosting = state.hosting
        when {
            joined != null -> Joined(joined, onLeave = jams::leave, onSearch = jams::search, onAdd = jams::addFound)
            hosting != null -> HostingPart(state)
            else -> NotIn(state, link)
        }
    }
}

@Composable
private fun NotIn(state: JamUi, given: String) {
    val ui = LocalUi.current
    val jams = ui.graph.jams
    val colors = Theme.colors
    val name = rememberTextFieldState(state.name)
    val link = rememberTextFieldState(given)
    LaunchedEffect(given) { if (given.isNotEmpty()) link.setTextAndPlaceCursorAtEnd(given) }
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun join() {
        val typed = link.text.toString()
        if (typed.isNotBlank() && !state.busy) jams.join(typed, name.text.toString())
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Theme.dimens.pagePad)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(stringResource(R.string.jam_intro), style = Theme.type.body.copy(color = colors.muted))
                Labelled(stringResource(R.string.jam_your_name)) {
                    Field(name, stringResource(R.string.jam_your_name), placeholder = stringResource(R.string.jam_your_name_hint), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                }
                Labelled(stringResource(R.string.jam_join_a_jam)) {
                    Field(
                        link, stringResource(R.string.jam_link_label),
                        placeholder = stringResource(R.string.jam_link_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        onKeyboardAction = { join() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(stringResource(R.string.jam_paste), {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrBlank()) link.setTextAndPlaceCursorAtEnd(text.trim().take(2048))
                            }
                        })
                        TextButton(stringResource(if (state.busy) R.string.jam_joining else R.string.jam_join), ::join, primary = true, enabled = link.text.isNotBlank() && !state.busy)
                    }
                }
                state.problem?.let { problem ->
                    BasicText(problem, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.body.copy(color = colors.warm))
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(colors.line))
                if (state.canHost) {
                    Labelled(stringResource(R.string.jam_host_a_jam)) {
                        BasicText(stringResource(R.string.jam_host_intro), style = Theme.type.body.copy(color = colors.muted))
                        TextButton(
                            stringResource(if (state.busy) R.string.jam_starting else R.string.jam_start),
                            { jams.start(name.text.toString()) },
                            Modifier.padding(top = 8.dp),
                            enabled = !state.busy,
                        )
                    }
                } else {
                    BasicText(stringResource(R.string.jam_host_off), style = Theme.type.body.copy(color = colors.muted))
                    TextButton(stringResource(R.string.jam_open_settings), { ui.open(Route.Settings) })
                }
            }
        }
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, Modifier.semantics { heading() }, style = Theme.type.label.copy(color = Theme.colors.text, fontWeight = FontWeight.SemiBold))
        content()
    }
}

@Composable
private fun Joined(joined: JoinedUi, onLeave: () -> Unit, onSearch: (String) -> Unit, onAdd: (String) -> Unit) {
    val colors = Theme.colors
    val query = rememberTextFieldState()
    // Looked up once typing pauses, as Windows waits a quarter of a second.
    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collectLatest { text ->
            if (text.isNotBlank()) delay(250)
            onSearch(text)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") {
            Column(Modifier.padding(Theme.dimens.pagePad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(Theme.dimens.radius)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(R.drawable.ic_party_popper, size = 18.dp, tint = colors.accentBright)
                        BasicText(stringResource(R.string.jam_label), style = Theme.type.caption.copy(color = colors.accentBright, fontWeight = FontWeight.Bold))
                    }
                    BasicText(stringResource(R.string.jam_hosts_jam, joined.host), Modifier.semantics { heading() }, style = Theme.type.title.copy(color = colors.text))
                    BasicText(joined.with, style = Theme.type.body.copy(color = colors.muted))
                    TextButton(stringResource(R.string.jam_leave), onLeave, Modifier.padding(top = 4.dp))
                }
                joined.notice?.let { notice ->
                    BasicText(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Theme.type.body.copy(color = colors.warm))
                }
                BasicText(stringResource(R.string.jam_plays_where, joined.host), style = Theme.type.body.copy(color = colors.muted))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        query, stringResource(R.string.jam_find_label), Modifier.weight(1f),
                        placeholder = stringResource(R.string.jam_find_song, joined.host),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    if (query.text.isNotEmpty()) IconButton(R.drawable.ic_x, stringResource(R.string.jam_clear_search), { query.clearText() })
                }
            }
        }
        val results = joined.results
        if (results != null) {
            item(key = "songs") {
                BasicText(stringResource(R.string.jam_songs), Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp).semantics { heading() }, style = Theme.type.section.copy(color = colors.text))
            }
            if (results.isEmpty()) {
                item(key = "none") {
                    BasicText(stringResource(R.string.jam_nothing_matches, joined.host), Modifier.padding(horizontal = Theme.dimens.pagePad), style = Theme.type.body.copy(color = colors.muted))
                }
            }
            items(results, key = { "s" + it.song.id }, contentType = { "song" }) { found -> FoundRow(found, onAdd) }
        }
        item(key = "foot") { Box(Modifier.heightIn(min = 24.dp)) }
    }
}

@Composable
private fun FoundRow(found: Found, onAdd: (String) -> Unit) {
    val colors = Theme.colors
    val song = found.song
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(song.coverArt, Modifier.size(40.dp), CoverShape.ROW)
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(song.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val about = if (song.album.isEmpty()) song.artistLabel else "${song.artistLabel} • ${song.album}"
            BasicText(about, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicText(clock(song.duration), style = Theme.type.rowDetail.copy(color = colors.faint))
        when (found.state) {
            3 -> Box(Modifier.size(Theme.dimens.touch).semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
                Icon(R.drawable.ic_check, size = 20.dp, tint = colors.accentBright, contentDescription = stringResource(R.string.jam_song_waiting, song.title))
            }
            2 -> IconButton(R.drawable.ic_plus, stringResource(R.string.jam_adding_song, song.title), {}, enabled = false)
            else -> IconButton(R.drawable.ic_plus, stringResource(R.string.jam_add_song, song.title), { onAdd(song.id) }, tint = colors.text)
        }
    }
}

/** The jam hosted here: built with hosting (see HostingPage.kt). */
@Composable
private fun HostingPart(state: JamUi) {
    HostingPage(state)
}
