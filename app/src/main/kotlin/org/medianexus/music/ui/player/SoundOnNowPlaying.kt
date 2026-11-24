package org.medianexus.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.lyrics.LyricsShown
import org.medianexus.music.net.Song
import org.medianexus.music.playback.SleepTimer
import org.medianexus.music.ui.parts.Cover
import org.medianexus.music.ui.parts.CoverShape
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/**
 * The sleep timer's button, Windows' moon: lit while the timer is set, with what is left
 * beside it ("12 min", "After this song"), where it was set. Tapped, the menu Windows opens
 * from it ("Pause music after").
 */
@Composable
fun SleepButton() {
    val ui = LocalUi.current
    val sleep = ui.graph.sleep
    val state by sleep.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val res = LocalResources.current
    // What is left, moved on each minute while it counts down (only this reads it).
    val left by produceState(sleep.minutesLeft(state), state) {
        while (state is SleepTimer.State.At) {
            value = sleep.minutesLeft(state)
            delay(1_000)
        }
        value = 0
    }
    val label = when (state) {
        is SleepTimer.State.At -> res.getString(R.string.sleep_timer_left, left)
        SleepTimer.State.EndOfSong -> res.getString(R.string.sleep_timer_after_song)
        SleepTimer.State.Off -> res.getString(R.string.sleep_timer)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(R.drawable.ic_moon, label, { open = true }, active = state != SleepTimer.State.Off, iconSize = 22.dp)
        val short = when (state) {
            is SleepTimer.State.At -> stringResource(R.string.sleep_left_short, left)
            SleepTimer.State.EndOfSong -> stringResource(R.string.sleep_after_song_short)
            SleepTimer.State.Off -> null
        }
        // (Said with the button; the words are for the eye.)
        if (short != null) BasicText(short, Modifier.semantics { contentDescription = "" }, style = Theme.type.caption.copy(color = Theme.colors.accentBright), maxLines = 1)
    }
    if (open) SleepSheet(state, left) { open = false }
}

/** Windows' sleep menu, as a sheet: how long is left when it counts, then the choices, a
 *  tick on the one in use. Choosing says what will happen, as Windows' toast does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(state: SleepTimer.State, left: Int, onClose: () -> Unit) {
    val ui = LocalUi.current
    val sleep = ui.graph.sleep
    val res = LocalResources.current
    val colors = Theme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun choose(action: () -> Unit) {
        action()
        scope.launch { sheet.hide() }.invokeOnCompletion { onClose() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = colors.overlay,
        contentColor = colors.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.faint) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            BasicText(stringResource(R.string.pause_music_after), Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp).semantics { heading() }, style = Theme.type.heading.copy(color = colors.text))
            if (state is SleepTimer.State.At) {
                // The time-left line is there to be read, not to change anything.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Theme.dimens.pagePad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(R.drawable.ic_moon, size = 20.dp, tint = colors.accentBright)
                    BasicText(pluralStringResource(R.plurals.sleep_minutes_left, left, left), style = Theme.type.body.copy(color = colors.text, fontSize = Theme.type.rowTitle.fontSize))
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.line))
            }
            for (minutes in listOf(5, 10, 15, 30, 45, 60)) {
                val words = if (minutes == 60) stringResource(R.string.sleep_hour) else stringResource(R.string.sleep_minutes, minutes)
                Choice(words, selected = false) {
                    choose {
                        sleep.set(minutes)
                        ui.graph.toasts.show(res.getString(R.string.sleep_will_pause_in, minutes))
                    }
                }
            }
            Choice(stringResource(R.string.end_of_this_song), selected = state == SleepTimer.State.EndOfSong) {
                choose {
                    sleep.endOfSong()
                    ui.graph.toasts.show(res.getString(R.string.sleep_will_pause_end))
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.line))
            Choice(stringResource(R.string.off), selected = state == SleepTimer.State.Off) {
                choose {
                    sleep.set(0)
                    ui.graph.toasts.show(res.getString(R.string.sleep_off))
                }
            }
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(R.drawable.ic_check, size = 20.dp, tint = Theme.colors.accentBright)
        }
        BasicText(text, style = Theme.type.body.copy(color = Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Whether Now Playing shows the lyrics in the cover's place: remembered while the app runs,
 *  as Windows' lyrics page stays where it was until left, not each time Now Playing opens. */
object LyricsChoice {
    var shown by mutableStateOf(false)
}

/**
 * The lyrics of the song playing, in the cover's place (Windows' `LyricsPage`, and its Now
 * Playing's lyrics beside the cover): what's kept at once, then the server's. Timed lyrics
 * follow the music, the line being sung lit and the ones sung dimmed, and a tap on a line
 * plays from it; scrolled by hand, the view is left where it was put for a few seconds before
 * it follows again. No lyrics: said plainly, with what's up next beneath, as Windows' Now
 * Playing shows it when there are none.
 */
@Composable
fun LyricsPanel(song: Song, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val account = ui.account ?: return
    val shown by remember(account, song.id) { account.lyrics.forSong(song.id) }.collectAsStateWithLifecycle()
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Theme.dimens.radius)).background(Theme.colors.panel.copy(alpha = 0.6f))) {
        when (val s = shown) {
            LyricsShown.Looking -> Status(stringResource(R.string.looking_for_lyrics), null)
            is LyricsShown.None -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                Status(stringResource(R.string.no_lyrics), stringResource(if (s.offline) R.string.no_lyrics_offline else R.string.no_lyrics_detail), Modifier.fillMaxWidth())
                UpNextList()
            }
            is LyricsShown.Lines -> Lines(s)
        }
    }
}

@Composable
private fun Status(title: String, note: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = Theme.colors
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.raised), contentAlignment = Alignment.Center) {
            Icon(R.drawable.ic_mic_vocal, size = 26.dp, tint = colors.faint)
        }
        BasicText(title, style = Theme.type.heading.copy(color = colors.text, textAlign = TextAlign.Center, fontSize = 22.sp))
        if (note != null) BasicText(note, style = Theme.type.body.copy(color = colors.muted, textAlign = TextAlign.Center, fontSize = 15.sp))
    }
}

/** A glance at what's coming, as Windows' Now Playing has beside the cover when a song has
 *  no lyrics; the queue has the rest. */
@Composable
private fun UpNextList() {
    val ui = LocalUi.current
    val colors = Theme.colors
    val queue by ui.player.queue.collectAsStateWithLifecycle()
    BasicText(
        stringResource(if (queue.songs.isEmpty()) R.string.nothing_queued_after else R.string.up_next),
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = Theme.type.label.copy(color = colors.muted),
    )
    for ((index, song) in queue.songs.take(3).withIndex()) {
        val uid = queue.uids.getOrNull(index)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { uid?.let { ui.player.jump(it) } }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(song.coverArt, Modifier.size(40.dp), CoverShape.ROW)
            Column(Modifier.weight(1f)) {
                BasicText(song.title, style = Theme.type.rowTitle.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(song.artistLabel, style = Theme.type.rowDetail.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Lines(lyrics: LyricsShown.Lines) {
    val ui = LocalUi.current
    val colors = Theme.colors
    val list = rememberLazyListState()
    // Only the line being sung is worked out from the position, which moves four times a
    // second: what's drawn changes when the line does, not with every tick.
    val position = ui.player.position.collectAsStateWithLifecycle()
    val active by remember(lyrics) { derivedStateOf { if (lyrics.timed) LyricsShown.lineAt(lyrics.times, position.value) else -1 } }
    // Scrolled by hand: left where it was put, for a few seconds after the finger lifts.
    var handAt by remember { mutableLongStateOf(0L) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> holding = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    holding = false
                    handAt = android.os.SystemClock.uptimeMillis()
                }
            }
        }
    }
    val head = if (lyrics.timed) 0 else 1
    LaunchedEffect(active, holding) {
        if (active < 0 || holding) return@LaunchedEffect
        val waited = android.os.SystemClock.uptimeMillis() - handAt
        if (waited < HANDS_OFF_MS) delay(HANDS_OFF_MS - waited)
        // A third of the way down, so the lines to come are in sight.
        val third = list.layoutInfo.viewportSize.height / 3
        list.animateScrollToItem(active + head, -third)
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp)) {
        if (!lyrics.timed) {
            item(key = "note") {
                BasicText(stringResource(R.string.lyrics_not_timed), Modifier.padding(bottom = 12.dp), style = Theme.type.label.copy(color = colors.faint))
            }
        }
        itemsIndexed(lyrics.lines) { index, line ->
            val now = index == active
            val past = lyrics.timed && index < active
            val color = when {
                now || !lyrics.timed -> colors.text
                past -> colors.faint
                else -> colors.muted
            }
            val seek = if (lyrics.timed) {
                val label = stringResource(R.string.lyric_line_seek)
                Modifier.clickable(role = Role.Button, onClickLabel = label) { ui.player.seek(lyrics.times[index]) }
            } else {
                Modifier
            }
            BasicText(
                line.ifEmpty { "♪" },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).then(seek).padding(vertical = 7.dp),
                style = Theme.type.title.copy(color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

private const val HANDS_OFF_MS = 4_000L
