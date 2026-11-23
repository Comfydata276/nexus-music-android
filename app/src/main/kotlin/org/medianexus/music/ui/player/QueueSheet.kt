package org.medianexus.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.LinkText
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.theme.Theme

/**
 * The queue, as a sheet over Now Playing, laid out as the Windows queue panel: what's
 * playing, then "Next in queue" (the songs queued by hand, with "Clear queue"), then the rest
 * of the list. Tap a song to play it now; drag it by its handle to move it, across the
 * heading into "Next in queue" or out of it; swipe it away, or choose "Remove from queue"
 * from its menu, to take it out. Screen readers get "Move up", "Move down" and "Remove" on
 * each song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onClose: () -> Unit) {
    val colors = Theme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The sheet comes up to the foot of Now Playing's top bar and no further, so what's
    // behind it ("Playing from …") is either all there or not there at all.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val window = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // The sheet keeps the navigation bar's height clear below what it holds.
    val navigation = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val height = (window - statusBar - NOW_PLAYING_BAR - SHEET_HANDLE - navigation).coerceAtLeast(240.dp)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = colors.panel,
        contentColor = colors.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.faint) },
    ) {
        Box(Modifier.height(height)) { QueueList() }
    }
}

/** Now Playing's top bar, and the sheet's own handle above its content. */
private val NOW_PLAYING_BAR = 56.dp
private val SHEET_HANDLE = 48.dp

private val ROW = 64.dp

@Composable
private fun QueueList() {
    val ui = LocalUi.current
    val player = ui.player
    val colors = Theme.colors
    val upNext by player.queue.collectAsStateWithLifecycle()
    val song by player.song.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

    // The rows as shown: the queue's, except while a song is being dragged (see QueueDrag).
    val drag = remember { QueueDrag() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(upNext) { drag.show(upNext) }
    // A move that brings no new queue within a moment was refused or changed nothing: the
    // player's order is shown again.
    val awaiting = drag.awaiting
    LaunchedEffect(awaiting) {
        if (awaiting == null) return@LaunchedEffect
        kotlinx.coroutines.delay(SETTLE_MILLIS)
        drag.settle()
    }
    val rows = drag.rows

    fun send(move: org.medianexus.music.ui.player.EntryMove?) {
        if (move != null) player.move(move.uid, move.after, move.queued)
    }

    fun dragBy(amount: Float) {
        val key = drag.dragging ?: return
        dragOffset += amount
        val items = list.layoutInfo.visibleItemsInfo
        val info = items.firstOrNull { it.key == key } ?: return
        val middle = info.offset + dragOffset + info.size / 2f
        // Only the queue's own rows (keys "u…" and the heading between them) change places.
        val over = items.firstOrNull { it.key != key && isRowKey(it.key) && middle >= it.offset && middle <= it.offset + it.size }
        if (over != null && drag.over(over.key as String)) dragOffset += info.offset - over.offset
        // Near an end of the list, it scrolls to bring more into reach.
        val viewport = list.layoutInfo.viewportEndOffset
        val step = when {
            middle < edge -> -edge / 6
            middle > viewport - edge -> edge / 6
            else -> 0f
        }
        if (step != 0f) scope.launch { dragOffset += list.scrollBy(step) }
    }

    val nextInQueue = stringResource(R.string.next_in_queue)
    val nextFrom = if (upNext.context.isEmpty()) stringResource(R.string.next_up) else stringResource(R.string.next_from, upNext.context)
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val removeWord = stringResource(R.string.remove_from_queue)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title", contentType = "title") {
            BasicText(
                stringResource(R.string.queue),
                Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp).semantics { heading() },
                style = Theme.type.heading.copy(color = colors.text),
            )
        }
        val now = song
        if (now == null) {
            item(key = "nothing", contentType = "note") {
                BasicText(stringResource(R.string.queue_nothing), Modifier.padding(Theme.dimens.pagePad), style = Theme.type.body.copy(color = colors.muted))
            }
            return@LazyColumn
        }
        item(key = "now-heading", contentType = "heading") { Heading(stringResource(R.string.now_playing)) }
        item(key = "now", contentType = "song") {
            SongLine(now, current = true, playing = playing, onClick = player::playPause, onMenu = { ui.menu = SongMenu(now) })
        }
        item(key = "next-heading", contentType = "heading") {
            when {
                upNext.songs.isEmpty() -> Heading(stringResource(R.string.nothing_queued))
                upNext.queuedAhead > 0 -> Heading(nextInQueue, stringResource(R.string.clear_queue)) { player.clear(queued = true) }
                else -> Heading(nextFrom, stringResource(R.string.clear)) { player.clear(queued = false) }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> if (row is QueueRow.Gap) "gap" else "song" }) { index, row ->
            val lifted = row.key == drag.dragging
            val place = Modifier
                .then(if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier.animateItem())
            when (row) {
                QueueRow.Gap -> Box(place.height(ROW), contentAlignment = Alignment.BottomStart) {
                    Heading(nextFrom, stringResource(R.string.clear)) { player.clear(queued = false) }
                }
                is QueueRow.Entry -> {
                    // For screen readers: a step up or down, against the rows as shown. Only
                    // offered where it does something.
                    val stepTo = { target: Int ->
                        val snapshot = upNext
                        val rowsNow = QueueRows.of(snapshot.songs, snapshot.queuedAhead, snapshot.uids)
                        val at = rowsNow.indexOfFirst { it.key == row.key }
                        if (at >= 0 && target in rowsNow.indices) send(QueueRows.entryMove(snapshot, at, target))
                    }
                    val actions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUp) { stepTo(index - 1); true })
                        if (index < rows.lastIndex) add(CustomAccessibilityAction(moveDown) { stepTo(index + 1); true })
                        add(CustomAccessibilityAction(removeWord) { player.remove(row.uid); true })
                    }
                    SwipeAway(row.key, onGone = {
                        drag.removeNow(row.key)
                        player.remove(row.uid)
                    }, modifier = place) {
                        SongLine(
                            row.song,
                            current = false,
                            playing = false,
                            lifted = lifted,
                            onClick = { player.jump(row.uid) },
                            onMenu = { ui.menu = SongMenu(row.song, upcoming = row.uid) },
                            handle = Modifier.pointerInput(row.key) {
                                detectDragGestures(
                                    onDragStart = {
                                        drag.start(row.key)
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        dragOffset = 0f
                                        send(drag.drop())
                                    },
                                    onDragCancel = {
                                        dragOffset = 0f
                                        drag.cancel()
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragBy(amount.y)
                                }
                            },
                            actions = actions,
                            queued = row.queued,
                        )
                    }
                }
            }
        }
    }
}

/** Whether a list key is one of the queue's own rows. */
private fun isRowKey(key: Any): Boolean = key is String && (key == "gap" || (key.length > 1 && key[0] == 'u'))

/** How long a move sent may take to come back as a new queue before the sheet takes it that
 *  nothing changed. */
private const val SETTLE_MILLIS = 1_000L

@Composable
private fun Heading(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Theme.dimens.touch).padding(start = Theme.dimens.pagePad, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text,
            Modifier.weight(1f).semantics { heading() },
            style = Theme.type.label.copy(color = Theme.colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) LinkText(action, onAction)
    }
}

/** One song in the queue: cover, title (in the accent if it's playing), artist, length, its
 *  menu, and for songs to come a handle to drag it by (`handle` is what makes it one). */
@Suppress("ModifierParameter")
@Composable
private fun SongLine(
    song: Song,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    lifted: Boolean = false,
    queued: Boolean = false,
    handle: Modifier? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = Theme.colors
    val queuedWord = stringResource(R.string.in_next_in_queue)
    val playingWord = stringResource(if (playing) R.string.track_playing else R.string.track_paused)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW)
            .background(if (lifted) colors.overlay else colors.panel)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (current) (if (playing) R.string.pause else R.string.play) else R.string.play), onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = playingWord else if (queued) stateDescription = queuedWord
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(song.coverArt, Modifier.size(Theme.dimens.rowCover), CoverShape.ROW)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(song.title, style = Theme.type.rowTitle.copy(color = if (current) colors.accentBright else colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(song.artistLabel, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // On a narrow screen the length gives way to the name, as Windows' gives way to the
        // menu button.
        if (song.duration > 0 && with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() } >= 400.dp) {
            BasicText(clock(song.duration), style = Theme.type.rowDetail.copy(color = colors.muted))
        }
        IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, song.title), onMenu, iconSize = 20.dp)
        if (handle != null) {
            val label = stringResource(R.string.drag_to_move, song.title)
            Box(handle.size(Theme.dimens.touch).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(R.drawable.ic_grip_vertical, size = 20.dp, tint = colors.muted)
            }
        }
    }
}

/** A song that can be swiped sideways out of the queue. */
@Composable
private fun SwipeAway(key: String, onGone: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = Theme.colors
    val scope = rememberCoroutineScope()
    val shift = remember(key) { Animatable(0f) }
    Box(modifier.fillMaxWidth().background(colors.danger.copy(alpha = 0.85f))) {
        Row(Modifier.matchParentSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shift.value > 0) Icon(R.drawable.ic_trash_2, size = 22.dp, tint = colors.base)
            Box(Modifier.weight(1f))
            if (shift.value < 0) Icon(R.drawable.ic_trash_2, size = 22.dp, tint = colors.base)
        }
        Box(
            Modifier
                .graphicsLayer { translationX = shift.value }
                .alpha(1f - (abs(shift.value) / 1200f).coerceIn(0f, 0.5f))
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(shift.value) > size.width * 0.4f) {
                                scope.launch {
                                    shift.animateTo(if (shift.value > 0) size.width.toFloat() else -size.width.toFloat(), tween(160))
                                    onGone()
                                }
                            } else {
                                scope.launch { shift.animateTo(0f, tween(180)) }
                            }
                        },
                        onDragCancel = { scope.launch { shift.animateTo(0f, tween(180)) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { shift.snapTo(shift.value + amount) }
                    }
                },
        ) { content() }
    }
}
