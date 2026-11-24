package org.medianexus.music.gallery

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.Chip
import org.medianexus.music.ui.parts.ChipRow
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.LinkText
import org.medianexus.music.ui.parts.MediaCard
import org.medianexus.music.ui.parts.NoticeBar
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.PlayButton
import org.medianexus.music.ui.parts.SectionTitle
import org.medianexus.music.ui.parts.Segmented
import org.medianexus.music.ui.parts.SkeletonRows
import org.medianexus.music.ui.parts.Slider
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.parts.TrackRow
import org.medianexus.music.ui.parts.TrackRowData
import org.medianexus.music.ui.theme.NexusTheme
import org.medianexus.music.ui.theme.THEMES
import org.medianexus.music.ui.theme.Theme
import org.medianexus.music.ui.theme.themeNamed

/**
 * Every shared part in every state, in any theme, for reviewing the look from screenshots.
 * Debug builds only. Open it with
 *
 *     adb shell am start -n org.medianexus.music/.gallery.GalleryActivity \
 *         --es theme Ocean --es section Rows
 *
 * `theme` is one of the theme names; `section` one of the names in `SECTIONS`. Both can be
 * changed on screen too.
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val theme = intent.getStringExtra("theme") ?: THEMES[0].name
        val section = SECTIONS.indexOfFirst { it.equals(intent.getStringExtra("section"), ignoreCase = true) }.coerceAtLeast(0)
        setContent { Gallery(theme, section) }
    }
}

private val SECTIONS = listOf("Buttons", "Icons", "Inputs", "Choices", "Covers", "Rows", "Status")

@Composable
private fun Gallery(initialTheme: String, initialSection: Int) {
    var themeName by rememberSaveable { mutableStateOf(themeNamed(initialTheme).name) }
    var section by rememberSaveable { mutableIntStateOf(initialSection) }
    NexusTheme(themeNamed(themeName)) {
        Column(Modifier.fillMaxSize().background(Theme.colors.base)) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                ChipRow(THEMES.map { it.name }, THEMES.indexOfFirst { it.name == themeName }, { themeName = THEMES[it].name })
                ChipRow(SECTIONS, section, { section = it })
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 24.dp),
            ) {
                when (SECTIONS[section]) {
                    "Buttons" -> Buttons()
                    "Icons" -> Icons()
                    "Inputs" -> Inputs()
                    "Choices" -> Choices()
                    "Covers" -> Covers()
                    "Rows" -> Rows()
                    "Status" -> Status()
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    BasicText(
        text,
        Modifier.padding(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, top = 16.dp, bottom = 4.dp),
        style = Theme.type.caption.copy(color = Theme.colors.faint),
    )
}

@Composable
private fun Line(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Buttons() {
    Label("IconButton: normal, text tint, active, disabled")
    Line {
        IconButton(R.drawable.ic_shuffle, "Shuffle", {})
        IconButton(R.drawable.ic_shuffle, "Shuffle", {}, tint = Theme.colors.text)
        IconButton(R.drawable.ic_shuffle, "Shuffle", {}, active = true)
        IconButton(R.drawable.ic_shuffle, "Shuffle", {}, enabled = false)
    }
    Label("PlayButton: play, pause, adds, 44dp")
    var playing by remember { mutableStateOf(false) }
    Line {
        PlayButton(playing = playing, onClick = { playing = !playing })
        PlayButton(playing = true, onClick = {})
        PlayButton(playing = false, onClick = {}, adds = true, label = "Request")
        PlayButton(playing = false, onClick = {}, size = 44.dp)
    }
    Label("TextButton: outlined, primary, danger, disabled")
    Line {
        TextButton("Try again", {})
        TextButton("Connect", {}, primary = true)
    }
    Line {
        TextButton("Delete", {}, danger = true)
        TextButton("Connecting…", {}, primary = true, enabled = false)
    }
    Label("TextButton: a long label in a narrow space")
    Line { TextButton("A label much too long to fit in the room given to it", {}, Modifier.width(200.dp)) }
    Label("LinkText")
    Line {
        LinkText("Show all", {})
        LinkText("Marrow & Pine", {}, color = Theme.colors.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Icons() {
    // Every ic_ drawable but the launcher's layers, found by name (this is a debug build).
    val icons = remember {
        R.drawable::class.java.fields
            .filter { it.name.startsWith("ic_") && !it.name.startsWith("ic_launcher") }
            .sortedBy { it.name }
            .map { it.name.removePrefix("ic_") to it.getInt(null) }
    }
    Label("${icons.size} icons, in muted, text and accent")
    FlowRow(Modifier.padding(horizontal = Theme.dimens.pagePad), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icons.forEachIndexed { index, (name, id) ->
            val tint = when (index % 3) {
                0 -> Theme.colors.muted
                1 -> Theme.colors.text
                else -> Theme.colors.accentBright
            }
            Column(Modifier.width(76.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(id, size = 24.dp, tint = tint)
                BasicText(name, style = Theme.type.caption.copy(color = Theme.colors.faint, fontSize = Theme.type.caption.fontSize * 0.85f), maxLines = 1)
            }
        }
    }
    Label("At 18dp and 32dp")
    Line {
        Icon(R.drawable.ic_heart, size = 18.dp)
        Icon(R.drawable.ic_heart_fill, size = 18.dp, tint = Theme.colors.warm)
        Icon(R.drawable.ic_play_fill, size = 18.dp, tint = Theme.colors.text)
        Icon(R.drawable.ic_heart, size = 32.dp)
        Icon(R.drawable.ic_heart_fill, size = 32.dp, tint = Theme.colors.warm)
        Icon(R.drawable.ic_play_fill, size = 32.dp, tint = Theme.colors.text)
        Icon(R.drawable.ic_tags, size = 32.dp, tint = Theme.colors.text)
    }
}

@Composable
private fun Inputs() {
    Label("Field: empty with a placeholder, filled, secret, disabled")
    Column(Modifier.padding(horizontal = Theme.dimens.pagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(rememberTextFieldState(), "Server address", placeholder = "http://192.168.1.10:4533")
        Field(rememberTextFieldState("https://music.example.org/a/long/path/that/scrolls/inside/the/field"), "Server address")
        Field(rememberTextFieldState("correct horse"), "Password", secret = true)
        Field(rememberTextFieldState("ada"), "Username", enabled = false)
    }
    Label("Slider: drag it; at 0, at 1, disabled")
    var value by remember { mutableFloatStateOf(0.35f) }
    Column(Modifier.padding(horizontal = Theme.dimens.pagePad)) {
        Slider(value, "Seek", onRelease = { value = it })
        Slider(0f, "Seek", onRelease = {})
        Slider(1f, "Volume", onRelease = {})
        Slider(0.6f, "Seek", onRelease = {}, enabled = false)
    }
}

@Composable
private fun Choices() {
    Label("Toggle: tap it; on, off, disabled")
    var on by remember { mutableStateOf(true) }
    Line {
        Toggle(on, { on = it }, "Gapless")
        Toggle(true, {}, "On")
        Toggle(false, {}, "Off")
        Toggle(true, {}, "Disabled", enabled = false)
    }
    Label("Segmented: as wide as its words, and sharing the width")
    var quality by remember { mutableIntStateOf(1) }
    Column(Modifier.padding(horizontal = Theme.dimens.pagePad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(listOf("Quiet", "Normal", "Loud"), quality.coerceAtMost(2), { quality = it }, "Loudness")
        Segmented(listOf("Low", "Normal", "High", "Lossless"), quality, { quality = it }, "Quality", Modifier.fillMaxWidth(), fill = true)
    }
    Label("Chip: chosen and not")
    Line {
        Chip("Albums", selected = true, onClick = {})
        Chip("Artists", selected = false, onClick = {})
    }
    Label("ChipRow: more than fits; it scrolls and fades at the end with more")
    var chosen by remember { mutableIntStateOf(0) }
    ChipRow(
        listOf("Recently added", "Name", "Artist", "Year", "Most played", "Recently played", "Random", "Rating"),
        chosen,
        { chosen = it },
    )
}

private val sampleCards = listOf(
    CardData(CardKind.ALBUM, "al1", "Field Notes", "Marrow & Pine"),
    CardData(CardKind.ARTIST, "ar1", "Marrow & Pine", "Artist"),
    CardData(CardKind.PLAYLIST, "pl1", "Long drives home through the hills at night", "41 songs"),
    CardData(CardKind.MIX, "mx1", "Your evening mix", "Made for you"),
)

@Composable
private fun Covers() {
    Label("Cover: card corners, row corners, round; 120, 56, 40dp")
    Line {
        Cover("c1", Modifier.size(120.dp))
        Cover("c1", Modifier.size(56.dp), CoverShape.ROW, R.drawable.ic_disc_3)
        Cover("c1", Modifier.size(40.dp), CoverShape.ROW)
        Cover("c1", Modifier.size(56.dp), CoverShape.ROUND, R.drawable.ic_user_round)
    }
    SectionTitle("Recently added", Modifier.padding(top = 16.dp), onShowAll = {})
    LazyRow(contentPadding = PaddingValues(horizontal = Theme.dimens.pagePad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(sampleCards, key = { it.id }) { card ->
            MediaCard(card, {}, Modifier.width(150.dp), onPlay = if (card.kind == CardKind.ARTIST) null else ({}), playing = card.kind == CardKind.MIX)
        }
    }
    SectionTitle("Genres, and a title with no Show all")
    Row(Modifier.padding(horizontal = Theme.dimens.pagePad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaCard(CardData(CardKind.GENRE, "g1", "Folk", "212 songs"), {}, Modifier.weight(1f))
        MediaCard(CardData(CardKind.GENRE, "g2", "Ambient and drone", "38 songs"), {}, Modifier.weight(1f))
    }
    SectionTitle("Cards without a play button")
    Row(Modifier.padding(horizontal = Theme.dimens.pagePad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        sampleCards.take(2).forEach { MediaCard(it, {}, Modifier.weight(1f)) }
    }
}

@Composable
private fun Rows() {
    val base = TrackRowData("s1", "Lanterns", "Marrow & Pine", number = "1", duration = "3:41")
    val none = org.medianexus.music.ui.downloads.RowMark.NONE
    val rows = listOf(
        Triple("Plain", base, none),
        Triple("Playing", base.copy(id = "s2", current = true), none),
        Triple("Playing, paused", base.copy(id = "s3", current = true, paused = true), none),
        Triple("Liked and downloaded", base.copy(id = "s4", liked = true), org.medianexus.music.ui.downloads.RowMark(org.medianexus.music.downloads.SongState.Done)),
        Triple("Waiting to download", base.copy(id = "s5w"), org.medianexus.music.ui.downloads.RowMark(org.medianexus.music.downloads.SongState.Waiting)),
        Triple("Downloading", base.copy(id = "s5"), org.medianexus.music.ui.downloads.RowMark(org.medianexus.music.downloads.SongState.Fetching(0.4f))),
        Triple("Couldn't be downloaded", base.copy(id = "s5f"), org.medianexus.music.ui.downloads.RowMark(org.medianexus.music.downloads.SongState.Failed("The disk is full."))),
        Triple("Unavailable offline", base.copy(id = "s6"), org.medianexus.music.ui.downloads.RowMark(unavailable = true)),
        Triple("Selected", base.copy(id = "s7", selected = true), none),
        Triple("Long words", base.copy(id = "s8", title = "A song with a title far too long for any phone held upright", artist = "Marrow & Pine, Elm, and the Late Night Ensemble"), none),
    )
    Label("TrackRow, with covers")
    rows.forEach { (_, track, mark) -> TrackRow(track, {}, {}, mark = mark) }
    Label("TrackRow, numbered as on an album page, with times")
    rows.take(3).forEach { (_, track, mark) -> TrackRow(track, {}, {}, showCover = false, showNumber = true, showDuration = true, mark = mark) }
    Label("SkeletonRows")
    SkeletonRows(rows = 4)
}

@Composable
private fun Status() {
    Label("TitleBar: a tab's first page, and a page with Back")
    TitleBar("Library") { IconButton(R.drawable.ic_settings, "Settings", {}, tint = Theme.colors.text) }
    TitleBar("A playlist whose name runs on and on", onBack = {}) { IconButton(R.drawable.ic_ellipsis, "More", {}) }
    Label("NoticeBar")
    NoticeBar("Couldn't save your password; you'll need to sign in next time.", {})
    Label("PageStatus: loading, empty, failed")
    for (state in listOf(
        PageState.Loading,
        PageState.Empty("No playlists yet", "Playlists you make on any device show up here.", R.drawable.ic_list_music),
        PageState.Error("Can't reach the server. The connection timed out. Check the address and your network."),
    )) {
        Box(Modifier.fillMaxWidth().height(300.dp).padding(horizontal = Theme.dimens.pagePad).background(Theme.colors.panel)) {
            PageStatus(state)
        }
        Box(Modifier.height(8.dp))
    }
}
