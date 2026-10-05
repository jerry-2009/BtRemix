package com.fusion.melodyLinkNeo.melody.api

/**
 * The `melody_method_*` payload of `EarphoneControlProvider.call` (M5.3,
 * `HANDOFF_MELODY_M5_PLAN.md` §4 M5.3 / `docs/melody-capability-map.md` §8.6).
 *
 * The device-centre SDK drives the headset through a
 * `ContentProvider.call(String method, String arg, Bundle extras)` protocol. The only method that
 * writes the headset's ANC is [METHOD_NOISE_REDUCTION] (`0x200`), which carries its target as
 * `extras.type` - the host's **`modeType`**, exactly like the `setgate` broadcast. `name` / `address`
 * identify the device (the host checks both against its active earphone before doing anything) and
 * `switch` is the on/off value of the `melody_method_enable_*` family; neither influences the
 * noise-reduction write, so this parser reads only `address` + `type`.
 *
 * The extras are read through [Extras] instead of `android.os.Bundle`, which keeps the decode and the
 * redirect decision pinnable on the JVM (`MelodyProviderCallPolicyTest`,
 * `MelodyProviderCallRedirectInjectionTest`).
 *
 * Parsing is pure and total: a different method, a missing/blank address or a missing `type` all
 * return `null`, which the caller turns into a fail-open `chain.proceed()`.
 */
object MelodyProviderCallPolicy {

    /** Method literal for the ANC write (`CODE_METHOD_NOISE_REDUCTION` = `0x200` in 17.6.3). */
    const val METHOD_NOISE_REDUCTION: String = "melody_method_noise_reduction"

    /** The `extras` keys 17.6.3 reads in `EarphoneControlProvider.call`. */
    const val KEY_NAME: String = "name"
    const val KEY_ADDRESS: String = "address"
    const val KEY_TYPE: String = "type"
    const val KEY_SWITCH: String = "switch"

    /**
     * The `extras` reads the provider lane needs. The production implementation wraps a `Bundle`; JVM
     * tests supply a map. `null` means "the key is absent" - the host's own `Bundle.getInt` would
     * answer `0`, which resolves to no index either, so failing open here has the same visible result.
     */
    interface Extras {
        fun string(key: String): String?

        fun int(key: String): Int?
    }

    /** One decoded ANC write: the target device and the host `modeType` it asked for. */
    data class Command(val mac: String, val modeType: Int)

    /**
     * Decodes [method] + [extras] into an ANC target, or `null` when the call is not one we take over.
     * `melody_method_spatial` / `melody_method_active_device` and the `control` / `enable` families
     * all stay on the host path (M5_PLAN §4 M5.3 step 2); the read-only `melody.command.call`
     * observation line still records every method.
     */
    fun parse(method: String?, extras: Extras?): Command? {
        if (method != METHOD_NOISE_REDUCTION) return null
        val mac = extras?.string(KEY_ADDRESS)?.takeIf { it.isNotBlank() } ?: return null
        val modeType = extras.int(KEY_TYPE) ?: return null
        return Command(mac = mac, modeType = modeType)
    }
}
