package org.medianexus.music.ui.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/** What a list page's header shows (the Windows app's `Hero`). */
data class HeroData(
    /** "Album", "Playlist", "Artist". */
    val label: String,
    val title: String,
    /** The artist or owner, which may open a page. */
    val subtitle: String = "",
    /** The facts after it: "2024, 10 songs, 32 min". */
    val meta: String = "",
    val cover: String = "",
    val round: Boolean = false,
    /** Liked songs: a heart on the accent instead of a cover. */
    val liked: Boolean = false,
    /** A playlist: its stand-in, until a cover is drawn, is a list's. */
    val playlist: Boolean = false,
)

/**
 * The top of an album, playlist or artist page: a large cover with a soft shadow, then what
 * it is, its name in the display face, and a line of facts. On Windows the cover sits beside
 * the words; so it does here wherever there's room for both (on its side, or 600dp and
 * wider). On a phone held upright it goes above them, as large as it can be and still leave
 * the songs in sight. `actions` (a phone on its side) puts the action bar at the end of the
 * same row.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Hero(hero: HeroData, modifier: Modifier = Modifier, onSubtitle: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val tall = with(androidx.compose.ui.platform.LocalDensity.current) { window.height.toDp() }
        val wide = maxWidth >= 600.dp || window.width > window.height
        if (wide) {
            // Beside: sized by the screen's height, which on a phone on its side is what is
            // short (the bars take most of it), so the songs start within sight.
            val side = (tall * 0.28f).coerceIn(96.dp, 220.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                HeroCover(hero, side)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { HeroWords(hero, onSubtitle) }
                // A phone on its side: the play button beside the words, not in a row of its
                // own under them, so the first songs are in sight.
                actions?.invoke()
            }
        } else {
            // Large, but not so large that a list's first songs can't be seen under it.
            val side = (maxWidth * 0.56f).coerceAtMost(tall * 0.42f).coerceIn(120.dp, 260.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.align(Alignment.CenterHorizontally)) { HeroCover(hero, side) }
                Spacer(Modifier.height(14.dp))
                HeroWords(hero, onSubtitle)
            }
        }
    }
}

@Composable
private fun HeroCover(hero: HeroData, side: androidx.compose.ui.unit.Dp) {
    val shape = if (hero.round) CircleShape else RoundedCornerShape(Theme.dimens.radius)
    Box(Modifier.size(side).shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)) {
        if (hero.liked) {
            LikedTile(Modifier.fillMaxSize())
        } else {
            Cover(
                hero.cover,
                Modifier.fillMaxSize(),
                if (hero.round) CoverShape.ROUND else CoverShape.CARD,
                when {
                    hero.round -> R.drawable.ic_user_round
                    hero.playlist -> R.drawable.ic_list_music
                    else -> R.drawable.ic_disc_3
                },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HeroWords(hero: HeroData, onSubtitle: (() -> Unit)?) {
    val colors = Theme.colors
    if (hero.label.isNotEmpty()) {
        BasicText(hero.label, style = Theme.type.label.copy(color = colors.text))
    }
    BasicText(
        hero.title,
        Modifier.semantics { heading() },
        style = Theme.type.title.copy(color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = if (hero.title.length > 34) 24.sp else 28.sp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    // The artist or owner, then the facts; on a narrow screen the facts go under.
    // As on Windows, the two sit side by side with a gap and nothing between them.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (hero.subtitle.isNotEmpty()) {
            val link = if (onSubtitle != null) {
                Modifier.heightIn(min = Theme.dimens.touch).clickable(role = Role.Button, onClick = onSubtitle)
            } else {
                Modifier
            }
            Box(link, contentAlignment = Alignment.CenterStart) {
                BasicText(hero.subtitle, style = Theme.type.label.copy(color = colors.text, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (hero.meta.isNotEmpty()) {
            BasicText(hero.meta, Modifier.heightIn(min = 24.dp).wrapContentHeight(), style = Theme.type.body.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Liked songs' picture: a white heart on the accent, fading to the warm colour. */
@Composable
fun LikedTile(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Theme.dimens.radius)) {
    val colors = Theme.colors
    BoxWithConstraints(
        modifier.clip(shape).background(Brush.linearGradient(listOf(darker(colors.accent), colors.warm))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(R.drawable.ic_heart_fill, size = maxWidth * 0.34f, tint = Color.White)
    }
}

private fun darker(color: Color) = Color(color.red * 0.75f, color.green * 0.75f, color.blue * 0.75f, color.alpha)

/**
 * The row under a header (the Windows app's `ActionBar`): the big play button, the shuffle
 * button that says whether this list plays shuffled (it doesn't start anything), and a heart
 * where the page's subject can be liked.
 */
@Composable
fun ActionBar(
    title: String,
    /** Something to play: without it, play and shuffle aren't offered (an empty playlist). */
    canPlay: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    shuffle: Boolean,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    liked: Boolean? = null,
    onLike: () -> Unit = {},
    /** Beside a header's words, rather than a row of its own. */
    inline: Boolean = false,
    /** The list's download button (an album, a playlist, Liked songs), if it has one. */
    download: DownloadButton? = null,
) {
    val colors = Theme.colors
    Row(
        if (inline) modifier.height(64.dp) else modifier.fillMaxWidth().height(80.dp).padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canPlay) PlayButton(
            playing = playing,
            onClick = onPlay,
            size = 56.dp,
            label = stringResource(if (playing) R.string.pause_named else R.string.play_named, title),
        )
        if (canPlay) Box(contentAlignment = Alignment.Center) {
            IconButton(
                R.drawable.ic_shuffle,
                stringResource(if (shuffle) R.string.shuffle_off_for else R.string.shuffle_on_for, title),
                onShuffle,
                active = shuffle,
                iconSize = 24.dp,
            )
            if (shuffle) {
                // The dot under it, as on Windows: on, and not just pressed.
                Box(Modifier.padding(top = 34.dp).size(4.dp).clip(CircleShape).background(colors.accentBright))
            }
        }
        if (liked != null) {
            IconButton(
                if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart,
                stringResource(if (liked) R.string.remove_from_liked_albums else R.string.add_to_liked_albums),
                onLike,
                tint = if (liked) colors.warm else colors.muted,
                iconSize = 24.dp,
            )
        }
        if (download != null) {
            IconButton(
                if (download.state == 0) R.drawable.ic_download else R.drawable.ic_circle_arrow_down,
                stringResource(
                    when (download.state) {
                        0 -> R.string.download
                        1 -> R.string.cancel_download
                        else -> R.string.remove_download
                    },
                ),
                download.onClick,
                tint = if (download.state == 0) colors.muted else colors.accentBright,
                active = download.state == 2,
                iconSize = 24.dp,
            )
            if (download.state == 1) {
                BasicText("${download.done}/${download.total}", style = Theme.type.label.copy(color = colors.muted))
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}

/** A list's download button: 0 nothing downloaded, 1 on its way (`done` of `total`), 2 all
 *  of it downloaded (as Windows' `download_state`). */
data class DownloadButton(val state: Int, val done: Int, val total: Int, val onClick: () -> Unit)

/** A heading between parts of a list: "Disc 2". */
@Composable
fun ListHeading(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = Theme.dimens.pagePad, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text,
            Modifier.weight(1f).semantics { heading() },
            style = Theme.type.label.copy(color = Theme.colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) LinkText(action, onAction)
    }
}
