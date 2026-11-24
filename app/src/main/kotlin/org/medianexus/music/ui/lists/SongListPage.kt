package org.medianexus.music.ui.lists

import org.medianexus.music.ui.parts.FastScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.medianexus.music.library.columns
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.ActionBar
import org.medianexus.music.ui.parts.FailedStrip
import org.medianexus.music.ui.parts.Hero
import org.medianexus.music.ui.parts.HeroData
import org.medianexus.music.ui.parts.ListHeading
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.Refreshable
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.PageFeed
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.shell.likedSongs
import org.medianexus.music.ui.theme.Theme
import org.medianexus.music.ui.downloads.ListDownload
import org.medianexus.music.ui.downloads.rememberDownloadMarks
import org.medianexus.music.ui.downloads.rowMark
import kotlinx.coroutines.launch
import org.medianexus.music.ui.menu.movePlaylistSong
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer

/** How a list's songs are drawn. */
@Immutable
data class ListLook(
    /** An album: its own track numbers, no covers, a heading for each disc. Anything else
     *  (a playlist, Liked songs) shows each song's album beside its artist, as Windows does,
     *  so two versions of a song can be told apart. */
    val album: Boolean = false,
)

/**
 * A page that is a list of songs under a header: an album, a playlist, Liked songs. The
 * header shows at once (from what opened the page, until the page arrives), then the kept
 * copy, then the server's. Tapping a song plays the list from it; the row playing is marked
 * only if it is that very row of this list.
 */
@Composable
fun SongListPage(
    key: String,
    hero: HeroData,
    songs: List<Song>?,
    context: String,
    feed: PageFeed<*>,
    empty: String,
    look: ListLook = ListLook(),
    onSubtitle: (() -> Unit)? = null,
    liked: Boolean? = null,
    onLike: () -> Unit = {},
    /** What the play button starts, when it isn't the songs listed (an artist's albums). */
    playOverride: (() -> Unit)? = null,
    /** What follows the songs, told how many cards to a line suit the page's width. */
    below: LazyListScope.(columns: Int) -> Unit = {},
    /** The list can be downloaded whole (an album, a playlist, Liked songs). */
    downloadable: Boolean = false,
    /** What the download button does instead of downloading the list as its page has it
     *  (a list shown from the downloads: only removing). */
    onDownload: ((ListDownload) -> Unit)? = null,
    /** The playlist these songs are, where the account may change it: its songs can be
     *  dragged into another order (in its own order) and taken out. */
    editable: org.medianexus.music.net.Playlist? = null,
    /** Whether its songs can be dragged now (the server there to take the change). */
    draggable: Boolean = true,
    /** Its songs can't be moved now (a change to it on its way, or it holds songs missing from
     *  the library): its handles show it, and do nothing. */
    busy: Boolean = false,
    /** The header's "…" (the page's own menu), if it has one: given what the page drew and
     *  its play button, for the menu to act on. */
    onMore: ((org.medianexus.music.ui.menu.FromPage) -> Unit)? = null,
    /** Said under the header (a playlist that is someone else's). */
    note: String? = null,
    /** A heading over the songs (an artist's "Popular"). */
    songsTitle: String? = null,
) {
    val ui = LocalUi.current
    val android = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The downloads and being offline, as state this page never reads itself: each row reads
    // its own mark, and the download button its list's, so a song arriving redraws its row
    // and the button, not the page.
    val marks = rememberDownloadMarks()
    val ids = remember(songs) { songs?.mapTo(HashSet()) { it.id } ?: emptySet<String>() }
    val listDownload by remember(marks, ids) { derivedStateOf { ListDownload.of(marks.now, ids) } }
    val offline by remember(marks) { derivedStateOf { marks.offline } }
    val player = ui.player
    val from by player.from.collectAsStateWithLifecycle()
    val queueShuffle by player.shuffle.collectAsStateWithLifecycle()
    val settings by ui.graph.settings.state.collectAsStateWithLifecycle()
    val likedSet = likedSongs()
    val discWord = androidx.compose.ui.res.stringResource(org.medianexus.music.R.string.disc)
    // Whether to head each disc: one look along the list, nothing kept per song. What a row
    // shows is made when the row is drawn (a list of thousands is drawn a screenful at a
    // time), not for every song when the page opens.
    val discs = remember(songs, look) { look.album && songs != null && songs.any { it.discNumber != songs[0].discNumber } }
    // A playlist, Liked songs: found in and sorted (Windows' own lists). The order is the one
    // the list was last left in; what is typed is forgotten when the page goes, as on Windows.
    val findable = org.medianexus.music.library.ListView.findable(key)
    // The order is the settings' own, as they are now, not one read when the page opened:
    // a sort chosen on another device shows on a list that is open (and one chosen here is
    // in the settings before the page is drawn again).
    val order = org.medianexus.music.library.ListView.opening(key, settings.lists[key])
    val box = androidx.compose.foundation.text.input.rememberTextFieldState()
    val view = if (!findable) org.medianexus.music.library.ListView()
        else org.medianexus.music.library.ListView(order.sort, order.descending, box.text.toString())
    val account = ui.account
    // Which songs are shown, in what order, as places in the list. Worked out off the main
    // thread for a long list; meanwhile, a list whose order is its own is shown as it is.
    val arranged = rememberShown(songs, view) { list -> account?.addedDates(key, list) ?: LongArray(list.size) }
    val shown = arranged?.order
    // What a tap plays is the arrangement drawn, handed to the player as it is: while more
    // typing (or another order) is being worked out, the rows still shown, in their order.
    fun play(start: Int, anyStart: Boolean) {
        val drawn = arranged ?: return
        ui.play(drawn.list, start, context, key, anyStart = anyStart, view = drawn.view, shown = drawn.order.takeIf { !drawn.view.isDefault })
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    // Several songs chosen together (a long press starts it): by their places in the list.
    val selection = rememberSelection(arranged)
    // A playlist's songs in its own order can be dragged by their handles (see PlaylistDrag).
    val drag = remember(key) { PlaylistDrag() }
    androidx.compose.runtime.LaunchedEffect(songs) { drag.reset() }
    val canDrag = editable != null && draggable && view.isDefault && !selection.active
    // As the queue has it if this is the list playing, else as the list remembers it.
    val shuffle = if (key == from.source) queueShuffle else settings.lists[key]?.shuffle ?: false
    val playingHere = from.isList(key) && !from.paused

    Column(Modifier.fillMaxSize()) {
        // The name goes up into the bar once the header has scrolled away, or when there's
        // no header to show (nothing kept, and the server out of reach).
        val bare = songs == null && feed.page.error != null
        if (selection.active && songs != null) {
            SelectionBar(selection, { selection.places.sorted().mapNotNull { songs.getOrNull(it) } }, inPlaylist = editable)
        } else {
            TitleBar(if (scrolled || bare) hero.title else "", onBack = ui.navigator::back)
        }
        Refreshable(feed.refreshing, feed::refresh) {
            // Offline, a failure to reach the server is what offline is: the pill in the title
            // bar says so, once, rather than a strip on every page.
            val error = feed.page.error?.takeIf { !(offline && it is org.medianexus.music.net.ApiError.Network) }
            if (songs == null && feed.page.error != null) {
                if (error == null) {
                    PageStatus(PageState.Empty(stringResource(org.medianexus.music.R.string.not_on_phone), stringResource(org.medianexus.music.R.string.not_on_phone_detail), org.medianexus.music.R.drawable.ic_wifi_off))
                } else {
                    PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
                }
                return@Refreshable
            }
            val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
            val columns = org.medianexus.music.ui.lists.gridColumns(width)
            // A phone on its side has the height for the header and a song or two: the play
            // button goes beside the header's words.
            val sideways = org.medianexus.music.ui.shell.sidewaysPhone()
            val actions = @Composable { inline: Boolean ->
                ActionBar(
                    title = hero.title,
                    canPlay = (!songs.isNullOrEmpty() && shown?.isNotEmpty() != false) || playOverride != null,
                    playing = playingHere,
                    onPlay = {
                        when {
                            from.isList(key) -> player.playPause()
                            playOverride != null -> playOverride()
                            !songs.isNullOrEmpty() -> shown?.firstOrNull()?.let { play(it, anyStart = true) }
                        }
                    },
                    shuffle = shuffle,
                    onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                    liked = liked,
                    onLike = onLike,
                    inline = inline,
                    onMore = onMore?.let { open ->
                        {
                            // What is drawn now, in the order drawn, and the play button's own start.
                            val drawn = arranged?.let { a -> a.order.map { a.list[it] } }.orEmpty()
                            open(org.medianexus.music.ui.menu.FromPage(drawn) { shown?.firstOrNull()?.let { play(it, anyStart = true) } })
                        }
                    },
                    download = if (!downloadable || songs.isNullOrEmpty()) null else {
                        val now = listDownload
                        org.medianexus.music.ui.parts.DownloadButton(
                            state = now.state, done = now.done, total = now.total, progress = now.progress,
                            onClick = {
                                val account = ui.account ?: return@DownloadButton
                                val go = { scope.launch { org.medianexus.music.downloads.DownloadPages.toggle(android, account, key)?.let(ui::toast) }; Unit }
                                when {
                                    onDownload != null -> onDownload(now)
                                    // All of it downloaded: what the button offers, not done
                                    // at once (a thumb brushes it more easily than a pointer).
                                    now.state == 2 -> ui.sheet = org.medianexus.music.ui.shell.Sheet.RemoveList(hero.title, hero.cover, go)
                                    now.state == 1 -> go()
                                    // The first download asks for the notification it shows
                                    // progress in.
                                    else -> ui.askToDownload(go)
                                }
                            },
                        )
                    },
                )
            }
            val today = stringResource(org.medianexus.music.R.string.added_today)
            val yesterday = stringResource(org.medianexus.music.R.string.added_yesterday)
            // Sorted by date added: the date on each row where its length otherwise goes.
            val added = arranged?.added
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "hero", contentType = "hero") { Hero(hero, onSubtitle = onSubtitle, actions = if (sideways) ({ actions(true) }) else null) }
                    if (!sideways) item(key = "actions", contentType = "actions") { actions(false) }
                    if (error != null && songs != null) {
                        item(key = "failed", contentType = "failed") { FailedStrip(error.message.orEmpty(), feed::refresh) }
                    }
                    if (note != null) item(key = "note", contentType = "note") {
                        androidx.compose.foundation.text.BasicText(note, androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 4.dp), style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
                    }
                    if (findable && !songs.isNullOrEmpty()) item(key = "find", contentType = "find") {
                        FindAndSort(box, view, playlist = key.startsWith("playlist:"), onSort = { choice ->
                            ui.rememberSort(key, view.sortedBy(choice))
                        })
                    }
                    if (songsTitle != null && !songs.isNullOrEmpty()) item(key = "songs-title", contentType = "title") { org.medianexus.music.ui.parts.SectionTitle(songsTitle) }
                    when {
                        songs == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                        songs.isEmpty() -> if (empty.isNotEmpty()) item(key = "empty", contentType = "empty") {
                            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(empty)) }
                        }
                        shown == null -> {}
                        shown.isEmpty() -> item(key = "nothing", contentType = "empty") {
                            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) { PageStatus(PageState.Empty(stringResource(org.medianexus.music.R.string.nothing_matches_here, view.filter.trim()))) }
                        }
                        else -> items(shown.size, key = { row -> drag.at(shown, row).let { "$it/${songs[it].id}" } }, contentType = { "song" }) { row ->
                            // Its place in the list itself: what plays, and what marks it playing.
                            // (While a song is dragged, the rows as they are being moved.)
                            val index = drag.at(shown, row)
                            val song = songs[index]
                            val heading = if (discs && view.isDefault && (index == 0 || songs[index - 1].discNumber != song.discNumber)) "$discWord ${song.discNumber.coerceAtLeast(1)}" else null
                            val base = remember(song, row, look, added) {
                                rowOf(song, row, look).let { r -> if (added != null) r.copy(duration = org.medianexus.music.library.Arrange.dateLabel(added[index], today, yesterday)) else r }
                            }
                            Column {
                                heading?.let { ListHeading(it) }
                                val current = from.isRow(key, index, song.id)
                                val lifted = drag.lifted == index
                                val moveUp = stringResource(org.medianexus.music.R.string.move_up)
                                val moveDown = stringResource(org.medianexus.music.R.string.move_down)
                                TrackRow(
                                    base.copy(liked = song.id in likedSet, current = current, paused = current && from.paused, selected = index in selection.places),
                                    onClick = { if (selection.active) selection.toggle(index) else play(index, anyStart = false) },
                                    onMore = { ui.menu = SongMenu(song, inPlaylist = editable?.let { org.medianexus.music.ui.shell.InPlaylist(it, index) }) },
                                    onLongClick = { selection.toggle(index) },
                                    modifier = (if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset }.background(Theme.colors.overlay) else Modifier)
                                        // A list long enough for the fast-scroll handle: the drag
                                        // handles keep clear of the edge it takes touches in.
                                        .then(if (canDrag && shown.size >= org.medianexus.music.ui.parts.FAST_SCROLL_FROM) Modifier.padding(end = org.medianexus.music.ui.parts.FAST_SCROLL_EDGE) else Modifier),
                                    showCover = !look.album,
                                    showNumber = look.album,
                                    showDuration = true,
                                    mark = marks.rowMark(song),
                                    handle = if (!canDrag) null else ({
                                        // Busy: dimmed, and nothing to drag until the change on its way settles.
                                        DragHandle(song.title, if (busy) Modifier.alpha(0.38f) else Modifier.dragRows(drag, list, index) { from, to -> editable?.let { ui.movePlaylistSong(it, from, to) } ?: false })
                                    }),
                                    actions = if (!canDrag) emptyList() else buildList {
                                        val playlist = editable ?: return@buildList
                                        if (index > 0) add(androidx.compose.ui.semantics.CustomAccessibilityAction(moveUp) { ui.movePlaylistSong(playlist, index, index - 1); true })
                                        if (index < songs.lastIndex) add(androidx.compose.ui.semantics.CustomAccessibilityAction(moveDown) { ui.movePlaylistSong(playlist, index, index + 1); true })
                                    },
                                )
                            }
                        }
                    }
                    below(columns)
                }
                // A long list's handle: the bubble says where by what the list is sorted by.
                val rows = shown
                if (rows != null && songs != null && rows.size >= org.medianexus.music.ui.parts.FAST_SCROLL_FROM) {
                    // (The rows follow the hero, the play button unless it sits beside the hero,
                    // a failure strip if any, and the find box if the list can be found in.)
                    val first = 1 + (if (!sideways) 1 else 0) + (if (error != null) 1 else 0) + (if (findable) 1 else 0)
                    FastScroll(list, first, rows.size) { row ->
                        val song = songs[rows[row]]
                        when (arranged?.view?.sort ?: org.medianexus.music.library.ListView.OWN) {
                            org.medianexus.music.library.ListView.TITLE -> org.medianexus.music.ui.parts.initialOf(song.title)
                            org.medianexus.music.library.ListView.ARTIST -> org.medianexus.music.ui.parts.initialOf(song.artistLabel)
                            org.medianexus.music.library.ListView.ALBUM -> org.medianexus.music.ui.parts.initialOf(song.album)
                            org.medianexus.music.library.ListView.DURATION -> clock(song.duration)
                            org.medianexus.music.library.ListView.ADDED -> added?.let { org.medianexus.music.library.Arrange.monthOf(it[rows[row]]) }
                            // In the list's own order: how far down it is.
                            else -> (row + 1).toString()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which of `songs` a list shows, in what order, for `view` (see [org.medianexus.music.library.Arrange]),
 * as places in `songs`, with the songs and the view it was worked out for; null while there
 * is none. A list as it comes is itself at once; a short one is worked out there and then; a
 * long one (thousands of songs, a sort's keys for each) off the main thread, and until it is
 * ready the list shows what it showed for these songs before, if anything (and a tap plays
 * what it shows). `dates` gives when each
 * song was added, for "Date added"; it is asked only for that order.
 */
@Composable
fun rememberShown(songs: List<Song>?, view: org.medianexus.music.library.ListView, dates: (List<Song>) -> LongArray): org.medianexus.music.library.Shown<List<Song>>? {
    if (songs == null) return null
    if (view.isDefault) return remember(songs, view) { org.medianexus.music.library.Shown(songs, view, IntArray(songs.size) { it }) }
    val work = { cancelled: () -> Boolean ->
        val added = if (view.sort == org.medianexus.music.library.ListView.ADDED) dates(songs) else null
        org.medianexus.music.library.Shown(songs, view, org.medianexus.music.library.Arrange.arrange(songs.columns(), view, cancelled) { i -> added?.get(i) ?: 0L }, added)
    }
    if (songs.size <= ARRANGED_AT_ONCE) return remember(songs, view) { work { false } }
    val arranged by androidx.compose.runtime.produceState<org.medianexus.music.library.Shown<List<Song>>?>(null, songs, view) {
        value = value?.takeIf { it.list === songs }
        // Stopped part-way when another keystroke or order replaces it.
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            work { job?.isActive == false }
        }
    }
    return arranged?.takeIf { it.list === songs }
}

/** A list this long or shorter is arranged as it is drawn: well under a frame's time. */
private const val ARRANGED_AT_ONCE = 600

/** What one row shows, before what changes while it's shown (liked, playing). */
private fun rowOf(song: Song, index: Int, look: ListLook): TrackRowData {
    val number = if (look.album && song.track > 0) song.track.toString() else (index + 1).toString()
    val detail = if (!look.album && song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
    return TrackRowData(song.id, song.title, detail, song.coverArt, number, clock(song.duration))
}

/** For screens: the padding a page's own list leaves at its sides. */
val PagePad = PaddingValues(horizontal = Theme.dimens.pagePad)
