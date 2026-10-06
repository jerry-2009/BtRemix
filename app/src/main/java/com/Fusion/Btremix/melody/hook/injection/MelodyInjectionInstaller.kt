package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.hook.MelodyLog
import io.github.libxposed.api.XposedInterface
import java.io.File

/**
 * Installs the M3.3 provider injection (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.3).
 *
 * Deliberately separate from [com.Fusion.Btremix.melody.hook.observation.MelodyObservationInstaller]
 * and behind its own `melody_bridge.injection_enabled` switch (M3-D5): observation stays read-only and
 * can be on while injection is off, and a broken injection anchor degrades to one log line instead of
 * touching the observation path.
 *
 * Runs in every `com.oplus.melody` process; the provider lives in the main process, and installing the
 * hook in `:fg` too is harmless (the anchor never fires there).
 */
internal class MelodyInjectionInstaller(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    private val processName: String?,
    /** Host `ApplicationInfo.sourceDir`; the DexKit fallback for the transport anchors needs it. */
    private val hostApkPath: String?,
) {

    fun install() {
        log.event(
            "melody.injection.install",
            "process" to processName,
            // APK mtime of the module dex this process actually loaded. LSPosed only re-reads a module
            // when the process starts, so a host that was not restarted after an install keeps answering
            // with the previous stamp - the fastest way to tell "code is old" from "code did nothing".
            "module_apk_ms" to moduleApkStamp(),
        )
        runCatching { MelodyAliveProviderInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.failed", it) }
        // M3.4: the registry the detail page is actually built from, then the two transport backstops.
        runCatching { MelodyDeviceInfoInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.deviceinfo_failed", it) }
        runCatching { MelodyTransportInjection(module, log, loader, hostApkPath).install() }
            .onFailure { log.warn("melody.injection.transport_failed", it) }
        // M3.4b: the in-memory whitelist repository the device-centre card code asks (the Provider
        // injection alone cannot reach it, see MelodyWhitelistRepositoryInjection). The instance is
        // kept because the collection-lookup injection below answers from the same DTO.
        val whitelistRepo = runCatching { MelodyWhitelistRepositoryInjection(module, log, loader) }.getOrNull()
        whitelistRepo?.let { repo ->
            runCatching { repo.install() }
                .onFailure { log.warn("melody.injection.whitelist_repo_failed", it) }
        }
        // 2026-10-06 card debug: `WhitelistUtils.a/b` search a *caller-supplied* collection, which our
        // repository hooks never see - they are what prints `findWhitelistConfig failed … 404` when the
        // device-centre card is rebuilt. Answer them from the same repository answer.
        runCatching {
            MelodyWhitelistLookupInjection(
                module = module,
                log = log,
                loader = loader,
                answerByProduct = { productId, name -> whitelistRepo?.answerByProduct(productId, name) },
                answerByMac = { mac -> whitelistRepo?.answerByMac(mac) },
            ).install()
        }.onFailure { log.warn("melody.injection.whitelist_lookup_failed", it) }
        // M4.2: hide/grey the official detail-page rows the Definition asked to remove. Read-only with
        // respect to the official data - it only flips `setVisible`/`setEnabled` from the envelope policy.
        runCatching { MelodyPanelInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.panel_failed", it) }
        // M4.3a: project the session lifecycle + battery into the `EarphoneDTO` the detail and OneSpace
        // headers read, so both show the native "connected + battery" header while our session is up.
        runCatching { MelodyEarphoneInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.header_failed", it) }
        // M4.3b D-12: rewrite the ANC mode texts at the host's `DeviceControlWidget` collection point,
        // so a mode the host's vocabulary renders as "Adaptive" shows the Definition's own wording.
        runCatching { MelodyAncLabelInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.anc_label_failed", it) }
        // M5.1: redirect every official ANC write (the single `earphone/b;->v0` collection point) into
        // `IMelodyBridge.execute`, so system/device-centre initiated controls also run through BtRemix.
        runCatching { MelodyAncRedirectInjection(module, log, loader, hostApkPath).install() }
            .onFailure { log.warn("melody.injection.redirect_failed", it) }
        // M5.2: the `setgate` broadcast (device card / settings / OneSpace) is the second layer. It is
        // upstream of `v0`, so taking it over also skips the host's own `modeType` mapping and wear
        // validation, and it keeps the entrance covered if the `v0` anchor is ever renamed. The M1
        // `melody.command.receive` observation is emitted by this same hook (one hook per method).
        runCatching { MelodySetgateRedirectInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.setgate_failed", it) }
        // M5.3: the device-centre SDK (`EarphoneControlProvider.call`) is the third entrance. It maps
        // its `extras.type` (`modeType`) through the injected table itself, like `setgate`, but its host
        // resolver also searches each entry's「降噪效果」children, so a child target is taken over too.
        // The M1 `melody.command.call` observation is emitted by this same hook (one hook per method).
        runCatching { MelodyProviderCallRedirectInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.provider_failed", it) }
        // M5.1 follow-up: the device-centre card's rows come from the SDK repository row, which the host
        // fills from its own (suppressed) session - and its own restore path even uses an *empty* noise
        // value, i.e. "关闭". Installed first so the snapshot refresh below can replay it after a write.
        val cardMenus = runCatching { MelodyAncCardMenuInjection(module, log, loader) }.getOrNull()
        cardMenus?.let {
            runCatching { it.install() }.onFailure { error -> log.warn("melody.injection.anc_card_failed", error) }
        }
        // M5.1 follow-up: after a redirected write the host's own ANC view-model is stale (its LiveData
        // is fed by the suppressed official session), so re-dispatch the projected index to the live
        // detail-page item and OneSpace fragment. Snapshot-driven, fail-open.
        runCatching {
            MelodyAncRefreshInjection(module, log, loader) { mac -> cardMenus?.republish(mac) }.install()
        }
            .onFailure { log.warn("melody.injection.anc_refresh_failed", it) }
        // M5.1 follow-up: the device-centre card is not built from the DTO - its rows read the btsdk
        // `CurrentNoiseModeInfo.getCurrentNoiseReductionModeIndex()`, which our redirect never touches.
        runCatching { MelodyAncNoiseInfoInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.anc_noiseinfo_failed", it) }
        // M5.1 diagnostics: what the desktop card actually receives (read-only), so a "card shows 关闭"
        // report can be told apart from "no push happened" without a debugger.
        runCatching { MelodyCardPushInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.card_push_failed", it) }
    }

    private fun moduleApkStamp(): Long? =
        runCatching { File(module.moduleApplicationInfo.sourceDir).lastModified() }.getOrNull()
}
