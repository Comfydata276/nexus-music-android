package org.medianexus.music.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.medianexus.music.R

/** Bricolage Grotesque, for titles and headings: the weights the Windows app bundles. */
val Display = FontFamily(
    Font(R.font.bricolage_grotesque_semi_bold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extra_bold, FontWeight.ExtraBold),
)

/** Hanken Grotesk, for everything else. */
val Body = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
    Font(R.font.hanken_grotesk_semi_bold, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk_bold, FontWeight.Bold),
)

/**
 * The type styles. Sizes follow the Windows app's, in sp so they follow the phone's text
 * size; the larger titles are a step smaller than on a computer screen.
 */
@Immutable
data class Types(
    /** The sign-in screen's and Settings' big title (34px, 800 on Windows). */
    val hero: TextStyle,
    /** A page's own name at the top of it: an album, a playlist (26px, 700). */
    val title: TextStyle,
    /** The name in a page's title bar. */
    val bar: TextStyle,
    /** A shelf on a page: "Recently played" (22px, 700). */
    val section: TextStyle,
    /** A run of settings, a sheet's title (18px, 700). */
    val heading: TextStyle,
    /** A genre tile's name (19px, 700). */
    val tile: TextStyle,
    /** Plain reading text (14px). */
    val body: TextStyle,
    /** A song's title in a list: 15sp semibold, as the design sets for the phone. */
    val rowTitle: TextStyle,
    /** What's under it: the artist (13px). */
    val rowDetail: TextStyle,
    /** A card's title (14px, 600). */
    val cardTitle: TextStyle,
    /** A field's label, a setting's name (13px, 600). */
    val label: TextStyle,
    /** Buttons (14px, 600). */
    val button: TextStyle,
    /** Chips and segmented choices (13px, 500). */
    val chip: TextStyle,
    /** Small print (12px). */
    val caption: TextStyle,
)

private val lines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, weight: FontWeight, height: TextUnit = 1.3.em) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = height,
    lineHeightStyle = lines,
)

val TYPES = Types(
    hero = style(Display, 32, FontWeight.ExtraBold, 1.12.em),
    title = style(Display, 26, FontWeight.Bold, 1.15.em),
    bar = style(Display, 20, FontWeight.Bold, 1.2.em),
    section = style(Display, 22, FontWeight.Bold, 1.2.em),
    heading = style(Display, 18, FontWeight.Bold, 1.2.em),
    tile = style(Display, 19, FontWeight.Bold, 1.2.em),
    body = style(Body, 14, FontWeight.Normal, 1.4.em),
    rowTitle = style(Body, 15, FontWeight.SemiBold, 1.25.em),
    rowDetail = style(Body, 13, FontWeight.Normal, 1.25.em),
    cardTitle = style(Body, 14, FontWeight.SemiBold, 1.25.em),
    label = style(Body, 13, FontWeight.SemiBold, 1.25.em),
    button = style(Body, 14, FontWeight.SemiBold, 1.2.em),
    chip = style(Body, 13, FontWeight.Medium, 1.2.em),
    caption = style(Body, 12, FontWeight.Normal, 1.35.em),
)
