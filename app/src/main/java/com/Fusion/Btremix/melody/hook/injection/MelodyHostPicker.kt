package com.Fusion.Btremix.melody.hook.injection

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.Fusion.Btremix.melody.hook.Reflect
import kotlin.math.roundToInt

/**
 * Fallback single-choice / slider sheet for the self-built「高级功能」rows (M6).
 *
 * Choice rows normally never reach here: [MelodyPanelGroupApplier] builds them as the host's own
 * `COUIMenuPreference`, so tapping one opens the native ColorOS popup menu (`COUIClickSelectMenu` ->
 * `COUIPopupListWindow`). This sheet is the fail-open path for a host build where that class or its
 * R8-short entry setters are unavailable (`COUIJumpPreference` + click binder).
 *
 * The native look comes from the host's own `com.coui.appcompat.panel.COUIListBottomSheetDialog$Builder`
 * (verified in Melody 17.6.3 via mt-apk-mcp): the builder class name and its
 * `setTitle`/`setSingleChoiceItems`/`getBottomSheetDialog` methods survive R8, and every parameter
 * type is a framework type - so the sheet is reached with a small, stable reflection surface. If the
 * anchor drifts (host upgrade) the picker falls back to a framework [AlertDialog]; if even that fails
 * it returns `false` and the caller logs `melody.panel.picker.failed` and does nothing (fail-open).
 *
 * `ListPreference` is deliberately never used (spec §7.3: the host R8-stripped its entry setters).
 */
internal class MelodyHostPicker(
    private val loader: ClassLoader,
    private val log: MelodyGroupLog? = null,
) :
    MelodyPanelChoicePresenter,
    MelodyPanelSliderPresenter {

    override fun present(
        context: Context?,
        title: CharSequence,
        items: List<CharSequence>,
        checkedIndex: Int,
        onPick: (Int) -> Unit,
    ): Boolean {
        if (items.isEmpty()) return false
        val target = context?.let(::uiContext) ?: return false
        val listener = DialogInterface.OnClickListener { _, which -> onPick(which) }
        if (couiChoice(target, title, items, checkedIndex, listener)) {
            note("choice", "coui")
            return true
        }
        val fallback = runCatching {
            AlertDialog.Builder(target)
                .setTitle(title)
                .setSingleChoiceItems(items.toTypedArray(), checkedIndex, listener)
                .show()
            true
        }.getOrDefault(false)
        if (fallback) note("choice", "system") else note("choice", "failed")
        return fallback
    }

    override fun present(
        context: Context?,
        title: CharSequence,
        min: Double,
        max: Double,
        step: Double?,
        value: Double,
        unit: String?,
        onPick: (Double) -> Unit,
    ): Boolean {
        if (!min.isFinite() || !max.isFinite() || max < min) return false
        val target = context?.let(::uiContext) ?: return false
        val size = step?.takeIf { it.isFinite() && it > 0.0 } ?: ((max - min) / 100.0).takeIf { it > 0.0 } ?: 1.0
        val steps = ((max - min) / size).roundToInt().coerceIn(1, MAX_STEPS)
        val start = ((value - min) / (max - min) * steps).roundToInt().coerceIn(0, steps)
        val layout = sliderLayout(target, title, min, max, size, steps, start, unit, onPick)
        if (couiSlider(target, layout)) {
            note("slider", "coui")
            return true
        }
        val fallback = runCatching {
            AlertDialog.Builder(target).setTitle(title).setView(layout).show()
            true
        }.getOrDefault(false)
        if (fallback) note("slider", "system") else note("slider", "failed")
        return fallback
    }

    private fun note(kind: String, outcome: String) {
        if (outcome == "failed") {
            log?.event("melody.panel.picker.failed", listOf("kind" to kind))
        } else {
            log?.event("melody.panel.picker.sheet", listOf("kind" to kind, "source" to outcome))
        }
    }

    // --- COUI bottom sheet (preferred) ------------------------------------------------------------

    private fun couiChoice(
        context: Context,
        title: CharSequence,
        items: List<CharSequence>,
        checkedIndex: Int,
        listener: DialogInterface.OnClickListener,
    ): Boolean {
        val outcome = runCatching {
            val builder = newBuilder(context) ?: return@runCatching false
            val cls = builder.javaClass
            invoke(cls, builder, "setTitle", arrayOf<Class<*>>(CharSequence::class.java), arrayOf(title))
            invoke(
                cls,
                builder,
                "setSingleChoiceItems",
                arrayOf(Array<CharSequence>::class.java, Int::class.javaPrimitiveType!!, DialogInterface.OnClickListener::class.java),
                arrayOf(items.toTypedArray(), checkedIndex, listener),
            )
            val dialog = invoke(cls, builder, "getBottomSheetDialog", emptyArray(), emptyArray()) as? Dialog
                ?: return@runCatching false
            dialog.show()
            true
        }
        outcome.exceptionOrNull()?.let { noteCouiFailure("choice", it) }
        return outcome.getOrDefault(false)
    }

    private fun couiSlider(context: Context, layout: ViewGroup): Boolean {
        val outcome = runCatching {
            val builder = newBuilder(context) ?: return@runCatching false
            val dialog = invoke(
                builder.javaClass,
                builder,
                "getBottomSheetDialog",
                emptyArray(),
                emptyArray(),
            ) as? Dialog ?: return@runCatching false
            dialog.setContentView(layout)
            dialog.show()
            true
        }
        outcome.exceptionOrNull()?.let { noteCouiFailure("slider", it) }
        return outcome.getOrDefault(false)
    }

    /** One line naming the COUI failure; the caller then logs the framework fallback it used instead. */
    private fun noteCouiFailure(kind: String, error: Throwable) {
        log?.event(
            "melody.panel.picker.coui",
            listOf("kind" to kind, "err" to error.javaClass.simpleName, "msg" to error.message?.take(120)),
        )
    }

    private fun newBuilder(context: Context): Any? {
        val cls = Reflect.loadClass(BUILDER_CLASS, loader)
        if (cls == null) {
            log?.event("melody.panel.picker.coui", listOf("kind" to "builder", "err" to "class_missing"))
            return null
        }
        return runCatching { cls.getConstructor(Context::class.java).newInstance(context) }
            .onFailure { noteCouiFailure("builder", it) }
            .getOrNull()
    }

    private fun invoke(
        cls: Class<*>,
        target: Any,
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): Any? {
        val method = cls.getMethod(name, *paramTypes)
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    // --- shared slider view -----------------------------------------------------------------------

    /**
     * A title + `SeekBar` + value line. The seek bar is the host's own COUI seek bar when it resolves
     * (same widget the native ANC surfaces use) and a framework [SeekBar] otherwise.
     */
    private fun sliderLayout(
        context: Context,
        title: CharSequence,
        min: Double,
        max: Double,
        step: Double,
        steps: Int,
        start: Int,
        unit: String?,
        onPick: (Double) -> Unit,
    ): LinearLayout {
        val padding = (16 * context.resources.displayMetrics.density).roundToInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(
            TextView(context).apply {
                text = title
                textSize = 16f
            },
        )
        val valueLabel = TextView(context).apply {
            textSize = 14f
            text = display(min + start * step, unit)
        }
        val seekBar = newSeekBar(context) ?: SeekBar(context)
        seekBar.max = steps
        seekBar.progress = start
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = display(min + progress * step, unit)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    onPick(min + (bar?.progress ?: start) * step)
                }
            },
        )
        layout.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        layout.addView(valueLabel)
        return layout
    }

    private fun newSeekBar(context: Context): SeekBar? = runCatching {
        val cls = Reflect.loadClass(SEEK_BAR_CLASS, loader) ?: return null
        cls.getConstructor(Context::class.java).newInstance(context) as? SeekBar
    }.getOrNull()

    /** `12` instead of `12.0` for integer-shaped values; keeps a real fraction otherwise. */
    private fun display(value: Double, unit: String?): String {
        val text = if (value.isFinite() && value % 1.0 == 0.0) value.roundToInt().toString() else value.toString()
        return unit?.takeIf { it.isNotBlank() }?.let { text + it } ?: text
    }

    private fun uiContext(context: Context): Context {
        var current: Context? = context
        var guard = 0
        while (current is ContextWrapper && guard++ < MAX_WRAPPER_DEPTH) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return context
    }

    private companion object {
        const val BUILDER_CLASS = "com.coui.appcompat.panel.COUIListBottomSheetDialog\$Builder"
        const val SEEK_BAR_CLASS = "com.coui.appcompat.seekbar.COUISeekBar"
        const val MAX_STEPS = 1000
        const val MAX_WRAPPER_DEPTH = 8
    }
}
