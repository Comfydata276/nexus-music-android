package org.medianexus.music.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.medianexus.music.R
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.theme.Theme

@Composable
internal fun BatchEntry(close: () -> Unit, submit: (String) -> Unit) {
    val state = remember { TextFieldState() }
    val title = stringResource(R.string.nexus_batch_request)
    val labels = stringResource(R.string.nexus_batch_lines)
    Dialog(onDismissRequest = close) {
        Column(
            Modifier.fillMaxWidth().background(Theme.colors.raised, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(title, style = Theme.type.rowTitle.copy(color = Theme.colors.text))
            BasicText(stringResource(R.string.nexus_batch_detail), style = Theme.type.rowDetail.copy(color = Theme.colors.muted))
            BasicTextField(
                state,
                Modifier.fillMaxWidth().background(Theme.colors.base, RoundedCornerShape(8.dp)).padding(8.dp)
                    .semantics { contentDescription = labels },
                textStyle = Theme.type.rowDetail.copy(color = Theme.colors.text),
                cursorBrush = SolidColor(Theme.colors.accentBright),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.cancel), onClick = close)
                TextButton(stringResource(R.string.nexus_batch_send), primary = true,
                    enabled = state.text.isNotBlank(), onClick = { submit(state.text.toString()) })
            }
        }
    }
}
