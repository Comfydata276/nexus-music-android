package org.medianexus.music.ui.lists

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
    var sorted by androidx.compose.runtime.saveable.rememberSaveable(key) { androidx.compose.runtime.mutableIntStateOf(-1) }
    var descending by androidx.compose.runtime.saveable.rememberSaveable(key) { androidx.compose.runtime.mutableStateOf(false) }
    val opening = remember(key) { org.medianexus.music.library.ListView.opening(key, settings.lists[key]) }
    val box = androidx.compose.foundation.text.input.rememberTextFieldState()
    val view = if (!findable) org.medianexus.music.library.ListView()
        else org.medianexus.music.library.ListView(if (sorted < 0) opening.sort else sorted, if (sorted < 0) opening.descending else descending, box.text.toString())
    val account = ui.account
    // Which songs are shown, in what order, as places in the list. Worked out off the main
    // thread for a long list; meanwhile, a list whose order is its own is shown as it is.
    val shown = rememberShown(songs, view) { list -> account?.addedDates(key, list) ?: LongArray(list.size) }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    // As the queue has it if this is the list playing, else as the list remembers it.
    val shuffle = if (key == from.source) queueShuffle else settings.lists[key]?.shuffle ?: false
    val playingHere = from.isList(key) && !from.paused

    Column(Modifier.fillMaxSize()) {
        // The name goes up into the bar once the header has scrolled away, or when there's
        // no header to show (nothing kept, and the server out of reach).
        val bare = songs == null && feed.page.error != null
        TitleBar(if (scrolled || bare) hero.title else "", onBack = ui.navigator::back)
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
                            !songs.isNullOrEmpty() -> shown?.firstOrNull()?.let { ui.play(songs, it, context, key, anyStart = true, view = view) }
                        }
                    },
                    shuffle = shuffle,
                    onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                    liked = liked,
                    onLike = onLike,
                    inline = inline,
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
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero", contentType = "hero") { Hero(hero, onSubtitle = onSubtitle, actions = if (sideways) ({ actions(true) }) else null) }
                if (!sideways) item(key = "actions", contentType = "actions") { actions(false) }
                if (error != null && songs != null) {
                    item(key = "failed", contentType = "failed") { FailedStrip(error.message.orEmpty(), feed::refresh) }
                }
                if (findable && !songs.isNullOrEmpty()) item(key = "find", contentType = "find") {
                    FindAndSort(box, view, playlist = key.startsWith("playlist:"), onSort = { choice ->
                        val next = view.sortedBy(choice)
                        sorted = next.sort
                        descending = next.descending
                        ui.rememberSort(key, next)
                    })
                }
                when {
                    songs == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    songs.isEmpty() -> if (empty.isNotEmpty()) item(key = "empty", contentType = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(empty)) }
                    }
                    shown == null -> {}
                    shown.isEmpty() -> item(key = "nothing", contentType = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) { PageStatus(PageState.Empty(stringResource(org.medianexus.music.R.string.nothing_matches_here, view.filter.trim()))) }
                    }
                    else -> items(shown.size, key = { row -> "${shown[row]}/${songs[shown[row]].id}" }, contentType = { "song" }) { row ->
                        // Its place in the list itself: what plays, and what marks it playing.
                        val index = shown[row]
                        val song = songs[index]
                        val heading = if (discs && view.isDefault && (index == 0 || songs[index - 1].discNumber != song.discNumber)) "$discWord ${song.discNumber.coerceAtLeast(1)}" else null
                        val base = remember(song, row, look) { rowOf(song, row, look) }
                        Column {
                            heading?.let { ListHeading(it) }
                            val current = from.isRow(key, index, song.id)
                            TrackRow(
                                base.copy(liked = song.id in likedSet, current = current, paused = current && from.paused),
                                onClick = { ui.play(songs, index, context, key, view = view) },
                                onMore = { ui.menu = SongMenu(song) },
                                showCover = !look.album,
                                showNumber = look.album,
                                showDuration = true,
                                mark = marks.rowMark(song),
                            )
                        }
                    }
                }
                below(columns)
            }
        }
    }
}

/**
 * Which of `songs` a list shows, in what order, for `view` (see [org.medianexus.music.library.Arrange]),
 * as places in `songs`; null while that is being worked out. A list as it comes is itself at
 * once; a short one is worked out there and then; a long one (thousands of songs, a sort's
 * keys for each) off the main thread, and until it is ready the list shows what it showed
 * for these songs before, if anything. `dates` gives when each song was added, for "Date
 * added"; it is asked only for that order.
 */
@Composable
fun rememberShown(songs: List<Song>?, view: org.medianexus.music.library.ListView, dates: (List<Song>) -> LongArray): IntArray? {
    if (songs == null) return null
    if (view.isDefault) return remember(songs) { IntArray(songs.size) { it } }
    val work = { cancelled: () -> Boolean ->
        org.medianexus.music.library.Arrange.arrange(songs.columns(), view, cancelled, (if (view.sort == org.medianexus.music.library.ListView.ADDED) dates(songs) else null).let { d -> { i: Int -> d?.get(i) ?: 0L } })
    }
    if (songs.size <= ARRANGED_AT_ONCE) return remember(songs, view) { work { false } }
    val arranged by androidx.compose.runtime.produceState<Pair<List<Song>, IntArray>?>(null, songs, view) {
        val keep = value?.takeIf { it.first === songs }
        value = keep
        // Stopped part-way when another keystroke or order replaces it.
        value = songs to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            work { job?.isActive == false }
        }
    }
    return arranged?.takeIf { it.first === songs }?.second
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
