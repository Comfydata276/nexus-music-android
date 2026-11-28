package org.medianexus.music.ui.parts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.playback.Steered
import org.medianexus.music.store.CoverStore
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
 * the size it's shown. A smaller copy already on the phone can stand in while the requested
 * tier loads. `large` asks for Now Playing's larger copy, showing the usual one until it arrives.
 */
@Composable
fun Cover(
    cover: String,
    modifier: Modifier = Modifier,
    shape: CoverShape = CoverShape.CARD,
    @DrawableRes placeholder: Int = R.drawable.ic_music,
    large: Boolean = false,
    /** `cover` is a web address (an album or artist that isn't in the library): fetched by
     *  [WebCovers], under its rule, and never asked for again once it has failed. */
    web: Boolean = false,
) {
    val useCellSize = !large && !web && cover.isNotEmpty() && !cover.startsWith(Steered.FOREIGN)
    var cellPixels by remember { mutableIntStateOf(0) }
    val displayedPixels = if (useCellSize) cellPixels else 0
    val requestedSize = when {
        !useCellSize -> CoverStore.SIZE
        displayedPixels == 0 -> 0
        else -> CoverStore.sizeFor(displayedPixels, round = shape == CoverShape.ROUND)
    }
    val measure = if (useCellSize) Modifier.onSizeChanged { bounds ->
        cellPixels = maxOf(bounds.width, bounds.height)
    } else Modifier
    Box(
        modifier.then(measure).clip(shapeOf(shape)).background(Theme.colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        var shown by remember(cover) { mutableStateOf(false) }
        var lastPainter by remember(cover) { mutableStateOf<Painter?>(null) }
        // Now Playing's large copy, once it has drawn: the usual one was only standing in
        // for it, and leaves, so the two aren't both decoded and held for one place.
        var largeShown by remember(cover) { mutableStateOf(false) }
        // Either copy that failed is asked for again when the server can be reached again
        // (`CoverRetry`). Listened for only while one has failed, and only while the app is in
        // front: a screenful of covers that loaded holds no subscription at all.
        var failed by remember(cover, requestedSize) { mutableStateOf(false) }
        var largeFailed by remember(cover) { mutableStateOf(false) }
        var attempt by remember(cover) { mutableIntStateOf(0) }
        var largeAttempt by remember(cover) { mutableIntStateOf(0) }
        if (web) {
            if (!shown) Icon(placeholder, Modifier.glyphSize(), tint = Theme.colors.faint)
            if (cover.isNotEmpty()) {
                val art = remember(cover) { WebCover(cover) }
                AsyncImage(art, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop, onSuccess = { shown = true })
            }
            return@Box
        }
        if (cover.isNotEmpty() && (failed || largeFailed)) {
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(cover, requestedSize, failed, largeFailed) {
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
            if (!largeShown && requestedSize > 0) {
                key(cover, requestedSize) {
                    var ready by remember { mutableStateOf(false) }
                    var previewShown by remember { mutableStateOf(false) }
                    if (useCellSize && !ready) {
                        // A resized cell can keep drawing its last painter while both requests
                        // look for a file. The preview can replace it with a sharper kept tier.
                        if (!previewShown) lastPainter?.let {
                            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        }
                        if (requestedSize > CoverStore.SMALLEST) {
                            val preview = remember { CoverArt(cover, size = requestedSize, preview = true) }
                            AsyncImage(
                                withoutFade(preview),
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = {
                                    if (!ready) {
                                        lastPainter = it.painter
                                        previewShown = true
                                        shown = true
                                    }
                                },
                            )
                        }
                    }
                    val art = remember(cover, attempt) { CoverArt(cover, attempt = attempt, size = requestedSize) }
                    AsyncImage(
                        if (useCellSize) withoutFade(art) else art,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = {
                            if (useCellSize) lastPainter = it.painter
                            ready = true
                            shown = true
                            failed = false
                        },
                        onError = { failed = true },
                    )
                }
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

/** The replacement is opaque as soon as the layer underneath is removed. */
@Composable
private fun withoutFade(art: CoverArt): ImageRequest {
    val context = LocalContext.current
    return remember(context, art) { ImageRequest.Builder(context).data(art).crossfade(false).build() }
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
