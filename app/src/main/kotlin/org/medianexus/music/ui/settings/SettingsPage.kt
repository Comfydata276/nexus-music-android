package org.medianexus.music.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.medianexus.music.BuildConfig
import org.medianexus.music.Graph
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.downloads.Downloads
import org.medianexus.music.store.CACHE_AHEAD
import org.medianexus.music.store.CACHE_LIMITS
import org.medianexus.music.store.MOBILE_QUALITIES
import org.medianexus.music.store.QUALITIES
import org.medianexus.music.store.SAME_AS_WIFI
import org.medianexus.music.ui.parts.ChipRow
import org.medianexus.music.ui.parts.Segmented
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.theme.THEMES
import org.medianexus.music.ui.theme.Theme
import org.medianexus.music.ui.theme.ThemeColors

/** Settings' sections, as Windows' chips name them and in its order: Requests only in a build
 *  that has them. (Jams are off until the Nexus server hosts them: the backend design's step 6.) */
private enum class Section(val title: Int) { APPEARANCE(R.string.appearance), PLAYBACK(R.string.playback), SOUND(R.string.sound), STORAGE(R.string.storage), REQUESTS(R.string.requests), ACCOUNT(R.string.account), ABOUT(R.string.about) }

/**
 * Settings, as Windows' `SettingsPage` (ui/pages.slint) has them, rebuilt for a phone: the
 * same sections in a row of chips under the title, the same rows (a name, what it does, and
 * its control), the same headings within a section, in the same order and words; and only
 * what does something on the phone today.
 */
@Composable
fun SettingsPage(graph: Graph, onBack: () -> Unit, onDiagnostics: () -> Unit = {}, onLicences: () -> Unit = {}, onControlApps: () -> Unit = {}) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.settings), onBack = onBack)
        // (Requests only in a build where they exist: see requests/RequestsBuild.)
        val sections = Section.entries.filter { it != Section.REQUESTS || org.medianexus.music.requests.RequestsBuild.on }
        ChipRow(sections.map { stringResource(it.title) }, selected = section, onChoose = {
            section = it
        })
        Box(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad).height(1.dp).background(Theme.colors.line))
        // Each section opens at its top, as on Windows.
        androidx.compose.runtime.LaunchedEffect(section) { scroll.scrollTo(0) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = Theme.dimens.pagePad)
                .padding(bottom = 56.dp),
        ) {
            when (sections[section.coerceIn(0, sections.lastIndex)]) {
                Section.APPEARANCE -> Appearance(graph)
                Section.PLAYBACK -> Playback(graph, onControlApps)
                Section.SOUND -> Sound(graph)
                Section.STORAGE -> Storage(graph)
                Section.REQUESTS -> org.medianexus.music.ui.requests.RequestsSettings()
                Section.ACCOUNT -> Account(graph)
                Section.ABOUT -> About(graph, onDiagnostics, onLicences)
            }
        }
    }
}

@Composable
private fun Appearance(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    SettingRow(stringResource(R.string.theme), stringResource(R.string.theme_detail), stacked = true) {
        ThemeSwatches(settings.theme) { name ->
            // Chosen here: kept as the change for the account's other devices, and shown
            // once it is kept (sync/Chosen.kt).
            graph.choose(org.medianexus.music.sync.Chosen.Did.Theme(name))
        }
    }
}

@Composable
private fun Playback(graph: Graph, onControlApps: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.report_plays)
    SettingRow(title, stringResource(R.string.report_plays_detail)) {
        Toggle(settings.scrobble, { on -> graph.settings.update { it.copy(scrobble = on) } }, title)
    }
    // An app that has asked to control playback and not been looked at yet is said here by
    // a dot, quietly: nothing pops up and no notification is sent (playback/ControlApps.kt).
    val unseen = settings.playbackApps.count { !it.seen && !it.allowed }
    SettingRow(stringResource(R.string.control_apps), stringResource(R.string.control_apps_detail), stacked = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(stringResource(R.string.control_apps_show), onClick = onControlApps)
            if (unseen > 0) {
                val asked = androidx.compose.ui.res.pluralStringResource(R.plurals.control_apps_asked, unseen, unseen)
                Box(Modifier.size(9.dp).background(Theme.colors.accentBright, androidx.compose.foundation.shape.CircleShape))
                BasicText(asked, style = Theme.type.rowDetail.copy(color = Theme.colors.text))
            }
        }
    }
}

/** The quality names and Windows' words for each, in `QUALITIES`' order. */
private val QUALITY_NAMES = listOf(R.string.quality_low, R.string.quality_normal, R.string.quality_high, R.string.quality_lossless)
private val QUALITY_DETAILS = listOf(R.string.quality_low_detail, R.string.quality_normal_detail, R.string.quality_high_detail, R.string.quality_lossless_detail)

@Composable
private fun Sound(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val format by graph.player.format.collectAsStateWithLifecycle()
    val at = QUALITIES.indexOf(settings.bitrate).takeIf { it >= 0 } ?: QUALITIES.lastIndex
    val title = stringResource(R.string.streaming_quality)
    // As Windows says it: what the choice costs, and what is playing now. A change applies to
    // the song playing at once (see the playback service).
    val detail = stringResource(QUALITY_DETAILS[at]) + if (format.text.isNotEmpty()) "\n" + stringResource(R.string.playing_now_as, format.text) else ""
    SettingRow(title, detail, stacked = true) {
        Choices(QUALITY_NAMES.map { stringResource(it) }, at, title) { chosen -> graph.settings.update { it.copy(bitrate = QUALITIES[chosen]) } }
    }
    // The phone's own (Windows has no mobile data): a second limit for songs streamed over
    // it, the same choices and words, and "Same as Wi-Fi" first, which is the one above.
    val mobileAt = MOBILE_QUALITIES.indexOf(settings.mobileBitrate).takeIf { it >= 0 } ?: 0
    val mobileTitle = stringResource(R.string.on_mobile_data)
    val mobileDetail = stringResource(R.string.on_mobile_data_detail) +
        if (MOBILE_QUALITIES[mobileAt] != SAME_AS_WIFI) "\n" + stringResource(QUALITY_DETAILS[mobileAt - 1]) else ""
    SettingRow(mobileTitle, mobileDetail, stacked = true) {
        Choices((listOf(R.string.same_as_wifi) + QUALITY_NAMES).map { stringResource(it) }, mobileAt, mobileTitle) { chosen ->
            graph.settings.update { it.copy(mobileBitrate = MOBILE_QUALITIES[chosen]) }
        }
    }
    VolumeAndTone(graph)
}

@Composable
private fun Storage(graph: Graph) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val offlineTitle = stringResource(R.string.offline_mode)
    SettingRow(offlineTitle, stringResource(R.string.offline_mode_detail)) {
        Toggle(settings.offline, { on ->
            graph.settings.update { it.copy(offline = on) }
            if (on) graph.toasts.show(res.getString(R.string.offline_mode_on))
        }, offlineTitle)
    }

    SettingHeading(stringResource(R.string.downloads))
    val qualityTitle = stringResource(R.string.download_quality)
    SettingRow(qualityTitle, stringResource(R.string.download_quality_detail), stacked = true) {
        val at = QUALITIES.indexOf(settings.downloadQuality).takeIf { it >= 0 } ?: QUALITIES.lastIndex
        Choices(QUALITY_NAMES.map { stringResource(it) }, at, qualityTitle) { chosen -> graph.settings.update { it.copy(downloadQuality = QUALITIES[chosen]) } }
    }
    val wifiTitle = stringResource(R.string.wifi_only)
    SettingRow(wifiTitle, stringResource(R.string.wifi_only_detail)) {
        Toggle(settings.downloadWifiOnly, { on -> graph.settings.update { it.copy(downloadWifiOnly = on) } }, wifiTitle)
    }
    val downloads = org.medianexus.music.ui.downloads.downloadsNow()
    val summary = org.medianexus.music.ui.downloads.summary(res, downloads)
    SettingRow(stringResource(R.string.downloaded_songs), stringResource(R.string.downloaded_songs_detail, summary), stacked = true) {
        TextButton(stringResource(R.string.remove_all), danger = true, enabled = downloads.downloaded.isNotEmpty() || downloads.busy, onClick = {
            // Windows removes at once. Everything downloaded is a lot to lose to a thumb that
            // brushed the button, and fetching it back may need Wi-Fi the phone hasn't got: said
            // first, and asked.
            ui.sheet = Sheet.Confirm(
                res.getString(R.string.remove_all_title),
                res.getString(R.string.remove_all_detail, summary),
                res.getString(R.string.remove_all),
            ) {
                // (Everything in the downloads' folder goes, off the main thread; said when
                // it has.)
                val account = ui.account
                account?.downloads?.let { downloads ->
                    account.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        graph.toasts.show(res.getString(if (downloads.removeAll()) R.string.downloads_removed else R.string.couldnt_remove_all_downloads))
                    }
                }
            }
        })
    }

    SettingHeading(stringResource(R.string.kept_songs))
    // What the kept songs take now, looked up on opening (a folder's worth of files) and
    // again when the limit changes.
    val keptDir = ui.account?.keptSongsDir
    val kept by produceState("0 MB", keptDir, settings.cacheLimitMb) {
        value = withContext(Dispatchers.IO) { Downloads.sizeLabel(keptDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L) }
    }
    val keepTitle = stringResource(R.string.keep_songs)
    SettingRow(keepTitle, stringResource(R.string.keep_songs_detail, kept), stacked = true) {
        val at = CACHE_LIMITS.indexOf(settings.cacheLimitMb).coerceAtLeast(0)
        Choices(CACHE_LIMITS.map { if (it == 0) stringResource(R.string.off) else stringResource(R.string.size_gb, it / 1024) }, at, keepTitle) { chosen ->
            graph.settings.update { it.copy(cacheLimitMb = CACHE_LIMITS[chosen]) }
        }
    }
    if (settings.cacheLimitMb > 0) {
        val aheadTitle = stringResource(R.string.fetch_ahead)
        SettingRow(aheadTitle, stringResource(R.string.fetch_ahead_detail), nested = true, stacked = true) {
            val at = CACHE_AHEAD.indexOf(settings.cacheAhead).coerceAtLeast(0)
            Choices(CACHE_AHEAD.map { if (it == 0) stringResource(R.string.off) else it.toString() }, at, aheadTitle) { chosen ->
                graph.settings.update { it.copy(cacheAhead = CACHE_AHEAD[chosen]) }
            }
        }
    }
}

@Composable
private fun Account(graph: Graph) {
    val session by graph.session.state.collectAsStateWithLifecycle()
    if (session !is SessionState.SignedIn) return
    // **Everything here is the Nexus server's machine's** (nexus/Nexus.kt): who is signed in,
    // the row for the server, the devices and their words, what changing a password says. The
    // phone draws it and says what was pressed.
    NexusAccount(graph)
    // Where Windows has it, in Windows' words. Off, nothing of this is read from the server
    // or written to it. Not shown at all in a build where it doesn't exist (this one: see
    // sync/InStepBuild.kt).
    if (!org.medianexus.music.sync.InStepBuild.on) return
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val inStep = stringResource(R.string.keep_in_step)
    // Said under it while this phone's clock disagrees with the server's: what changes here
    // is kept, and shared once the clock is right.
    val clockWrong by (graph.accounts.state.collectAsStateWithLifecycle().value?.step?.clockWrong ?: androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    SettingRow(inStep, stringResource(R.string.keep_in_step_detail) + if (clockWrong && settings.sync) " " + stringResource(R.string.keep_in_step_clock) else "") {
        Toggle(settings.sync, { on -> graph.settings.update { it.copy(sync = on) } }, inStep)
    }
}

@Composable
private fun About(graph: Graph, onDiagnostics: () -> Unit, onLicences: () -> Unit) {
    SettingRow(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), stringResource(R.string.about_detail)) {}
    val diagnostics = graph.recording.diagnostics
    val state by diagnostics.settings.collectAsStateWithLifecycle()
    val title = stringResource(R.string.diagnostics_send)
    val what = stringResource(R.string.diagnostics_send_detail)
    val detail = listOfNotNull(what, state.status).joinToString("\n")
    SettingRow(title, detail) {
        Toggle(state.switchedOn, diagnostics::choose, title)
    }
    SettingRow(stringResource(R.string.diagnostics), "", stacked = true) {
        TextButton(stringResource(R.string.diagnostics_what_sent), onClick = onDiagnostics)
    }
    SettingRow(stringResource(R.string.licences), stringResource(R.string.licences_detail), stacked = true) {
        TextButton(stringResource(R.string.show_licences), onClick = onLicences)
    }
}

/**
 * One setting, as Windows' `SettingRow`: its name (15, medium), what it does (13, muted), and
 * its control, with a rule under it. A small control (a switch, a button) sits at the end of
 * the row; a wide one (choices, swatches) goes under the words, as Windows puts it when the
 * row is narrow. `nested`: it belongs to the setting above, set in beside a rule.
 */
@Composable
internal fun SettingRow(
    title: String,
    detail: String,
    nested: Boolean = false,
    stacked: Boolean = false,
    control: @Composable () -> Unit,
) {
    val colors = Theme.colors
    Row(Modifier.fillMaxWidth()) {
        if (nested) {
            Box(Modifier.padding(start = 5.dp, end = 15.dp).width(2.dp).heightIn(min = 48.dp).background(colors.line))
        }
        Column(Modifier.weight(1f)) {
            val words = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(title, style = Theme.type.body.copy(color = colors.text, fontSize = Theme.type.rowTitle.fontSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                    if (detail.isNotEmpty()) BasicText(detail, style = Theme.type.rowDetail.copy(color = colors.muted))
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    words(Modifier.fillMaxWidth())
                    control()
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 61.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    words(Modifier.weight(1f))
                    control()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        }
    }
}

/** Names a run of related settings within a section (Windows' `SettingHeading`). */
@Composable
internal fun SettingHeading(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.BottomStart) {
        BasicText(text, Modifier.padding(bottom = 6.dp).semantics { heading() }, style = Theme.type.heading.copy(color = Theme.colors.text))
    }
}

/** Windows' segmented choices; on a narrow phone, with large type, they scroll sideways
 *  rather than squeeze. */
@Composable
internal fun Choices(options: List<String>, selected: Int, label: String, onChoose: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Segmented(options, selected, onChoose, label)
    }
}

/** The themes to pick from, as on Windows: each one's surface with a dot of its accent, and
 *  its name. Three to a line on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSwatches(chosen: String, onChoose: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        for (option in THEMES) {
            Swatch(option, option.name == chosen, Modifier.weight(1f).widthIn(max = 124.dp)) { onChoose(option.name) }
        }
    }
}

@Composable
private fun Swatch(option: ThemeColors, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(Theme.dimens.radius)
    val edge by animateColorAsState(if (selected) colors.accentBright else colors.line, label = "swatch")
    val name = stringResource(R.string.theme_named, option.name)
    Box(
        modifier
            .height(84.dp)
            .clip(shape)
            .background(option.panel)
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(14.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(option.accent))
        BasicText(
            option.name,
            Modifier.align(Alignment.BottomStart),
            style = Theme.type.label.copy(color = Color.White),
            maxLines = 1,
        )
    }
}
