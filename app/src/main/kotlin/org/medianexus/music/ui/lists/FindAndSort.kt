package org.medianexus.music.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.medianexus.music.R
import org.medianexus.music.library.ListView
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.theme.Theme

/** The words for each of Windows' track orders (`TRACK_SORTS`), by its number; the list's
 *  own order is "Custom order" for a playlist (its owner chose it), "Default order" else. */
fun sortWord(sort: Int, playlist: Boolean): Int = when (sort) {
    ListView.TITLE -> R.string.sort_title
    ListView.ARTIST -> R.string.sort_artist
    ListView.ALBUM -> R.string.sort_album
    ListView.DURATION -> R.string.sort_duration
    ListView.ADDED -> R.string.sort_added
    else -> if (playlist) R.string.sort_custom else R.string.sort_default
}

/**
 * A list's find box and its sort button, as Windows' action bar has them (`FilterBox`,
 * `SortButton`), side by side under the list's play button. What is typed narrows the list at
 * once; the sort button says the order and which way it runs, and opens the orders to choose
 * from (Windows' "Sort by" menu, as a sheet). Choosing the order in use turns it round.
 */
@Composable
fun FindAndSort(box: TextFieldState, view: ListView, playlist: Boolean, onSort: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val colors = Theme.colors
    Row(
        modifier.fillMaxWidth().padding(start = Theme.dimens.pagePad, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FindBox(box, Modifier.weight(1f))
        val name = stringResource(sortWord(view.sort, playlist))
        val way = if (view.sort == ListView.OWN) "" else stringResource(if (view.descending) R.string.sort_descending else R.string.sort_ascending)
        val said = stringResource(R.string.sort_named, if (way.isEmpty()) name else "$name, $way")
        val ownWord = stringResource(sortWord(ListView.OWN, playlist))
        Row(
            Modifier
                .heightIn(min = Theme.dimens.touch)
                .widthIn(max = 168.dp)
                .clip(RoundedCornerShape(50))
                .clickable(role = Role.Button) { ui.sheet = Sheet.SortBy(view, ownWord, onSort) }
                .semantics(mergeDescendants = true) {
                    contentDescription = said
                    role = Role.Button
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val custom = view.sort == ListView.OWN
            BasicText(
                name,
                Modifier.weight(1f, fill = false),
                style = Theme.type.label.copy(color = if (custom) colors.muted else colors.text, fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                when {
                    custom -> R.drawable.ic_chevron_down
                    view.descending -> R.drawable.ic_arrow_down
                    else -> R.drawable.ic_arrow_up
                },
                size = 15.dp,
                tint = if (custom) colors.muted else colors.text,
            )
        }
    }
}

/** Windows' `FilterBox`: a search icon, "Find in this list", and a way to clear it. */
@Composable
private fun FindBox(state: TextFieldState, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val label = stringResource(R.string.find_in_list)
    val shape = RoundedCornerShape(20.dp)
    val style = Theme.type.body.copy(color = colors.text, fontSize = 14.sp)
    BasicTextField(
        state = state,
        modifier = modifier.semantics { contentDescription = label },
        textStyle = style,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        onKeyboardAction = { keyboard?.hide() },
        interactionSource = source,
        cursorBrush = SolidColor(colors.accentBright),
        decorator = TextFieldDecorator { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Theme.dimens.touch)
                    .clip(shape)
                    .background(if (focused || state.text.isNotEmpty()) colors.raised else colors.hover)
                    .border(1.dp, if (focused) colors.accentBright else colors.raised.copy(alpha = 0f), shape)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(R.drawable.ic_search, size = 18.dp, tint = if (focused) colors.text else colors.muted)
                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) BasicText(label, style = style.copy(color = colors.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (state.text.isNotEmpty()) {
                    IconButton(R.drawable.ic_x, stringResource(R.string.clear), { state.clearText() }, iconSize = 16.dp)
                } else {
                    Box(Modifier.height(Theme.dimens.touch).padding(end = 4.dp))
                }
            }
        },
    )
}
