package org.medianexus.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/**
 * The mini player, docked above the bottom bar once anything is in the queue: the cover, the
 * song and artist, like, and play/pause, with a thin line along its foot for how far the
 * song has got. Tap it or swipe it up for Now Playing; swipe it sideways for the next or
 * previous song.
 */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val player = ui.player
    val song by player.song.collectAsStateWithLifecycle()
    val now = song ?: return
    val playing by player.playing.collectAsStateWithLifecycle()
    val liked by player.liked.collectAsStateWithLifecycle()
    val buffering by player.buffering.collectAsStateWithLifecycle()
    val reported by player.error.collectAsStateWithLifecycle()
    // The service words it for the car ("When it's safe, open Nexus Music…"); on the phone
    // itself, it's said plainly.
    val blocked by ui.graph.localAccess.blocked.collectAsStateWithLifecycle()
    // What the server itself said when it refused something the car asked for: shown here,
    // where the phone shows a playback error, and nowhere outside the app (see Refusals).
    val refusal by ui.graph.refusals.last.collectAsStateWithLifecycle()
    val error = if (reported != null && blocked) stringResource(R.string.local_network_short) else reported ?: refusal?.words
    // Read only while drawing the line, so it moving redraws the line and nothing else.
    val position = player.position.collectAsStateWithLifecycle()
    val duration = player.duration.collectAsStateWithLifecycle()
    val colors = Theme.colors
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    val openLabel = stringResource(R.string.open_now_playing)
    val nextLabel = stringResource(R.string.next)
    val previousLabel = stringResource(R.string.previous)
    val shape = RoundedCornerShape(Theme.dimens.radius)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(Theme.dimens.miniPlayer)
            .clip(shape)
            .background(colors.overlay)
            .progressLine(position, duration, colors.text, colors.track)
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val wide = size.width * 0.25f
                        when {
                            dy < -48.dp.toPx() && abs(dy) > abs(dx) -> ui.nowPlaying = true
                            dx < -wide -> player.next()
                            dx > wide -> player.previous()
                        }
                        scope.launch { slide.animateTo(0f, tween(180)) }
                    },
                    onDragCancel = { scope.launch { slide.animateTo(0f, tween(180)) } },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                    if (abs(dx) > abs(dy)) scope.launch { slide.snapTo(dx * 0.6f) }
                }
            }
            .clickable(onClickLabel = openLabel, role = Role.Button) { ui.nowPlaying = true }
            .semantics(mergeDescendants = true) {
                onClick(openLabel) { ui.nowPlaying = true; true }
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { player.next(); true },
                    CustomAccessibilityAction(previousLabel) { player.previous(); true },
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = slide.value }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(now.coverArt, Modifier.size(44.dp), CoverShape.ROW)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(now.title, style = Theme.type.rowTitle.copy(color = colors.text, fontSize = Theme.type.cardTitle.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // What the player is waiting on takes the artist's place for a moment.
                val status = when {
                    error != null -> error
                    buffering -> stringResource(R.string.buffering)
                    else -> null
                }
                BasicText(
                    status ?: now.artistLabel,
                    style = Theme.type.rowDetail.copy(color = if (error != null) colors.warm else colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart,
                stringResource(if (liked) R.string.remove_from_liked else R.string.add_to_liked),
                {
                // As on Windows, a like says so.
                player.toggleLike()
                ui.toast(res.getString(if (liked) R.string.removed_from_liked else R.string.added_to_liked))
            },
                tint = if (liked) colors.warm else colors.muted,
            )
            IconButton(
                if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
                stringResource(if (playing) R.string.pause else R.string.play),
                {
                    player.playPause()
                },
                tint = colors.text,
                iconSize = 24.dp,
            )
        }
    }
}

/** A thin line along the foot: how far through the song. Drawn from the position as it is
 *  when drawing, so the position changing redraws this and nothing else. */
private fun Modifier.progressLine(position: State<Long>, duration: State<Long>, fill: androidx.compose.ui.graphics.Color, track: androidx.compose.ui.graphics.Color) =
    drawWithContent {
        drawContent()
        val total = duration.value
        val done = if (total > 0) (position.value.toFloat() / total).coerceIn(0f, 1f) else 0f
        val thick = 2.dp.toPx()
        val y = size.height - thick
        drawRect(track, Offset(0f, y), Size(size.width, thick))
        drawRect(fill, Offset(0f, y), Size(size.width * done, thick))
    }
