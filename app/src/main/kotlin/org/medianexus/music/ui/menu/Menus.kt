package org.medianexus.music.ui.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.shell.Ui
import org.medianexus.music.ui.theme.Theme

// The menus of albums, artists and playlists, and the pickers and questions the menus open:
// Windows' `collection_menu`, `show_playlist_picker`, `show_rating_picker`, its prompts and
// the warning before adding a song that is already there (src/actions.rs), in its order and
// words, with what exists on the phone.

/** What a collection's page drew (its songs in the order shown) and its play button, for
 *  the collection's menu opened from that page. */
class FromPage(val songs: List<Song>, val play: () -> Unit)

/** What a collection menu is about (Windows' `Target`). */
sealed interface Collection {
    val id: String
    val title: String

    data class Album(override val id: String, override val title: String, val artist: String = "", val cover: String = "", override val page: FromPage? = null) : Collection
    data class Artist(override val id: String, override val title: String) : Collection
    data class Playlist(override val id: String, override val title: String, val playlist: org.medianexus.music.net.Playlist? = null, override val page: FromPage? = null) : Collection

    /** Opened from the collection's own page: what the page drew, in the order drawn, and its
     *  play button. The menu plays and queues that, not a fresh read of the collection. */
    val page: FromPage? get() = null

    /** Its page's route key, where it has one. */
    val key: String? get() = when (this) {
        is Album -> "album:$id"
        is Playlist -> "playlist:$id"
        is Artist -> null
    }
}

/**
 * The songs a collection stands for (Windows' `with_songs`): an album's or a playlist's as the
 * phone has them (kept, else the server's), an artist's first forty releases'. Null, said, if
 * they can't be had; an empty list is said too ("There are no songs in that.").
 */
suspend fun Ui.songsOf(target: Collection): List<Song>? {
    val res = graph.resources
    val library = account?.library ?: return null
    val songs = try {
        when (target) {
            is Collection.Album -> library.quick(library.album(target.id)).songs
            is Collection.Playlist -> library.quick(library.playlist(target.id)).songs
            is Collection.Artist -> {
                val artist = library.quick(library.artist(target.id))
                artist.albums.sortedWith(compareByDescending<org.medianexus.music.net.Album> { it.year }.thenBy { it.name }).take(40).flatMap { library.quick(library.album(it.id)).songs }
            }
        }
    } catch (e: ApiError) {
        toast(e.message.orEmpty())
        return null
    }
    if (songs.isEmpty()) {
        toast(res.getString(R.string.there_are_no_songs))
        return null
    }
    return songs
}

/** Opens a collection's menu. */
fun Ui.openMenu(target: Collection) {
    sheet = Sheet.Drawn { choose -> CollectionMenu(target, choose) }
}

@Composable
private fun CollectionMenu(target: Collection, choose: (() -> Unit) -> Unit) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val android = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val settings = ui.graph.settings.state.collectAsStateWithLifecycle().value
    Title(target.title)
    Rule()
    // Opened from its own page: what the page drew. Otherwise looked up, and acted on, on the
    // main thread, only if nothing has been chosen since (see PlayerConnection.afterLookup).
    val page = target.page
    val songs: suspend () -> List<Song>? = { page?.songs ?: ui.songsOf(target) }
    Entry(R.drawable.ic_play_fill, stringResource(R.string.play)) {
        choose {
            if (page != null) page.play()
            else ui.player.afterLookup(ui.graph.scope, songs) { ui.play(it, 0, target.title, target.key.orEmpty(), anyStart = true) }
        }
    }
    Entry(R.drawable.ic_list_start, stringResource(R.string.play_next)) {
        choose {
            ui.player.afterLookup(ui.graph.scope, songs) {
                ui.player.playNext(it)
                ui.toast(res.getString(R.string.playing_next))
            }
        }
    }
    Entry(R.drawable.ic_list_end, stringResource(R.string.add_to_queue)) {
        choose {
            ui.player.afterLookup(ui.graph.scope, songs) {
                ui.player.addToQueue(it)
                ui.toast(res.getString(R.string.added_to_queue))
            }
        }
    }
    val download = {
        choose {
            val account = ui.account ?: return@choose
            val key = target.key ?: return@choose
            ui.askToDownload {
                ui.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    val (list, songs) = org.medianexus.music.downloads.DownloadPages.listFor(account, key, res.getString(R.string.liked_songs)) ?: return@launch ui.toast(res.getString(R.string.cant_reach_server))
                    val asked = account.downloads?.download(songs, list) ?: return@launch
                    ui.toast(org.medianexus.music.downloads.DownloadPages.said(android, asked))
                }
            }
        }
    }
    when (target) {
        is Collection.Album -> {
            Rule()
            Entry(R.drawable.ic_list_plus, stringResource(R.string.add_to_playlist)) {
                choose { ui.graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { songs()?.let { ui.pickPlaylist(it) } } }
            }
            if (ui.account?.downloads != null) Entry(R.drawable.ic_download, stringResource(R.string.download), onClick = download)
        }
        is Collection.Playlist -> {
            Rule()
            val pinned = target.id in settings.pinnedPlaylists
            Entry(R.drawable.ic_pin, stringResource(if (pinned) R.string.unpin else R.string.pin_to_top)) { choose { ui.togglePin(target.id) } }
            if (ui.account?.downloads != null) Entry(R.drawable.ic_download, stringResource(R.string.download), onClick = download)
            // (From a card, the playlist as the kept list of playlists has it: whose it is.)
            val known = target.playlist ?: org.medianexus.music.ui.shell.rememberPageFeed("playlists") { it.playlists() }.value?.firstOrNull { it.id == target.id }
            // Offered only when it is known to be the account's own: not while its record
            // hasn't loaded (and checked again as each change is sent).
            when {
                known == null -> {}
                ui.account?.playlists?.canEdit(known) == true -> {
                    Entry(R.drawable.ic_pencil, stringResource(R.string.rename)) { choose { ui.askRename(known) } }
                    Entry(R.drawable.ic_trash_2, stringResource(R.string.delete_playlist), danger = true) { choose { ui.askDelete(known) } }
                }
                else -> Note(stringResource(R.string.not_your_playlist, known.owner))
            }
        }
        is Collection.Artist -> {}
    }
}

/** A card's menu (Windows' `open_card_menu`): an album's, a playlist's or an artist's. */
fun openCardMenu(ui: Ui, card: org.medianexus.music.ui.parts.CardData) {
    when (card.kind) {
        org.medianexus.music.ui.parts.CardKind.ALBUM -> ui.openMenu(Collection.Album(card.id, card.title, card.subtitle, card.cover))
        org.medianexus.music.ui.parts.CardKind.PLAYLIST -> ui.openMenu(Collection.Playlist(card.id, card.title))
        org.medianexus.music.ui.parts.CardKind.ARTIST -> ui.openMenu(Collection.Artist(card.id, card.title))
        else -> {}
    }
}

/** Pins a playlist to the top of Library, or unpins it (Windows' "Pin to top"). */
fun Ui.togglePin(id: String) {
    graph.settings.update { s ->
        val was = id in s.pinnedPlaylists
        s.copy(pinnedPlaylists = s.pinnedPlaylists.filter { it != id } + if (was) emptyList() else listOf(id), pinsAt = System.currentTimeMillis() / 1000)
    }
}

// ---- Playlists: the picker, new, rename, delete, the warning before adding ------------------

/** "Add to playlist": New playlist, then the account's own playlists (Windows' picker). */
fun Ui.pickPlaylist(songs: List<Song>) {
    val wanted = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }
    if (wanted.isEmpty() || !said(account?.playlists?.reachableNow())) return
    sheet = Sheet.Drawn { choose -> PlaylistPicker(wanted, choose) }
}

@Composable
private fun PlaylistPicker(songs: List<Song>, choose: (() -> Unit) -> Unit) {
    val ui = LocalUi.current
    val feed = org.medianexus.music.ui.shell.rememberPageFeed("playlists") { it.playlists() }
    val settings = ui.graph.settings.state.collectAsStateWithLifecycle().value
    val playlists = remember(feed.value, settings.pinnedPlaylists) {
        val own = feed.value.orEmpty().filter { ui.account?.playlists?.canEdit(it) == true }
        org.medianexus.music.library.SearchRank.playlistsInOrder(own, settings.pinnedPlaylists)
    }
    Title(stringResource(R.string.add_to_playlist))
    Rule()
    Entry(R.drawable.ic_plus, stringResource(R.string.new_playlist)) { choose { ui.askNewPlaylist(songs) } }
    if (playlists.isNotEmpty()) Rule()
    for (playlist in playlists) {
        Entry(R.drawable.ic_list_music, playlist.name) { choose { ui.addToPlaylist(playlist, songs) } }
    }
}

/** Says why a playlist can't be changed now, if it can't; true if it can. */
fun Ui.said(unavailable: org.medianexus.music.library.Playlists.Unavailable?): Boolean {
    unavailable ?: return true
    val words = when (unavailable) {
        org.medianexus.music.library.Playlists.Unavailable.OFFLINE -> R.string.playlists_need_server
        org.medianexus.music.library.Playlists.Unavailable.NOT_YOURS -> R.string.playlist_not_changeable
        org.medianexus.music.library.Playlists.Unavailable.BUSY -> R.string.playlist_busy
        org.medianexus.music.library.Playlists.Unavailable.NOTHING_TO_UNDO -> null
        org.medianexus.music.library.Playlists.Unavailable.MISSING_SONGS -> R.string.playlist_missing_songs
        org.medianexus.music.library.Playlists.Unavailable.RESERVED_NAME -> R.string.playlist_name_reserved
    }
    words?.let { toast(graph.resources.getString(it)) }
    return false
}

/**
 * Adds songs to a playlist, first asking what to do about any already in it, as the server has
 * the playlist now (Windows' `add_to_playlist`, its words and choices: the cautious answer is
 * the highlighted one). `done` is told whether the server took the songs (never if none were
 * added: the question answered "Don't add", or nothing could be sent).
 */
fun Ui.addToPlaylist(playlist: Playlist, songs: List<Song>, done: () -> Unit = {}) {
    val account = account ?: return
    val res = graph.resources
    if (account.playlists.canEdit(playlist).not()) return run { said(org.medianexus.music.library.Playlists.Unavailable.NOT_YOURS) }
    graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
        if (!said(account.playlists.reachableNow())) return@launch
        // Asked of the server's copy now; if it can't be read, nothing is added.
        val there = account.playlists.alreadyIn(playlist.id, songs) ?: return@launch toast(res.getString(R.string.playlist_unsent))
        val fresh = songs.filter { it.id !in there }
        val repeats = songs.size - fresh.size
        fun add(these: List<Song>) {
            said(account.playlists.add(playlist, these) { taken ->
                if (taken) {
                    toast(res.getString(R.string.added_to_named, playlist.name))
                    done()
                }
            })
        }
        if (repeats == 0) return@launch add(songs)
        val name = playlist.name
        val (title, message, addAll, skip) = when {
            songs.size == 1 -> listOf(res.getString(R.string.already_added), res.getString(R.string.already_in_one, name), res.getString(R.string.add_anyway), res.getString(R.string.dont_add))
            fresh.isEmpty() -> listOf(res.getString(R.string.already_added), res.getString(R.string.already_in_all, name), res.getString(R.string.add_anyway), res.getString(R.string.dont_add))
            else -> listOf(res.getString(R.string.some_already_added), res.getString(R.string.already_in_some, repeats, songs.size, name), res.getString(R.string.add_all), res.getString(R.string.add_new_ones))
        }
        sheet = Sheet.Drawn { choose ->
            Words(title, message)
            Buttons {
                TextButton(addAll, onClick = { choose { add(songs) } })
                TextButton(skip, primary = true, onClick = { choose { if (fresh.isNotEmpty()) add(fresh) } })
            }
        }
    }
}

/** "New playlist": a name, then made with these songs (none, from Library's own button). */
fun Ui.askNewPlaylist(songs: List<Song>) {
    val res = graph.resources
    if (!said(account?.playlists?.reachableNow())) return
    sheet = Sheet.Drawn { choose ->
        Ask(stringResource(R.string.name_your_playlist), "", stringResource(R.string.create_playlist), choose) { name ->
            val account = account ?: return@Ask
            if (said(account.playlists.create(name, songs) { taken -> if (taken) toast(res.getString(R.string.created_named, name)) })) Unit
        }
    }
}

fun Ui.askRename(record: Playlist) {
    if (!said(account?.playlists?.reachableNow())) return
    sheet = Sheet.Drawn { choose ->
        Ask(stringResource(R.string.rename_playlist), record.name, stringResource(R.string.save), choose) { name -> said(account?.playlists?.rename(record, name)) }
    }
}

/** Windows' question before deleting a playlist; once the server has deleted it, the
 *  playlist's page (if open) goes. */
fun Ui.askDelete(record: Playlist) {
    val res = graph.resources
    if (!said(account?.playlists?.reachableNow())) return
    sheet = Sheet.Confirm(res.getString(R.string.delete_playlist_title), res.getString(R.string.delete_playlist_detail, record.name), res.getString(R.string.delete_playlist)) {
        said(account?.playlists?.delete(record) { taken ->
            if (!taken) return@delete
            toast(res.getString(R.string.playlist_deleted))
            if ((navigator.visible.lastOrNull()?.route as? Route.Playlist)?.id == record.id) navigator.back()
        })
    }
}

/** Takes rows of a playlist (as drawn) out; once the server has, "Undo" on the toast
 *  (Windows' `remove_rows_from_playlist`). */
fun Ui.removeFromPlaylist(playlist: Playlist, rows: List<Int>) {
    val account = account ?: return
    val res = graph.resources
    val count = rows.distinct().count { it in playlist.songs.indices }
    if (count == 0) return
    said(account.playlists.remove(playlist, rows) { taken ->
        if (!taken) return@remove
        val words = if (count == 1) res.getString(R.string.removed_from_playlist) else res.getString(R.string.removed_many_from_playlist, count)
        graph.toasts.show(words, res.getString(R.string.undo)) { undoPlaylist(playlist.id) }
    })
}

/** Moves a playlist's song from one row to another (the playlist in its own order); once the
 *  server has, "Undo" on the toast (Windows' `move_playlist_track`). False if it wasn't
 *  begun (and said why). */
fun Ui.movePlaylistSong(playlist: Playlist, from: Int, to: Int): Boolean {
    val account = account ?: return false
    val res = graph.resources
    if (from == to) return false
    return said(account.playlists.move(playlist, from, to) { taken ->
        if (taken) graph.toasts.show(res.getString(R.string.moved), res.getString(R.string.undo)) { undoPlaylist(playlist.id) }
    })
}

/** Windows' `undo_last`: "Put back", once the server has. */
fun Ui.undoPlaylist(id: String) {
    said(account?.playlists?.undoLast(id) { taken -> if (taken) toast(graph.resources.getString(R.string.put_back)) })
}

/** "Save queue as playlist": the songs in the queue at the tap, in the order they play
 *  (`fromNowOn`, taken then), made into songs away from the main thread. */
fun Ui.saveQueueAsPlaylist(fromNowOn: () -> List<Song>) {
    val who = account
    graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
        val wanted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { fromNowOn().filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() } }
        if (wanted.isNotEmpty() && account === who) askNewPlaylist(wanted)
    }
}

/** A question with a name to type (Windows' prompt with text). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Ask(title: String, initial: String, confirm: String, choose: (() -> Unit) -> Unit, onConfirm: (String) -> Unit) {
    val box = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        box.setTextAndPlaceCursorAtEnd(initial)
        focus.requestFocus()
    }
    val go = {
        val name = box.text.toString().trim()
        if (name.isNotEmpty()) choose { onConfirm(name) }
    }
    Words(title, "")
    Field(
        box, stringResource(R.string.playlist_name),
        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad).focusRequester(focus),
        onKeyboardAction = { go() },
    )
    // With the keyboard up (in landscape it leaves little room), the sheet scrolls the buttons
    // into view, so the choice is on screen rather than under the keys.
    val buttons = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) {
        if (keyboard) {
            // (Once the sheet has been laid out above the keyboard.)
            kotlinx.coroutines.delay(KEYBOARD_SETTLE_MILLIS)
            buttons.bringIntoView()
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().bringIntoViewRequester(buttons)) {
        Buttons {
            TextButton(stringResource(R.string.cancel), onClick = { choose {} })
            TextButton(confirm, primary = true, onClick = go)
        }
    }
}

/** How long the keyboard takes to finish opening before the name sheet's buttons are brought
 *  into view. */
private const val KEYBOARD_SETTLE_MILLIS = 300L

// ---- Ratings ------------------------------------------------------------------------------

/** What a song's menu says of its rating (Windows' words). */
@Composable
fun ratingLabel(rating: Int): String = when (rating) {
    0 -> stringResource(R.string.rate)
    1 -> stringResource(R.string.rated_one)
    else -> stringResource(R.string.rated_stars, rating)
}

/** The rating picker: five stars down to one, then "No rating", the one in force ticked. */
fun Ui.pickRating(song: Song) {
    sheet = Sheet.Drawn { choose ->
        val account = LocalUi.current.account
        val now = account?.ratings?.of(song) ?: song.userRating
        val res = LocalResources.current
        Title(stringResource(R.string.rate))
        Rule()
        for (stars in 5 downTo 1) {
            Choice(if (stars == 1) stringResource(R.string.rating_one) else stringResource(R.string.rating_stars, stars), stars == now) { choose { rate(song, stars, res) } }
        }
        Rule()
        Choice(stringResource(R.string.no_rating), now == 0) { choose { rate(song, 0, res) } }
    }
}

private fun Ui.rate(song: Song, stars: Int, res: android.content.res.Resources) {
    account?.ratings?.rate(song, stars)
    toast(when (stars) {
        0 -> res.getString(R.string.rating_removed)
        1 -> res.getString(R.string.rated_one)
        else -> res.getString(R.string.rated_stars, stars)
    })
}

// ---- Radio --------------------------------------------------------------------------------

/** "Start radio from this song": the song now, and others like it after (Windows'
 *  `play_radio`; the songs are found by the player, see `Autoplay`). */
fun Ui.startRadio(song: Song) {
    if (song.id.isEmpty() || song.radioUrl.isNotEmpty()) return
    player.playRadio(song, graph.resources.getString(R.string.song_radio, song.title))
}

// ---- The parts these sheets are made of ---------------------------------------------------

@Composable
internal fun Title(text: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp).semantics { heading() }, style = Theme.type.rowTitle.copy(color = Theme.colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Words(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = Theme.colors.text))
        if (detail.isNotEmpty()) BasicText(detail, style = Theme.type.body.copy(color = Theme.colors.muted))
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 12.dp), style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    // (A button too wide to sit beside the other, with large type on a narrow phone, goes
    // under it rather than being cut short.)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** One entry of a menu: its icon and words, the whole row a 52dp button. */
@Composable
internal fun Entry(@DrawableRes icon: Int, text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, size = 20.dp, tint = if (danger) Theme.colors.danger else Theme.colors.muted)
        BasicText(text, style = Theme.type.body.copy(color = if (danger) Theme.colors.danger else Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One of a picker's choices, the one in force ticked. */
@Composable
private fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = chosen }
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(R.drawable.ic_check, size = 20.dp, tint = Theme.colors.accentBright)
        }
        BasicText(text, style = Theme.type.body.copy(color = if (chosen) Theme.colors.text else Theme.colors.muted, fontSize = Theme.type.rowTitle.fontSize))
    }
}

@Composable
internal fun Rule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Theme.colors.line))
}
