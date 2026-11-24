package org.medianexus.music.ui.lists

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.theme.Theme

/**
 * A playlist's song being dragged to another place, as the queue sheet's songs are: while the
 * finger is down the rows are shown in the order being made (each still named by its place in
 * the playlist as shown, so nothing else is disturbed), and only the drop is a change: the song
 * at one place moved to another, which goes to the playlist's owner as a move by the song and
 * the one it now follows (see `Playlists.move`). A drag that is cancelled changes nothing.
 * Only the rows are reordered; nothing on a page of five thousand songs is rebuilt for it but
 * the rows that move.
 */
@Stable
class PlaylistDrag {
    /** The playlist's places in the order being made, while a song is dragged. */
    private var order: IntArray? by mutableStateOf(null)

    /** The place (in the playlist as shown) of the song being dragged. */
    var lifted: Int? by mutableStateOf(null)
        private set

    /** How far the dragged row is drawn from where the list puts it. */
    var offset by mutableFloatStateOf(0f)

    /** The place of the song at `row`: as `shown` has it, or as the drag has made it. */
    fun at(shown: IntArray, row: Int): Int = order?.getOrNull(row) ?: shown[row]

    fun start(place: Int, count: Int) {
        order = IntArray(count) { it }
        lifted = place
        offset = 0f
    }

    /** The dragged song is over the row of the song at `place`: they change places. */
    fun over(place: Int): Boolean {
        val now = order ?: return false
        val moving = lifted ?: return false
        val from = now.indexOf(moving)
        val to = now.indexOf(place)
        if (from < 0 || to < 0 || from == to) return false
        val next = now.copyOf()
        val song = next[from]
        if (from < to) System.arraycopy(now, from + 1, next, from, to - from) else System.arraycopy(now, to, next, to + 1, from - to)
        next[to] = song
        order = next
        return true
    }

    /** Dropped: from where to where, or null for no move. A move keeps the rows in their
     *  new order until the playlist itself has it ([reset], when its songs change), so they
     *  don't spring back for a frame. */
    fun drop(): Pair<Int, Int>? {
        val moving = lifted
        val to = order?.indexOf(moving ?: -1) ?: -1
        if (moving == null || to < 0 || to == moving) {
            reset()
            return null
        }
        lifted = null
        offset = 0f
        return moving to to
    }

    fun reset() {
        order = null
        lifted = null
        offset = 0f
    }
}

/** What a playlist's song is dragged by (Lucide's grip-vertical, as in the queue). */
@Composable
fun DragHandle(title: String, modifier: Modifier) {
    val label = stringResource(R.string.drag_to_move_in_playlist, title)
    Box(modifier.size(Theme.dimens.touch).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(R.drawable.ic_grip_vertical, size = 20.dp, tint = Theme.colors.muted)
    }
}

/**
 * The drag itself, on a row's handle: the row follows the finger, the rows it passes change
 * places with it, the list scrolls when it nears an end, and the drop is `moved` (from, to).
 * Song rows are told apart from the rest of the page by their keys ("<place>/<id>").
 */
@Composable
fun Modifier.dragRows(drag: PlaylistDrag, list: LazyListState, place: Int, count: Int, moved: (Int, Int) -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val onMoved by rememberUpdatedState(moved)
    val size by rememberUpdatedState(count)
    fun placeOf(key: Any): Int? = (key as? String)?.substringBefore('/', "")?.toIntOrNull()
    fun dragBy(amount: Float) {
        val moving = drag.lifted ?: return
        drag.offset += amount
        val items = list.layoutInfo.visibleItemsInfo
        val info = items.firstOrNull { placeOf(it.key) == moving } ?: return
        val middle = info.offset + drag.offset + info.size / 2f
        val over = items.firstOrNull { item -> placeOf(item.key).let { it != null && it != moving } && middle >= item.offset && middle <= item.offset + item.size }
        if (over != null && drag.over(placeOf(over.key)!!)) drag.offset += info.offset - over.offset
        val viewport = list.layoutInfo.viewportEndOffset
        val step = when {
            middle < edge -> -edge / 6
            middle > viewport - edge -> edge / 6
            else -> 0f
        }
        if (step != 0f) scope.launch { drag.offset += list.scrollBy(step) }
    }
    // The handle takes the touch from the moment it is pressed, so the row under it neither
    // plays nor starts a selection (its long press), however long the finger rests before
    // moving: and so the drag goes with the finger at once.
    return pointerInput(place) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            drag.start(place, size)
            var finished = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        finished = true
                        break
                    }
                    val dy = change.positionChange().y
                    change.consume()
                    if (dy != 0f) dragBy(dy)
                }
            } finally {
                if (finished) drag.drop()?.let { (from, to) -> onMoved(from, to) } else drag.reset()
            }
        }
    }
}
