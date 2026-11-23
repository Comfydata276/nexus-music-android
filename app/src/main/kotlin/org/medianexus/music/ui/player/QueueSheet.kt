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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import org.medianexus.music.playback.UpNext
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
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = colors.panel,
        contentColor = colors.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.faint) },
    ) {
        Box(Modifier.fillMaxHeight(0.92f)) { QueueList() }
    }
}

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

    // The rows as shown: the queue's, except while a song is being dragged, when they are
    // rearranged here as the finger goes and the queue is told once it lets go.
    val rows = remember { mutableStateListOf<QueueRow>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var draggedFrom by remember { mutableStateOf<UpNext?>(null) }
    LaunchedEffect(upNext) {
        if (dragging == null) {
            rows.clear()
            rows.addAll(QueueRows.of(upNext.songs, upNext.queuedAhead))
        }
    }

    fun send(row: Int, target: Int, before: UpNext) {
        // The queue moved on while the finger was down: what was dragged may no longer be
        // where it was, so nothing is changed.
        if (before !== upNext) return
        val move = QueueRows.move(row, target, QueueRows.gap(before.songs.size, before.queuedAhead), before.queuedAhead) ?: return
        player.move(move.from, move.to, move.queued)
    }

    fun drop() {
        val key = dragging ?: return
        val target = rows.indexOfFirst { it.key == key }
        val before = draggedFrom
        dragging = null
        dragOffset = 0f
        if (before != null && target >= 0) send(dragFrom, target, before)
        // Back to the queue as it is (the move, when it arrives, changes it again).
        rows.clear()
        rows.addAll(QueueRows.of(upNext.songs, upNext.queuedAhead).let { if (before != null && target >= 0 && before === upNext) arranged(it, dragFrom, target) else it })
    }

    fun dragBy(amount: Float) {
        val key = dragging ?: return
        dragOffset += amount
        val items = list.layoutInfo.visibleItemsInfo
        val info = items.firstOrNull { it.key == key } ?: return
        val middle = info.offset + dragOffset + info.size / 2f
        val keys = rows.map { it.key }.toSet()
        val over = items.firstOrNull { it.key != key && it.key in keys && middle >= it.offset && middle <= it.offset + it.size }
        if (over != null) {
            val from = rows.indexOfFirst { it.key == key }
            val to = rows.indexOfFirst { it.key == over.key }
            if (from >= 0 && to >= 0) {
                rows.add(to, rows.removeAt(from))
                dragOffset += info.offset - over.offset
            }
        }
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
            val lifted = row.key == dragging
            val place = Modifier
                .then(if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier.animateItem())
            when (row) {
                QueueRow.Gap -> Box(place.height(ROW), contentAlignment = Alignment.BottomStart) {
                    Heading(nextFrom, stringResource(R.string.clear)) { player.clear(queued = false) }
                }
                is QueueRow.Entry -> {
                    val moveTo = { target: Int ->
                        if (target in rows.indices) send(index, target, upNext)
                    }
                    SwipeAway(row.key, onGone = {
                        rows.remove(row)
                        player.remove(row.offset)
                    }, modifier = place) {
                        SongLine(
                            row.song,
                            current = false,
                            playing = false,
                            lifted = lifted,
                            onClick = { player.jump(row.offset) },
                            onMenu = { ui.menu = SongMenu(row.song, upcoming = row.offset) },
                            handle = Modifier.pointerInput(row.key) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = row.key
                                        dragFrom = rows.indexOfFirst { it.key == row.key }
                                        draggedFrom = upNext
                                        dragOffset = 0f
                                    },
                                    onDragEnd = { drop() },
                                    onDragCancel = { drop() },
                                ) { change, amount ->
                                    change.consume()
                                    dragBy(amount.y)
                                }
                            },
                            actions = listOf(
                                CustomAccessibilityAction(stringResource(R.string.move_up)) { moveTo(index - 1); true },
                                CustomAccessibilityAction(stringResource(R.string.move_down)) { moveTo(index + 1); true },
                                CustomAccessibilityAction(stringResource(R.string.remove_from_queue)) { player.remove(row.offset); true },
                            ),
                            queued = row.queued,
                        )
                    }
                }
            }
        }
    }
}

/** The rows as they will be once a move has been carried out: shown meanwhile, so the song
 *  doesn't jump back for a moment. */
private fun arranged(rows: List<QueueRow>, from: Int, to: Int): List<QueueRow> {
    if (from !in rows.indices || to !in rows.indices) return rows
    return rows.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun Heading(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = Theme.dimens.pagePad, end = 8.dp),
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
                Icon(R.drawable.ic_grip_vertical, size = 20.dp, tint = colors.faint)
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
