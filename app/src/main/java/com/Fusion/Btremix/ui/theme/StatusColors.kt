package com.Fusion.Btremix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors (D-UI-8, DEVICE_CENTER_UI_PLAN §4.1).
 *
 * Material 3 has no `success` role, so Running / Warning / Error / Idle are the one place allowed to
 * hard-code colors. Everything else must come from [MaterialTheme.colorScheme]; status colors are
 * only ever used for dots, bars, icons and small amounts of text.
 */
@Immutable
data class StatusColors(
    val running: Color,
    val warning: Color,
    val error: Color,
    val idle: Color,
) {
    /** Connected shares the running color; connecting shares the warning color. */
    val connected: Color get() = running
    val connecting: Color get() = warning
    val disconnected: Color get() = idle
}

val LightStatusColors: StatusColors = StatusColors(
    running = Color(0xFF1B8A4B),
    warning = Color(0xFFB26A00),
    error = Color(0xFFC0392B),
    idle = Color(0xFF8A8F98),
)

val DarkStatusColors: StatusColors = StatusColors(
    running = Color(0xFF5FD08A),
    warning = Color(0xFFFFB868),
    error = Color(0xFFFF8A80),
    idle = Color(0xFF9AA0A6),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** `MaterialTheme.statusColors.running` — mirrors the `MaterialTheme.colorScheme` accessor style. */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current
