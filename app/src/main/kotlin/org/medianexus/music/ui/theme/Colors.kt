package org.medianexus.music.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A colour theme: surfaces from darkest to lightest, text from strongest to faintest, and
 * the accent. The six themes are the Windows app's (`THEMES` in src/config.rs), colour for
 * colour.
 */
@Immutable
data class ThemeColors(
    /** The name kept in settings, the same on every device. */
    val name: String,
    val base: Color,
    val panel: Color,
    val raised: Color,
    val overlay: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentBright: Color,
    /** Text and icons drawn on top of the accent. */
    val onAccent: Color,
    /** Marks things you've liked. */
    val warm: Color,
) {
    // The same in every theme (ui/theme.slint).
    val hover: Color get() = HOVER
    val press: Color get() = PRESS
    val line: Color get() = LINE
    val danger: Color get() = DANGER

    /** The empty part of a slider or a switch that's off. */
    val track: Color get() = TRACK
}

private val HOVER = Color(0x10FFFFFF)
private val PRESS = Color(0x1CFFFFFF)
private val LINE = Color(0x16FFFFFF)
private val TRACK = Color(0x2BFFFFFF)
private val DANGER = Color(0xFFF47676)

private fun rgb(value: Long) = Color(0xFF000000 or value)

val THEMES: List<ThemeColors> = listOf(
    ThemeColors(
        name = "Violet",
        base = rgb(0x0b0811), panel = rgb(0x14101d), raised = rgb(0x1e182b), overlay = rgb(0x251e35),
        text = rgb(0xf3effa), muted = rgb(0xa89fbb), faint = rgb(0x6f6783),
        accent = rgb(0x8b5cf6), accentBright = rgb(0xa98bfb), onAccent = rgb(0x150c2b), warm = rgb(0xf5b85c),
    ),
    ThemeColors(
        name = "Green",
        base = rgb(0x000000), panel = rgb(0x121212), raised = rgb(0x1f1f1f), overlay = rgb(0x2a2a2a),
        text = rgb(0xffffff), muted = rgb(0xb3b3b3), faint = rgb(0x6a6a6a),
        accent = rgb(0x1db954), accentBright = rgb(0x1ed760), onAccent = rgb(0x04130a), warm = rgb(0x1ed760),
    ),
    ThemeColors(
        name = "Ember",
        base = rgb(0x0e0907), panel = rgb(0x17100c), raised = rgb(0x241811), overlay = rgb(0x2f2017),
        text = rgb(0xfbf3ec), muted = rgb(0xc2ab98), faint = rgb(0x7f6b5c),
        accent = rgb(0xf97316), accentBright = rgb(0xfb923c), onAccent = rgb(0x2a1203), warm = rgb(0xfbbf24),
    ),
    ThemeColors(
        name = "Ocean",
        base = rgb(0x070b12), panel = rgb(0x0e1420), raised = rgb(0x172033), overlay = rgb(0x1e2a42),
        text = rgb(0xeef3fb), muted = rgb(0x9fb0c9), faint = rgb(0x647692),
        accent = rgb(0x3b82f6), accentBright = rgb(0x60a5fa), onAccent = rgb(0x06142e), warm = rgb(0xf5b85c),
    ),
    ThemeColors(
        name = "Rose",
        base = rgb(0x110810), panel = rgb(0x1b0f18), raised = rgb(0x291724), overlay = rgb(0x341d2e),
        text = rgb(0xfbeff6), muted = rgb(0xc4a4b7), faint = rgb(0x866b7b),
        accent = rgb(0xec4899), accentBright = rgb(0xf472b6), onAccent = rgb(0x2d0a1c), warm = rgb(0xf5b85c),
    ),
    ThemeColors(
        name = "Graphite",
        base = rgb(0x0a0a0b), panel = rgb(0x131315), raised = rgb(0x1e1e21), overlay = rgb(0x28282c),
        text = rgb(0xf4f4f5), muted = rgb(0xa1a1aa), faint = rgb(0x6b6b74),
        accent = rgb(0xd4d4d8), accentBright = rgb(0xf4f4f5), onAccent = rgb(0x18181b), warm = rgb(0xf5b85c),
    ),
)

/** The theme with this name, or Violet for a name that isn't one (as on Windows). */
fun themeNamed(name: String): ThemeColors = THEMES.firstOrNull { it.name == name } ?: THEMES[0]
