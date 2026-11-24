package org.medianexus.music.ui.parts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    /** Part of a selection being built up. */
    val selected: Boolean = false,
)

/**
 * One song in a list: 56dp high, a 40dp cover, the title (in the accent when it's what's
 * playing) over the artist. Tap plays; "…" or a long press opens its menu.
 *
 * `mark` is where its download stands and whether it can play now, as Windows' rows show
 * them: downloaded, a mark before the artist; waiting or arriving, a bar in place of the
 * length, filling as it comes; couldn't be downloaded, a mark of its own in the warm colour;
 * and offline, a song that isn't on the phone is dimmed, can't be tapped, and is said to be
 * unavailable. `trailing`, if given, stands in for the "…" button (the Downloads page's
 * Cancel on a song on its way).
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
    mark: org.medianexus.music.ui.downloads.RowMark = org.medianexus.music.ui.downloads.RowMark.NONE,
    /** A second line of its own in place of the artist (why it couldn't be downloaded). */
    detail: String? = null,
    /** Lines the title may take: two where titles that share a long beginning must be told
     *  apart in large type (the Songs page); the row grows to fit. */
    titleLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Theme.colors
    val unavailable = mark.unavailable
    val state = mark.state
    // What the dimming, the accent and the marks show, said to screen readers.
    val status = when {
        unavailable -> stringResource(R.string.track_unavailable)
        track.current && track.paused -> stringResource(R.string.track_paused)
        track.current -> stringResource(R.string.track_playing)
        state == org.medianexus.music.downloads.SongState.Waiting -> stringResource(R.string.waiting_to_download)
        else -> null
    }
    val menu by rememberUpdatedState(onLongClick ?: onMore)
    val press = if (unavailable) {
        // Nothing to play, so no play action for a finger or a screen reader; the menu still
        // opens on a long press.
        Modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menu() }) }
            .semantics { onLongClick { menu(); true } }
    } else {
        Modifier.combinedClickable(onClickLabel = stringResource(R.string.play), onLongClick = { menu() }, onClick = onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (titleLines > 1) Modifier.heightIn(min = Theme.dimens.rowHeight) else Modifier.height(Theme.dimens.rowHeight))
            .alpha(if (unavailable) 0.4f else 1f)
            .background(if (track.selected) colors.press else androidx.compose.ui.graphics.Color.Transparent)
            .then(press)
            // Read out as its title, artist and marks; the "…" button stays a button of its own.
            .semantics(mergeDescendants = true) {
                selected = track.selected
                if (status != null) stateDescription = status
            }
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
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (state) {
                    org.medianexus.music.downloads.SongState.Done ->
                        Icon(R.drawable.ic_circle_arrow_down, size = 13.dp, tint = colors.accentBright, contentDescription = stringResource(R.string.downloaded))
                    is org.medianexus.music.downloads.SongState.Failed ->
                        Icon(R.drawable.ic_circle_alert, size = 13.dp, tint = colors.warm, contentDescription = stringResource(R.string.couldnt_be_downloaded_short))
                    else -> {}
                }
                if (track.liked) {
                    Icon(R.drawable.ic_heart_fill, size = 13.dp, tint = colors.warm, contentDescription = stringResource(R.string.liked))
                }
                BasicText(
                    detail ?: track.artist,
                    style = Theme.type.rowDetail.copy(color = if (detail != null) colors.warm else colors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val progress = when (state) {
            org.medianexus.music.downloads.SongState.Waiting -> 0f
            is org.medianexus.music.downloads.SongState.Fetching -> state.fraction.coerceAtLeast(0f)
            else -> -1f
        }
        if (progress >= 0f) {
            DownloadBar(progress, stringResource(R.string.downloading_named, track.title))
        } else if (showDuration && track.duration.isNotEmpty()) {
            BasicText(track.duration, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1)
        }
        if (trailing != null) trailing() else IconButton(R.drawable.ic_ellipsis, stringResource(R.string.more_options_for, track.title), onMore, iconSize = 20.dp)
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
