package org.medianexus.music.ui.parts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.medianexus.music.ui.theme.Theme

/** A switch for a setting that takes effect at once. */
@Composable
fun Toggle(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    val fill by animateColorAsState(if (on) colors.accentBright else colors.track, tween(140), label = "toggle")
    val knob by animateDpAsState(if (on) 21.dp else 3.dp, tween(140), label = "knob")
    Box(
        modifier
            .size(width = 56.dp, height = Theme.dimens.touch)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, interactionSource = null, indication = null, onValueChange = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape).background(fill)) {
            Box(
                Modifier
                    .padding(start = knob, top = 3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.onAccent else colors.text),
            )
        }
    }
}

/**
 * A choice of one from a few, as one control: the options side by side in one track, the
 * chosen one filled in. For settings; `Chip` is for narrowing a list. With `fill`, the
 * options share the width given.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onChoose: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val colors = Theme.colors
    Row(
        modifier
            .height(Theme.dimens.touch)
            .padding(vertical = 3.dp)
            .clip(CircleShape)
            .background(colors.raised)
            .border(1.dp, colors.line, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, name ->
            val on = index == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val back by animateColorAsState(
                when {
                    on -> colors.text
                    pressed -> colors.press
                    else -> Color.Transparent
                },
                tween(120),
                label = "segment",
            )
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .clip(CircleShape)
                    .background(back)
                    .selectable(selected = on, interactionSource = source, indication = null, role = Role.RadioButton) { onChoose(index) }
                    .semantics { contentDescription = "$label: $name" }
                    .padding(horizontal = 14.dp)
                    .height(Theme.dimens.touch - 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    name,
                    style = Theme.type.chip.copy(
                        color = if (on) colors.base else colors.muted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One option in a row of choices, e.g. a sort order. Drawn 32dp tall; the space to tap it
 *  reaches the full 48. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val back by animateColorAsState(
        when {
            selected -> colors.text
            pressed -> colors.press
            else -> colors.hover
        },
        tween(120),
        label = "chip",
    )
    Box(
        modifier
            .height(Theme.dimens.touch)
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(32.dp).clip(CircleShape).background(back).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text, style = Theme.type.chip.copy(color = if (selected) colors.base else colors.text), maxLines = 1)
        }
    }
}

/**
 * Chips to choose one of a few things by, on one line. When they don't all fit, the line
 * scrolls sideways and fades out at the end that has more. Choosing a chip that's partly out
 * of sight brings it into view.
 */
@Composable
fun ChipRow(
    names: List<String>,
    selected: Int,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Theme.dimens.pagePad),
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    LazyRow(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            // Fades the ends that have more out of sight.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (list.canScrollBackward) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
                }
                if (list.canScrollForward) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                        topLeft = Offset.Zero,
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        state = list,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(names, key = { index, name -> "$index/$name" }, contentType = { _, _ -> "chip" }) { index, name ->
            Chip(name, selected = index == selected, onClick = {
                onChoose(index)
                val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                val viewport = list.layoutInfo.viewportEndOffset
                val wholly = shown != null && shown.offset >= 0 && shown.offset + shown.size <= viewport
                if (!wholly) scope.launch { list.animateScrollToItem(index, -viewport / 3) }
            })
        }
    }
}
