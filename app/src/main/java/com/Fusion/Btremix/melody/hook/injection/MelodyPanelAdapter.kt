package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.api.PanelRow
import com.Fusion.Btremix.melody.hook.PreferenceTree
import com.Fusion.Btremix.melody.hook.Reflect

/**
 * Reflection-backed [PanelRow] adapter for the M4.2 applier (MELODY_BRIDGE_SPEC §7.2).
 *
 * `androidx.preference.Preference` and the COUI categories are R8-minified inside
 * `com.oplus.melody`, so nothing here can be referenced by type. Accessors are the public API the host
 * itself calls (`getKey`/`isVisible`/`setVisible`/`isEnabled`/`setEnabled`/`getPreferenceCount`), each
 * with the same field fallback M1's [PreferenceTree] already uses.
 *
 * A row with no key reports an empty key: it can never match a policy entry (policy keys come from a
 * real dump and are never blank), so it is only ever touched by the empty-group cleanup of its parent.
 */
internal object MelodyPanelAdapter {

    /** The top-level rows of a screen (or any container). */
    fun rowsOf(container: Any): List<PanelRow> = PreferenceTree.childrenOf(container).map(::ReflectionPanelRow)
}

/** One host `Preference` seen through the reflection surface above. */
internal class ReflectionPanelRow(private val preference: Any) : PanelRow {

    override val key: String =
        Reflect.callString(preference, "getKey")?.takeIf { it.isNotBlank() }
            ?: (Reflect.readField(preference, "mKey", "key") as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
            ?: ""

    override val isGroup: Boolean = PreferenceTree.isGroup(preference)

    override val isVisible: Boolean
        get() = Reflect.callBoolean(preference, "isVisible")
            ?: (Reflect.readField(preference, "mVisible", "visible") as? Boolean)
            ?: true

    override val isEnabled: Boolean
        get() = Reflect.callBoolean(preference, "isEnabled")
            ?: (Reflect.readField(preference, "mEnabled", "enabled") as? Boolean)
            ?: true

    override fun children(): List<PanelRow> =
        if (isGroup) PreferenceTree.childrenOf(preference).map(::ReflectionPanelRow) else emptyList()

    override fun setVisible(visible: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setVisible", visible)) return
        Reflect.writeField(preference, arrayOf("mVisible", "visible"), visible)
    }

    override fun setEnabled(enabled: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setEnabled", enabled)) return
        Reflect.writeField(preference, arrayOf("mEnabled", "enabled"), enabled)
    }
}
