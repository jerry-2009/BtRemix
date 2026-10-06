package com.fusion.melodyLinkNeo.melody.hook.injection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.fusion.melodyLinkNeo.melody.api.MelodyMac
import com.fusion.melodyLinkNeo.melody.hook.MelodyAnchorSession
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.Reflect
import com.fusion.melodyLinkNeo.melody.hook.anchor.MelodyAnchorCatalog
import com.fusion.melodyLinkNeo.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * M7 header artwork: paint the device package's own `assets/icon.png` where Melody would otherwise show
 * its generic placeholder.
 *
 * The two device pages have their own placeholder branch, and both were located in `17.6.3` before this
 * hook was written:
 *
 *  - `DetailMainActivity`: `MelodyDetailModelView.e()` sets `melody_ui_detail_default_img` (or the
 *    neckband / OWS variant) on the header `ImageView` when the detail source carries no webp / picture,
 *    or when the 5 s load timer expires;
 *  - `OneSpaceDetailActivity`: `OneSpaceHeaderPreference.j(ImageView)` is the "detail image file is
 *    missing" branch; it assigns the placeholder from a `CompletableFuture`, i.e. **after** the method
 *    returns.
 *
 * The detail header also spends that whole window playing a loading animation (`MelodyDetailModelView`'s
 * `LottieAnimationView`), so the placeholder branch alone would still leave the user watching a spinner
 * for up to 5 s. The header's bind (`setViewModel`) is hooked as well: as soon as the package picture is
 * in hand - and only then - the spinner is cancelled and the picture is drawn, which is the "no spinner,
 * show it now" behaviour for a device this module owns.
 *
 * The picture comes from the bridge (`resolveIcon`), so the panel and the BtRemix Devices page show the
 * very same file (D-UI-5) and no image is embedded in the module for one specific headset. Everything is
 * fail-open: an unresolved anchor, a page whose view-model carries no MAC-shaped address, an unmanaged
 * MAC, a cold binder or undecodable bytes all leave the host's own placeholder **and its spinner** alone.
 */
internal class MelodyDetailImageInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Decoding and the one binder round trip run off the main thread: both page hooks fire while the
     * host is building the page, and `resolveIcon` can block up to its own budget on a cold link.
     */
    private val fetch: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BtRemixMelodyIcon").apply { isDaemon = true }
    }

    /** Decoded package picture per MAC; a Definition's icon only changes when it is re-installed. */
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    /** Last painted page per MAC, so a re-bound view does not repeat the diagnostic line. */
    private val painted = ConcurrentHashMap<String, String>()

    fun install() {
        installDetailBind()
        installDetailPlaceholder()
        installOneSpacePlaceholder()
    }

    // --- detail page ------------------------------------------------------------------------------

    /**
     * Early half: the header was just bound to its view-model, which is the first moment the device
     * address and the loading animation are both reachable. Paint immediately so the spinner never gets
     * a chance to run.
     */
    private fun installDetailBind() {
        val anchor = MelodyAnchorSession.anchor(MelodyAnchorCatalog.DETAIL_HEADER_BIND, loader)
        val method = anchor?.method
        if (anchor == null || method == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "target" to "detail.bind")
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { detailBound(chain.thisObject) }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "target" to "detail.bind",
            "class" to anchor.clazz.name,
            "method" to method.name,
        )
    }

    /** `MelodyDetailModelView`, freshly bound: `d` is the picture, `e` the loading animation, `g` the device. */
    private fun detailBound(header: Any?) {
        if (header == null) return
        val imageView = Reflect.readField(header, "d") as? ImageView ?: return
        val mac = MelodyHeaderArtwork.macOf(Reflect.readField(header, "g")) ?: return
        // Only the "we own this page" case may stop the host's own loading animation.
        apply(imageView, mac, WHERE_DETAIL, loading = Reflect.readField(header, "e"))
    }

    private fun installDetailPlaceholder() {
        val anchor = MelodyAnchorSession.anchor(MelodyAnchorCatalog.DETAIL_IMAGE_PLACEHOLDER, loader)
        val method = anchor?.method
        if (anchor == null || method == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "target" to "detail")
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { detailPlaceholder(chain.thisObject) }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "target" to "detail",
            "class" to anchor.clazz.name,
            "method" to method.name,
        )
    }

    /** `MelodyDetailModelView`: the header view holds both the image (`d`) and the device view-model (`g`). */
    private fun detailPlaceholder(header: Any?) {
        if (header == null) return
        val imageView = Reflect.readField(header, "d") as? ImageView ?: return
        // The view-model is the header's own field; it carries the address the page was opened for.
        val mac = MelodyHeaderArtwork.macOf(Reflect.readField(header, "g")) ?: return
        // The host has just written its placeholder here, so the cached picture replaces it in the same
        // frame; the animation is already gone on this path.
        apply(imageView, mac, WHERE_DETAIL, loading = null)
    }

    // --- OneSpace page ----------------------------------------------------------------------------

    private fun installOneSpacePlaceholder() {
        val anchor = MelodyAnchorSession.anchor(MelodyAnchorCatalog.ONESPACE_IMAGE_PLACEHOLDER, loader)
        val method = anchor?.method
        if (anchor == null || method == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "target" to "onespace")
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching {
                val imageView = chain.args.getOrNull(0) as? ImageView ?: return@runCatching
                val preference = chain.thisObject
                // `b` is the preference's `OneSpaceVM`; `f()` is the accessor the host itself uses.
                val viewModel = Reflect.readField(preference, "b") ?: Reflect.call(preference, "f")
                val mac = MelodyHeaderArtwork.macOf(viewModel) ?: return@runCatching
                apply(imageView, mac, WHERE_ONE_SPACE, loading = null, repaint = true)
            }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "target" to "onespace",
            "class" to anchor.clazz.name,
            "method" to method.name,
        )
    }

    // --- painting ---------------------------------------------------------------------------------

    /**
     * Draws the package picture over the host's picture, and (when [loading] is given) stops the loading
     * animation that would otherwise keep spinning. A picture already in the cache is applied on the
     * caller's thread - the placeholder branch is synchronous and a post would flash the host's own
     * default image for a frame.
     */
    private fun apply(
        imageView: ImageView,
        mac: String,
        where: String,
        loading: Any?,
        repaint: Boolean = false,
    ) {
        val cached = bitmaps[mac]
        if (cached != null) {
            runOnMain { runCatching { draw(imageView, mac, cached, where, loading) } }
            if (repaint) {
                handler.postDelayed({ runCatching { draw(imageView, mac, cached, where, null) } }, REPAINT_DELAY_MS)
            }
            return
        }
        fetch.execute {
            val bitmap = bitmapFor(imageView, mac) ?: return@execute
            val paint = Runnable { runCatching { draw(imageView, mac, bitmap, where, loading) } }
            handler.post(paint)
            // OneSpace assigns the placeholder from an async task, so re-assert after it has certainly
            // run. The detail page is synchronous and does not need the second pass.
            if (repaint) handler.postDelayed(paint, REPAINT_DELAY_MS)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** The package picture for [mac], or `null` when the host's placeholder must stand. */
    private fun bitmapFor(imageView: ImageView, mac: String): Bitmap? {
        bitmaps[mac]?.let { return it }
        val client = runCatching { MelodyBridgeClients.getOrCreate(imageView.context, log) }.getOrNull() ?: return null
        // Cheap guard first: an unmanaged device must not pay a binder round trip on every page open.
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList())
        if (mac !in managed) return null
        val bytes = runCatching { client.icon(mac) }.getOrNull() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        if (!MelodyHeaderArtwork.isUsableIcon(bitmap.width, bitmap.height)) return null
        bitmaps[mac] = bitmap
        return bitmap
    }

    private fun draw(imageView: ImageView, mac: String, bitmap: Bitmap, where: String, loading: Any?) {
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        val spinnerOff = MelodyHeaderArtwork.stopLoading(loading)
        if (painted.put(mac, where) != where) {
            log.event(
                "melody.panel.artwork.painted",
                "mac" to mac,
                "where" to where,
                "size" to "${bitmap.width}x${bitmap.height}",
                "spinner_off" to spinnerOff,
            )
        }
    }

    private companion object {
        const val HOOK = "inject.artwork"
        const val WHERE_DETAIL = "detail"
        const val WHERE_ONE_SPACE = "onespace"

        /** Long enough for the OneSpace `supplyAsync` + main-thread `thenAcceptAsync` placeholder to land. */
        const val REPAINT_DELAY_MS = 250L
    }
}

/**
 * Reads the device address out of a host header view-model.
 *
 * `DetailMainViewModel` (`DetailBaseViewModel`) and `OneSpaceVM` both keep the page's MAC in a plain
 * `String` field, but the field is named by R8 (`e` / `c` in `17.6.3`) and the names move with every
 * host release. Scanning the string fields for a MAC-shaped value keeps the lookup working after a
 * rename without guessing which obfuscated slot is the address.
 *
 * Pure reflection over the host object, so the contract is pinned by a JVM test with stand-ins that
 * carry the same fields.
 */
internal object MelodyHeaderArtwork {

    fun macOf(viewModel: Any?): String? {
        val raw = Reflect.readStringFieldWhere(viewModel) { MelodyMac.isMacAddress(it) } ?: return null
        return MelodyMac.normalize(raw)
    }

    /**
     * Smallest picture that may replace the host's placeholder.
     *
     * A package whose `assets/icon.png` is a 1x1 marker (or any thumbnail smaller than a header icon)
     * would otherwise paint a blank / blurred rectangle over a perfectly good placeholder. Size is the
     * only property checked here - the bytes are the package author's own file, and the authoring guide
     * asks for a real product picture.
     */
    const val MIN_ICON_PX: Int = 48

    fun isUsableIcon(width: Int, height: Int): Boolean = width >= MIN_ICON_PX && height >= MIN_ICON_PX

    /**
     * Stops the header's loading animation, returning `true` when it was actually hidden.
     *
     * `MelodyDetailModelView` starts a `LottieAnimationView` in `onFinishInflate` and keeps it running
     * until the product picture resolves - for a device whose picture this module supplies, that wait is
     * pointless. Both calls go through reflection because the module does not link Lottie.
     */
    fun stopLoading(loading: Any?): Boolean {
        if (loading == null) return false
        runCatching { Reflect.call(loading, "cancelAnimation") }
        return runCatching { Reflect.invokeSingleArg(loading, "setVisibility", GONE) }.getOrDefault(false)
    }

    /** `android.view.View.GONE`, inlined so the JVM suite needs no Android framework. */
    private const val GONE = 8
}
