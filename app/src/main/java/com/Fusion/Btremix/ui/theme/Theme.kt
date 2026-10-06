package com.Fusion.Btremix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Fallback colour schemes used when 动态取色 is off. Once the theme is built they are handed to
 * [MaterialTheme] exactly like the dynamic schemes, so the expressive shape/type system is identical
 * in both modes.
 */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = FusionPalette.LPrimary,
    onPrimary = FusionPalette.LOnPrimary,
    primaryContainer = FusionPalette.LPrimaryContainer,
    onPrimaryContainer = FusionPalette.LOnPrimaryContainer,
    secondary = FusionPalette.LSecondary,
    onSecondary = FusionPalette.LOnSecondary,
    secondaryContainer = FusionPalette.LSecondaryContainer,
    onSecondaryContainer = FusionPalette.LOnSecondaryContainer,
    tertiary = FusionPalette.LTertiary,
    onTertiary = FusionPalette.LOnTertiary,
    tertiaryContainer = FusionPalette.LTertiaryContainer,
    onTertiaryContainer = FusionPalette.LOnTertiaryContainer,
    error = FusionPalette.LError,
    onError = FusionPalette.LOnError,
    errorContainer = FusionPalette.LErrorContainer,
    onErrorContainer = FusionPalette.LOnErrorContainer,
    background = FusionPalette.LBackground,
    onBackground = FusionPalette.LOnBackground,
    surface = FusionPalette.LSurface,
    onSurface = FusionPalette.LOnSurface,
    surfaceVariant = FusionPalette.LSurfaceVariant,
    onSurfaceVariant = FusionPalette.LOnSurfaceVariant,
    outline = FusionPalette.LOutline,
    outlineVariant = FusionPalette.LOutlineVariant,
    inverseSurface = FusionPalette.LInverseSurface,
    inverseOnSurface = FusionPalette.LInverseOnSurface,
    inversePrimary = FusionPalette.LInversePrimary,
    surfaceTint = FusionPalette.LPrimary,
    surfaceContainerLowest = FusionPalette.LSurfaceContainerLowest,
    surfaceContainerLow = FusionPalette.LSurfaceContainerLow,
    surfaceContainer = FusionPalette.LSurfaceContainer,
    surfaceContainerHigh = FusionPalette.LSurfaceContainerHigh,
    surfaceContainerHighest = FusionPalette.LSurfaceContainerHighest,
)

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = FusionPalette.DPrimary,
    onPrimary = FusionPalette.DOnPrimary,
    primaryContainer = FusionPalette.DPrimaryContainer,
    onPrimaryContainer = FusionPalette.DOnPrimaryContainer,
    secondary = FusionPalette.DSecondary,
    onSecondary = FusionPalette.DOnSecondary,
    secondaryContainer = FusionPalette.DSecondaryContainer,
    onSecondaryContainer = FusionPalette.DOnSecondaryContainer,
    tertiary = FusionPalette.DTertiary,
    onTertiary = FusionPalette.DOnTertiary,
    tertiaryContainer = FusionPalette.DTertiaryContainer,
    onTertiaryContainer = FusionPalette.DOnTertiaryContainer,
    error = FusionPalette.DError,
    onError = FusionPalette.DOnError,
    errorContainer = FusionPalette.DErrorContainer,
    onErrorContainer = FusionPalette.DOnErrorContainer,
    background = FusionPalette.DBackground,
    onBackground = FusionPalette.DOnBackground,
    surface = FusionPalette.DSurface,
    onSurface = FusionPalette.DOnSurface,
    surfaceVariant = FusionPalette.DSurfaceVariant,
    onSurfaceVariant = FusionPalette.DOnSurfaceVariant,
    outline = FusionPalette.DOutline,
    outlineVariant = FusionPalette.DOutlineVariant,
    inverseSurface = FusionPalette.DInverseSurface,
    inverseOnSurface = FusionPalette.DInverseOnSurface,
    inversePrimary = FusionPalette.DInversePrimary,
    surfaceTint = FusionPalette.DPrimary,
    surfaceContainerLowest = FusionPalette.DSurfaceContainerLowest,
    surfaceContainerLow = FusionPalette.DSurfaceContainerLow,
    surfaceContainer = FusionPalette.DSurfaceContainer,
    surfaceContainerHigh = FusionPalette.DSurfaceContainerHigh,
    surfaceContainerHighest = FusionPalette.DSurfaceContainerHighest,
)

/**
 * Device Center theme (DEVICE_CENTER_UI_PLAN §4), restyled for Material 3 Expressive.
 *
 * The expressive theme entry point (`MaterialExpressiveTheme`) is still `internal` in the 1.4.0
 * artifact, so the expressive look is composed from the public primitives instead: a full expressive
 * colour scheme, the expressive shape scale in [DcShapes], the heavier expressive type scale in
 * [Typography], and the expressive motion springs in [DcMotion] used by the components.
 *
 * Dynamic color stays the default (minSdk 35 means it is always available); [darkTheme] and
 * [dynamicColor] are parameters so the Settings screen can drive "跟随系统 / 浅色 / 深色" and the
 * "动态取色" switch without a second theme.
 */
@Composable
fun BtRemixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = DcShapes.material,
            typography = Typography,
            content = content,
        )
    }
}
