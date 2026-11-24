package org.medianexus.music.ui.stats

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.medianexus.music.R
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.library.PlacedSongs
import org.medianexus.music.library.Stats
import org.medianexus.music.store.Plays
import org.medianexus.music.ui.downloads.rememberDownloadMarks
import org.medianexus.music.ui.downloads.rowMark
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.ChipRow
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.shell.open
import org.medianexus.music.ui.shell.rememberPageFeed
import org.medianexus.music.ui.theme.Theme

private val NO_COPY = MutableStateFlow<LibrarySnapshot?>(null)
private val NO_PLAYS = MutableStateFlow(Plays.EMPTY)

/**
 * "Your stats" (Windows' `stats` page): how much listening a stretch of time comes to, and
 * the songs, artists, albums, kinds of music and playlists played most, for today, this
 * week, this month and all time; under All time, the last 3, 6 or 12 months or a calendar
 * year. Worked out off the main thread from what is on the phone, so it is the same online
 * and offline; it follows each play as it counts.
 */
@Composable
fun StatsPage(route: Route.Stats) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val account = ui.account
    val copy by (account?.copy?.state ?: NO_COPY).collectAsStateWithLifecycle()
    val read by (account?.copy?.read ?: MutableStateFlow(false)).collectAsStateWithLifecycle()
    val plays by (account?.playLog?.state ?: NO_PLAYS).collectAsStateWithLifecycle()
    // The playlists' names are the server's; offline, the phone's copy of its list.
    val playlists = rememberPageFeed("playlists") { it.playlists() }
    val snapshot = copy?.takeIf { it.complete }
    // The period chosen, kept with the page (the chips change it in place, as tabs would).
    var period by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(route.period) }
    val page by produceState<Stats.Page?>(null, snapshot, plays, playlists.value, period) {
        val library = snapshot ?: return@produceState
        value = withContext(Dispatchers.Default) {
            Stats.page(period, library.songs, library.albums, library.artists, library.stamp.fetched, plays, playlists.value.orEmpty(), Stats.now())
        }
    }
    // The chips scroll with the page: on a phone on its side, two rows of them standing
    // still would leave the figures a strip.
    val chips = listOf(R.string.stats_today, R.string.stats_week, R.string.stats_month, R.string.stats_all).map { stringResource(it) }
    val shown = page?.takeIf { snapshot != null }
    val filterNames = shown?.filters?.map { filterName(res, it) }.orEmpty()
    val head: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        // "All time" stays chosen for its finer choices.
        item(key = "periods", contentType = "chips") {
            ChipRow(chips, selected = period.coerceAtMost(Stats.ALL), onChoose = { chosen -> if (chosen != period.coerceAtMost(Stats.ALL)) period = chosen })
        }
        if (period >= Stats.ALL && shown != null && shown.filters.isNotEmpty()) item(key = "filters", contentType = "chips") {
            ChipRow(filterNames, selected = shown.filters.indexOfFirst { it.period == shown.period }.coerceAtLeast(0), onChoose = { chosen ->
                val next = shown.filters[chosen].period
                if (next != shown.period) period = next
            }, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.your_stats), onBack = ui.navigator::back)
        when {
            snapshot == null && read -> LazyColumn(Modifier.fillMaxSize()) {
                head()
                item(key = "reading") { Column(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(stringResource(R.string.library_still_read), icon = R.drawable.ic_chart_no_axes_column)) } }
            }
            shown == null -> LazyColumn(Modifier.fillMaxSize()) {
                head()
                item(key = "loading") { SkeletonRows() }
            }
            // (Each period starts at its top, its rows at their starts: not where the last was.)
            else -> androidx.compose.runtime.key(shown.period) { StatsContent(shown, snapshot!!, head) }
        }
    }
}

@Composable
private fun StatsContent(page: Stats.Page, library: LibrarySnapshot, head: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val note = noteFor(res, page)
    if (page.empty) {
        val empty = stringResource(
            when (page.period) {
                Stats.TODAY -> R.string.stats_empty_today
                Stats.WEEK -> R.string.stats_empty_week
                Stats.MONTH -> R.string.stats_empty_month
                Stats.ALL -> R.string.stats_empty_all
                else -> R.string.stats_empty_other
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            head()
            item(key = "empty") { Column(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(empty, icon = R.drawable.ic_chart_no_axes_column)) } }
            if (note.isNotEmpty() && page.period >= Stats.ALL) item(key = "note") { Note(note) }
        }
        return
    }
    val marks = rememberDownloadMarks()
    val liked = likedSongs()
    val player = ui.player
    val from by player.from.collectAsStateWithLifecycle()
    val songs = remember(page, library) { PlacedSongs.of(library.songs, page.songs) }
    val context = stringResource(R.string.mix_top)
    val key = Route.Stats(page.period).key
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        head()
        item(key = "figures", contentType = "figures") { Figures(figures(res, page)) }
        if (note.isNotEmpty()) item(key = "note", contentType = "note") { Note(note) }
        if (page.artists.isNotEmpty()) item(key = "artists", contentType = "shelf") {
            Shelf(stringResource(R.string.stats_top_artists), remember(page, res) {
                page.artists.map { CardData(CardKind.ARTIST, it.what.id, it.what.name, plays(res, it.times), it.what.coverArt) }
            }, onOpen = { ui.open(it) })
        }
        item(key = "songs-title", contentType = "title") { SectionTitle(stringResource(R.string.stats_top_songs)) }
        items(songs.size, key = { "song/$it/${songs.id(it)}" }, contentType = { "song" }) { index ->
            val song = remember(songs, index) { songs[index] }
            val current = from.isRow(key, index, song.id)
            TrackRow(
                TrackRowData(
                    song.id, song.title, if (song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel, song.coverArt,
                    number = (index + 1).toString(), duration = plays(res, page.songTimes[index]),
                    liked = song.id in liked, current = current, paused = current && from.paused,
                ),
                onClick = { ui.play(songs, index, context, key) },
                onMore = { ui.menu = SongMenu(song) },
                showCover = true,
                showNumber = true,
                showDuration = true,
                mark = marks.rowMark(song),
            )
        }
        if (page.albums.isNotEmpty()) item(key = "albums", contentType = "shelf") {
            Shelf(stringResource(R.string.stats_top_albums), remember(page, res) {
                page.albums.map { ranked ->
                    // An album keeps its artist beside the count; an artist has only the count.
                    val artist = ranked.what.artistLabel
                    CardData(CardKind.ALBUM, ranked.what.id, ranked.what.name, if (artist.isEmpty()) plays(res, ranked.times) else res.getString(R.string.stats_album_plays, plays(res, ranked.times), artist), ranked.what.coverArt)
                }
            }, onOpen = { ui.open(it) })
        }
        if (page.genres.isNotEmpty()) item(key = "genres", contentType = "shelf") {
            Shelf(stringResource(R.string.stats_top_genres), remember(page, res) {
                page.genres.map { CardData(CardKind.GENRE, it.what, it.what, plays(res, it.times)) }
            }, onOpen = { ui.open(it) })
        }
        if (page.playlists.isNotEmpty()) item(key = "playlists", contentType = "shelf") {
            Shelf(stringResource(R.string.stats_top_playlists), remember(page, res) {
                page.playlists.map { CardData(CardKind.PLAYLIST, it.what.id, it.what.name, plays(res, it.times), it.what.coverArt) }
            }, onOpen = { ui.open(it) })
        }
    }
}

/** A finer choice's name under "All time". */
private fun filterName(res: Resources, filter: Stats.Filter): String = when {
    filter.year -> filter.period.toString()
    filter.period == Stats.MONTHS_3 -> res.getString(R.string.stats_3_months)
    filter.period == Stats.MONTHS_6 -> res.getString(R.string.stats_6_months)
    filter.period == Stats.MONTHS_12 -> res.getString(R.string.stats_12_months)
    else -> res.getString(R.string.stats_everything)
}

/** "1 play", "5,210 plays". */
private fun plays(res: Resources, times: Long) = res.getString(if (times == 1L) R.string.stats_play else R.string.stats_plays, Stats.grouped(times))

/** The four figures, each a number over what it counts (Windows' words and forms). */
private fun figures(res: Resources, page: Stats.Page): List<Pair<String, String>> {
    val minutes = page.seconds / 60
    // Minutes, until there are so many that hours say it better.
    val listened = if (minutes >= 6000) Stats.grouped(page.seconds / 3600) to res.getString(R.string.stats_hours_listened)
        else Stats.grouped(minutes) to res.getString(if (minutes == 1L) R.string.stats_minute_listened else R.string.stats_minutes_listened)
    return listOf(
        listened,
        Stats.grouped(page.total) to res.getString(if (page.total == 1L) R.string.stats_song_played else R.string.stats_songs_played),
        Stats.grouped(page.songCount.toLong()) to res.getString(if (page.songCount == 1) R.string.stats_different_song else R.string.stats_different_songs),
        Stats.grouped(page.artistCount.toLong()) to res.getString(if (page.artistCount == 1) R.string.stats_artist else R.string.stats_artists),
    )
}

/** Where the numbers come from, when that's worth saying. */
private fun noteFor(res: Resources, page: Stats.Page): String {
    val since = Instant.ofEpochSecond(page.since).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH))
    return when (page.note) {
        Stats.Note.EVERYTHING -> res.getString(R.string.stats_note_everything, since)
        Stats.Note.BEFORE_LOG -> res.getString(R.string.stats_note_before, since)
        Stats.Note.NONE -> ""
    }
}

/** The figures: all on one line where there's room, otherwise two to a line (Windows'). */
@Composable
private fun Figures(figures: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp)) {
        val across = if (maxWidth >= 128.dp * figures.size) figures.size else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (line in figures.chunked(across)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((value, label) in line) Figure(value, label, Modifier.weight(1f))
                    repeat(across - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(Theme.dimens.radius))
            .background(Theme.colors.raised)
            .semantics(mergeDescendants = true) {}
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        BasicText(value, style = Theme.type.hero.copy(color = Theme.colors.text, fontSize = 28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(label, style = Theme.type.rowDetail.copy(color = Theme.colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), style = Theme.type.body.copy(color = Theme.colors.muted))
}
