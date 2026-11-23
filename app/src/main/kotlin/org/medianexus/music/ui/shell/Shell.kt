package org.medianexus.music.ui.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.medianexus.music.Graph
import org.medianexus.music.Notice
import org.medianexus.music.R
import org.medianexus.music.store.SettingsDamage
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.NoticeBar
import org.medianexus.music.ui.settings.SettingsPage
import org.medianexus.music.ui.theme.Theme

/**
 * The app once signed in: the page, any notices, room for the mini player, and the bottom
 * bar. Pages draw their own title bars, below the status bar; the bottom bar reaches under
 * the navigation bar.
 */
@Composable
fun Shell(graph: Graph) {
    val navigator = rememberNavigator()
    val colors = Theme.colors
    Column(Modifier.fillMaxSize().background(colors.base)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Pages(graph, navigator)
        }
        NoticeList(graph)
        // M1 docks the mini player here, between the page and the bottom bar.
        BottomBar(navigator.tab, navigator::select)
    }
}

@Composable
private fun Pages(graph: Graph, navigator: Navigator) {
    val decorator = rememberSaveableStateHolderNavEntryDecorator<Place>()
    val provider = remember(graph, navigator) {
        { place: Place ->
            NavEntry(place, contentKey = place.id) { Page(graph, navigator, it.route) }
        }
    }
    // Every tab's entries are kept, not only the one showing, so a tab's pages keep their
    // place while another tab is in front.
    val entries = Tab.entries.associateWith { tab ->
        rememberDecoratedNavEntries(navigator.stack(tab), listOf(decorator), provider)
    }
    val home = entries.getValue(Tab.HOME)
    val shown = if (navigator.tab == Tab.HOME) home else home + entries.getValue(navigator.tab)
    NavDisplay(
        entries = shown,
        onBack = navigator::back,
        transitionSpec = { if (navigator.motion == Motion.SWITCH) fade() else slide(forward = true) },
        popTransitionSpec = { if (navigator.motion == Motion.SWITCH) fade() else slide(forward = false) },
        predictivePopTransitionSpec = { slide(forward = false) },
    )
}

private fun fade() = fadeIn(tween(160)) togetherWith fadeOut(tween(120))

/** A page coming in from the side it lies on, the one it covers moving a little the other way. */
private fun slide(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(tween(260)) { width -> sign * width / 4 } + fadeIn(tween(200))) togetherWith
        (slideOutHorizontally(tween(260)) { width -> -sign * width / 8 } + fadeOut(tween(160)))
}

@Composable
private fun Page(graph: Graph, navigator: Navigator, route: Route) {
    val openSettings = { navigator.open(Route.Settings) }
    when (route) {
        Route.Home -> PlaceholderPage(R.string.tab_home, R.string.home_soon, R.drawable.ic_house, openSettings)
        Route.Search -> PlaceholderPage(R.string.tab_search, R.string.search_soon, R.drawable.ic_search, openSettings)
        Route.Library -> PlaceholderPage(R.string.tab_library, R.string.library_soon, R.drawable.ic_library, openSettings)
        Route.Settings -> SettingsPage(graph, onBack = navigator::back)
    }
}

/** The notices waiting to be seen, one bar each. */
@Composable
fun NoticeList(graph: Graph, modifier: Modifier = Modifier) {
    val notices by graph.notices.state.collectAsStateWithLifecycle()
    for (notice in notices) {
        val text = when (notice) {
            is Notice.SettingsDamaged -> when (val damage = notice.damage) {
                SettingsDamage.UsedBackup -> stringResource(R.string.notice_settings_backup)
                is SettingsDamage.StartedAfresh -> stringResource(R.string.notice_settings_reset, damage.keptAs)
            }
            Notice.PasswordNotSaved -> stringResource(R.string.notice_password_not_saved)
            Notice.SignedInWithoutEncryption -> stringResource(R.string.notice_unencrypted)
        }
        NoticeBar(text, onDismiss = { graph.notices.dismiss(notice) }, modifier)
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.panel)
            .drawBehind { drawLine(colors.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp)
            .selectableGroup(),
    ) {
        for (tab in Tab.entries) {
            val on = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(tab.icon, size = 24.dp, tint = if (on) colors.text else colors.faint)
                BasicText(
                    stringResource(tab.label),
                    style = Theme.type.caption.copy(color = if (on) colors.text else colors.muted, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}
