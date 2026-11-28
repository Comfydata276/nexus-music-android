package org.medianexus.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.medianexus.music.R
import org.medianexus.music.nexus.AppScope
import org.medianexus.music.nexus.ListeningResult
import org.medianexus.music.nexus.ListeningStats
import org.medianexus.music.nexus.Nexus
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.theme.Theme

@Composable
internal fun ListeningSetting(nexus: Nexus) {
    var open by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.your_stats), stringResource(R.string.nexus_stats_detail)) {
        TextButton(stringResource(R.string.nexus_show_stats), onClick = { open = true })
    }
    if (open) ListeningDialog(nexus) { open = false }
}

@Composable
internal fun RecordingSetting() {
    var open by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.nexus_records_title), "") {
        TextButton(stringResource(R.string.nexus_records_read), onClick = { open = true })
    }
    if (open) {
        val close = { open = false }
        ListeningDialogFrame(stringResource(R.string.nexus_records_title), close, actions = {
            TextButton(stringResource(R.string.nexus_listening_done), onClick = close, primary = true)
        }) {
            ListeningText(stringResource(R.string.nexus_records_songs))
            ListeningText(stringResource(R.string.nexus_records_search))
            ListeningText(stringResource(R.string.nexus_records_copies))
            ListeningText(stringResource(R.string.nexus_records_diagnostics))
        }
    }
}

private data class Reading(val scope: AppScope, val word: String)

@Composable
private fun ListeningDialog(nexus: Nexus, close: () -> Unit) {
    val session by nexus.appSession.collectAsStateWithLifecycle()
    val work = rememberCoroutineScope()
    val resources = LocalResources.current
    val scope = session?.scope
    val page by nexus.listening.page.collectAsStateWithLifecycle()
    val stats = page?.takeIf { it.scope == scope }?.stats
    var reading by remember { mutableStateOf<Reading?>(null) }
    var result by remember { mutableStateOf<Reading?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf<AppScope?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(session, refresh) {
        if (!deleting && deleteOpen != scope) deleteOpen = null
        if (scope == null || session?.offline != false || session?.verified != true || deleteOpen != null || deleting) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        reading = Reading(scope, resources.getString(R.string.nexus_stats_getting))
        val outcome = nexus.listening.read(scope)
        if (nexus.appSession.value == session) {
            reading = Reading(scope, when (outcome) {
                is ListeningResult.Done -> if (outcome.value.recent.isEmpty()) resources.getString(R.string.nexus_stats_empty) else ""
                is ListeningResult.Error -> outcome.words
            })
        }
        busy = false
    }

    val deletion = deleteOpen
    if (deletion != null && (deleting || deletion == scope)) {
        DeleteHistory(
            working = deleting,
            words = result?.takeIf { it.scope == scope }?.word.orEmpty(),
            close = { if (!deleting) { deleteOpen = null; result = null } },
            delete = { password ->
                if (!deleting && nexus.appSession.value?.scope == deletion) {
                    deleting = true
                    result = null
                    work.launch {
                        val outcome = nexus.listening.delete(deletion, password)
                        val now = nexus.appSession.value?.scope
                        when {
                            outcome is ListeningResult.Done && now == outcome.value -> {
                                result = Reading(outcome.value, resources.getString(R.string.nexus_history_deleted))
                                deleteOpen = null
                            }
                            outcome is ListeningResult.Error && now != null && now == outcome.owner -> {
                                result = Reading(now, outcome.words)
                                deleteOpen = null
                            }
                            outcome is ListeningResult.Error && now == deletion -> result = Reading(deletion, outcome.words)
                            else -> deleteOpen = null
                        }
                        deleting = false
                    }
                }
            },
        )
        return
    }

    ListeningDialogFrame(stringResource(R.string.your_stats), close, actions = {
        TextButton(
            stringResource(R.string.nexus_history_delete_open),
            onClick = { result = null; deleteOpen = scope },
            enabled = session?.verified == true && session?.offline == false && !busy && stats?.canDelete == true,
        )
        TextButton(stringResource(R.string.refresh), onClick = { refresh++ }, enabled = !busy && session?.offline == false)
        TextButton(stringResource(R.string.nexus_listening_done), onClick = close, primary = true)
    }) {
        result?.takeIf { it.scope == scope }?.let { ListeningText(it.word) }
        if (stats != null) {
            val count = stats.starts.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
            BasicText(pluralStringResource(R.plurals.nexus_song_starts, count, stats.starts.toString()),
                style = Theme.type.rowTitle.copy(color = Theme.colors.text))
        }
        val status = when {
            session == null -> stringResource(R.string.nexus_stats_unavailable)
            session?.offline == true -> stringResource(R.string.nexus_stats_offline)
            session?.verified != true -> stringResource(R.string.nexus_stats_waiting)
            else -> reading?.takeIf { it.scope == scope }?.word.orEmpty()
        }
        if (status.isNotEmpty()) ListeningText(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (stats != null && stats.recent.isNotEmpty()) {
            BasicText(stringResource(R.string.nexus_recent_plays), style = Theme.type.rowTitle.copy(color = Theme.colors.text))
            stats.recent.forEach { play ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(play.title, style = Theme.type.rowTitle.copy(color = Theme.colors.text))
                    ListeningText(listOf(play.artist, play.album).filter(String::isNotEmpty).joinToString(" · "))
                    ListeningText(play.whenPlayed)
                }
            }
        }
    }
}

@Composable
private fun DeleteHistory(working: Boolean, words: String, close: () -> Unit, delete: (String) -> Unit) {
    val password = remember { TextFieldState() }
    fun clear() {
        password.clearText()
        password.undoState.clearHistory()
    }
    DisposableEffect(password) { onDispose { clear() } }
    val submit = {
        if (!working && password.text.isNotEmpty()) {
            delete(password.text.toString())
            clear()
        }
    }
    ListeningDialogFrame(stringResource(R.string.nexus_history_delete_title), close, actions = {
        TextButton(stringResource(R.string.cancel), onClick = close, enabled = !working)
        TextButton(stringResource(if (working) R.string.nexus_history_deleting else R.string.nexus_history_delete),
            onClick = submit, enabled = !working && password.text.isNotEmpty(), danger = true)
    }) {
        ListeningText(stringResource(R.string.nexus_history_delete_detail))
        ListeningText(stringResource(R.string.nexus_history_delete_copies))
        ListeningText(stringResource(R.string.nexus_history_delete_password))
        val label = stringResource(R.string.nexus_history_password)
        Field(password, label, placeholder = label, secret = true, enabled = !working,
            contentType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            onKeyboardAction = { submit() })
        if (words.isNotEmpty()) ListeningText(words, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListeningDialogFrame(
    title: String,
    close: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = close) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .background(Theme.colors.raised, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(title, Modifier.semantics { heading() }, style = Theme.type.rowTitle.copy(color = Theme.colors.text))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions() }
        }
    }
}

@Composable
private fun ListeningText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
}
