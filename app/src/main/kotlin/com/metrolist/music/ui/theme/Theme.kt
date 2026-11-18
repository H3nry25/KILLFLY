/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

val DefaultThemeColor = Color(0xFFED5564)
val LiquidGlassBlackSeedColor = Color(0xFF70B9FF)

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    liquidGlassBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Determine if system dynamic colors should be used (Android S+ and default theme color)
    val useSystemDynamicColor = (themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !liquidGlassBlack)

    // Select the appropriate color scheme generation method
    val baseColorScheme = if (useSystemDynamicColor) {
        // Use standard Material 3 dynamic color functions for system wallpaper colors
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // Use materialKolor only when a specific seed color is provided
        rememberDynamicColorScheme(
            seedColor = if (liquidGlassBlack) LiquidGlassBlackSeedColor else themeColor,
            isDark = if (liquidGlassBlack) true else darkTheme,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            style = PaletteStyle.TonalSpot // Keep existing style
        )
    }

    // Apply liquidGlassBlack or pureBlack modification if needed
    val colorScheme = remember(baseColorScheme, pureBlack, liquidGlassBlack, darkTheme) {
        if (liquidGlassBlack) {
            baseColorScheme.liquidGlassBlack(true)
        } else if (darkTheme && pureBlack) {
            baseColorScheme.pureBlack(true)
        } else {
            baseColorScheme
        }
    }

    // Use standard MaterialTheme instead of MaterialExpressiveTheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

fun ColorScheme.liquidGlassBlack(apply: Boolean = true): ColorScheme =
    if (apply) copy(
        background = Color(0xFF030305),
        onBackground = Color(0xFFF1F1F5),
        surface = Color(0xFF08090E),
        onSurface = Color(0xFFF1F1F5),
        surfaceVariant = Color(0xFF131520),
        onSurfaceVariant = Color(0xFFC4C8D8),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0C13),
        surfaceContainer = Color(0xFF0E101A),
        surfaceContainerHigh = Color(0xFF151824),
        surfaceContainerHighest = Color(0xFF1D2030),
        surfaceBright = Color(0xFF262A3C),
        surfaceDim = Color(0xFF06070B),
        outline = Color(0xFF383D50),
        outlineVariant = Color(0xFF1E2230),
        primary = Color(0xFF70B9FF),
        onPrimary = Color(0xFF002F55),
        primaryContainer = Color(0xFF14273E),
        onPrimaryContainer = Color(0xFFD4E6FF),
        inversePrimary = Color(0xFF389BF2),
        secondary = Color(0xFF73E0E8),
        onSecondary = Color(0xFF00363C),
        secondaryContainer = Color(0xFF122E34),
        onSecondaryContainer = Color(0xFFC7F3F7),
        tertiary = Color(0xFFB4C5D6),
        onTertiary = Color(0xFF1E2B38),
        tertiaryContainer = Color(0xFF1C2733),
        onTertiaryContainer = Color(0xFFD8E4EE),
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
