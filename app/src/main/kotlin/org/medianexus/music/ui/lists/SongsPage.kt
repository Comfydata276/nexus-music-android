package org.medianexus.music.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.medianexus.music.R
import org.medianexus.music.library.Arrange
import org.medianexus.music.library.Library
import org.medianexus.music.library.LibrarySnapshot
import org.medianexus.music.library.ListView
import org.medianexus.music.library.columns
import org.medianexus.music.ui.downloads.rememberDownloadMarks
import org.medianexus.music.ui.downloads.rowMark
import org.medianexus.music.ui.parts.ActionBar
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.parts.FastScroll
import org.medianexus.music.ui.parts.initialOf
import org.medianexus.music.ui.parts.songCount
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.theme.Theme

/** The Songs page on its own (Library's chip shows the same without a title bar). */
@Composable
fun SongsPage() {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.songs), onBack = ui.navigator::back)
        SongsContent()
    }
}


/**
 * Every song in the library, the newest arrivals first, sortable and findable, as Windows
 * has it (`all_songs`): made from the copy of the library kept on the phone, so it is there
 * at once, with or without the server, and fills as the first copy arrives. A song is made
 * into a row only when its row is shown: 50,000 of them are a list of places, not of songs.
 * Tapping one plays what is shown, in the order shown, from that song; the service reads
 * the songs from the copy itself (the screen never holds them all).
 */
@Composable
fun SongsContent() {
    val ui = LocalUi.current
    val res = LocalResources.current
    val account = ui.account
    val copy by (account?.copy?.state ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val fetching by (account?.copy?.fetching ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val read by (account?.copy?.read ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val key = Library.SONGS
    val opening = remember { ListView.opening(key, settings.lists[key]) }
    var sorted by rememberSaveable { mutableIntStateOf(-1) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val box = rememberTextFieldState()
    val view = ListView(if (sorted < 0) opening.sort else sorted, if (sorted < 0) opening.descending else descending, box.text.toString())
    // Worked out off the main thread (the newest-first order of the copy is a sort of every
    // song, made once a copy; a find or another order on top of it, at each change). What was
    // shown stays until the new arrangement is ready, if it is of the same copy.
    // What is shown for one order and one find: places in the copy's song table, for that very
    // copy.
    val shown by produceState<org.medianexus.music.library.Shown<LibrarySnapshot>?>(null, copy, view) {
        val snap = copy ?: return@produceState
        if (value?.list !== snap) value = null
        value = withContext(Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            val newest = snap.newestFirst
            // (Made here, once a copy, not on the main thread: what marks the row playing.)
            snap.newestPlace
            val order = if (view.isDefault) newest else {
                val table = snap.songs
                // Places in the newest-first list, then back to places in the table. Stopped
                // part-way if more typing has replaced it.
                Arrange.arrange(table.columns(newest), view, cancelled = { job?.isActive == false }) { Arrange.dayStart(table.createdMillis(newest[it]) / 1000) }
                    .let { at -> IntArray(at.size) { newest[at[it]] } }
            }
            org.medianexus.music.library.Shown(snap, view, order)
        }
    }
    val marks = rememberDownloadMarks()
    val likedSet = likedSongs()
    val player = ui.player
    val from by player.from.collectAsStateWithLifecycle()
    val queueShuffle by player.shuffle.collectAsStateWithLifecycle()
    val shuffle = if (key == from.source) queueShuffle else settings.lists[key]?.shuffle ?: false
    val list = rememberLazyListState()
    val now = shown
    val table = now?.list?.songs
    // Rows of an arrangement that more typing (or another order, or a newer copy) has replaced
    // are shown until the new one is ready, but a tap on them plays nothing (see Shown).
    val tappable = now != null && copy != null && now.tappable(copy!!, view)
    val context = stringResource(R.string.songs)
    val sideways = org.medianexus.music.ui.shell.sidewaysPhone()

    // The place of a song (by its place in the table) in the newest-first list: what the
    // service is told, and what marks the row playing. Made off the main thread, with the
    // arrangement.
    val ownPlace = now?.list?.newestPlace ?: IntArray(0)
    fun play(row: Int, any: Boolean = false) {
        val n = now ?: return
        if (!tappable) return
        val t = n.order.getOrNull(row) ?: return
        // The arrangement on the screen (its find text and order), and the song tapped.
        player.playPage(key, ownPlace[t], if (any) null else n.list.songs.id(t), context, anyStart = any, view = n.view)
    }

    when {
        account == null -> return
        copy == null && !read -> SkeletonRows()
        copy == null || (copy?.songs?.size == 0 && fetching == null && copy?.complete == true) -> {
            val offline = org.medianexus.music.ui.downloads.reachNow().offline
            PageStatus(PageState.Empty(stringResource(if (offline && fetching == null) R.string.songs_offline_empty else R.string.songs_empty), icon = R.drawable.ic_music))
        }
        else -> Box(Modifier.fillMaxSize()) {
            // In large type, titles that share a long beginning are told apart by a second line.
            val titleLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= LARGE_TYPE) 2 else 1
            val today = stringResource(R.string.added_today)
            val yesterday = stringResource(R.string.added_yesterday)
            // Sorted by when songs were added (the page's own order is newest first): the date on
            // each row where its length otherwise goes, as Windows' date-added column.
            val byDate = view.sort == ListView.OWN || view.sort == ListView.ADDED
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                val total = copy?.songs?.size ?: 0
                val facts = @Composable { modifier: Modifier ->
                    // (What it is, "Songs", is said just above: by Library's chip, or the page's title.)
                    Column(modifier) {
                        // How many, and in the order shown (its own: newest first).
                        BasicText(res.getString(R.string.songs_in_order, songCount(res, total), res.getString(orderWords(view, ownWords = R.string.order_newest_first))), style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
                        fetching?.takeIf { it.first }?.let {
                            BasicText(res.getString(R.string.songs_arriving, songCount(res, it.songs)), Modifier.padding(top = 2.dp), style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
                        }
                    }
                }
                val actions = @Composable { inline: Boolean ->
                    ActionBar(
                        title = context,
                        canPlay = now?.order?.isNotEmpty() == true,
                        playing = from.isList(key) && !from.paused,
                        onPlay = { if (from.isList(key)) player.playPause() else play(0, any = true) },
                        shuffle = shuffle,
                        onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                        inline = inline,
                    )
                }
                val find = @Composable { modifier: Modifier ->
                    FindAndSort(box, view, playlist = false, modifier = modifier, onSort = { choice ->
                        val next = view.sortedBy(choice)
                        sorted = next.sort
                        descending = next.descending
                        ui.rememberSort(key, next)
                    })
                }
                if (sideways) {
                    // On its side there is height for a few rows: the facts, the play button and the
                    // find box share one line, so the first view has songs in it.
                    item(key = "head", contentType = "head") {
                        Row(Modifier.fillMaxWidth().padding(start = Theme.dimens.pagePad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            actions(true)
                            facts(Modifier.weight(0.7f))
                            find(Modifier.weight(1f))
                        }
                    }
                } else {
                    item(key = "head", contentType = "head") { facts(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad).padding(top = 8.dp)) }
                    item(key = "actions", contentType = "actions") { actions(false) }
                    item(key = "find", contentType = "find") { find(Modifier) }
                }
                when {
                    now == null || table == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    now.order.isEmpty() -> item(key = "nothing", contentType = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                            PageStatus(PageState.Empty(stringResource(R.string.nothing_matches_here, view.filter.trim())))
                        }
                    }
                    else -> items(now.order.size, key = { row -> table.id(now.order[row]) }, contentType = { "song" }) { row ->
                        val t = now.order[row]
                        // Made when the row is shown, and kept while it is.
                        val song = remember(table, t) { table.song(t) }
                        val base = remember(song, row, byDate) {
                            val detail = if (song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
                            val end = if (byDate) Arrange.dateLabel(table.createdMillis(t) / 1000, today, yesterday) else clock(song.duration)
                            TrackRowData(song.id, song.title, detail, song.coverArt, (row + 1).toString(), end)
                        }
                        val current = from.isRow(key, ownPlace.getOrElse(t) { -1 }, song.id)
                        TrackRow(
                            base.copy(liked = song.id in likedSet, current = current, paused = current && from.paused),
                            onClick = { play(row) },
                            onMore = { ui.menu = SongMenu(song) },
                            showDuration = true,
                            mark = marks.rowMark(song),
                            titleLines = titleLines,
                        )
                    }
                }
            }
            // The handle for moving through 50,000 songs, its bubble saying where: the letter of
            // what the list is sorted by, or the month songs were added.
            val shownNow = now
            if (shownNow != null && table != null) {
                FastScroll(list, first = if (sideways) 1 else 3, count = shownNow.order.size) { row ->
                    val t = shownNow.order[row]
                    when (shownNow.view.sort) {
                        ListView.TITLE -> initialOf(table.title(t))
                        ListView.ARTIST -> initialOf(table.artistLabel(t))
                        ListView.ALBUM -> initialOf(table.album(t))
                        ListView.DURATION -> clock(table.duration(t))
                        else -> Arrange.monthOf(table.createdMillis(t) / 1000)
                    }
                }
            }
        }
    }
}

/** Font scale from which song titles may take two lines (the design's large-type case is 1.3). */
const val LARGE_TYPE = 1.3f

/**
 * Recently played: the last hundred songs played, newest first, each playable again by itself
 * (Windows' side panel tab). The song playing now is left off the top, as Windows leaves it:
 * the player already shows it.
 */
@Composable
fun RecentlyPlayedPage() {
    val ui = LocalUi.current
    val account = ui.account
    val played by (account?.recentlyPlayed?.state ?: remember { MutableStateFlow(emptyList()) }).collectAsStateWithLifecycle()
    val playing by ui.player.song.collectAsStateWithLifecycle()
    val marks = rememberDownloadMarks()
    val likedSet = likedSongs()
    val songs = remember(played, playing?.id) {
        val hidden = if (playing != null && played.firstOrNull()?.song?.id == playing?.id) 1 else 0
        played.drop(hidden)
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.recently_played), onBack = ui.navigator::back)
        if (songs.isEmpty()) {
            PageStatus(PageState.Empty(stringResource(R.string.recently_played_empty), icon = R.drawable.ic_history))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(songs.size, key = { "${songs[it].at}/${songs[it].song.id}/$it" }, contentType = { "song" }) { row ->
                val song = songs[row].song
                val base = remember(song) {
                    val detail = if (song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
                    TrackRowData(song.id, song.title, detail, song.coverArt, duration = clock(song.duration))
                }
                TrackRow(
                    base.copy(liked = song.id in likedSet),
                    // By itself, as if it had been picked on its own (Windows' `play_history`).
                    onClick = { ui.play(listOf(song), 0, "") },
                    onMore = { ui.menu = SongMenu(song) },
                    showDuration = true,
                    mark = marks.rowMark(song),
                )
            }
        }
    }
}
