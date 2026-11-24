package org.medianexus.music.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.ui.menu.Entry
import org.medianexus.music.ui.menu.Rule
import org.medianexus.music.ui.menu.Title
import org.medianexus.music.ui.menu.pickPlaylist
import org.medianexus.music.ui.menu.removeFromPlaylist
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.shell.Ui
import org.medianexus.music.ui.theme.Theme

/**
 * Several songs of a list chosen together (Windows' Ctrl and Shift selection; on a phone a
 * long press starts it). **A selection belongs to the very list it was made on** (see
 * [rememberSelection]): its rows are places in that list, which never changes, and the moment
 * the list behind the page is replaced by another (a refresh, another search answer, another
 * order or find, another account) the selection goes with it. So whatever is done to the
 * selection is done to exactly the rows that were ticked, which copy of a song included.
 */
@Stable
class Selection {
    var places: Set<Int> by mutableStateOf(emptySet())
        private set

    val active: Boolean get() = places.isNotEmpty()

    fun toggle(place: Int) {
        places = if (place in places) places - place else places + place
    }

    fun clear() {
        places = emptySet()
    }
}

/** A selection of `list` (the very list, as drawn: compared by identity), dropped the moment
 *  the page draws another. */
@Composable
fun rememberSelection(list: Any?): Selection = remember(Identity(list)) { Selection() }

/** A list known by its identity, not by what it holds: two lists that read the same are still
 *  two lists, and a selection of one is not of the other. */
private class Identity(val of: Any?) {
    override fun equals(other: Any?) = other is Identity && other.of === of
    override fun hashCode() = System.identityHashCode(of)
}

/**
 * What a page puts in place of its title bar while songs are chosen: how to leave (the X, and
 * Back), how many, and Windows' actions for several songs: Add to playlist and like on the bar
 * itself, and all of them in its menu. `songs` are the chosen songs, in the list's order;
 * `inPlaylist` the playlist they are rows of, where the account may change it.
 */
@Composable
fun SelectionBar(selection: Selection, songs: () -> List<Song>, inPlaylist: Playlist? = null, top: Boolean = true) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val colors = Theme.colors
    BackHandler(enabled = selection.active) { selection.clear() }
    val count = selection.places.size
    val liked = org.medianexus.music.ui.shell.likedSongs()
    Row(
        Modifier.fillMaxWidth().background(colors.raised).then(if (top) Modifier.statusBarsPadding() else Modifier).height(Theme.dimens.touch + 8.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(R.drawable.ic_x, stringResource(R.string.leave_selection), selection::clear)
        BasicText(
            if (count == 1) res.getString(R.string.selected_count_one) else res.getString(R.string.selected_count, count),
            Modifier.weight(1f).semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = Theme.type.rowTitle.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(R.drawable.ic_list_plus, stringResource(R.string.add_to_playlist), {
            ui.pickPlaylist(songs())
            selection.clear()
        })
        val all = songs()
        val allLiked = all.isNotEmpty() && all.all { it.id in liked }
        IconButton(if (allLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart, stringResource(if (allLiked) R.string.remove_from_liked else R.string.add_to_liked), {
            ui.likeAll(songs(), !allLiked)
            selection.clear()
        }, tint = if (allLiked) colors.warm else colors.muted)
        IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more), { ui.openSelectionMenu(selection, songs(), inPlaylist) })
    }
}

/** Likes (or unlikes) several songs: only those it changes anything for, each once (Windows'
 *  "star-all"), and says how many. */
fun Ui.likeAll(songs: List<Song>, like: Boolean) {
    val likes = account?.likes ?: return
    val res = graph.resources
    val changing = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() && likes.isLiked(it.id) != like }.distinctBy { it.id }
    for (song in changing) likes.set(song, like)
    val n = changing.size
    toast(when {
        like && n == 1 -> res.getString(R.string.songs_liked_one)
        like -> res.getString(R.string.songs_liked, n)
        n == 1 -> res.getString(R.string.songs_unliked_one)
        else -> res.getString(R.string.songs_unliked, n)
    })
}

/** Windows' menu for several songs (`songs_menu`), in its order and words. */
private fun Ui.openSelectionMenu(selection: Selection, songs: List<Song>, inPlaylist: Playlist?) {
    val chosen = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }
    if (chosen.isEmpty()) return
    val places = selection.places.sorted()
    sheet = Sheet.Drawn { choose ->
        val ui = LocalUi.current
        val res = LocalResources.current
        val android = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val done = { action: () -> Unit -> choose { action(); selection.clear() } }
        Title(res.getString(R.string.songs_named, chosen.size))
        Rule()
        Entry(R.drawable.ic_list_start, stringResource(R.string.play_next)) { done { ui.player.playNext(chosen); ui.toast(res.getString(R.string.playing_next)) } }
        Entry(R.drawable.ic_list_end, stringResource(R.string.add_to_queue)) { done { ui.player.addToQueue(chosen); ui.toast(res.getString(R.string.added_to_queue)) } }
        Rule()
        Entry(R.drawable.ic_list_plus, stringResource(R.string.add_to_playlist)) { done { ui.pickPlaylist(chosen) } }
        val liked = org.medianexus.music.ui.shell.likedSongs()
        val allLiked = chosen.all { it.id in liked }
        Entry(if (allLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart, stringResource(if (allLiked) R.string.remove_from_liked else R.string.add_to_liked)) { done { ui.likeAll(chosen, !allLiked) } }
        // Whichever of these applies to at least one of the songs.
        val downloads = ui.account?.downloads
        if (downloads != null) {
            val now = org.medianexus.music.ui.downloads.downloadsNow()
            val saved = chosen.filter { now.stateOf(it.id).let { s -> s == org.medianexus.music.downloads.SongState.Done || s == org.medianexus.music.downloads.SongState.Waiting || s is org.medianexus.music.downloads.SongState.Fetching } }
            if (saved.size < chosen.size) Entry(R.drawable.ic_download, stringResource(R.string.download)) {
                done {
                    val account = ui.account ?: return@done
                    ui.askToDownload { ui.graph.scope.launch { org.medianexus.music.downloads.DownloadPages.songs(android, account, chosen)?.let(ui::toast) } }
                }
            }
            if (saved.isNotEmpty()) Entry(R.drawable.ic_circle_arrow_down, stringResource(R.string.remove_downloads)) {
                done { downloads.remove(saved.map { it.id }) { ok -> ui.toast(res.getString(if (ok) R.string.downloads_removed else R.string.couldnt_change_downloads)) } }
            }
        }
        if (inPlaylist != null) {
            Rule()
            Entry(R.drawable.ic_trash_2, stringResource(R.string.remove_from_playlist)) { done { ui.removeFromPlaylist(inPlaylist, places) } }
        }
    }
}
