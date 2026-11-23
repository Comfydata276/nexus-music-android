package org.medianexus.music.ui.parts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
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
    /** Set into something that has corners of its own (Home's quick tiles). */
    SQUARE,
}

/**
 * Album, artist or playlist artwork, sized by `modifier`. `cover` is the server's cover id;
 * an empty one has no art. Until art is drawn (and for none), a glyph stands in.
 *
 * The picture comes from the cover store through Coil (see `coverImageLoader`), decoded at
 * the size it's shown. `large` asks for Now Playing's larger copy, showing the usual one
 * until it arrives.
 */
@Composable
fun Cover(
    cover: String,
    modifier: Modifier = Modifier,
    shape: CoverShape = CoverShape.CARD,
    @DrawableRes placeholder: Int = R.drawable.ic_music,
    large: Boolean = false,
) {
    Box(
        modifier.clip(shapeOf(shape)).background(Theme.colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        var shown by remember(cover) { mutableStateOf(false) }
        // Now Playing's large copy, once it has drawn: the usual one was only standing in
        // for it, and leaves, so the two aren't both decoded and held for one place.
        var largeShown by remember(cover) { mutableStateOf(false) }
        // Either copy that failed is asked for again when the server can be reached again
        // (`CoverRetry`). Listened for only while one has failed, and only while the app is in
        // front: a screenful of covers that loaded holds no subscription at all.
        var failed by remember(cover) { mutableStateOf(false) }
        var largeFailed by remember(cover) { mutableStateOf(false) }
        var attempt by remember(cover) { mutableIntStateOf(0) }
        var largeAttempt by remember(cover) { mutableIntStateOf(0) }
        if (cover.isNotEmpty() && (failed || largeFailed)) {
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(cover, failed, largeFailed) {
                val at = CoverRetry.tries.value
                val next = CoverRetry.tries.flowWithLifecycle(lifecycle).first { it > at }
                if (failed) {
                    attempt = next
                    failed = false
                }
                if (largeFailed) {
                    largeAttempt = next
                    largeFailed = false
                }
            }
        }
        if (!shown) Icon(placeholder, Modifier.glyphSize(), tint = Theme.colors.faint)
        if (cover.isNotEmpty()) {
            if (!largeShown) {
                val art = remember(cover, attempt) { CoverArt(cover, attempt = attempt) }
                AsyncImage(
                    art,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        shown = true
                        failed = false
                    },
                    onError = { failed = true },
                )
            }
            if (large) {
                val big = remember(cover, largeAttempt) { CoverArt(cover, large = true, attempt = largeAttempt) }
                AsyncImage(
                    big,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        shown = true
                        largeShown = true
                        largeFailed = false
                    },
                    onError = { largeFailed = true },
                )
            }
        }
    }
}

private val card = RoundedCornerShape(Theme.dimens.radius)
private val row = RoundedCornerShape(Theme.dimens.rowCoverRadius)

private fun shapeOf(shape: CoverShape): Shape = when (shape) {
    CoverShape.CARD -> card
    CoverShape.ROW -> row
    CoverShape.ROUND -> CircleShape
    CoverShape.SQUARE -> androidx.compose.ui.graphics.RectangleShape
}

/** The stand-in glyph is a little over a third of the cover, within limits (as on Windows). */
private fun Modifier.glyphSize() = layout { measurable, constraints ->
    val side = (constraints.maxWidth * 0.36f).coerceIn(14.dp.toPx(), 72.dp.toPx()).toInt()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}
