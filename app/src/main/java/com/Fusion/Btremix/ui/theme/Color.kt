package com.Fusion.Btremix.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Fusion" expressive palette (DEVICE_CENTER_UI_PLAN §4.1, restyled for Material 3 Expressive).
 *
 * Dynamic colour stays the default (minSdk 35 always has it), so these values are only the fallback
 * used when 动态取色 is switched off. The palette keeps the Material 3 role structure intact but
 * pushes saturation and tonal separation so surfaces read as expressive tonal blocks instead of the
 * flat baseline greys.
 */
object FusionPalette {

    // ---- Light ----
    val LPrimary = Color(0xFF4A5AC4)
    val LOnPrimary = Color(0xFFFFFFFF)
    val LPrimaryContainer = Color(0xFFDFE0FF)
    val LOnPrimaryContainer = Color(0xFF00105C)

    val LSecondary = Color(0xFF00696E)
    val LOnSecondary = Color(0xFFFFFFFF)
    val LSecondaryContainer = Color(0xFF9CF1F7)
    val LOnSecondaryContainer = Color(0xFF002022)

    val LTertiary = Color(0xFF8F4C38)
    val LOnTertiary = Color(0xFFFFFFFF)
    val LTertiaryContainer = Color(0xFFFFDBD1)
    val LOnTertiaryContainer = Color(0xFF3A0B01)

    val LError = Color(0xFFBA1A1A)
    val LOnError = Color(0xFFFFFFFF)
    val LErrorContainer = Color(0xFFFFDAD6)
    val LOnErrorContainer = Color(0xFF410002)

    val LBackground = Color(0xFFFDF7FF)
    val LOnBackground = Color(0xFF1B1B22)
    val LSurface = Color(0xFFFDF7FF)
    val LOnSurface = Color(0xFF1B1B22)
    val LSurfaceVariant = Color(0xFFE2E1EC)
    val LOnSurfaceVariant = Color(0xFF45464F)
    val LOutline = Color(0xFF767680)
    val LOutlineVariant = Color(0xFFC6C5D0)
    val LInverseSurface = Color(0xFF303038)
    val LInverseOnSurface = Color(0xFFF2EFF7)
    val LInversePrimary = Color(0xFFBBC3FF)

    val LSurfaceContainerLowest = Color(0xFFFFFFFF)
    val LSurfaceContainerLow = Color(0xFFF7F2FA)
    val LSurfaceContainer = Color(0xFFF1ECF4)
    val LSurfaceContainerHigh = Color(0xFFEBE6EF)
    val LSurfaceContainerHighest = Color(0xFFE5E0E9)

    // ---- Dark ----
    val DPrimary = Color(0xFFBBC3FF)
    val DOnPrimary = Color(0xFF12207A)
    val DPrimaryContainer = Color(0xFF313F9F)
    val DOnPrimaryContainer = Color(0xFFDFE0FF)

    val DSecondary = Color(0xFF80D4DA)
    val DOnSecondary = Color(0xFF003739)
    val DSecondaryContainer = Color(0xFF004F53)
    val DOnSecondaryContainer = Color(0xFF9CF1F7)

    val DTertiary = Color(0xFFFFB59D)
    val DOnTertiary = Color(0xFF551A08)
    val DTertiaryContainer = Color(0xFF73321E)
    val DOnTertiaryContainer = Color(0xFFFFDBD1)

    val DError = Color(0xFFFFB4AB)
    val DOnError = Color(0xFF690005)
    val DErrorContainer = Color(0xFF93000A)
    val DOnErrorContainer = Color(0xFFFFDAD6)

    val DBackground = Color(0xFF131318)
    val DOnBackground = Color(0xFFE5E1E9)
    val DSurface = Color(0xFF131318)
    val DOnSurface = Color(0xFFE5E1E9)
    val DSurfaceVariant = Color(0xFF45464F)
    val DOnSurfaceVariant = Color(0xFFC6C5D0)
    val DOutline = Color(0xFF90909A)
    val DOutlineVariant = Color(0xFF45464F)
    val DInverseSurface = Color(0xFFE5E1E9)
    val DInverseOnSurface = Color(0xFF303038)
    val DInversePrimary = Color(0xFF4A5AC4)

    val DSurfaceContainerLowest = Color(0xFF0E0E13)
    val DSurfaceContainerLow = Color(0xFF1B1B21)
    val DSurfaceContainer = Color(0xFF1F1F25)
    val DSurfaceContainerHigh = Color(0xFF2A2A31)
    val DSurfaceContainerHighest = Color(0xFF35353C)
}
