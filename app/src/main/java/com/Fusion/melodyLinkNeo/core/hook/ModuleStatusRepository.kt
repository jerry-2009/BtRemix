package com.fusion.melodyLinkNeo.core.hook

import com.fusion.melodyLinkNeo.core.hook.api.HookGateway
import com.fusion.melodyLinkNeo.core.hook.api.HookGatewayState
import com.fusion.melodyLinkNeo.core.hook.api.ModuleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Turns the raw [HookGatewayState] into the four-state module card (DEVICE_CENTER_UI_PLAN §3.3).
 *
 * The thresholds are data so they can be unit tested and later moved into the settings repository;
 * nothing here reads Android APIs or the `melody` package.
 */
data class ModuleStatusPolicy(
    /** Below this anchor coverage the module is "partially degraded" (warning). */
    val minCoverage: Float = 0.9f,
    /**
     * How long a handshake stays valid. Kept for when the gateway exposes a real handshake clock;
     * today the host reports on start, so the state is treated as fresh until the next host restart.
     */
    val handshakeTtl: Duration = 10.minutes,
)

/** Rendered module card state. [title]/[description] are already user-facing Chinese copy. */
data class ModuleStatusUi(
    val status: ModuleStatus,
    val title: String,
    val description: String,
    val version: String?,
    val coverage: String?,
    val showDetail: Boolean,
)

class ModuleStatusRepository(
    private val gateway: HookGateway,
    scope: CoroutineScope,
    private val policy: ModuleStatusPolicy = ModuleStatusPolicy(),
) {
    val status: StateFlow<ModuleStatusUi> = gateway.state
        .map(policy::evaluate)
        .stateIn(scope, SharingStarted.Eagerly, policy.evaluate(gateway.state.value))

    fun refresh() = gateway.refresh()

    /** Mirrors the module's `diagnostics_enabled` switch for Settings → 开发者. */
    val diagnosticsEnabled: StateFlow<Boolean> = gateway.diagnosticsEnabled

    /** Dismisses the "宿主已更新" prompt once the user has seen it. */
    fun acknowledgeUpdate() = gateway.acknowledgeUpdate()

    /** Clears the anchor cache so the next host start re-runs the full DexKit relocation pass. */
    fun requestDexRescan() = gateway.requestDexRescan()

    fun setDiagnosticsEnabled(value: Boolean) = gateway.setDiagnosticsEnabled(value)
}

/** Pure mapping, no side effects - covered by JVM tests. */
fun ModuleStatusPolicy.evaluate(state: HookGatewayState): ModuleStatusUi {
    val version = state.version
    val coverageText = if (state.coverage.total > 0) {
        "${state.coverage.hits}/${state.coverage.total}"
    } else {
        null
    }
    return when {
        !state.hostInstalled -> ModuleStatusUi(
            status = ModuleStatus.STOPPED,
            title = "模块未启用",
            description = "未检测到 ColorOS Melody，或模块尚未在 LSPosed 中启用",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )

        state.awaitingHost -> ModuleStatusUi(
            status = ModuleStatus.WARNING,
            title = "等待宿主启动",
            description = "打开一次 Melody 面板即可完成注入与锚点定位",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )

        state.coverage.total == 0 -> ModuleStatusUi(
            status = ModuleStatus.WARNING,
            title = "等待锚点报告",
            description = "宿主已启动，尚未回报锚点位置",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )

        state.coverage.hits == 0 -> ModuleStatusUi(
            status = ModuleStatus.ERROR,
            title = "Hook 注入失败",
            description = "锚点全部缺失，请查看诊断",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )

        state.coverage.ratio < minCoverage -> ModuleStatusUi(
            status = ModuleStatus.WARNING,
            title = "模块部分降级",
            description = "锚点 ${state.coverage.hits}/${state.coverage.total}，部分功能不可用",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )

        else -> ModuleStatusUi(
            status = ModuleStatus.RUNNING,
            title = "模块运行中",
            description = "Hook 模块已激活，设备中心正在正常工作",
            version = version,
            coverage = coverageText,
            showDetail = true,
        )
    }
}
