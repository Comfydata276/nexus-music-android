package org.medianexus.music.ui.parts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.medianexus.music.ui.theme.Theme
import kotlin.math.roundToInt

/** Lists shorter than this have no handle: a flick or two gets through them. */
const val FAST_SCROLL_FROM = 60

/**
 * A handle for moving through a long list quickly (the Songs page, a long playlist, Albums,
 * Artists), made of what Compose gives. It shows at the right edge while the list moves, and
 * can then be dragged: the list follows the finger, a bubble beside it saying where in the
 * list that is (`label` of the item under it: a letter, or a month and year for a list in the
 * order songs were added). Hidden again a moment after the list comes to rest. Only the
 * handle itself takes touches, and only while it shows: the rest of the edge, and all of it
 * while hidden, is the list's. For a screen reader it is a position in the list that moves
 * up and down in steps, saying where it is as the bubble does.
 *
 * `count` items of the list, from item `first` of the lazy list (what comes before them, a
 * header, isn't moved through).
 */
@Composable
fun BoxScope.FastScroll(state: LazyListState, first: Int, count: Int, label: (Int) -> String?) {
    if (count < FAST_SCROLL_FROM) return
    val at by remember(state, first, count) { derivedStateOf { ((state.firstVisibleItemIndex - first).coerceIn(0, count - 1)).toFloat() / (count - 1) } }
    Handle(at, state.isScrollInProgress, count, { state.scrollToItem(first + it) }, label)
}

/** As above, for a grid. */
@Composable
fun BoxScope.FastScroll(state: LazyGridState, first: Int, count: Int, label: (Int) -> String?) {
    if (count < FAST_SCROLL_FROM) return
    val at by remember(state, first, count) { derivedStateOf { ((state.firstVisibleItemIndex - first).coerceIn(0, count - 1)).toFloat() / (count - 1) } }
    Handle(at, state.isScrollInProgress, count, { state.scrollToItem(first + it) }, label)
}

@Composable
private fun BoxScope.Handle(at: Float, moving: Boolean, count: Int, scrollTo: suspend (Int) -> Unit, label: (Int) -> String?) {
    val colors = Theme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var height by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // Where the finger has taken it (0 to 1), while dragging; and each place it was taken to,
    // numbered, for the list to follow (kept after the finger lifts, so the last is applied).
    var target by remember { mutableFloatStateOf(-1f) }
    var goTo by remember { mutableStateOf(0 to -1f) }
    val labelOf by rememberUpdatedState(label)
    val thumb = with(density) { THUMB.toPx() }
    // Shown while the list moves or the handle is held, and for a moment after.
    LaunchedEffect(moving, dragging) {
        if (moving || dragging) shown = true else {
            delay(HIDE_AFTER_MILLIS)
            shown = false
        }
    }
    // The list follows the finger: only to the latest place, however fast it moves.
    LaunchedEffect(count) {
        snapshotFlow { goTo }.collectLatest { (_, to) -> if (to >= 0f) scrollTo((to * (count - 1)).roundToInt().coerceIn(0, count - 1)) }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fast scroll")
    val fraction = if (dragging && target >= 0f) target else at
    val top = (fraction * (height - thumb)).coerceAtLeast(0f)
    val topNow by rememberUpdatedState(top)
    // `y` is down the whole edge, not the handle (which moves with the finger).
    fun place(y: Float) {
        if (height <= thumb) return
        target = ((y - thumb / 2) / (height - thumb)).coerceIn(0f, 1f)
        goTo = (goTo.first + 1) to target
    }
    val row = (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
    // The edge itself takes no touches: a strip over the list would take the taps meant for
    // the rows beneath it (their "…" above all). It only measures the height to move in.
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(TOUCH)
            .onSizeChanged { height = it.height.toFloat() },
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, top.roundToInt()) }
                .width(TOUCH)
                .height(THUMB)
                // For a screen reader, a position in the list that is moved up and down in
                // steps, saying where it is as the bubble does.
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(row.toFloat(), 0f..(count - 1).toFloat())
                    stateDescription = labelOf(row) ?: "${row + 1}"
                    setProgress { value ->
                        val to = value.roundToInt().coerceIn(0, count - 1)
                        scope.launch { scrollTo(to) }
                        true
                    }
                }
                // Only the handle takes touches, and only while it shows.
                .then(
                    if (!shown && !dragging) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            var y = topNow + down.position.y
                            place(y)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                // (The last movement too, on lifting.)
                                y += change.positionChange().y
                                change.consume()
                                place(y)
                                if (!change.pressed) break
                            }
                            dragging = false
                            target = -1f
                        }
                    },
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .alpha(alpha)
                    .width(if (dragging) 8.dp else 5.dp)
                    .height(THUMB)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (dragging) colors.accentBright else colors.muted),
            )
        }
    }
    // The bubble: where in the list the handle is.
    val text = if (dragging) labelOf(row) else null
    if (text != null) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-with(density) { (TOUCH + 8.dp).roundToPx() }, (top + thumb / 2 - with(density) { 28.dp.toPx() }).roundToInt().coerceAtLeast(0)) }
                .widthIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.raised)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text, style = Theme.type.heading.copy(color = colors.text), maxLines = 1)
        }
    }
}

private val THUMB = 48.dp
private val TOUCH = 32.dp
private const val HIDE_AFTER_MILLIS = 1_500L

/** The letter a name is filed under in a list's bubble: its first letter or digit, as search
 *  folds it ("É" under "E", "The Quiet Harbour" under "T"), in capitals; "#" for none. */
fun initialOf(text: String): String {
    val folded = org.medianexus.music.library.SearchRank.fold(text).trimStart()
    if (folded.isEmpty()) return "#"
    val first = folded.codePointAt(0)
    return if (Character.isDigit(first)) "#" else String(Character.toChars(first)).uppercase()
}
