package com.fusion.melodyLinkNeo.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 8dp spacing system (DEVICE_CENTER_UI_PLAN §4.4).
 *
 * Values are plain `Dp` constants instead of a CompositionLocal: the scale never changes at runtime,
 * and plain constants keep the call sites (`DcSpacing.md`) readable without a theme lookup.
 */
object DcSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val screenPadding: Dp = 20.dp
    val gridGutter: Dp = 12.dp
    val bottomBarHeight: Dp = 64.dp
    val contentBottomInset: Dp = 96.dp
}

/** Corner tokens (DEVICE_CENTER_UI_PLAN §4.3). [capsule] is the Liquid Glass bottom-bar shape. */
object DcShapes {
    val extraSmall: Shape = RoundedCornerShape(8.dp)
    val small: Shape = RoundedCornerShape(12.dp)
    val medium: Shape = RoundedCornerShape(16.dp)
    val large: Shape = RoundedCornerShape(20.dp)
    val extraLarge: Shape = RoundedCornerShape(28.dp)

    /**
     * Capsule used by the glass bar and the selected pill. It stays a [RoundedCornerShape] on purpose:
     * `backdrop`'s `lens()` only supports `CornerBasedShape`/`RoundedRectangularShape`.
     */
    val capsule: Shape = RoundedCornerShape(percent = 50)

    val material: Shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** Motion tokens (DEVICE_CENTER_UI_PLAN §4.7). */
object DcMotion {
    const val PAGE_TRANSITION_MS: Int = 220
    const val STATUS_COLOR_MS: Int = 300
    const val VALUE_CHANGE_MS: Int = 400
    const val PRESS_MS: Int = 150

    val fastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/** Monospaced style for MACs, UUIDs, hex payloads, versions and session timers (§4.2). */
object DcType {
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
}
