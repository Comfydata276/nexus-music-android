package org.medianexus.music.ui.parts

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.medianexus.music.R
import org.medianexus.music.ui.theme.Theme

/** One of the app's icons (`R.drawable.ic_…`), tinted. Decorative unless given a description. */
@Composable
fun Icon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Theme.colors.muted,
    contentDescription: String? = null,
) {
    val filter = remember(tint) { ColorFilter.tint(tint) }
    Image(painterResource(icon), contentDescription, modifier.size(size), colorFilter = filter)
}

/**
 * A round button showing just an icon. `label` is what a screen reader says for it, so every
 * one has a name.
 */
@Composable
fun IconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.muted,
    iconSize: Dp = 22.dp,
    /** Toggled on, e.g. shuffle. */
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(Theme.dimens.touch)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, size = iconSize, tint = if (active) Theme.colors.accentBright else tint)
    }
}

/** The round button that starts playback: a filled accent circle with the play icon. */
@Composable
fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    /** What a screen reader says; "Play" or "Pause" unless given. */
    label: String? = null,
    /** Shows a plus instead: the button adds something, it doesn't play it. */
    adds: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) Theme.colors.accent else Theme.colors.accentBright, tween(120), label = "play")
    val name = label ?: stringResource(if (playing) R.string.pause else R.string.play)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when {
                adds -> R.drawable.ic_plus
                playing -> R.drawable.ic_pause_fill
                else -> R.drawable.ic_play_fill
            },
            size = size * if (adds) 0.5f else 0.42f,
            tint = Theme.colors.onAccent,
        )
    }
}

/**
 * A button with words on it: outlined, or filled for the one main action (`primary`) or a
 * destructive one (`danger`).
 */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val filled = primary || danger
    val fill by animateColorAsState(
        when {
            danger -> if (pressed) colors.danger.copy(alpha = 0.85f) else colors.danger
            primary -> if (pressed) colors.accent else colors.accentBright
            else -> if (pressed) colors.press else Color.Transparent
        },
        tween(120),
        label = "button",
    )
    Box(
        modifier
            .heightIn(min = Theme.dimens.touch)
            .defaultMinSize(minWidth = 88.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, if (pressed) colors.text else colors.faint, CircleShape))
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = Theme.type.button.copy(color = if (filled) colors.onAccent else colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Words that act like a link: "Show all". */
@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Theme.colors.muted,
) {
    Box(
        modifier
            .heightIn(min = Theme.dimens.touch)
            .widthIn(min = Theme.dimens.touch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Theme.type.label.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
