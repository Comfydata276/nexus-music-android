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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onAutofillText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
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
    /** Shown as typed, but never copied or cut out of the field (a one-time code): the secure
     *  field's clipboard rules, with what is typed left visible. Paste still works. */
    uncopyable: Boolean = false,
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
                // It says what to type (the server's address takes the form shown), so it has
                // to be readable: muted meets 4.5:1 on the field, faint doesn't.
                BasicText(placeholder, style = style.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            inner()
        }
    }
    val described = modifier.semantics {
        contentDescription = label
        if (contentType != null) this.contentType = contentType
    }
    if (secret || uncopyable) {
        // Compose's secure field hides the characters on screen but gives accessibility
        // services the password itself (anything reading the screen saw it in full). A
        // password field of the system's gives them only dots. So the field's own semantics
        // are replaced by a password's: dots, its name, and the actions TalkBack and autofill
        // use to focus it, type into it and fill it in.
        val focus = remember { FocusRequester() }
        val dots = remember(state.text.length) { AnnotatedString("•".repeat(state.text.length)) }
        // (A code shown as typed: its semantics are the field's own text, still with no copy.)
        Box(
            modifier.clearAndSetSemantics {
                contentDescription = label
                if (secret) password()
                editableText = if (secret) dots else AnnotatedString(state.text.toString())
                this.focused = focused
                if (contentType != null) this.contentType = contentType
                if (enabled) {
                    onClick { focus.requestFocus(); true }
                    requestFocus { focus.requestFocus(); true }
                    setText { text -> state.setTextAndPlaceCursorAtEnd(text.text); true }
                    onAutofillText { text -> state.setTextAndPlaceCursorAtEnd(text.text); true }
                    onImeAction(keyboardOptions.imeAction) { onKeyboardAction?.onKeyboardAction {}; true }
                } else {
                    disabled()
                }
            },
        ) {
            BasicSecureTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textObfuscationMode = if (uncopyable) androidx.compose.foundation.text.input.TextObfuscationMode.Visible else androidx.compose.foundation.text.input.TextObfuscationMode.RevealLastTyped,
                enabled = enabled,
                textStyle = style,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = onKeyboardAction,
                interactionSource = source,
                cursorBrush = SolidColor(colors.accentBright),
                decorator = decorator,
            )
        }
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
