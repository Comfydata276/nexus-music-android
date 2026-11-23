package org.medianexus.music.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Sizes shared by every screen: the Windows app's corners and gaps, and the phone's own. */
@Immutable
object Dimens {
    /** Corners on panels, cards and covers. */
    val radius: Dp = 10.dp
    /** Corners on the small covers in song rows. */
    val rowCoverRadius: Dp = 4.dp
    /** Gaps go up in steps of this. */
    val gap: Dp = 8.dp
    /** Space at a page's sides. */
    val pagePad: Dp = 16.dp
    /** The smallest a thing to tap may be. */
    val touch: Dp = 48.dp
    val rowHeight: Dp = 56.dp
    val rowCover: Dp = 40.dp
    /** Where the mini player sits, above the bottom bar (see the shell). */
    val miniPlayer: Dp = 64.dp
}

private val LocalThemeColors = staticCompositionLocalOf { THEMES[0] }
private val LocalTypes = staticCompositionLocalOf { TYPES }

/** The theme in use: `Theme.colors.accent`, `Theme.type.rowTitle`. */
object Theme {
    val colors: ThemeColors
        @Composable @ReadOnlyComposable get() = LocalThemeColors.current
    val type: Types
        @Composable @ReadOnlyComposable get() = LocalTypes.current
    val dimens: Dimens get() = Dimens
}

/**
 * Draws everything inside it in one of the app's themes. Material 3's own colours, type and
 * ripples are mapped onto ours, so a Material part used anywhere looks like the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusTheme(colors: ThemeColors, content: @Composable () -> Unit) {
    val scheme = remember(colors) { colorSchemeOf(colors) }
    val press = remember(colors) { PressIndication(colors.press) }
    val selection = remember(colors) { TextSelectionColors(handleColor = colors.accentBright, backgroundColor = colors.accent.copy(alpha = 0.4f)) }
    val ripple = remember(colors) { RippleConfiguration(color = colors.text) }
    MaterialTheme(colorScheme = scheme, typography = TYPOGRAPHY, shapes = SHAPES) {
        CompositionLocalProvider(
            LocalThemeColors provides colors,
            LocalTypes provides TYPES,
            LocalIndication provides press,
            LocalRippleConfiguration provides ripple,
            LocalTextSelectionColors provides selection,
            LocalContentColor provides colors.text,
            LocalTextStyle provides TYPES.body,
            content = content,
        )
    }
}

private fun colorSchemeOf(c: ThemeColors) = darkColorScheme(
    primary = c.accentBright,
    onPrimary = c.onAccent,
    primaryContainer = c.accent,
    onPrimaryContainer = c.onAccent,
    inversePrimary = c.accent,
    secondary = c.accentBright,
    onSecondary = c.onAccent,
    secondaryContainer = c.overlay,
    onSecondaryContainer = c.text,
    tertiary = c.warm,
    onTertiary = c.base,
    tertiaryContainer = c.overlay,
    onTertiaryContainer = c.text,
    background = c.base,
    onBackground = c.text,
    surface = c.panel,
    onSurface = c.text,
    surfaceVariant = c.raised,
    onSurfaceVariant = c.muted,
    // No tinting of raised surfaces: ours are set colours.
    surfaceTint = Color.Transparent,
    inverseSurface = c.text,
    inverseOnSurface = c.base,
    error = c.danger,
    onError = c.base,
    errorContainer = c.danger,
    onErrorContainer = c.base,
    outline = c.faint,
    outlineVariant = c.line,
    scrim = Color(0x99000000),
    surfaceBright = c.overlay,
    surfaceDim = c.base,
    surfaceContainerLowest = c.base,
    surfaceContainerLow = c.panel,
    surfaceContainer = c.panel,
    surfaceContainerHigh = c.raised,
    surfaceContainerHighest = c.overlay,
)

private val TYPOGRAPHY = Typography(
    displayLarge = TYPES.hero,
    displayMedium = TYPES.hero,
    displaySmall = TYPES.title,
    headlineLarge = TYPES.title,
    headlineMedium = TYPES.section,
    headlineSmall = TYPES.heading,
    titleLarge = TYPES.bar,
    titleMedium = TYPES.heading,
    titleSmall = TYPES.label,
    bodyLarge = TYPES.body,
    bodyMedium = TYPES.body,
    bodySmall = TYPES.caption,
    labelLarge = TYPES.button,
    labelMedium = TYPES.label,
    labelSmall = TYPES.caption,
)

private val SHAPES = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(Dimens.radius),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * What a press looks like everywhere: the Windows app's `press` wash over whatever was
 * pressed, fading in and out, instead of Material's ripple. Clip the pressable thing to its
 * shape first and the wash takes that shape.
 */
private class PressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color)
    override fun equals(other: Any?) = other is PressIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class PressNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private val shown = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                }
                launch { shown.animateTo(if (pressed > 0) 1f else 0f, tween(if (pressed > 0) 60 else 180)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = shown.value
        if (alpha > 0f) drawRect(color, alpha = alpha)
    }
}
