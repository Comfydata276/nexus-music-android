package org.medianexus.music.ui.parts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/** One song as a list shows it. Screens build these from what the library knows. */
@Immutable
data class TrackRowData(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String = "",
    /** Its number on the album, shown when the list numbers its songs. */
    val number: String = "",
    /** "3:41". */
    val duration: String = "",
    val liked: Boolean = false,
    /** This very row is what's playing (not merely the same song somewhere else). */
    val current: Boolean = false,
    /** What's playing is paused: the playing mark dims. */
    val paused: Boolean = false,
    val downloaded: Boolean = false,
    /** Can't be played now (offline, and not downloaded). */
    val unavailable: Boolean = false,
    /** How far a download has got, 0 to 1; below 0 when it isn't downloading. */
    val progress: Float = -1f,
    /** Part of a selection being built up. */
    val selected: Boolean = false,
)

/**
 * One song in a list: 56dp high, a 40dp cover, the title (in the accent when it's what's
 * playing) over the artist. Tap plays; "…" or a long press opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: TrackRowData,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showCover: Boolean = true,
    showNumber: Boolean = false,
    showDuration: Boolean = false,
) {
    val colors = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(Theme.dimens.rowHeight)
            .alpha(if (track.unavailable) 0.4f else 1f)
            .background(if (track.selected) colors.press else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onLongClick = onLongClick ?: onMore, onClick = onClick)
            // Read out as its title, artist and marks; the "…" button stays a button of its own.
            .semantics(mergeDescendants = true) { selected = track.selected }
            .padding(start = Theme.dimens.pagePad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showNumber) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                if (track.current) {
                    Icon(R.drawable.ic_bars, Modifier.alpha(if (track.paused) 0.55f else 1f), size = 14.dp, tint = colors.accentBright)
                } else {
                    BasicText(track.number, style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.End), maxLines = 1)
                }
            }
        }
        if (showCover) {
            Cover(track.cover, Modifier.size(Theme.dimens.rowCover), CoverShape.ROW)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                track.title,
                style = Theme.type.rowTitle.copy(color = if (track.current) colors.accentBright else colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (track.downloaded) {
                    Icon(R.drawable.ic_circle_arrow_down, size = 13.dp, tint = colors.accentBright, contentDescription = stringResource(R.string.downloaded))
                }
                if (track.liked) {
                    Icon(R.drawable.ic_heart_fill, size = 13.dp, tint = colors.warm, contentDescription = stringResource(R.string.liked))
                }
                BasicText(track.artist, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track.progress >= 0f) {
            DownloadBar(track.progress, stringResource(R.string.downloading_named, track.title))
        } else if (showDuration && track.duration.isNotEmpty()) {
            BasicText(track.duration, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1)
        }
        IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, track.title), onMore, iconSize = 20.dp)
    }
}

/** While a song is being downloaded (or waiting to be), how far along it is. */
@Composable
private fun DownloadBar(progress: Float, label: String) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .width(40.dp)
            .heightIn(min = 4.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(shape).background(colors.press))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(shape).background(colors.accentBright))
    }
}
