package org.medianexus.music.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.playback.Repeat
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.Slider
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalNetworkBar
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.SongMenu
import org.medianexus.music.ui.theme.Theme

/**
 * Now Playing, over everything: the cover large with a soft shadow, the song in the display
 * face, its artist (which opens the artist), the format line as Windows shows it, the seek
 * bar, and shuffle / previous / play / next / repeat, then like and the queue. Swipe down or
 * go back to close it; back is predictive, the page following the finger.
 */
@Composable
fun NowPlaying(onClose: () -> Unit) {
    val ui = LocalUi.current
    val player = ui.player
    val song by player.song.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val colors = Theme.colors
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    // Only while it's open: while it slides away a second Back is for the page underneath.
    // (A second Back can arrive before the screen has been redrawn without this handler; it
    // is passed on to the page from here.)
    val activity = androidx.activity.compose.LocalActivity.current
    var closing by remember { mutableStateOf(false) }
    // The lyrics in the cover's place (Windows' lyrics button), until turned off again.
    var lyrics by LyricsChoice::shown
    PredictiveBackHandler(enabled = ui.nowPlaying && !ui.queueOpen) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            if (closing) {
                if (ui.navigator.canGoBack) ui.navigator.back() else activity?.finish()
            } else {
                closing = true
                onClose()
            }
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val accent = colors.accent
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = drag.value + backProgress * 160.dp.toPx()
                val shrink = 1f - backProgress * 0.08f
                scaleX = shrink
                scaleY = shrink
            }
            .background(colors.base)
            .drawBehind {
                // A wash of the accent behind the cover, as the sign-in screen has.
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)), center = Offset(size.width / 2, size.height * 0.25f), radius = size.width * 0.9f))
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.value > 120.dp.toPx()) onClose() else scope.launch { drag.animateTo(0f, tween(200)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, tween(200)) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                }
            }
            // Nothing pressed here reaches the page underneath. A plain catch of taps, not a
            // click: a click would be offered to screen readers as something to do, and would
            // gather everything on the screen (the seek bar's own actions too) into one.
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val now = song
        val from by player.from.collectAsStateWithLifecycle()
        // "Playing from" names the album when the queue came from it: then it is the link.
        val fromAlbum = now != null && now.albumId.isNotEmpty() && from.source == "album:${now.albumId}"
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(R.drawable.ic_chevron_down, stringResource(R.string.close_now_playing), onClose, tint = colors.text, iconSize = 26.dp)
                val album = now?.takeIf { fromAlbum }
                val link = if (album != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.go_to_album)) {
                        ui.open(Route.Album(album.albumId, album.album, album.artistLabel, album.coverArt))
                    }
                } else {
                    Modifier
                }
                Column(Modifier.weight(1f).heightIn(min = Theme.dimens.touch).then(link), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (queue.context.isNotEmpty()) {
                        BasicText(stringResource(R.string.playing_from), style = Theme.type.caption.copy(color = colors.muted), maxLines = 1)
                        BasicText(queue.context, style = Theme.type.label.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Offline, the same pill as every page's title bar.
                org.medianexus.music.ui.parts.LocalTitlePill.current()
                // On its side the queue button sits up here, where it is always in sight.
                val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
                if (now != null && window.width > window.height) {
                    IconButton(R.drawable.ic_list_music, stringResource(R.string.queue), { ui.queueOpen = true }, tint = colors.text)
                }
                if (now != null) {
                    IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, now.title), { ui.menu = SongMenu(now) }, tint = colors.text)
                } else {
                    Spacer(Modifier.width(Theme.dimens.touch))
                }
            }
            // The same word as every page has, when the home network is out of bounds.
            LocalNetworkBar(ui.graph)
            if (now == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(stringResource(R.string.nothing_playing), style = Theme.type.section.copy(color = colors.muted))
                }
                return@Column
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth
                val tall = maxHeight
                if (wide > tall) {
                    // Turned on its side: the cover beside the controls, which scroll if large
                    // type makes them taller than a short screen.
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        val side = (tall - 16.dp).coerceAtMost(wide * 0.45f)
                        if (lyrics) LyricsPanel(now, Modifier.size(side)) else Art(now.coverArt, side)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Controls(showAlbum = !fromAlbum, queueButton = false, lyrics = lyrics, onLyrics = { lyrics = !lyrics }) }
                    }
                } else {
                    // The cover takes what the controls leave; if large type leaves it too
                    // little, the whole screen scrolls instead of pushing the controls off.
                    val side = (wide - 48.dp).coerceAtMost(tall - 360.dp).coerceAtLeast(140.dp)
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = tall).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        if (lyrics) LyricsPanel(now, Modifier.fillMaxWidth().height(side)) else Art(now.coverArt, side)
                        Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) { Controls(showAlbum = !fromAlbum, lyrics = lyrics, onLyrics = { lyrics = !lyrics }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Art(cover: String, side: Dp) {
    val shape = RoundedCornerShape(Theme.dimens.radius)
    Box(Modifier.size(side).shadow(32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)) {
        // The larger copy, fetched for this screen only; the usual one shows until it's here.
        Cover(cover, Modifier.fillMaxSize(), large = true, placeholder = R.drawable.ic_disc_3)
    }
}

@Composable
private fun ColumnScope.Controls(showAlbum: Boolean, queueButton: Boolean = true, lyrics: Boolean = false, onLyrics: () -> Unit = {}) {
    val ui = LocalUi.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val player = ui.player
    val colors = Theme.colors
    val song by player.song.collectAsStateWithLifecycle()
    val now = song ?: return
    val liked by player.liked.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val buffering by player.buffering.collectAsStateWithLifecycle()
    val reported by player.error.collectAsStateWithLifecycle()
    // The service words it for the car ("When it's safe, open Nexus Music…"); on the phone
    // itself, it's said plainly.
    val blocked by ui.graph.localAccess.blocked.collectAsStateWithLifecycle()
    // What the server itself said when it refused something the car asked for: shown here,
    // where the phone shows a playback error, and nowhere outside the app (see Refusals).
    val refusal by ui.graph.refusals.last.collectAsStateWithLifecycle()
    val error = if (reported != null && blocked) stringResource(R.string.local_network_short) else reported ?: refusal?.words
    val format by player.format.collectAsStateWithLifecycle()
    val shuffle by player.shuffle.collectAsStateWithLifecycle()
    val repeat by player.repeat.collectAsStateWithLifecycle()
    val canPrevious by player.canPrevious.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                now.title,
                Modifier.semantics { heading() },
                style = Theme.type.title.copy(color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The artist and (unless "Playing from" above already is) the album, each a link
            // to its page as on Windows, with a full-size place to tap.
            val artistLink = if (now.artistId.isNotEmpty()) {
                Modifier.clickable(role = Role.Button) { ui.open(Route.Artist(now.artistId, now.artist)) }
            } else {
                Modifier
            }
            Box(artistLink.heightIn(min = Theme.dimens.touch), contentAlignment = Alignment.CenterStart) {
                BasicText(now.artistLabel, style = Theme.type.body.copy(color = colors.muted, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showAlbum && now.album.isNotEmpty()) {
                val albumLink = if (now.albumId.isNotEmpty()) {
                    Modifier.clickable(role = Role.Button) { ui.open(Route.Album(now.albumId, now.album, now.artistLabel, now.coverArt)) }
                } else {
                    Modifier
                }
                Box(albumLink.heightIn(min = Theme.dimens.touch), contentAlignment = Alignment.CenterStart) {
                    BasicText(now.album, style = Theme.type.body.copy(color = colors.muted, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
            iconSize = 26.dp,
        )
    }
    // What the player is waiting on, in words, where the format line goes; else the format.
    val waiting = when {
        error != null -> error
        buffering -> stringResource(R.string.buffering)
        else -> null
    }
    BasicText(
        waiting ?: format.text,
        Modifier.fillMaxWidth().padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = Theme.type.label.copy(
            color = when {
                error != null -> colors.warm
                waiting != null -> colors.muted
                format.lossless -> colors.accentBright
                else -> colors.muted
            },
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    SeekBar()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            R.drawable.ic_shuffle,
            stringResource(if (shuffle) R.string.shuffle_off else R.string.shuffle),
            { player.setShuffle(!shuffle) },
            active = shuffle,
            iconSize = 24.dp,
        )
        IconButton(R.drawable.ic_prev_fill, stringResource(R.string.previous), player::previous, tint = colors.text, iconSize = 30.dp, modifier = Modifier.size(56.dp), enabled = canPrevious)
        BigPlay(playing) { player.playPause() }
        IconButton(R.drawable.ic_next_fill, stringResource(R.string.next), player::next, tint = colors.text, iconSize = 30.dp, modifier = Modifier.size(56.dp))
        IconButton(
            if (repeat == Repeat.ONE) R.drawable.ic_repeat_1 else R.drawable.ic_repeat,
            stringResource(
                when (repeat) {
                    Repeat.OFF -> R.string.repeat
                    Repeat.ALL -> R.string.repeat_one
                    else -> R.string.repeat_off
                },
            ),
            player::cycleRepeat,
            active = repeat != Repeat.OFF,
            iconSize = 24.dp,
        )
    }
    // The sleep timer and the lyrics (Windows' player bar has both), then the queue.
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SleepButton()
        IconButton(R.drawable.ic_mic_vocal, stringResource(if (lyrics) R.string.hide_lyrics else R.string.lyrics), onLyrics, active = lyrics)
        Spacer(Modifier.weight(1f))
        if (queueButton) IconButton(R.drawable.ic_list_music, stringResource(R.string.queue), { ui.queueOpen = true }, tint = colors.text)
    }
}

/** The play button of Now Playing: a large circle in the text colour, as Windows' transport. */
@Composable
private fun BigPlay(playing: Boolean, onClick: () -> Unit) {
    val colors = Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val label = stringResource(if (playing) R.string.pause else R.string.play)
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (pressed) colors.muted else colors.text)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill, size = 30.dp, tint = colors.base)
    }
}

/** The seek bar and the times either side of it. Only this reads the position, so only this
 *  redraws as the song plays. A drag belongs to the queue entry it began on, not the song (the
 *  same song twice in a row is two): when the entry changes it is abandoned, the bar starts
 *  afresh, and nothing is sent. The time shown while dragging goes whenever the drag ends,
 *  however it ends. */
@Composable
private fun SeekBar() {
    val player = LocalUi.current.player
    val colors = Theme.colors
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    val entry by player.entry.collectAsStateWithLifecycle()
    key(entry) {
        val drag = remember { SeekDrag(entry) }
        val shown = drag.at?.let { (it * duration).toLong() } ?: position
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                label = stringResource(R.string.seek),
                onMove = drag::move,
                onRelease = { place ->
                    // Let go after the entry has changed (the old bar is gone, but its
                    // gesture's end can still arrive): not for this one.
                    drag.end(place, player.entry.value)?.let { if (duration > 0) player.seek((it * duration).toLong()) }
                },
                onCancel = drag::cancel,
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth()) {
                BasicText(clock((shown / 1000).toInt()), style = Theme.type.caption.copy(color = colors.muted))
                Spacer(Modifier.weight(1f))
                BasicText(
                    if (duration > 0) clock((duration / 1000).toInt()) else "",
                    style = Theme.type.caption.copy(color = colors.muted, textAlign = TextAlign.End),
                )
            }
        }
    }
}

/**
 * A seek being dragged on Now Playing: the queue entry it began on, and where the finger is
 * (shown as the time, until the drag ends). Plain state, so it can be tested without a
 * screen.
 */
@androidx.compose.runtime.Stable
class SeekDrag(val entry: Long) {
    /** Where the finger is, 0 to 1, while dragging. */
    var at: Float? by mutableStateOf(null)
        private set

    fun move(to: Float) {
        at = to
    }

    /** Let go at `place`, with `entryNow` playing: where to seek, or null if the entry has
     *  changed since the drag began. Either way the drag is over. */
    fun end(place: Float, entryNow: Long): Float? {
        at = null
        return place.takeIf { entryNow == entry }
    }

    /** Ended some other way: nothing to seek to. */
    fun cancel() {
        at = null
    }
}
