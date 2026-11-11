/* Adapted from Flocket's Material theme and shape scale.
 * Copyright Flocket contributors. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R

/**
 * Trefoil design tokens. Names follow the design canvas: `bg` ground, `s1`–`s3` raised surfaces,
 * `line` hairlines, `text`/`muted` copy, `accent` leaf green, and status hues for needs-you
 * (amber), working (blue), done (green) and errors (red).
 */
@Immutable
data class TrefoilColors(
    val isDark: Boolean,
    val bg: Color, val s1: Color, val s2: Color, val s3: Color, val line: Color,
    val text: Color, val muted: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val amber: Color, val amberSoft: Color,
    val blue: Color, val blueSoft: Color,
    val green: Color, val red: Color,
    val sheet: Color, val rail: Color, val scrim: Color,
)

val TrefoilDark = TrefoilColors(
    isDark = true,
    bg = Color(0xFF0F1111), s1 = Color(0xFF161919), s2 = Color(0xFF1F2323), s3 = Color(0xFF2A2F2F), line = Color(0xFF272C2C),
    text = Color(0xFFECEFEE), muted = Color(0xFF9CA4A2),
    accent = Color(0xFF8EDB9B), accentSoft = Color(0xFF1B3222), onAccent = Color(0xFF08200F),
    amber = Color(0xFFF4B55B), amberSoft = Color(0xFF35280F),
    blue = Color(0xFF82BCFF), blueSoft = Color(0xFF15243A),
    green = Color(0xFF74D08F), red = Color(0xFFF28B82),
    sheet = Color(0xFF181B1B), rail = Color(0xFF0B0D0D), scrim = Color(0x9E000000),
)

val TrefoilLight = TrefoilColors(
    isDark = false,
    bg = Color(0xFFF6F7F6), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFECEFED), s3 = Color(0xFFE1E6E3), line = Color(0xFFDDE2DF),
    text = Color(0xFF111514), muted = Color(0xFF56605D),
    accent = Color(0xFF1E7A3A), accentSoft = Color(0xFFDCF0E0), onAccent = Color(0xFFFFFFFF),
    amber = Color(0xFF955700), amberSoft = Color(0xFFFBEACB),
    blue = Color(0xFF1C5DB5), blueSoft = Color(0xFFDCE8FA),
    green = Color(0xFF1E7A3A), red = Color(0xFFB3261E),
    sheet = Color(0xFFFFFFFF), rail = Color(0xFFEDF0EE), scrim = Color(0x61111514),
)

val LocalTrefoilColors = staticCompositionLocalOf { TrefoilDark }

/** Inter for UI copy, JetBrains Mono for branches, paths, commands and code. Both bundled (OFL-1.1). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val InterFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.inter, it) },
)
val MonoFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { variable(R.font.jetbrains_mono, it) },
)

/** Extra text roles from the design that Material's scale doesn't name. */
object TrefoilType {
    /** Uppercase section label, e.g. NEEDS YOU / WORKING / SAVED. Uppercase the string yourself. */
    val section = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em)
    /** Branch names, paths, commands: 12sp JetBrains Mono medium. */
    val mono = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    /** Code blocks and inline code inside messages. */
    val code = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    /** Row title: 16/22 semibold. */
    val rowTitle = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    /** Row preview: 14/20 regular, muted. */
    val preview = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    /** Row meta line and timestamps: 13/18 medium, muted. */
    val meta = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    /** Assistant message body: 15/23. */
    val message = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    /** Big screen title such as "Machines": 32/38 bold, tight tracking. */
    val display = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em)
}

private fun trefoilTypography(): Typography {
    val base = Typography()
    fun TextStyle.inter(weight: FontWeight? = null) = copy(fontFamily = InterFamily, fontWeight = weight ?: fontWeight)
    return Typography(
        displayLarge = base.displayLarge.inter(FontWeight.Bold), displayMedium = base.displayMedium.inter(FontWeight.Bold), displaySmall = base.displaySmall.inter(FontWeight.Bold),
        headlineLarge = TrefoilType.display,
        headlineMedium = base.headlineMedium.inter(FontWeight.Bold).copy(letterSpacing = (-0.02).em),
        headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
        titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
        titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    )
}

private fun TrefoilColors.toMaterial(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = text,
        inversePrimary = accent,
        secondary = muted, onSecondary = bg, secondaryContainer = s2, onSecondaryContainer = text,
        tertiary = blue, onTertiary = bg, tertiaryContainer = blueSoft, onTertiaryContainer = text,
        background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = s2, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = text, inverseOnSurface = bg,
        error = red, onError = bg, errorContainer = if (isDark) Color(0xFF3A1A18) else Color(0xFFFADEDC), onErrorContainer = text,
        outline = if (isDark) Color(0xFF3A4040) else Color(0xFFC3CAC6), outlineVariant = line,
        scrim = Color.Black,
        surfaceBright = s2, surfaceDim = bg,
        surfaceContainerLowest = bg, surfaceContainerLow = s1, surfaceContainer = s1,
        surfaceContainerHigh = sheet, surfaceContainerHighest = s2,
    )
}

@Composable
fun TrefoilTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tokens = if (darkTheme) TrefoilDark else TrefoilLight
    CompositionLocalProvider(LocalTrefoilColors provides tokens) {
        MaterialTheme(
            colorScheme = tokens.toMaterial(),
            typography = trefoilTypography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

/** Shortcut: `Tf.colors.amber`. */
object Tf {
    val colors: TrefoilColors @Composable @ReadOnlyComposable get() = LocalTrefoilColors.current
}
