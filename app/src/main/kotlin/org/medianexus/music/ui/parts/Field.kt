package org.medianexus.music.ui.parts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.medianexus.music.ui.theme.Theme

/**
 * A text box. The text lives in `state`, which the screen holds; a long entry scrolls inside
 * the box and never pushes the layout wider.
 */
@Composable
fun Field(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** Hides what's typed, as a password. */
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** What the keyboard's action key does; by default it moves on, or closes the keyboard. */
    onKeyboardAction: KeyboardActionHandler? = null,
    /** What it holds, for autofill. */
    contentType: ContentType? = null,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val edge by animateColorAsState(if (focused) colors.accentBright else colors.line, tween(120), label = "field")
    val shape = RoundedCornerShape(8.dp)
    val style = Theme.type.body.copy(color = colors.text, fontSize = 15.sp)
    val decorator = TextFieldDecorator { inner ->
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Theme.dimens.touch)
                .background(colors.raised, shape)
                .border(1.dp, edge, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (state.text.isEmpty() && placeholder.isNotEmpty()) {
                BasicText(placeholder, style = style.copy(color = colors.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            inner()
        }
    }
    val described = modifier.semantics {
        contentDescription = label
        if (contentType != null) this.contentType = contentType
    }
    if (secret) {
        BasicSecureTextField(
            state = state,
            modifier = described,
            enabled = enabled,
            textStyle = style,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            interactionSource = source,
            cursorBrush = SolidColor(colors.accentBright),
            decorator = decorator,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = described,
            enabled = enabled,
            textStyle = style,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = TextFieldLineLimits.SingleLine,
            interactionSource = source,
            cursorBrush = SolidColor(colors.accentBright),
            decorator = decorator,
        )
    }
}
