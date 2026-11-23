package org.medianexus.music.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.medianexus.music.R
import org.medianexus.music.ui.parts.IconButton
import org.medianexus.music.ui.parts.PageState
import org.medianexus.music.ui.parts.PageStatus
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.theme.Theme

/** A tab's first page until its content arrives in a later milestone. */
@Composable
fun PlaceholderPage(@StringRes title: Int, @StringRes detail: Int, @DrawableRes icon: Int, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(title)) {
            IconButton(R.drawable.ic_settings, stringResource(R.string.settings), onSettings, tint = Theme.colors.text)
        }
        PageStatus(PageState.Empty(stringResource(R.string.coming_soon), stringResource(detail), icon))
    }
}
