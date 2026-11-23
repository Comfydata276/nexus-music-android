package org.medianexus.music.ui.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
) {
    val ui = LocalUi.current
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
            val error = feed.page.error
            if (songs == null && error != null) {
                PageStatus(PageState.Error(error.message.orEmpty()), onRetry = feed::refresh)
                return@Refreshable
            }
            val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
            val columns = org.medianexus.music.ui.lists.gridColumns(width)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero", contentType = "hero") { Hero(hero, onSubtitle = onSubtitle) }
                item(key = "actions", contentType = "actions") {
                    ActionBar(
                        title = hero.title,
                        canPlay = !songs.isNullOrEmpty() || playOverride != null,
                        playing = playingHere,
                        onPlay = {
                            when {
                                from.isList(key) -> player.playPause()
                                playOverride != null -> playOverride()
                                !songs.isNullOrEmpty() -> ui.play(songs, 0, context, key, anyStart = true)
                            }
                        },
                        shuffle = shuffle,
                        onShuffle = { ui.toggleListShuffle(key, from.source, queueShuffle) },
                        liked = liked,
                        onLike = onLike,
                    )
                }
                if (error != null && songs != null) {
                    item(key = "failed", contentType = "failed") { FailedStrip(error.message.orEmpty(), feed::refresh) }
                }
                when {
                    songs == null -> item(key = "loading", contentType = "loading") { SkeletonRows() }
                    songs.isEmpty() -> if (empty.isNotEmpty()) item(key = "empty", contentType = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { PageStatus(PageState.Empty(empty)) }
                    }
                    else -> itemsIndexed(songs, key = { index, song -> "$index/${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                        val heading = if (discs && (index == 0 || songs[index - 1].discNumber != song.discNumber)) "$discWord ${song.discNumber.coerceAtLeast(1)}" else null
                        val base = remember(song, index, look) { rowOf(song, index, look) }
                        Column {
                            heading?.let { ListHeading(it) }
                            val current = from.isRow(key, index, song.id)
                            TrackRow(
                                base.copy(liked = song.id in likedSet, current = current, paused = current && from.paused),
                                onClick = { ui.play(songs, index, context, key) },
                                onMore = { ui.menu = SongMenu(song) },
                                showCover = !look.album,
                                showNumber = look.album,
                                showDuration = true,
                            )
                        }
                    }
                }
                below(columns)
            }
        }
    }
}

/** What one row shows, before what changes while it's shown (liked, playing). */
private fun rowOf(song: Song, index: Int, look: ListLook): TrackRowData {
    val number = if (look.album && song.track > 0) song.track.toString() else (index + 1).toString()
    val detail = if (!look.album && song.album.isNotEmpty()) "${song.artistLabel} • ${song.album}" else song.artistLabel
    return TrackRowData(song.id, song.title, detail, song.coverArt, number, clock(song.duration))
}

/** For screens: the padding a page's own list leaves at its sides. */
val PagePad = PaddingValues(horizontal = Theme.dimens.pagePad)
