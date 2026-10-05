package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.MelodyAnchorSession
import com.fusion.melodyLinkNeo.melody.hook.Reflect
import com.fusion.melodyLinkNeo.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface

/**
 * M5.1 diagnostics for the **desktop card** (`com.oplus.melody.card.MelodyCardWidgetProvider`).
 *
 * The card's data is not read from the `EarphoneDTO` by the widget itself: `MelodyCardDataSender`
 * (`n7/e$a`) observes the active-earphone LiveData, maps it into `MelodyCardDataVO` (`n7/f`) with
 * `n1(0x1c)` and pushes it through `CardWidgetAction.postUpdateCommand(...)`. When the card keeps showing
 * "关闭" it is not visible in logcat whether (a) no push happens at all, (b) a push happens but the noise
 * entries still map to the off mode, or (c) the push is correct and the launcher ignores it.
 *
 * This hook only *reports*: it reads the pushed VO's `noiseEnabled` flag and the
 * (`modeType` / `protocolIndex` / switch) of the three noise entries, then lets the original call run
 * untouched. Read-only, fail-open.
 */
internal class MelodyCardPushInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    @Volatile
    private var lastPush: String? = null

    fun install() {
        val sender = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.CARD_SENDER, loader)
        if (sender == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to SENDER_CLASS)
            return
        }
        val vo = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.CARD_VO, loader)
        if (vo == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to VO_CLASS)
            return
        }
        val push = Reflect.findUniqueMethodByParams(sender, arrayOf(vo))
        if (push == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to sender.name, "params" to vo.name)
            return
        }
        module.hook(push).intercept(XposedInterface.Hooker { chain ->
            runCatching { note(chain.args.getOrNull(0)) }
            chain.proceed()
        })
        log.event("melody.anchor.hooked", "hook" to HOOK, "class" to sender.name, "method" to push.name)
    }

    private fun note(vo: Any?) {
        if (vo == null) return
        val enabled = Reflect.callBoolean(vo, "getNoiseEnabled")
        val close = entry(Reflect.call(vo, "getNoiseClose"))
        val anc = entry(Reflect.call(vo, "getNoiseReduction"))
        val ambient = entry(Reflect.call(vo, "getNoiseTransparent"))
        val line = "enabled=$enabled close=$close anc=$anc ambient=$ambient"
        if (lastPush == line) return
        lastPush = line
        log.event(
            "melody.card.push",
            "enabled" to enabled,
            "close" to close,
            "anc" to anc,
            "ambient" to ambient,
        )
    }

    /** `modeType/protocolIndex/switch` of one entry, so the log tells which mode the card highlights. */
    private fun entry(entry: Any?): String? {
        if (entry == null) return null
        val type = Reflect.callInt(entry, "getModeType")
        val index = Reflect.callInt(entry, "getProtocolIndex")
        val switch = Reflect.callBoolean(entry, "getModeSwitch")
        return "$type/$index/$switch"
    }

    private companion object {
        const val HOOK = "inject.card_push"

        /** `MelodyCardDataSender.CardValue.sendValue(MelodyCardDataVO)` (17.6.3). */
        const val SENDER_CLASS = "n7.e\$a"
        const val VO_CLASS = "n7.f"
    }
}
