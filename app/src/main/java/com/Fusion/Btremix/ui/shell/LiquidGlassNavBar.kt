package com.Fusion.Btremix.ui.shell

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.ui.theme.DcMotion
import com.Fusion.Btremix.ui.theme.DcShapes
import com.Fusion.Btremix.ui.theme.DcSpacing
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Liquid Glass bottom navigation (DEVICE_CENTER_UI_PLAN §4.9).
 *
 * `backdrop` only ships the low-level effect; the capsule bar, the sliding glass pill and the
 * fallback branch are ours. [glassEnabled] is false when the user turns 底栏半透明 off (the
 * `reduceTransparency` setting) or on a low-RAM device, in which case the bar degrades to an opaque
 * container with an outline and no lens.
 *
 * All four first-level destinations stay on the bar (D-UI-1), and the bar doubles as a pager
 * handle: dragging left/right moves the glass pill with the finger (one item per item-width),
 * highlights whichever item the pill currently sits on, and snaps to the nearest destination on
 * release - a fast fling moves one extra item. Tapping still selects directly.
 */
@Composable
fun LiquidGlassNavBar(
    backdrop: LayerBackdrop,
    items: List<AppTab>,
    selected: AppTab?,
    onSelect: (AppTab) -> Unit,
    glassEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val shape: Shape = DcShapes.capsule
    val canBlur = glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f)
    val pillTint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)

    val selectedIndex = items.indexOf(selected).takeIf { it >= 0 } ?: 0
    val maxIndex = (items.size - 1).toFloat()

    // The pill lives in "item units": 0f == first item, 3f == last item. Pending index holds where
    // the pill is heading; dragSlots is the finger offset from it while a drag is in flight.
    var pendingIndex by remember { mutableIntStateOf(selectedIndex) }
    var dragSlots by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Selection changed from outside the bar (tap, back navigation, deep link): move the pill there.
    LaunchedEffect(selectedIndex) { pendingIndex = selectedIndex }

    val pillPosition by animateFloatAsState(
        targetValue = (pendingIndex + if (dragging) dragSlots else 0f).coerceIn(0f, maxIndex),
        // Follow the finger exactly while dragging, spring into place on release.
        animationSpec = if (dragging) snap() else DcMotion.defaultSpatial,
        label = "nav-pill-position",
    )
    // Recomputes only when the pill crosses an item midpoint, so the highlight follows the pill
    // without recomposing the items on every drag frame.
    val nearestIndex by remember(items.size) {
        derivedStateOf { pillPosition.roundToInt().coerceIn(0, items.size - 1) }
    }

    val density = LocalDensity.current
    var barWidthPx by remember { mutableIntStateOf(1) }
    val slotWidthPx = barWidthPx.toFloat() / items.size

    val dragState = rememberDraggableState { deltaPx ->
        val slot = barWidthPx.toFloat() / items.size
        if (slot <= 0f) return@rememberDraggableState
        dragging = true
        dragSlots = (dragSlots + deltaPx / slot)
            .coerceIn(-pendingIndex.toFloat(), maxIndex - pendingIndex)
    }

    val barModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .height(DcSpacing.bottomBarHeight)
        .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
        .draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            onDragStopped = { velocityX ->
                var target = (pendingIndex + dragSlots).roundToInt().coerceIn(0, items.size - 1)
                if (abs(velocityX) > FLING_VELOCITY_PX_PER_S) {
                    target = if (velocityX > 0f) {
                        (target + 1).coerceAtMost(items.size - 1)
                    } else {
                        (target - 1).coerceAtLeast(0)
                    }
                }
                pendingIndex = target
                dragSlots = 0f
                dragging = false
                items.getOrNull(target)?.takeIf { it != selected }?.let(onSelect)
            },
        )
        .then(
            if (canBlur) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = { blur(8.dp.toPx()) },
                    highlight = { Highlight.Default },
                    shadow = { Shadow.Default },
                    onDrawSurface = { drawRect(tint) },
                )
            } else {
                Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            },
        )

    Box(modifier = barModifier, contentAlignment = Alignment.CenterStart) {
        // Sliding glass pill. The offset is read in the layer block, so a running drag invalidates
        // only the layer instead of recomposing the four items every frame.
        val pillInsetPx = with(density) { PillInset.toPx() }
        Box(
            Modifier
                .graphicsLayer { translationX = pillPosition * slotWidthPx + pillInsetPx }
                .width(with(density) { (slotWidthPx - 2 * pillInsetPx).toDp() })
                .height(56.dp)
                .clip(shape)
                .then(
                    if (canBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                lens(
                                    refractionHeight = 10.dp.toPx(),
                                    refractionAmount = 14.dp.toPx(),
                                    chromaticAberration = true,
                                )
                            },
                            highlight = null,
                            shadow = null,
                            onDrawSurface = { drawRect(pillTint) },
                        )
                    } else {
                        Modifier.background(pillTint)
                    },
                ),
        )

        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, tab ->
                NavItem(
                    tab = tab,
                    highlighted = index == nearestIndex,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: AppTab,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(DcMotion.STATUS_COLOR_MS),
        label = "nav-item-color",
    )
    // No per-item tile: the sliding pill is the only highlight, so it never gets dimmed by a
    // neighbouring slot's background while it travels underneath the labels.
    Box(
        modifier
            .padding(horizontal = PillInset, vertical = 4.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(tab.icon, contentDescription = tab.label, tint = contentColor, modifier = Modifier.size(22.dp))
            Text(
                tab.label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Horizontal inset that keeps consecutive glass pills from touching while sliding. */
private val PillInset = 4.dp

/** A fling on the bar moves one extra item; below this the bar snaps where the pill stopped. */
private const val FLING_VELOCITY_PX_PER_S = 900f
