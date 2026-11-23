package org.medianexus.music.ui.parts

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import org.medianexus.music.ui.theme.Theme

/**
 * A bar to drag along, for a song's place or a volume: `value` runs from 0 to 1. While a
 * finger is on it the bar shows where it would go (`onMove`), and lets go with `onRelease`,
 * so a seek happens once, not at every step.
 */
@Composable
fun Slider(
    value: Float,
    label: String,
    modifier: Modifier = Modifier,
    onMove: (Float) -> Unit = {},
    onRelease: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val shown = if (dragging) dragValue else value.coerceIn(0f, 1f)
    val knob by animateDpAsState(if (dragging) 16.dp else 12.dp, label = "knob")
    val fill = if (dragging) colors.accentBright else colors.text
    fun at(x: Float, width: Int) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(Theme.dimens.touch)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        release(target.coerceIn(0f, 1f))
                        true
                    }
                }
            }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> release(at(offset.x, size.width)) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragValue = at(offset.x, size.width)
                                move(dragValue)
                            },
                            onDragEnd = {
                                dragging = false
                                release(dragValue)
                            },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            dragValue = at(change.position.x, size.width)
                            move(dragValue)
                        }
                    },
            ),
    ) {
        val track = 4.dp.toPx()
        val middle = size.height / 2
        val radius = CornerRadius(track / 2)
        drawRoundRect(colors.track, Offset(0f, middle - track / 2), Size(size.width, track), radius, alpha = if (enabled) 1f else 0.5f)
        drawRoundRect(fill, Offset(0f, middle - track / 2), Size(size.width * shown, track), radius)
        if (enabled) {
            val r = knob.toPx() / 2
            drawCircle(Color.White, r, Offset((size.width * shown).coerceIn(r, size.width - r), middle))
        }
    }
}
