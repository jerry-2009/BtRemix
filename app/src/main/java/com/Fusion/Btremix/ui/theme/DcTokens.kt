package com.Fusion.Btremix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val contentBottomInset: Dp = 108.dp
}

/**
 * Corner tokens (DEVICE_CENTER_UI_PLAN §4.3) rebuilt on the expressive shape scale: every container
 * is noticeably rounder than the Material 3 baseline, and [pill] is the stadium shape used by
 * buttons, chips, badges and the floating bottom bar.
 */
object DcShapes {
    val extraSmall: Shape = RoundedCornerShape(12.dp)
    val small: Shape = RoundedCornerShape(16.dp)
    val medium: Shape = RoundedCornerShape(20.dp)
    val large: Shape = RoundedCornerShape(28.dp)
    val extraLarge: Shape = RoundedCornerShape(36.dp)

    /** Stadium shape for buttons, chips, badges and the bottom bar. */
    val pill: Shape = RoundedCornerShape(percent = 50)

    /**
     * Capsule used by the Liquid Glass bottom bar and its selected pill. Same geometry as [pill];
     * kept as its own token because `backdrop`'s `lens()` only supports `CornerBasedShape` /
     * `RoundedRectangularShape`, and the bar reads better referencing the glass-specific name.
     */
    val capsule: Shape = RoundedCornerShape(percent = 50)

    val material: Shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )
}

/**
 * Motion tokens (DEVICE_CENTER_UI_PLAN §4.7) aligned with the Material 3 expressive motion scheme:
 * spatial changes (size, position, shape) overshoot slightly through a spring, while effects
 * (colour, alpha) settle without bouncing.
 */
object DcMotion {
    const val PAGE_TRANSITION_MS: Int = 300
    const val STATUS_COLOR_MS: Int = 260
    const val VALUE_CHANGE_MS: Int = 400
    const val PRESS_MS: Int = 150

    val fastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** Expressive default spatial spring: `damping 0.8 / stiffness 380`. */
    val defaultSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Expressive fast spatial spring: `damping 0.6 / stiffness 800`. */
    val fastSpatial: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Expressive slow spatial spring: `damping 0.8 / stiffness 200`. */
    val slowSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)

    /** Expressive default effects spring: `damping 1.0 / stiffness 1600`. */
    val defaultEffects: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Expressive fast effects spring: `damping 1.0 / stiffness 3800`. */
    val fastEffects: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)

    /** Colour counterpart of [defaultEffects] for `animateColorAsState`. */
    val colorEffects: FiniteAnimationSpec<Color> = spring(dampingRatio = 1f, stiffness = 1600f)
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
