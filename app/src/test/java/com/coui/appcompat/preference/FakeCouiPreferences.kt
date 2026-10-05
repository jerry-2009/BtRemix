package com.coui.appcompat.preference

/**
 * JVM stand-ins for the R8-minified COUI preference classes the M4.3c panel-group applier reaches by
 * reflection (`MelodyPanelGroupApplierTest`). They are deliberately *not* the real host classes - they
 * only carry the accessor names/shapes the applier depends on, plus the two quirks M4.3b D-14 measured
 * on device:
 *
 *  - `COUIPreferenceCategory` exposes **no** `getPreferenceCount`/`getPreference` pair, so the tree
 *    walk has to fall back to the children `List` field;
 *  - its add method is obfuscated (`zz`), so insertion has to be effect-based rather than name-based.
 */
@Suppress("unused")
open class COUIPreference(initialKey: String? = null) {
    /**
     * The listener interface the real host inherits from `androidx.preference.Preference` (R8-renamed
     * to `Preference$d`, single abstract `j(Preference)Z`). Same shape here: one boolean method.
     */
    fun interface OnClickListener {
        fun onPreferenceClick(preference: Any?): Boolean
    }

    private var key: String? = initialKey
    private var title: CharSequence? = null
    private var summary: CharSequence? = null
    private var assignment: CharSequence? = null
    private var visible = true
    private var enabled = true
    private var order = 0
    private var selectable = true
    private var layoutResource = 0
    private var widgetLayoutResource = 0
    private var parent: Any? = null
    private var clickListener: OnClickListener? = null
    private var notifications = 0

    fun getKey(): String? = key
    fun setKey(value: String?) { key = value }
    fun getTitle(): CharSequence? = title
    fun setTitle(value: CharSequence?) { title = value }
    fun getSummary(): CharSequence? = summary
    fun setSummary(value: CharSequence?) { summary = value }
    fun getAssignment(): CharSequence? = assignment
    fun setAssignment(value: CharSequence?) { assignment = value }
    fun isVisible(): Boolean = visible
    fun setVisible(value: Boolean) { visible = value }
    fun isEnabled(): Boolean = enabled
    fun setEnabled(value: Boolean) { enabled = value }
    fun getOrder(): Int = order
    fun setOrder(value: Int) { order = value }
    fun isSelectable(): Boolean = selectable
    fun setSelectable(value: Boolean) { selectable = value }
    fun getLayoutResource(): Int = layoutResource
    fun setLayoutResource(value: Int) { layoutResource = value }
    fun getWidgetLayoutResource(): Int = widgetLayoutResource
    fun setWidgetLayoutResource(value: Int) { widgetLayoutResource = value }
    fun getParent(): Any? = parent
    fun getContext(): Any? = null
    fun notifyChanged() { notifications++ }
    fun notifyCount(): Int = notifications

    fun setOnPreferenceClickListener(listener: OnClickListener?) { clickListener = listener }
    fun getOnPreferenceClickListener(): OnClickListener? = clickListener

    /** Test driver: what the host does when the row is tapped. */
    fun performClick(): Boolean = clickListener?.onPreferenceClick(this) ?: false

    internal fun attachTo(container: Any) { parent = container }
}

@Suppress("unused")
open class COUISwitchPreference : COUIPreference() {
    private var checked = false

    fun isChecked(): Boolean = checked
    fun setChecked(value: Boolean) { checked = value }
}

@Suppress("unused")
open class COUIPreferenceCategory : COUIPreference() {
    // The real category's `getPreferenceCount`/`getPreference` are R8-stripped; the tree walk finds
    // the children through this list field instead.
    private val items: MutableList<Any> = mutableListOf()

    /** Obfuscated `addPreference`; the applier has to discover it by effect, not by name. */
    fun zz(preference: Any) {
        (preference as? COUIPreference)?.attachTo(this)
        items.add(preference)
    }

    fun children(): List<Any> = items
}
