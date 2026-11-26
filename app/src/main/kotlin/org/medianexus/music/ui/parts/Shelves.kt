package org.medianexus.music.ui.parts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.SharedFlow
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/**
 * A shelf: its name, and its cards in one row that scrolls sideways (Home's "Recently
 * added", an album's "More by…"). `cardWidth` is each card's width; the next one peeks in at
 * the edge, which says there's more.
 */
@Composable
fun Shelf(
    title: String,
    cards: List<CardData>,
    onOpen: (CardData) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 148.dp,
    onShowAll: (() -> Unit)? = null,
    onPlay: ((CardData) -> Unit)? = null,
    /** The button on a card asks for it (an album that isn't in the library): a plus, said
     *  as `playLabel` says, and only on the cards `playable` allows. */
    adds: Boolean = false,
    playLabel: ((CardData) -> String)? = null,
    playable: (CardData) -> Boolean = { true },
) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle(title, onShowAll = onShowAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Theme.dimens.pagePad),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { "${it.kind}/${it.id}" }, contentType = { it.kind }) { card ->
                MediaCard(
                    card,
                    onClick = { onOpen(card) },
                    modifier = Modifier.width(cardWidth),
                    onPlay = onPlay?.takeIf { playable(card) }?.let { play -> { play(card) } },
                    adds = adds,
                    playLabel = playLabel?.invoke(card),
                )
            }
        }
    }
}

/**
 * A page that can be pulled down to ask the server again. While it shows something, a check
 * that failed is a line across the top with "Try again", never in place of what's shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Theme.colors.overlay,
                color = Theme.colors.accentBright,
            )
        },
        content = content,
    )
}

/** The line that says a check with the server failed, over a page that still shows what it
 *  had. */
@Composable
fun FailedStrip(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.dimens.gap, vertical = 4.dp)
            .background(colors.overlay, RoundedCornerShape(Theme.dimens.radius))
            .padding(start = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(R.drawable.ic_wifi_off, size = 18.dp, tint = colors.warm)
        BasicText(
            message,
            Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
            style = Theme.type.rowDetail.copy(color = colors.text),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        LinkText(stringResource(R.string.try_again), onRetry, color = colors.text)
    }
}

/**
 * The toast: a few words at the foot of the screen for three seconds, as the Windows app's
 * (a pill in the text colour with the words in the background's). The newest replaces any
 * still showing. Screen readers read it out.
 *
 * 0.5.1's host, with three things added and nothing else changed: `account` is whoever is
 * signed in, and when it changes the note showing goes; an arrival's note is told it was
 * seen (`Toast.shown`) in the same effect that starts its time, so only once it is on screen
 * with the app in front; and a button acts on the screen alive when it is pressed, which is
 * the callers' part (`LiveScreen`). And a fourth, which the walk found: a note's time starts
 * in the effect that runs after it has been composed, as in 0.5.1, but only once the app is in
 * front and the note has been drawn (Compose runs the effect while the app is behind too, so
 * in 0.5.1 a note that came then could spend its time unseen).
 */
@Composable
fun ToastHost(toasts: SharedFlow<org.medianexus.music.Toasts.Toast>, modifier: Modifier = Modifier, account: Any? = null) {
    var toast by remember { mutableStateOf(org.medianexus.music.Toasts.Toast("")) }
    var shown by remember { mutableStateOf(false) }
    var said by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(toasts) {
        toasts.collect {
            toast = it
            shown = true
            said++
        }
    }
    // Sign-out, or another account: nothing said under the last one stays up under this one.
    LaunchedEffect(account) { shown = false }
    LaunchedEffect(said) {
        if (said == 0) return@LaunchedEffect
        // Its time starts once it is on screen: the app in front, and the note drawn. (Compose
        // goes on composing, and running this, while the app is behind: in 0.5.1 a note that
        // came then had spent its time before the app was back.)
        lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        withFrameNanos { }
        // On screen now: an arrival's note has been seen.
        if (shown) toast.shown?.invoke()
        // One with a button stays long enough to press it (Windows' eight seconds).
        delay(if (toast.action != null) 8_000 else 3_200)
        shown = false
    }
    val colors = Theme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .widthIn(max = 560.dp)
                    .shadow(12.dp, RoundedCornerShape(22.dp))
                    .background(colors.text, RoundedCornerShape(22.dp))
                    .heightIn(min = 44.dp)
                    .padding(start = 20.dp, end = if (toast.action != null) 6.dp else 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    toast.text,
                    Modifier.padding(vertical = 11.dp).then(if (toast.action != null) Modifier.weight(1f, fill = false) else Modifier),
                    style = Theme.type.button.copy(color = colors.base, textAlign = TextAlign.Center),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val action = toast.action
                val onAction = toast.onAction
                if (action != null && onAction != null) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .heightIn(min = Theme.dimens.touch)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = action) {
                                shown = false
                                onAction()
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(action, style = Theme.type.button.copy(color = colors.accent))
                    }
                }
            }
        }
    }
}
