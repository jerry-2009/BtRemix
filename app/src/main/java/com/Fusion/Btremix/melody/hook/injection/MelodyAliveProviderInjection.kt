package com.Fusion.Btremix.melody.hook.injection

import android.content.ContentProvider
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import com.Fusion.Btremix.melody.api.MelodyInjectionTable
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyProviderMerge
import com.Fusion.Btremix.melody.api.MelodyQueryPath
import com.Fusion.Btremix.melody.api.MelodyQueryTarget
import com.Fusion.Btremix.melody.api.MelodyWhitelistIdentity
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface

/**
 * M3.3 provider injection (`MELODY_BRIDGE_SPEC` §5.1, `HANDOFF_MELODY_M3_PLAN.md` §4 M3.3).
 *
 * `MelodyAliveProvider.query` is how SystemUI / wireless settings / the device centre ask "is this a
 * supported headset". The observation hook (M1/M3.-1) reads the same anchor without changing it; this
 * hook, behind its own `melody_bridge.injection_enabled` switch, *appends* our managed devices to the
 * host's answer.
 *
 * Only the cursor is synthesised here: the host's in-memory whitelist and `DeviceInfoManager` are left
 * untouched (that is M3.4). The first real-device run is therefore also the probe that decides whether
 * this layer is enough to get the device into the list and its detail page.
 *
 * The interceptor is exception-guarded end to end: a failure inside the injection returns the official
 * cursor unchanged and only leaves a `melody.inject.failed` line behind.
 */
internal class MelodyAliveProviderInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /** Decrypting/recompressing the 475 KB `whitelist_content` DO is memoized per official blob. */
    private val contentMemo = LinkedHashMap<Long, ByteArray>()

    fun install() {
        val providerClass = Reflect.loadClass(PROVIDER_CLASS, loader)
        if (providerClass == null) {
            log.event("melody.anchor.missing", "hook" to "inject", "class" to PROVIDER_CLASS)
            return
        }
        hookQuery(providerClass)
    }

    private fun hookQuery(providerClass: Class<*>) {
        val stringArray = emptyArray<String>().javaClass
        val candidates = listOf(
            arrayOf(Uri::class.java, stringArray, String::class.java, stringArray, String::class.java),
            arrayOf(Uri::class.java, stringArray, Bundle::class.java, CancellationSignal::class.java),
        )
        val method = candidates.firstNotNullOfOrNull { Reflect.findMethod(providerClass, "query", it) }
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "inject.query", "class" to PROVIDER_CLASS)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val official = chain.proceed()
            val replaced = runCatching {
                inject(
                    provider = chain.thisObject as? ContentProvider,
                    uri = chain.args.getOrNull(0) as? Uri,
                    selection = chain.args.getOrNull(2) as? String,
                    selectionArgs = (chain.args.getOrNull(3) as? Array<*>)
                        ?.mapNotNull { it as? String }
                        ?.toTypedArray(),
                    official = official as? Cursor,
                )
            }
                .onFailure { log.warn("melody.inject.failed", it) }
                .getOrNull()
            replaced ?: official
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to "inject.query",
            "class" to providerClass.name,
            "method" to method.name,
        )
    }

    /** Returns the cursor to hand back, or `null` to leave the official answer untouched. */
    private fun inject(
        provider: ContentProvider?,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?,
        official: Cursor?,
    ): Cursor? {
        val target = MelodyQueryTarget.parse(
            path = uri?.path ?: uri?.lastPathSegment,
            queryParameters = queryParameters(uri),
            selection = selection,
            selectionArgs = selectionArgs,
        ) ?: return null
        val context = provider?.context ?: return null
        val client = MelodyBridgeClients.getOrCreate(context, log)

        if (target.path.isWear) return injectWear(client, target, official)

        val identities = resolveIdentities(client, target)
        if (identities.isEmpty()) {
            val mac = target.mac
            if (mac != null && client.managedMacsFast().any { it == mac }) {
                log.event("melody.inject.target_miss", "path" to target.path.path, "mac" to mac)
            }
            return null
        }
        val officialTable = MelodyProviderCursor.materialize(official)
        val table = if (target.path == MelodyQueryPath.WHITELIST_CONTENT) {
            mergeWhitelistContent(officialTable, identities)
        } else {
            MelodyProviderMerge.mergeAll(target.path, officialTable, identities)
        } ?: return null
        log.event(
            "melody.inject." + target.path.path,
            "mac" to identities.joinToString(",") { it.mac },
            "path" to target.path.path,
            "official" to (officialTable?.rows?.size ?: 0),
            "rows" to table.rows.size,
        )
        return MelodyProviderCursor.toCursor(table, MelodyProviderCursor.extras(official))
    }

    private fun injectWear(client: MelodyBridgeClient, target: MelodyQueryTarget, official: Cursor?): Cursor? {
        val mac = target.mac ?: return null
        // No live wear state means "not our device / nothing to say", so the official answer stands.
        val bothInEar = client.wearState(mac) ?: return null
        val officialTable = MelodyProviderCursor.materialize(official)
        val table = MelodyProviderMerge.mergeWear(officialTable, mac, bothInEar)
        log.event(
            "melody.inject.wear",
            "mac" to MelodyMac.normalize(mac),
            "path" to target.path.path,
            "both_in_ear" to bothInEar,
            "official" to (officialTable?.rows?.size ?: 0),
            "rows" to table.rows.size,
        )
        return MelodyProviderCursor.toCursor(table, MelodyProviderCursor.extras(official))
    }

    /**
     * Which managed devices this query is about.
     *
     * `find_whitelist` asks about *one* device (MAC first, `productId` + `deviceName` second, M3 plan §4
     * M3.0 "两种编码"); `ears_whitelist` / `all_whitelist` / `whitelist_content` are whole-list
     * snapshots with no device parameter, so every managed device belongs in the answer - that is what
     * makes the headset show up in the host's device list at all.
     */
    private fun resolveIdentities(
        client: MelodyBridgeClient,
        target: MelodyQueryTarget,
    ): List<MelodyWhitelistIdentity> {
        val managed = client.managedMacsFast()
        return when {
            target.path.isWholeList -> managed.mapNotNull { identityOf(client, it) }
            target.mac != null -> listOfNotNull(identityOf(client, target.mac))
            target.productId != null -> managed
                .mapNotNull { identityOf(client, it) }
                .filter { identity ->
                    target.matches(identity.mac, identity.name, identity.decimalId, identity.hexId)
                }
                .take(1)
            else -> emptyList()
        }
    }

    private fun identityOf(client: MelodyBridgeClient, mac: String): MelodyWhitelistIdentity? =
        client.projectionFast(mac)?.let(MelodyProviderMerge::identityOf)

    /** `whitelist_content` DO merge for every managed device, memoized by the official blob. */
    private fun mergeWhitelistContent(
        official: MelodyInjectionTable?,
        identities: List<MelodyWhitelistIdentity>,
    ): MelodyInjectionTable? {
        var table = official ?: return null
        val index = table.columns.indexOf("content")
        if (index < 0) return null
        var changed = false
        for (identity in identities) {
            val blob = table.rows.firstOrNull()?.getOrNull(index) as? ByteArray ?: return null
            if (blob.size > MelodyProviderMerge.MAX_MERGE_BYTES) return null
            val key = blob.size.toLong() * 31L + blob.contentHashCode()
            val merged = contentMemo[key]
                ?: MelodyProviderMerge.mergeWhitelistContentBytes(blob, identity)
                    ?.takeIf { it.size <= MelodyProviderMerge.MAX_MERGE_BYTES }
                    ?.also { if (contentMemo.size < CONTENT_MEMO_MAX) contentMemo[key] = it }
                ?: return null
            if (merged.contentEquals(blob)) continue
            changed = true
            val rows = table.rows.mapIndexed { rowIndex, row ->
                if (rowIndex != 0) row else row.toMutableList().also { it[index] = merged }
            }
            table = MelodyInjectionTable(table.columns, rows)
        }
        return if (changed) table else null
    }

    private fun queryParameters(uri: Uri?): Map<String, String?> {
        if (uri == null) return emptyMap()
        return runCatching {
            uri.queryParameterNames.associateWith { name ->
                runCatching { uri.getQueryParameter(name) }.getOrNull()
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PROVIDER_CLASS = "com.oplus.melody.alive.provider.MelodyAliveProvider"
        const val CONTENT_MEMO_MAX = 4
    }
}
