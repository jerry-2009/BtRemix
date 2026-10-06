package com.Fusion.Btremix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.ui.theme.DcMotion
import com.Fusion.Btremix.ui.theme.DcShapes
import com.Fusion.Btremix.ui.theme.DcSpacing
import com.Fusion.Btremix.ui.theme.DcType
import com.Fusion.Btremix.ui.theme.statusColors

/**
 * Material 3 Expressive card: one large-radius, tonal-filled block, never a drop shadow.
 *
 * The fill is passed to `Surface` itself (never `Color.Transparent`): `Surface` derives its content
 * color from the background it is given, and a transparent background makes `contentColorFor()`
 * return `Unspecified`, which falls back to the framework's black text - invisible in dark mode.
 */
@Composable
fun DcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentPadding: PaddingValues = PaddingValues(DcSpacing.md),
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val body: @Composable () -> Unit = { Box(Modifier.padding(contentPadding)) { content() } }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = containerColor,
        ) { body() }
    } else {
        Surface(modifier = modifier, shape = shape, color = containerColor) { body() }
    }
}

/** `titleMedium` heading with optional trailing content; owns the top gap so screens stay tidy. */
@Composable
fun DcSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DcSpacing.lg, bottom = DcSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

/**
 * Big number + caption tile used by the Home stats row.
 *
 * [containerColor] lets the caller assign an expressive tonal role per tile (primary / secondary /
 * tertiary container), which is the cheapest way to give the dashboard the colour-blocked
 * expressive look without touching the copy.
 */
@Composable
fun DcStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    val onContainer = contentColorFor(containerColor).takeOrElse { MaterialTheme.colorScheme.onSurface }
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = DcSpacing.md, horizontal = DcSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DcSpacing.xs),
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = onContainer)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = onContainer.copy(alpha = 0.75f),
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = containerColor) { content() }
    } else {
        Surface(modifier = modifier, shape = shape, color = containerColor) { content() }
    }
}

/** 8dp status dot plus optional label. */
@Composable
fun DcStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    val animatedColor by animateColorAsState(color, DcMotion.colorEffects, label = "status-dot")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(animatedColor))
        if (label != null) Text(label, style = labelStyle, color = animatedColor)
    }
}

/** Small stadium pill with a tinted fill; used for secondary device statuses. */
@Composable
fun DcStatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = DcShapes.pill,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Battery icon + percentage; low charge switches to the warning color. */
@Composable
fun DcBatteryBadge(percent: Int?, charging: Boolean = false, modifier: Modifier = Modifier) {
    val warning = MaterialTheme.statusColors.warning
    val idle = MaterialTheme.statusColors.idle
    val color = when {
        percent == null -> idle
        percent < 20 && !charging -> warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        charging -> Icons.Rounded.BatteryChargingFull
        percent == null -> Icons.Rounded.BatteryAlert
        percent < 20 -> Icons.Rounded.BatteryAlert
        else -> Icons.Rounded.BatteryFull
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(percent?.let { "$it%" } ?: "—", style = DcType.mono, color = color)
    }
}

/** A 56dp-minimum list row: leading icon, title + optional subtitle, trailing slot. */
@Composable
fun DcListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DcSpacing.md, vertical = DcSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DcSpacing.md),
    ) {
        when {
            leadingContent != null -> leadingContent()
            leadingIcon != null -> Icon(
                leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Grouped settings container: rounded surface that clips a list of rows. */
@Composable
fun DcListGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column { content() }
    }
}

/** Scrollable filter chips; [options] are shown in order and [selected] may be absent (全部). */
@Composable
fun DcFilterChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DcSpacing.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) },
                shape = DcShapes.pill,
            )
        }
    }
}

/** Empty state: illustration + one sentence + exactly one primary action (§8). */
@Composable
fun DcEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(DcSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DcSpacing.sm),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(DcSpacing.xs))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(DcSpacing.xs))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Error state: human reason + retry + jump to logs (§8). */
@Composable
fun DcErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onViewLogs: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(DcSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f)) {
                Text("出错了", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (onRetry != null) {
                Button(onClick = onRetry) { Text("重试") }
            }
            if (onViewLogs != null) {
                Button(onClick = onViewLogs) { Text("查看日志") }
            }
        }
    }
}

/** Shimmering skeleton block; the Loading rule forbids a full-screen spinner (§8). */
@Composable
fun DcSkeletonBlock(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "dc-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dc-skeleton-alpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(DcShapes.pill)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** Skeleton screen for a list of cards. */
@Composable
fun DcLoadingState(modifier: Modifier = Modifier, rows: Int = 3) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = DcSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(DcSpacing.md),
    ) {
        repeat(rows) {
            DcCard {
                Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                    DcSkeletonBlock(height = 20.dp)
                    DcSkeletonBlock(height = 14.dp)
                    DcSkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f), height = 14.dp)
                }
            }
        }
    }
}

/**
 * Page top bar. First-level pages get the expressive large title treatment (`headlineSmall`) so the
 * four tabs read like full-bleed expressive headers; detail pages keep the compact 56dp bar with a
 * back affordance. [subtitle] is only used by the large variant and mirrors the dashboard hero
 * (title + one supporting line).
 */
@Composable
fun DcTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val large = onBack == null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (large) 72.dp else 56.dp)
            .padding(
                horizontal = if (large) DcSpacing.screenPadding else DcSpacing.sm,
                vertical = if (large && subtitle != null) DcSpacing.sm else 0.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
        }
        Column(Modifier.weight(1f).padding(start = if (large) 0.dp else DcSpacing.sm)) {
            Text(
                title,
                style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (large && subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** Refresh icon button reused by the page top bars. */
@Composable
fun DcRefreshButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
    }
}

/** Monospaced inline value with a muted label, used for MAC/UUID/version rows. */
@Composable
fun DcMonoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = DcSpacing.md, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = DcType.mono)
    }
}

/** Horizontal divider that matches the group separators. */
@Composable
fun DcDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Spacer that leaves room for the floating bottom bar. */
@Composable
fun DcBottomContentInset() {
    Spacer(Modifier.height(DcSpacing.contentBottomInset))
}

/** Convenience: width-only spacer. */
@Composable
fun DcHGap(width: androidx.compose.ui.unit.Dp = DcSpacing.sm) {
    Spacer(Modifier.width(width))
}
