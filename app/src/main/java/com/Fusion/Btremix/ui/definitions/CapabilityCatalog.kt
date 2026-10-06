package com.Fusion.Btremix.ui.definitions

/**
 * Display-only capability table (DEVICE_CENTER_UI_PLAN §3.4).
 *
 * The 2026-10-05 decision keeps capability-driven *rendering* out of this milestone, but the table
 * is still used for the Definitions detail page (分组标题/图标/排序) and Home statistics. Unknown ids
 * are rendered as-is and sorted last - the catalogue never rejects a package.
 */
data class CapabilityInfo(val id: String, val label: String, val order: Int)

object CapabilityCatalog {
    private val table = listOf(
        CapabilityInfo("power.battery", "电池", 10),
        CapabilityInfo("audio.noise_control", "降噪 / 环境声", 20),
        CapabilityInfo("audio.equalizer", "均衡器", 30),
        CapabilityInfo("audio.spatial", "空间音频", 40),
        CapabilityInfo("input.touch", "触控设置", 50),
        CapabilityInfo("device.info", "设备信息", 90),
        CapabilityInfo("device.raw", "原始调试", 999),
    ).associateBy { it.id }

    fun describe(capabilities: Collection<String>): List<CapabilityInfo> = capabilities
        .map { id -> table[id] ?: CapabilityInfo(id, id, 1000) }
        .sortedWith(compareBy({ it.order }, { it.id }))
}
