package com.Fusion.Btremix.melody.hook.injection

import android.content.Context
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyPanelActionArgs
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyPanelRow
import com.Fusion.Btremix.melody.api.MelodyPanelRowKind
import com.Fusion.Btremix.melody.hook.Reflect
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Attaches the row click listener; supplied by the injection, injectable in tests. */
internal fun interface MelodyRowClickBinder {
    fun bind(preference: Any, row: MelodyPanelRow, context: Context?): Boolean
}

/** Sends one resolved action to the bridge; the `onResult` code is the `IMelodyBridge.execute` result. */
internal fun interface MelodyPanelExecutor {
    fun execute(actionId: String, args: Map<String, StateValue>, onResult: (Int) -> Unit)
}

/** Host-native single-choice sheet (COUI bottom sheet), or `false` when it cannot be shown. */
internal fun interface MelodyPanelChoicePresenter {
    fun present(
        context: Context?,
        title: CharSequence,
        items: List<CharSequence>,
        checkedIndex: Int,
        onPick: (Int) -> Unit,
    ): Boolean
}

/** Host-native slider sheet (COUI bottom sheet + seekbar), or `false` when it cannot be shown. */
internal fun interface MelodyPanelSliderPresenter {
    fun present(
        context: Context?,
        title: CharSequence,
        min: Double,
        max: Double,
        step: Double?,
        value: Double,
        unit: String?,
        onPick: (Double) -> Unit,
    ): Boolean
}

/**
 * Sends one「高级功能」row's execute call to the bridge (M4.4, `HANDOFF_MELODY_M4_PLAN.md` §3 M4.4,
 * spec §7.4). The write path is exactly the Compose renderer's: `argName -> StateValue` ->
 * `IMelodyBridge.execute`. Nothing here owns a Definition or a device - the row already carries the
 * argument name/type, and [MelodyPanelActionArgs] rebuilds the value.
 */
internal object MelodyPanelActionBinder {

    /** One action per row is queued at a time; a second tap inside this window is ignored. */
    private const val REPEAT_WINDOW_MS = 400L

    fun binder(
        mac: String,
        currentMac: () -> String?,
        stateText: MelodyRowStateText,
        executor: MelodyPanelExecutor,
        choicePresenter: MelodyPanelChoicePresenter,
        sliderPresenter: MelodyPanelSliderPresenter,
        loader: ClassLoader,
        log: MelodyGroupLog,
    ): MelodyRowClickBinder {
        // Per screen + MAC: one row cannot be in flight twice while its value is still settling.
        val inFlight = ConcurrentHashMap<String, Long>()
        return MelodyRowClickBinder { preference, row, context ->
            bind(
                preference = preference,
                row = row,
                context = context,
                mac = mac,
                currentMac = currentMac,
                stateText = stateText,
                executor = executor,
                choicePresenter = choicePresenter,
                sliderPresenter = sliderPresenter,
                inFlight = inFlight,
                loader = loader,
                log = log,
            )
        }
    }

    /**
     * Attaches the click proxy and returns `true` when the host's setter accepted it. The caller only
     * invokes this once per row object.
     *
     * `androidx.preference.Preference.setOnPreferenceClickListener` keeps its name in 17.6.3, but the
     * listener interface is R8-renamed (`Preference$d`, single abstract `j(Preference)Z`), so the proxy
     * is created against the interface the setter actually declares - never a hard-coded type.
     */
    fun bind(
        preference: Any,
        row: MelodyPanelRow,
        context: Context?,
        mac: String,
        currentMac: () -> String?,
        stateText: MelodyRowStateText,
        executor: MelodyPanelExecutor,
        choicePresenter: MelodyPanelChoicePresenter,
        sliderPresenter: MelodyPanelSliderPresenter,
        inFlight: MutableMap<String, Long>,
        loader: ClassLoader,
        log: MelodyGroupLog,
    ): Boolean {
        val iface = clickListenerInterface(preference) ?: return fail(mac, row, "no_listener", log)
        val sam = singleAbstractMethod(iface)
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> objectMethod(proxy, method, args)
                samMatches(sam, method) || method.returnType == java.lang.Boolean.TYPE -> {
                    onClick(
                        preference = preference,
                        row = row,
                        context = context,
                        mac = mac,
                        currentMac = currentMac,
                        stateText = stateText,
                        executor = executor,
                        choicePresenter = choicePresenter,
                        sliderPresenter = sliderPresenter,
                        inFlight = inFlight,
                        log = log,
                    )
                    true
                }

                else -> null
            }
        }
        val proxyLoader = iface.classLoader ?: loader
        val proxy = runCatching {
            Proxy.newProxyInstance(proxyLoader, arrayOf(iface), handler)
        }.getOrNull() ?: return fail(mac, row, "proxy_failed", log)
        val attached = runCatching {
            Reflect.invokeSingleArg(preference, "setOnPreferenceClickListener", proxy)
        }.getOrDefault(false)
        if (!attached) return fail(mac, row, "set_refused", log)
        return true
    }

    private fun onClick(
        preference: Any,
        row: MelodyPanelRow,
        context: Context?,
        mac: String,
        currentMac: () -> String?,
        stateText: MelodyRowStateText,
        executor: MelodyPanelExecutor,
        choicePresenter: MelodyPanelChoicePresenter,
        sliderPresenter: MelodyPanelSliderPresenter,
        inFlight: MutableMap<String, Long>,
        log: MelodyGroupLog,
    ) {
        if (row.unavailable) return
        val action = row.action?.takeIf { it.isNotBlank() } ?: run {
            fail(mac, row, "no_action", log)
            return
        }
        // Spec §7.4: re-check that the page is still about the device this row was bound to.
        val liveMac = runCatching { currentMac() }.getOrNull()
        if (liveMac != mac) {
            log.event(
                "melody.panel.click",
                listOf("mac" to mac, "key" to row.key, "reason" to "mac_mismatch", "live" to liveMac),
            )
            return
        }
        val now = System.currentTimeMillis()
        inFlight[row.key]?.let { if (now - it < REPEAT_WINDOW_MS) return }

        when (row.kind) {
            MelodyPanelRowKind.SWITCH -> {
                val current = stateText.text(row.state.orEmpty())
                val checked = if (current != null) MelodyPanelActionArgs.isTruthy(current) else currentChecked(preference)
                dispatch(row, action, MelodyPanelActionArgs.switch(row, !checked), mac, inFlight, executor, log)
            }

            MelodyPanelRowKind.BUTTON ->
                dispatch(row, action, MelodyPanelActionArgs.button(row), mac, inFlight, executor, log)

            MelodyPanelRowKind.SEGMENTED -> {
                val labels = row.optionLabels.takeIf { it.size == row.options.size } ?: row.options
                val checkedIndex = row.options.indexOf(stateText.text(row.state.orEmpty()))
                val started = runCatching {
                    choicePresenter.present(context, row.title, labels, checkedIndex) { index ->
                        val option = row.options.getOrNull(index)
                        if (option != null) {
                            dispatch(
                                row,
                                action,
                                MelodyPanelActionArgs.choice(row, option),
                                mac,
                                inFlight,
                                executor,
                                log,
                            )
                        }
                    }
                }.getOrDefault(false)
                if (!started) fail(mac, row, "picker_failed", log)
            }

            MelodyPanelRowKind.SLIDER -> {
                val min = row.min ?: 0.0
                val max = row.max ?: 100.0
                val current = stateText.text(row.state.orEmpty())?.toDoubleOrNull() ?: min
                val started = runCatching {
                    sliderPresenter.present(context, row.title, min, max, row.step, current.coerceIn(min, max), row.unit) { picked ->
                        dispatch(
                            row,
                            action,
                            MelodyPanelActionArgs.slider(row, picked),
                            mac,
                            inFlight,
                            executor,
                            log,
                        )
                    }
                }.getOrDefault(false)
                if (!started) fail(mac, row, "picker_failed", log)
            }

            else -> Unit
        }
    }

    private fun dispatch(
        row: MelodyPanelRow,
        action: String,
        args: Map<String, StateValue>?,
        mac: String,
        inFlight: MutableMap<String, Long>,
        executor: MelodyPanelExecutor,
        log: MelodyGroupLog,
    ) {
        if (args == null) {
            fail(mac, row, "args", log)
            return
        }
        inFlight[row.key] = System.currentTimeMillis()
        val rendered = args.entries.joinToString(",") { (name, value) -> "$name=${wireText(value)}" }
        log.event(
            "melody.panel.click",
            listOf("mac" to mac, "key" to row.key, "kind" to row.kind.wire, "action" to action, "args" to rendered),
        )
        runCatching {
            executor.execute(action, args) { code ->
                inFlight.remove(row.key)
                if (code != MelodyBridgeResult.OK) {
                    log.event(
                        "melody.panel.click",
                        listOf("mac" to mac, "key" to row.key, "reason" to "failed", "code" to code),
                    )
                }
            }
        }.onFailure {
            inFlight.remove(row.key)
            fail(mac, row, "dispatch", log)
        }
    }

    private fun currentChecked(preference: Any): Boolean =
        Reflect.callBoolean(preference, "isChecked")
            ?: (Reflect.readField(preference, "mChecked", "checked") as? Boolean)
            ?: false

    private fun wireText(value: StateValue): String = when (value) {
        is StateValue.BooleanValue -> value.value.toString()
        is StateValue.IntValue -> value.value.toString()
        is StateValue.LongValue -> value.value.toString()
        is StateValue.FloatValue -> value.value.toString()
        is StateValue.DoubleValue -> value.value.toString()
        is StateValue.StringValue -> value.value
        is StateValue.BytesValue -> value.value.joinToString("") { "%02x".format(it) }
        is StateValue.ListValue -> "list"
        is StateValue.MapValue -> "map"
    }

    private fun samMatches(sam: Method?, method: Method): Boolean =
        sam != null && sam.name == method.name && sam.parameterTypes.contentEquals(method.parameterTypes)

    private fun singleAbstractMethod(iface: Class<*>): Method? =
        runCatching { iface.methods }.getOrNull().orEmpty()
            .filter { Modifier.isAbstract(it.modifiers) && !it.isSynthetic }
            .singleOrNull()

    /**
     * The listener interface the host `setOnPreferenceClickListener` accepts. Primary lookup is by the
     * setter name (kept in 17.6.3); the fallback accepts any single-argument `setX(Interface)` whose
     * interface is a SAM returning `boolean`, so a rename does not silently disable clicks.
     */
    private fun clickListenerInterface(preference: Any): Class<*>? {
        val declared = Reflect.hierarchyOf(preference.javaClass)
            .flatMap { runCatching { it.declaredMethods }.getOrNull().orEmpty().asSequence() }
        declared.firstOrNull { it.name == "setOnPreferenceClickListener" && it.parameterTypes.size == 1 }
            ?.let { return it.parameterTypes[0] }
        return declared.firstOrNull { method ->
            method.parameterTypes.size == 1 &&
                method.name.startsWith("set") &&
                method.parameterTypes[0].isInterface &&
                singleAbstractMethod(method.parameterTypes[0])?.returnType == java.lang.Boolean.TYPE
        }?.parameterTypes?.get(0)
    }

    private fun objectMethod(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "equals" -> proxy === args?.getOrNull(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "BtRemixMelodyPanelClick"
        else -> null
    }

    private fun fail(mac: String, row: MelodyPanelRow, reason: String, log: MelodyGroupLog): Boolean {
        log.event(
            "melody.panel.click",
            listOf("mac" to mac, "key" to row.key, "kind" to row.kind.wire, "reason" to reason),
        )
        return false
    }
}
