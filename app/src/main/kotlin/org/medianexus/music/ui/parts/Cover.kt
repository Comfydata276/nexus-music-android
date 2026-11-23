package org.medianexus.music.ui.parts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/** How a cover's corners are drawn. */
enum class CoverShape {
    /** Cards, album pages, Now Playing: the app's 10dp corners. */
    CARD,
    /** The small covers in song rows: 4dp. */
    ROW,
    /** Artists. */
    ROUND,
}

/**
 * Album, artist or playlist artwork, sized by `modifier`. `cover` is the server's cover id;
 * an empty one has no art. Until art is drawn (and for none), a glyph stands in.
 *
 * Images arrive in a later milestone, through the cover store: this is where they'll be
 * drawn, asked for at the size shown, so nothing that shows a cover will need to change.
 */
@Composable
fun Cover(
    @Suppress("UNUSED_PARAMETER") cover: String,
    modifier: Modifier = Modifier,
    shape: CoverShape = CoverShape.CARD,
    @DrawableRes placeholder: Int = R.drawable.ic_music,
) {
    Box(
        modifier.clip(shapeOf(shape)).background(Theme.colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, Modifier.glyphSize(), tint = Theme.colors.faint)
    }
}

private val card = RoundedCornerShape(Theme.dimens.radius)
private val row = RoundedCornerShape(Theme.dimens.rowCoverRadius)

private fun shapeOf(shape: CoverShape): Shape = when (shape) {
    CoverShape.CARD -> card
    CoverShape.ROW -> row
    CoverShape.ROUND -> CircleShape
}

/** The stand-in glyph is a little over a third of the cover, within limits (as on Windows). */
private fun Modifier.glyphSize() = layout { measurable, constraints ->
    val side = (constraints.maxWidth * 0.36f).coerceIn(14.dp.toPx(), 72.dp.toPx()).toInt()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}
