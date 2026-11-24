package org.medianexus.music.ui.parts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/** What a page's title bar shows at its end, before its own buttons: the offline pill, once
 *  the shell is there to provide it (see `OfflinePill`). */
val LocalTitlePill = androidx.compose.runtime.staticCompositionLocalOf<@Composable () -> Unit> { {} }

/** The bar across the top of a page: Back (when there's somewhere to go back to), the page's
 *  name, and a few buttons at the end, after the offline pill when the app is offline. It
 *  sits below the status bar. */
@Composable
fun TitleBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(start = if (onBack == null) Theme.dimens.pagePad else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onBack, tint = Theme.colors.text, iconSize = 24.dp)
        }
        BasicText(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = Theme.type.bar.copy(color = Theme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LocalTitlePill.current()
        actions()
    }
}

/** A shelf's name on a page, with "Show all" when there's more of it. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    onShowAll: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Theme.dimens.touch).padding(start = Theme.dimens.pagePad, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text,
            Modifier.weight(1f).semantics { heading() },
            style = Theme.type.section.copy(color = Theme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onShowAll != null) LinkText(stringResource(R.string.show_all), onShowAll)
    }
}

/** Faint bars standing in for rows that are still loading. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, rows: Int = 6) {
    val bar = RoundedCornerShape(6.dp)
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Box(Modifier.fillMaxWidth().height(Theme.dimens.rowHeight).padding(horizontal = Theme.dimens.pagePad, vertical = 14.dp)) {
                Box(Modifier.fillMaxSize().alpha(1f - index * 0.13f).background(SKELETON, bar))
            }
        }
    }
}

private val SKELETON = androidx.compose.ui.graphics.Color(0x0DFFFFFF)

/** What a page shows in place of itself. */
@Immutable
sealed interface PageState {
    data object Loading : PageState

    /** Nothing to show, and why: "No playlists yet". */
    data class Empty(val title: String, val detail: String = "", @param:DrawableRes val icon: Int? = null) : PageState

    /** It failed; `message` says why (an `ApiError`'s words). */
    data class Error(val message: String) : PageState
}

/** Shown in place of a page while it loads, when there's nothing in it, or when it failed. */
@Composable
fun PageStatus(
    state: PageState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val colors = Theme.colors
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val centred = Modifier.widthIn(max = 420.dp)
        when (state) {
            PageState.Loading -> BasicText(
                stringResource(R.string.loading),
                centred,
                style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.Center),
            )
            is PageState.Empty -> {
                if (state.icon != null) Icon(state.icon, size = 40.dp, tint = colors.faint)
                BasicText(state.title, centred.semantics { heading() }, style = Theme.type.title.copy(fontSize = Theme.type.section.fontSize, color = colors.text, textAlign = TextAlign.Center))
                if (state.detail.isNotEmpty()) {
                    BasicText(state.detail, centred, style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.Center))
                }
            }
            is PageState.Error -> {
                BasicText(
                    stringResource(R.string.page_failed),
                    centred.semantics { heading() },
                    style = Theme.type.title.copy(fontSize = Theme.type.section.fontSize, color = colors.text, textAlign = TextAlign.Center),
                )
                BasicText(state.message, centred, style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.Center))
                TextButton(stringResource(R.string.try_again), onRetry)
            }
        }
    }
}

/** Something the app needs to say, across the top of the page until it's dismissed. */
@Composable
fun NoticeBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.dimens.gap, vertical = 4.dp)
            .background(colors.overlay, RoundedCornerShape(Theme.dimens.radius))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(R.drawable.ic_info, size = 18.dp, tint = colors.warm)
        Spacer(Modifier.size(10.dp))
        BasicText(text, Modifier.weight(1f).padding(vertical = 12.dp), style = Theme.type.body.copy(color = colors.text))
        IconButton(R.drawable.ic_x, stringResource(R.string.dismiss), onDismiss, iconSize = 18.dp)
    }
}
