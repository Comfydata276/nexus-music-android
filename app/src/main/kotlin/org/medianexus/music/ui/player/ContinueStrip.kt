package org.medianexus.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.medianexus.music.R
import org.medianexus.music.ui.parts.LinkText
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/**
 * What another device was playing, offered above the mini player: where a person looks on
 * opening the app. A queue they have used here is never replaced by itself (the shared
 * core's rule), so this is how the one they were playing elsewhere gets here: one tap.
 *
 * Shown while there is such an offer and **nothing is playing here**. It is a strip of its
 * own above the mini player, so nothing of the mini player moves or is covered, the play
 * button least of all; with it gone the page above simply has its line back. While a song
 * plays here the offer stays where it has always been, at the top of the queue.
 */
@Composable
fun ContinueStrip(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val step = ui.account?.queueStep
    val offered by (step?.elsewhere ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val there = offered ?: return
    val playing by ui.player.playing.collectAsStateWithLifecycle()
    // (Steering another device, the queue on show is that one's, and nothing is offered.)
    if (playing) return
    val colors = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(Theme.dimens.radius))
            .background(colors.raised)
            .padding(start = 12.dp, end = 4.dp)
            .heightIn(min = Theme.dimens.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            BasicText(
                there.by?.let { stringResource(R.string.continue_from, it) } ?: stringResource(R.string.continue_from_another),
                // (Two lines where it must: on a narrow phone with large type the words
                // beside the button would otherwise end "another devi…".)
                style = Theme.type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                if (there.artist.isEmpty()) there.title else stringResource(R.string.elsewhere_song, there.title, there.artist),
                style = Theme.type.rowDetail.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LinkText(stringResource(R.string.continue_here), { step?.continueHere() }, color = colors.accentBright)
    }
}
