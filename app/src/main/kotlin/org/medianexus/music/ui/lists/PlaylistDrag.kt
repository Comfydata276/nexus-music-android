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
 * the one it now follows (see `Playlists.move`), made on the playlist as drawn when the finger
 * went down. A drag that is cancelled changes nothing.
 * Only the rows are reordered; nothing on a page of five thousand songs is rebuilt or copied
 * for it, and a row crossed costs the same however long the playlist.
 */
@Stable
class PlaylistDrag {
    /** Where the dragged song was (`from`) and where it has been taken (`to`), as rows: the
     *  rows in the order being made are the playlist's with that one song moved, so a row
     *  crossed is one number changed, whatever the playlist's length. Null while nothing is
     *  dragged (or a drop is waiting for the playlist to have it). */
    private var moved: Pair<Int, Int>? by mutableStateOf(null)

    /** The place (in the playlist as shown) of the song being dragged. */
    var lifted: Int? by mutableStateOf(null)
        private set

    /** How far the dragged row is drawn from where the list puts it. */
    var offset by mutableFloatStateOf(0f)

    /** The place of the song at `row`: as `shown` has it, or as the drag has made it. */
    fun at(shown: IntArray, row: Int): Int {
        val (from, to) = moved ?: return shown[row]
        return shown[
            when {
                row == to -> from
                from < to && row in from until to -> row + 1
                from > to && row in to + 1..from -> row - 1
                else -> row
            },
        ]
    }

    /** A drag starts on the song at `place` (only a playlist in its own order is dragged, so
     *  its place is its row). */
    fun start(place: Int) {
        moved = place to place
        lifted = place
        offset = 0f
    }

    /** The dragged song is over the row of the song at `place`: it takes that row. */
    fun over(place: Int): Boolean {
        val (from, to) = moved ?: return false
        if (lifted == null || place == from) return false
        // That song's row now: its own place, give or take the one the dragged song left.
        val row = when {
            from < to && place in from + 1..to -> place - 1
            from > to && place in to until from -> place + 1
            else -> place
        }
        if (row == to) return false
        moved = from to row
        return true
    }

    /** Dropped: from where to where, or null for no move. A move keeps the rows in their
     *  new order until the playlist itself has it ([reset], when its songs change), so they
     *  don't spring back for a frame. */
    fun drop(): Pair<Int, Int>? {
        val moving = lifted
        val to = moved?.second
        if (moving == null || to == null || to == moving) {
            reset()
            return null
        }
        lifted = null
        offset = 0f
        return moving to to
    }

    fun reset() {
        moved = null
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
fun Modifier.dragRows(drag: PlaylistDrag, list: LazyListState, place: Int, moved: (Int, Int) -> Boolean): Modifier {
    val scope = rememberCoroutineScope()
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val onMoved by rememberUpdatedState(moved)
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
            // The drop is a move on the playlist as it was drawn when the finger went down,
            // whatever has been drawn since.
            val dropped = onMoved
            drag.start(place)
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
                // A move that isn't made lets the rows go back: what is drawn is always what a tap
                // would play (the playlist as shown, with the move once it is made).
                if (finished) drag.drop()?.let { (from, to) -> if (!dropped(from, to)) drag.reset() } else drag.reset()
            }
        }
    }
}
