package com.Fusion.Btremix.melody.api

/**
 * Read-only observation model for the official Melody panels (MELODY_BRIDGE_SPEC §7.2, milestone M1).
 *
 * M1 answers two questions without changing any host behaviour: "who asks what, and when" and "which
 * rows does the panel contain". The provider/command side of that answer is pure logcat, but the row
 * inventory is data, so it is modelled here as plain Kotlin: the hooks running inside
 * `com.oplus.melody` collect [PanelKeyRow]s into a [PanelKeyCatalog] and render them with
 * [renderPanelKeysMarkdown].
 *
 * Keeping this file free of Android and libxposed imports means the row bookkeeping and the Markdown
 * projection are covered by ordinary JVM tests, exactly like the rest of the protocol/definition code.
 */

/** One official row observed on a Melody panel screen. */
data class PanelKeyRow(
    /** Screen the row was found on, e.g. `DetailMainActivity`. */
    val screen: String,
    /** The Preference key, or a synthetic `<ClassName#order>` marker when the host left it null. */
    val key: String,
    /** Row title as rendered by the host, when the panel exposes one. */
    val title: String?,
    /** Concrete Preference class as loaded inside the host process (R8 names included). */
    val className: String,
    val visible: Boolean,
    val enabled: Boolean,
    val order: Int,
    /** Nesting depth inside the Preference tree; 0 = direct child of the screen. */
    val depth: Int,
) {
    /** Stable identity used for de-duplication across repeated dumps of the same screen. */
    val identity: String get() = screen + '|' + key + '|' + className
}

/** Outcome of feeding one row into the catalog. */
enum class PanelKeyChange {
    ADDED,
    UPDATED,
    UNCHANGED,
}

/**
 * Accumulates the rows seen across every observed screen. Rows are keyed by
 * [PanelKeyRow.identity] so re-dumping a screen (panels rebuild their list asynchronously) neither
 * duplicates entries nor loses order/visibility changes.
 */
class PanelKeyCatalog {
    private val entries = LinkedHashMap<String, PanelKeyRow>()

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun record(row: PanelKeyRow): PanelKeyChange {
        val previous = entries[row.identity]
        entries[row.identity] = row
        return when {
            previous == null -> PanelKeyChange.ADDED
            previous == row -> PanelKeyChange.UNCHANGED
            else -> PanelKeyChange.UPDATED
        }
    }

    /** All rows in panel order, grouped by screen. */
    fun rows(): List<PanelKeyRow> = entries.values.sortedWith(
        compareBy({ it.screen }, { it.order }, { it.key }, { it.className }),
    )

    fun screens(): List<String> = entries.values.map { it.screen }.distinct().sorted()
}

/**
 * Renders the observed rows as the `docs/melody-official-keys.md` document (MELODY_BRIDGE_SPEC
 * §7.2). The hook writes the same text next to the JSON dump inside the host data directory, so the
 * panel inventory can be pulled from a real device without a second transformation step.
 */
fun renderPanelKeysMarkdown(
    rows: List<PanelKeyRow>,
    hostVersion: String? = null,
    generatedAt: String? = null,
): String = buildString {
    appendLine("# Melody 官方控件 Key 清单（M1 只读观测）")
    appendLine()
    appendLine("> 由 BtRemix Melody 桥 M1 只读观测自动生成（MELODY_BRIDGE_SPEC §7.2）。请勿手工维护。")
    if (generatedAt != null) appendLine("> 生成时间：$generatedAt")
    appendLine("> 宿主版本：${hostVersion ?: "?"}")
    appendLine("> 观测行数：${rows.size}")
    appendLine()

    if (rows.isEmpty()) {
        appendLine("（尚未观测到任何行。请在真机上打开耳机详情页 / 负一屏设备空间后重新导出。）")
        return@buildString
    }

    val byScreen = rows.groupBy { it.screen }
    for (screen in byScreen.keys.sorted()) {
        appendLine("## $screen")
        appendLine()
        appendLine("| key | title | 类 | visible | enabled | order | depth |")
        appendLine("|---|---|---|---|---|---|---|")
        for (row in byScreen.getValue(screen)) {
            append("| `").append(cell(row.key)).append("` ")
            append("| ").append(cell(row.title ?: "")).append(' ')
            append("| ").append(cell(row.className)).append(' ')
            append("| ").append(row.visible).append(' ')
            append("| ").append(row.enabled).append(' ')
            append("| ").append(row.order).append(' ')
            append("| ").append(row.depth).appendLine(" |")
        }
        appendLine()
    }
}

private const val MAX_CELL_CHARS = 120

/** Fits a value into one Markdown table cell: no pipes, no line breaks, bounded length. */
private fun cell(value: String): String {
    val flattened = value.replace('\r', ' ').replace('\n', ' ').replace("|", "\\|").trim()
    return if (flattened.length <= MAX_CELL_CHARS) {
        flattened
    } else {
        flattened.take(MAX_CELL_CHARS - 1) + "…"
    }
}
