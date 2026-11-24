package org.medianexus.music.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import org.medianexus.music.ui.home.HomePage
import org.medianexus.music.ui.library.AlbumsPage
import org.medianexus.music.ui.library.LibraryPage
import org.medianexus.music.ui.lists.AlbumPage
import org.medianexus.music.ui.lists.ArtistPage
import org.medianexus.music.ui.lists.GenrePage
import org.medianexus.music.ui.lists.LikedPage
import org.medianexus.music.ui.lists.PlaylistPage
import org.medianexus.music.ui.menu.SongMenuSheet
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.NoticeBar
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.ToastHost
import org.medianexus.music.ui.player.MiniPlayer
import org.medianexus.music.ui.player.NowPlaying
import org.medianexus.music.ui.player.QueueSheet
import org.medianexus.music.ui.search.SearchPage
import org.medianexus.music.ui.settings.DiagnosticsPage
import org.medianexus.music.ui.settings.SettingsPage
import org.medianexus.music.ui.theme.Theme

/**
 * The app once signed in: the page, any notices, the mini player and the bottom bar; over
 * them, Now Playing, the queue, a song's menu and toasts. Pages draw their own title bars,
 * below the status bar; the bottom bar reaches under the navigation bar. While the keyboard
 * is up (searching) the bar and the mini player step aside for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(graph: Graph) {
    val navigator = rememberNavigator()
    // Rotating, or coming back after Android stopped the app, keeps Now Playing open if it
    // was: what was saved is where it starts, before anything else can look at it. It closes
    // for want of a song only once the player has said there is none: on coming back,
    // nothing is known until the screens have connected to it again and it has put back
    // the saved queue.
    var openSaved by rememberSaveable { mutableStateOf(false) }
    val ui = remember(graph, navigator) { Ui(graph, navigator).also { it.nowPlaying = openSaved } }
    LaunchedEffect(ui) { snapshotFlow { ui.nowPlaying }.collect { openSaved = it } }
    val account by graph.accounts.state.collectAsStateWithLifecycle()
    ui.account = account
    val colors = Theme.colors
    val keyboard = WindowInsets.isImeVisible
    val sideways = sidewaysPhone()
    val song by graph.player.song.collectAsStateWithLifecycle()
    val reported by graph.player.reported.collectAsStateWithLifecycle()
    LaunchedEffect(song == null, reported) { if (song == null && reported) ui.nowPlaying = false }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalUi provides ui,
        org.medianexus.music.ui.parts.LocalCardMenu provides { card -> org.medianexus.music.ui.menu.openCardMenu(ui, card) },
    ) {
      ui.askToDownload = org.medianexus.music.ui.downloads.rememberDownloadAsk()
      WithOfflinePill {
        Box(Modifier.fillMaxSize().background(colors.base)) {
            // On its side, a cut-out or the navigation bar can be at either edge: nothing is
            // drawn under them. (The page still reaches the screen's top and foot.)
            val edges = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
            if (sideways) {
                // A phone on its side has little height to spare: the three places go in a
                // rail down the leading edge, and the mini player sits along the foot of the
                // page only.
                Row(edges) {
                    SideRail(navigator.tab, navigator::select)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth().then(if (keyboard) Modifier.imePadding() else Modifier)) {
                            Pages(graph, navigator)
                        }
                        LocalNetworkBar(graph)
                        NoticeList(graph)
                        if (!keyboard) MiniPlayer(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                    }
                }
            } else {
                Column(edges) {
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (keyboard) Modifier.imePadding() else Modifier)) {
                        Pages(graph, navigator)
                    }
                    LocalNetworkBar(graph)
                    NoticeList(graph)
                    if (!keyboard) {
                        MiniPlayer()
                        BottomBar(navigator.tab, navigator::select)
                    }
                }
            }
            AnimatedVisibility(
                ui.nowPlaying,
                enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
            ) {
                NowPlaying(onClose = { ui.nowPlaying = false })
            }
            ToastHost(
                graph.toasts.all,
                Modifier
                    .align(Alignment.BottomCenter)
                    // Above the keyboard too, when it's up (searching).
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    // Above the bottom bar and the mini player, unless Now Playing covers them.
                    .padding(
                        bottom = when {
                            ui.nowPlaying || keyboard -> 16.dp
                            sideways -> Theme.dimens.miniPlayer + 8.dp
                            else -> 64.dp + Theme.dimens.miniPlayer + 8.dp
                        },
                    ),
            )
        }
        if (ui.queueOpen) QueueSheet(onClose = { ui.queueOpen = false })
        ui.menu?.let { menu -> SongMenuSheet(menu, onClose = { ui.menu = null }) }
        ui.sheet?.let { sheet -> SheetHost(sheet, onClose = { if (ui.sheet === sheet) ui.sheet = null }) }
      }
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
    when (route) {
        Route.Home -> HomePage()
        Route.Search -> SearchPage()
        Route.Library -> LibraryPage()
        Route.Settings -> SettingsPage(graph, onBack = navigator::back, onDiagnostics = { navigator.open(Route.Diagnostics) }, onLicences = { navigator.open(Route.Licences) })
        Route.Diagnostics -> DiagnosticsPage(graph, onBack = navigator::back)
        is Route.Album -> AlbumPage(route)
        is Route.Artist -> ArtistPage(route)
        is Route.Playlist -> PlaylistPage(route)
        Route.Liked -> LikedPage()
        is Route.Genre -> GenrePage(route)
        is Route.Albums -> AlbumsPage(route)
        Route.Downloads -> org.medianexus.music.ui.downloads.DownloadsPage()
        Route.Songs -> org.medianexus.music.ui.lists.SongsPage()
        Route.RecentlyPlayed -> org.medianexus.music.ui.lists.RecentlyPlayedPage()
        Route.Radio -> org.medianexus.music.ui.lists.RadioPage()
        is Route.Mix -> org.medianexus.music.ui.mixes.MixPage(route)
        is Route.Stats -> org.medianexus.music.ui.stats.StatsPage(route)
        is Route.Downloaded -> org.medianexus.music.ui.downloads.DownloadedListPage(route)
        Route.Licences -> org.medianexus.music.ui.settings.LicencesPage(onBack = navigator::back)
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
                is SettingsDamage.StartedAfresh -> damage.keptAs?.let { stringResource(R.string.notice_settings_reset, it) }
                    ?: stringResource(R.string.notice_settings_reset_lost)
            }
            Notice.PasswordNotSaved -> stringResource(R.string.notice_password_not_saved)
            Notice.SignedInWithoutEncryption -> stringResource(R.string.notice_unencrypted)
            Notice.SignOutFailed -> stringResource(R.string.notice_sign_out_failed)
            Notice.SignInGone -> stringResource(R.string.notice_sign_in_gone)
            Notice.SettingsNotSaved -> stringResource(R.string.notice_settings_not_saved)
        }
        NoticeBar(text, onDismiss = { graph.notices.dismiss(notice) }, modifier)
    }
}

/**
 * While the server is on the home network and the app isn't allowed to reach it (Android
 * 17), a bar says so and offers to ask; it goes once allowed. Over every page, and on Now
 * Playing.
 */
@Composable
fun LocalNetworkBar(graph: Graph, modifier: Modifier = Modifier) {
    val blocked by graph.localAccess.blocked.collectAsStateWithLifecycle()
    if (!blocked) return
    val certain by graph.localAccess.certain.collectAsStateWithLifecycle()
    val ask = rememberLocalNetworkAsk(graph.localAccess)
    val colors = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.dimens.gap, vertical = 4.dp)
            .background(colors.overlay, RoundedCornerShape(Theme.dimens.radius))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(R.drawable.ic_wifi_off, size = 18.dp, tint = colors.warm)
        BasicText(
            stringResource(if (certain) R.string.local_network_blocked else R.string.local_network_maybe),
            Modifier.weight(1f),
            style = Theme.type.rowDetail.copy(color = colors.text),
        )
        TextButton(stringResource(R.string.allow), { ask(null) })
    }
}

/**
 * A phone held on its side: wider than tall, and short (under 480dp). There the bottom bar
 * becomes a rail down the leading edge, and pages put their header and play button side by
 * side. A tablet on its side has the height for the bar, and keeps it.
 */
@Composable
fun sidewaysPhone(): Boolean {
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val tall = with(androidx.compose.ui.platform.LocalDensity.current) { window.height.toDp() }
    return window.width > window.height && tall < 480.dp
}

/** The bottom bar's three places, down the leading edge: for a phone on its side. */
@Composable
private fun SideRail(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = Theme.colors
    Column(
        Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(colors.panel)
            .drawBehind {
                // Its line on the side the pages are, whichever way the language runs.
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) size.width else 0f
                drawLine(colors.line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (tab in Tab.entries) {
            val on = tab == current
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(tab.icon, size = 24.dp, tint = if (on) colors.text else colors.faint)
                BasicText(
                    stringResource(tab.label),
                    style = Theme.type.caption.copy(color = if (on) colors.text else colors.muted, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
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
                    style = Theme.type.caption.copy(color = if (on) colors.text else colors.muted, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}
