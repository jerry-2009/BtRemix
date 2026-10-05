package com.fusion.melodyLinkNeo.ui.shell

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.ui.theme.DcShapes
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Liquid Glass bottom navigation (DEVICE_CENTER_UI_PLAN §4.9).
 *
 * `backdrop` only ships the low-level effect; the capsule bar, the selected pill and the fallback
 * branch are ours. [glassEnabled] is false when the user turns transparency off or on a low-RAM
 * device, in which case the bar degrades to an opaque container with an outline and no lens.
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
    val shape: Shape = DcShapes.capsule
    val canBlur = glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f)

    val barModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .height(DcSpacing.bottomBarHeight)
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

    Row(
        modifier = barModifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                backdrop = backdrop,
                glassEnabled = canBlur,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavItem(
    tab: AppTab,
    selected: Boolean,
    backdrop: LayerBackdrop,
    glassEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape: Shape = DcShapes.capsule
    val tint = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val pill = modifier
        .height(56.dp)
        .clip(pillShape)
        .clickable(onClick = onClick)
        .then(
            if (glassEnabled && selected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { pillShape },
                    effects = {
                        lens(
                            refractionHeight = 10.dp.toPx(),
                            refractionAmount = 14.dp.toPx(),
                            chromaticAberration = true,
                        )
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(tint) },
                )
            } else if (selected) {
                Modifier.background(tint)
            } else {
                Modifier
            },
        )

    Box(pill, contentAlignment = Alignment.Center) {
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
