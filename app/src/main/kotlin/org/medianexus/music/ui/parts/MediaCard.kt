package org.medianexus.music.ui.parts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

enum class CardKind { ALBUM, ARTIST, PLAYLIST, MIX, GENRE }

/** An album, artist, playlist, mix or genre, as a card shows it. */
@Immutable
data class CardData(
    val kind: CardKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
)

/** What a long press on a card opens (its menu: Play, Play next, Add to queue, …), given by the
 *  shell; none elsewhere. */
val LocalCardMenu = androidx.compose.runtime.staticCompositionLocalOf<((CardData) -> Unit)?> { null }

/**
 * An album, artist, playlist or mix in a grid or shelf: the cover, then its name and a line
 * under it. A genre is a tile with its name on it instead. The width comes from `modifier`.
 * With `onPlay`, a play button sits on the cover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    card: CardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    /** This is the list that's playing: the play button offers to pause. */
    playing: Boolean = false,
) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(Theme.dimens.radius)
    // A long press opens the card's menu (Windows' right-click on a card), where the shell
    // gives one.
    val cardMenu = LocalCardMenu.current
    val onLongClick = onLongClick ?: cardMenu?.let { menu -> { menu(card) } }.takeIf { card.kind != CardKind.GENRE }
    if (card.kind == CardKind.GENRE) {
        Box(
            modifier
                .height(96.dp)
                .clip(shape)
                .background(colors.raised)
                .border(1.dp, colors.line, shape)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicText(card.title, style = Theme.type.tile.copy(color = colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.subtitle.isNotEmpty()) {
                BasicText(
                    card.subtitle,
                    Modifier.align(Alignment.BottomStart),
                    style = Theme.type.caption.copy(color = colors.muted),
                    maxLines = 1,
                )
            }
        }
        return
    }
    val round = card.kind == CardKind.ARTIST
    Column(
        modifier
            .clip(shape)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(
                card.cover,
                Modifier.fillMaxSize(),
                if (round) CoverShape.ROUND else CoverShape.CARD,
                when (card.kind) {
                    CardKind.ARTIST -> R.drawable.ic_user_round
                    CardKind.PLAYLIST, CardKind.MIX -> R.drawable.ic_list_music
                    else -> R.drawable.ic_disc_3
                },
            )
            if (onPlay != null) {
                PlayButton(
                    playing = playing,
                    onClick = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    size = 44.dp,
                    label = stringResource(if (playing) R.string.pause_named else R.string.play_named, card.title),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(card.title, style = Theme.type.cardTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (card.subtitle.isNotEmpty()) {
            BasicText(card.subtitle, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
