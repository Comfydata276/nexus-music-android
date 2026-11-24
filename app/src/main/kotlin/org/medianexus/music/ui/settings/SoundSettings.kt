package org.medianexus.music.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.medianexus.music.Graph
import org.medianexus.music.R
import org.medianexus.music.sound.Eq
import org.medianexus.music.store.EQ_PRESETS
import org.medianexus.music.store.EqCurve
import org.medianexus.music.store.PREAMPS
import org.medianexus.music.store.SavedCurve
import org.medianexus.music.store.Settings
import org.medianexus.music.store.UNTAGGED_LEVELS
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.Toggle
import org.medianexus.music.ui.theme.Theme

/** The built-in presets' names, in `EQ_PRESETS`' order. */
val PRESET_NAMES = listOf(
    R.string.eq_flat, R.string.eq_bass_boost, R.string.eq_bass_reduce, R.string.eq_treble_boost, R.string.eq_vocal,
    R.string.eq_rock, R.string.eq_pop, R.string.eq_jazz, R.string.eq_classical, R.string.eq_electronic,
)

/**
 * Sound's "Volume and tone", as Windows has it under that heading (ui/pages.slint): even out
 * volume, and under it the level, what it levels by and what a song with nothing to go by
 * gets; mono; the equaliser, its panel and its preamp. In Windows' order and words. Every
 * change is heard at once, ramped (see SoundProcessor).
 */
@Composable
internal fun VolumeAndTone(graph: Graph) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    SettingHeading(stringResource(R.string.volume_and_tone))

    val even = stringResource(R.string.even_out_volume)
    SettingRow(even, stringResource(R.string.even_out_volume_detail)) {
        Toggle(settings.normalize, { on -> graph.settings.update { it.copy(normalize = on) } }, even)
    }
    if (settings.normalize) {
        SettingRow(stringResource(R.string.level), stringResource(R.string.level_detail), nested = true, stacked = true) {
            Choices(listOf(R.string.level_quiet, R.string.level_normal, R.string.level_loud).map { stringResource(it) }, settings.volumeLevel.coerceIn(0, 2), stringResource(R.string.volume_level)) { chosen ->
                graph.settings.update { it.copy(volumeLevel = chosen) }
            }
        }
        val by = stringResource(R.string.level_by)
        SettingRow(by, stringResource(R.string.level_by_detail), nested = true, stacked = true) {
            Choices(listOf(R.string.level_auto, R.string.level_song, R.string.level_album).map { stringResource(it) }, settings.levelMode.coerceIn(0, 2), by) { chosen ->
                graph.settings.update { it.copy(levelMode = chosen) }
            }
        }
        SettingRow(stringResource(R.string.untagged), stringResource(R.string.untagged_detail), nested = true, stacked = true) {
            val at = UNTAGGED_LEVELS.indexOfFirst { kotlin.math.abs(it - settings.untaggedDb) < 0.1f }.coerceAtLeast(0)
            Choices(UNTAGGED_LEVELS.map { if (it == 0f) stringResource(R.string.untagged_leave) else stringResource(R.string.decibels, -it.toInt()) }, at, stringResource(R.string.untagged_label)) { chosen ->
                graph.settings.update { it.copy(untaggedDb = UNTAGGED_LEVELS[chosen]) }
            }
        }
    }

    val mono = stringResource(R.string.mono_audio)
    SettingRow(mono, stringResource(R.string.mono_audio_detail)) {
        Toggle(settings.mono, { on -> graph.settings.update { it.copy(mono = on) } }, mono)
    }

    val eq = stringResource(R.string.equaliser)
    SettingRow(eq, stringResource(R.string.equaliser_detail)) {
        Toggle(settings.equalizer, { on -> graph.settings.update { it.copy(equalizer = on) } }, eq)
    }
    if (settings.equalizer) {
        EqPanel(graph, settings, Modifier.padding(vertical = 12.dp))
        SettingRow(stringResource(R.string.preamp), stringResource(R.string.preamp_detail), nested = true, stacked = true) {
            val at = PREAMPS.indexOfFirst { kotlin.math.abs(it - settings.eqPreamp) < 0.1f }.takeIf { it >= 0 } ?: 1
            Choices(PREAMPS.map { if (it > 0f) stringResource(R.string.preamp_auto) else if (it == 0f) stringResource(R.string.off) else stringResource(R.string.decibels, -it.toInt()) }, at, stringResource(R.string.preamp_label)) { chosen ->
                graph.settings.update { it.copy(eqPreamp = PREAMPS[chosen]) }
            }
        }
    }
}

/** A band's frequency as Windows labels it: "60 Hz", "2.4 kHz". */
@Composable
private fun bandName(band: Int): String {
    val hz = Eq.BANDS[band]
    return if (hz >= 1000f) {
        val k = hz / 1000f
        stringResource(R.string.khz, if (k == k.toInt().toFloat()) k.toInt().toString() else k.toString())
    } else {
        stringResource(R.string.hz, hz.toInt().toString())
    }
}

/** A gain as said and shown: "+3 dB", "−4.5 dB", "0 dB". */
private fun gainWords(gain: Float): String {
    val g = (gain * 2).roundToInt() / 2f
    val number = if (g == g.toInt().toFloat()) kotlin.math.abs(g.toInt()).toString() else kotlin.math.abs(g).toString()
    return when {
        g > 0f -> "+$number"
        g < 0f -> "−$number"
        else -> "0"
    }
}

/** Half-decibel steps are finer than anyone can hear and keep values tidy (as Windows). */
private fun tidy(gain: Float): Float = ((gain * 2).roundToInt() / 2f).coerceIn(-Eq.RANGE, Eq.RANGE)

private fun setBand(graph: Graph, band: Int, gain: Float) {
    graph.settings.update { s ->
        val gains = s.eqGains.toMutableList().also { it[band] = tidy(gain) }
        if (gains == s.eqGains) s else s.copy(eqGains = gains)
    }
}

/**
 * Windows' `EqPanel`: the preset in use (a pill that opens the presets) and Reset, then six
 * points on a plus-or-minus 12 dB plot, the curve through them with the area beneath it
 * filled. For a thumb, each band is a column the width of a sixth of the panel (48 dp or more
 * on a 360 dp phone): drag anywhere in it, or tap where the point should go. For TalkBack each
 * band is an adjustable control named with its frequency and value, with Raise and Lower
 * actions a decibel at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqPanel(graph: Graph, settings: Settings, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    val res = LocalResources.current
    var presets by remember { mutableStateOf(false) }
    val curve = settings.eqPreset()
    val curveName = when (curve) {
        is EqCurve.BuiltIn -> stringResource(PRESET_NAMES[curve.index])
        is EqCurve.Saved -> curve.name
        EqCurve.Custom -> stringResource(R.string.eq_custom)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.dimens.radius))
            .background(colors.raised)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(stringResource(R.string.preset), style = Theme.type.body.copy(color = colors.muted))
            val said = stringResource(R.string.equaliser_preset, curveName)
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = Theme.dimens.touch)
                    .clickable(role = Role.Button) { presets = true }
                    .semantics(mergeDescendants = true) { contentDescription = said },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(colors.hover).padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(curveName, Modifier.weight(1f, fill = false), style = Theme.type.label.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(R.drawable.ic_chevron_down, size = 16.dp, tint = colors.text)
                }
            }
            Box(Modifier.weight(1f, fill = true))
            TextButton(stringResource(R.string.reset), onClick = { graph.settings.update { it.copy(eqGains = List(6) { 0f }) } })
        }
        BasicText(stringResource(R.string.eq_top), Modifier.padding(top = 8.dp), style = Theme.type.caption.copy(color = colors.faint))
        Plot(graph, settings.eqGains)
        BasicText(stringResource(R.string.eq_bottom), style = Theme.type.caption.copy(color = colors.faint))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (band in 0 until 6) {
                BasicText(bandName(band), Modifier.weight(1f), style = Theme.type.caption.copy(color = colors.muted, textAlign = TextAlign.Center), maxLines = 1)
            }
        }
    }
    if (presets) {
        PresetSheet(graph, settings, curve, onClose = { presets = false }, toast = { graph.toasts.show(res.getString(it.first, *it.second)) })
    }
}

/** The plot: the zero line, the curve and its fill, and a point per band, each band's column
 *  a control of its own. */
@Composable
private fun Plot(graph: Graph, gains: List<Float>) {
    val colors = Theme.colors
    val plotHeight = 168.dp
    val accent = colors.accent
    val bright = colors.accentBright
    val line = colors.line
    Box(Modifier.fillMaxWidth().height(plotHeight + 24.dp)) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val top = 12.dp.toPx()
            val h = plotHeight.toPx()
            val step = size.width / 6
            fun x(band: Int) = step * band + step / 2
            fun y(gain: Float) = top + h / 2 - gain / Eq.RANGE * h / 2
            drawLine(line, Offset(0f, top + h / 2), Offset(size.width, top + h / 2), 1.dp.toPx())
            for (band in 0 until 6) drawLine(line, Offset(x(band), top), Offset(x(band), top + h), 1.dp.toPx())
            val path = Path().apply {
                moveTo(x(0), y(gains[0]))
                for (band in 1 until 6) lineTo(x(band), y(gains[band]))
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(5), top + h)
                lineTo(x(0), top + h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.04f)), startY = top, endY = top + h))
            drawPath(path, bright, style = Stroke(2.dp.toPx()))
            for (band in 0 until 6) drawCircle(Color.White, 6.dp.toPx(), Offset(x(band), y(gains[band])))
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            for (band in 0 until 6) Band(graph, band, gains[band], plotHeight, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** One band's column: drag in it, or tap, to set the band where the finger is. */
@Composable
private fun Band(graph: Graph, band: Int, gain: Float, plotHeight: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val name = stringResource(R.string.eq_band, bandName(band))
    val value = stringResource(R.string.eq_band_value, gainWords(gain))
    val raise = stringResource(R.string.eq_raise)
    val lower = stringResource(R.string.eq_lower)
    val current by rememberUpdatedState(gain)
    Box(
        modifier
            .semantics {
                contentDescription = name
                stateDescription = value
                progressBarRangeInfo = ProgressBarRangeInfo(gain, -Eq.RANGE..Eq.RANGE, steps = 47)
                setProgress { target ->
                    setBand(graph, band, target)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(raise) { setBand(graph, band, current + 1f); true },
                    CustomAccessibilityAction(lower) { setBand(graph, band, current - 1f); true },
                )
            }
            .pointerInput(band) {
                val top = 12.dp.toPx()
                val h = plotHeight.toPx()
                fun gainAt(y: Float) = ((top + h / 2 - y) / (h / 2) * Eq.RANGE).coerceIn(-Eq.RANGE, Eq.RANGE)
                detectTapGestures { setBand(graph, band, gainAt(it.y)) }
            }
            .pointerInput(band) {
                val top = 12.dp.toPx()
                val h = plotHeight.toPx()
                fun gainAt(y: Float) = ((top + h / 2 - y) / (h / 2) * Eq.RANGE).coerceIn(-Eq.RANGE, Eq.RANGE)
                detectVerticalDragGestures(onDragStart = { setBand(graph, band, gainAt(it.y)) }) { change, _ ->
                    change.consume()
                    setBand(graph, band, gainAt(change.position.y))
                }
            },
    )
}

/**
 * Windows' preset menu, as a sheet: the presets that come with the app, then your own, each
 * with a tick on the one in use; then "Save as a preset" and, when the curve in use is one
 * of your own, "Delete" it. Saving under a name already used replaces it (as Windows); the
 * names of the app's own presets are refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(graph: Graph, settings: Settings, curve: EqCurve, onClose: () -> Unit, toast: (Pair<Int, Array<Any>>) -> Unit) {
    val colors = Theme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    val name: TextFieldState = rememberTextFieldState()
    val builtIn = PRESET_NAMES.map { stringResource(it) }
    fun close(then: () -> Unit = {}) {
        then()
        scope.launch { state.hide() }.invokeOnCompletion { onClose() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = colors.overlay,
        contentColor = colors.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.faint) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            if (naming) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText(stringResource(R.string.name_this_preset), Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = colors.text))
                    BasicText(stringResource(R.string.name_this_preset_detail), style = Theme.type.body.copy(color = colors.muted))
                    Field(name, stringResource(R.string.preset_name))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(stringResource(R.string.cancel), onClick = { close() })
                        TextButton(stringResource(R.string.save_preset), primary = true, onClick = {
                            val chosen = name.text.toString().trim()
                            close {
                                when {
                                    chosen.isEmpty() -> {}
                                    chosen in builtIn -> toast(R.string.preset_name_taken to arrayOf())
                                    else -> {
                                        graph.settings.update { s -> s.copy(eqSaved = s.eqSaved.filter { it.name != chosen } + SavedCurve(chosen, s.eqGains)) }
                                        toast(R.string.preset_saved to arrayOf(chosen))
                                    }
                                }
                            }
                        })
                    }
                }
                return@Column
            }
            BasicText(stringResource(R.string.presets), Modifier.padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp).semantics { heading() }, style = Theme.type.heading.copy(color = colors.text))
            for ((index, gains) in EQ_PRESETS.withIndex()) {
                PresetEntry(builtIn[index], curve == EqCurve.BuiltIn(index)) { close { graph.settings.update { it.copy(eqGains = gains, equalizer = true) } } }
            }
            if (settings.eqSaved.isNotEmpty()) {
                Rule()
                for (saved in settings.eqSaved) {
                    PresetEntry(saved.name, curve == EqCurve.Saved(saved.name)) { close { graph.settings.update { it.copy(eqGains = saved.gains, equalizer = true) } } }
                }
            }
            Rule()
            Action(R.drawable.ic_list_plus, stringResource(R.string.save_as_preset)) { naming = true }
            if (curve is EqCurve.Saved) {
                Action(R.drawable.ic_trash_2, stringResource(R.string.delete_preset, curve.name), danger = true) {
                    close { graph.settings.update { s -> s.copy(eqSaved = s.eqSaved.filter { it.name != curve.name }) } }
                }
            }
        }
    }
}

@Composable
private fun PresetEntry(text: String, selected: Boolean, onClick: () -> Unit) {
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
        BasicText(text, style = Theme.type.body.copy(color = if (selected) Theme.colors.text else Theme.colors.muted, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Action(@DrawableRes icon: Int, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) Theme.colors.danger else Theme.colors.muted
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, size = 20.dp, tint = tint)
        BasicText(text, style = Theme.type.body.copy(color = if (danger) Theme.colors.danger else Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Theme.colors.line))
}
