package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.definition.json.JsonParser
import com.fusion.melodyLinkNeo.definition.json.JsonValue
import com.fusion.melodyLinkNeo.definition.json.JsonWriter

/**
 * Host anchor resolution report (M6: "Melody updates every release, so relocate with DexKit and tell
 * the user").
 *
 * The injected `com.oplus.melody` process resolves every host anchor at `onPackageLoaded` time (baseline
 * name first, then signature, then a DexKit scan) and reports the outcome to BtRemix over an explicit
 * broadcast ([MelodyAnchorBroadcast]). BtRemix persists the merged report in its own preferences, where
 * the *next* host process reads it back as a fast path (so a relocated name is not re-scanned by DexKit
 * on every start), and renders it on the "Melody" page.
 *
 * Everything here is plain Kotlin on top of the dependency-free JSON tree, so the codec is covered by
 * ordinary JVM tests - no Android, no DexKit.
 */

/** How one anchor was resolved. The wire names are stable and appear in the broadcast + log lines. */
enum class MelodyAnchorLevel(val wire: String) {
    /** Recorded in the 17.6.3 baseline and still present. */
    Baseline("baseline"),

    /** The class kept its baseline name but the method was renamed. */
    Rename("rename"),

    /** Located by a DexKit scan of the recorded packages. */
    Dexkit("dexkit"),

    /** Taken from the persisted report written for the same install fingerprint. */
    Cache("cache"),

    /** Neither the baseline nor a DexKit scan found it; the owning feature degrades (fail-open). */
    Missing("missing");

    companion object {
        fun fromWire(value: String?): MelodyAnchorLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** One resolved host anchor. [className]/[methodName] are `null` for a miss. */
data class MelodyAnchorEntry(
    val id: String,
    val className: String?,
    val methodName: String?,
    val level: MelodyAnchorLevel,
) {
    val missing: Boolean get() = level == MelodyAnchorLevel.Missing
}

/** The anchors resolved by one host process (`com.oplus.melody`, its `:fg`, or wirelesssettings). */
data class MelodyAnchorProcessReport(
    val processName: String,
    val anchors: List<MelodyAnchorEntry>,
) {
    val hits: Int get() = anchors.count { !it.missing }
    val total: Int get() = anchors.size
    val misses: List<MelodyAnchorEntry> get() = anchors.filter { it.missing }
}

/**
 * The merged report persisted by BtRemix and read back by the host.
 *
 * [installId] is the host install fingerprint (see `MelodyInstallId`): the report is only reused when it
 * matches the currently installed APK, which is what turns "Melody was updated" into "rescan".
 */
data class MelodyAnchorReport(
    val installId: String,
    val hostPackage: String,
    val version: String?,
    val processes: List<MelodyAnchorProcessReport>,
) {
    fun process(processName: String): MelodyAnchorProcessReport? =
        processes.firstOrNull { it.processName == processName }

    /** Anchors resolved for [processName], or `null` when there is no report for it yet. */
    fun anchorsFor(processName: String): List<MelodyAnchorEntry>? = process(processName)?.anchors

    companion object {

        /**
         * Report schema. Bumped to 2 when the resolver switched from a package-scoped allow-list to a
         * global shape match: the v1 reports written by the first deployment contain `missing` rows for
         * anchors that were never actually gone, and they must not be reused as a fast path.
         */
        const val SCHEMA: Int = 2

        /**
         * Merges one process report into [existing], replacing that process' entry. A changed
         * [installId] or [hostPackage] starts a fresh report (the old fingerprint is meaningless).
         */
        fun merge(
            existing: MelodyAnchorReport?,
            installId: String,
            hostPackage: String,
            version: String?,
            processReport: MelodyAnchorProcessReport,
        ): MelodyAnchorReport {
            val base = existing?.takeIf { it.installId == installId && it.hostPackage == hostPackage }
            val processes = (base?.processes ?: emptyList())
                .filterNot { it.processName == processReport.processName } + processReport
            return MelodyAnchorReport(
                installId = installId,
                hostPackage = hostPackage,
                version = version ?: base?.version,
                processes = processes,
            )
        }

        fun encode(report: MelodyAnchorReport): String = JsonWriter.write(
            obj(
                "schema" to JsonValue.NumberValue(SCHEMA.toString()),
                "installId" to JsonValue.StringValue(report.installId),
                "hostPackage" to JsonValue.StringValue(report.hostPackage),
                "version" to (report.version?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                "processes" to JsonValue.Array(
                    report.processes.map { process ->
                        obj(
                            "process" to JsonValue.StringValue(process.processName),
                            "anchors" to JsonValue.Array(
                                process.anchors.map { entry ->
                                    obj(
                                        "id" to JsonValue.StringValue(entry.id),
                                        "class" to (entry.className?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                                        "method" to (entry.methodName?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                                        "level" to JsonValue.StringValue(entry.level.wire),
                                    )
                                },
                            ),
                        )
                    },
                ),
            ),
        )

        /** Decodes [text]; `null` when it is blank, malformed, or missing a required field. */
        fun decode(text: String?): MelodyAnchorReport? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val root = runCatching { JsonParser.parse(trimmed) as? JsonValue.Object }.getOrNull() ?: return null
            val schema = (root.values["schema"] as? JsonValue.NumberValue)?.raw?.toIntOrNull()
            if (schema != SCHEMA) return null
            val installId = root.string("installId") ?: return null
            val hostPackage = root.string("hostPackage") ?: return null
            val version = root.string("version")
            val processes = (root.values["processes"] as? JsonValue.Array)?.values.orEmpty().mapNotNull { element ->
                val process = element as? JsonValue.Object ?: return@mapNotNull null
                val processName = process.string("process") ?: return@mapNotNull null
                val anchors = (process.values["anchors"] as? JsonValue.Array)?.values.orEmpty().mapNotNull { anchor ->
                    val entry = anchor as? JsonValue.Object ?: return@mapNotNull null
                    val id = entry.string("id") ?: return@mapNotNull null
                    val level = MelodyAnchorLevel.fromWire(entry.string("level")) ?: return@mapNotNull null
                    MelodyAnchorEntry(
                        id = id,
                        className = entry.string("class"),
                        methodName = entry.string("method"),
                        level = level,
                    )
                }
                if (anchors.isEmpty()) return@mapNotNull null
                MelodyAnchorProcessReport(processName = processName, anchors = anchors)
            }
            if (processes.isEmpty()) return null
            return MelodyAnchorReport(
                installId = installId,
                hostPackage = hostPackage,
                version = version,
                processes = processes,
            )
        }

        private fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Object =
            JsonValue.Object(linkedMapOf(*pairs))

        private fun JsonValue.Object.string(key: String): String? =
            ((values[key] as? JsonValue.StringValue)?.value)?.trim()?.ifEmpty { null }
    }
}

/**
 * The explicit host -> BtRemix broadcast that carries one process' anchor report
 * (`HANDOFF_MELODY_M6_PLAN.md`). It is deliberately independent of the bridge/`IMelodyBridge`: the host
 * has no session at `onPackageLoaded` time, and the report must arrive even when BtRemix is not running.
 */
object MelodyAnchorBroadcast {

    const val ACTION: String = "com.fusion.melodyLinkNeo.melody.HOST_ANCHORS"
    const val EXTRA_PROTOCOL: String = "protocol"
    const val EXTRA_INSTALL_ID: String = "install_id"
    const val EXTRA_HOST_PACKAGE: String = "host_package"
    const val EXTRA_PROCESS: String = "process_name"
    const val EXTRA_VERSION: String = "version"

    /** JSON of a single [MelodyAnchorProcessReport] (see [encodeProcess]/[decodeProcess]). */
    const val EXTRA_ANCHORS: String = "anchors"

    /** Bumped whenever the payload shape changes; the receiver ignores unknown versions. */
    const val PROTOCOL: Int = 1

    /** Host packages allowed to send [ACTION]; anything else is ignored. */
    val TRUSTED_HOSTS: Set<String> = setOf("com.oplus.melody", "com.oplus.wirelesssettings")

    fun isCompatible(protocol: Int): Boolean = protocol == PROTOCOL

    /**
     * A resolved anchor class has to be a *relocatable host* class.
     *
     * The first M6 cut used a `com.oplus.`/`com.coui.` allow list - which was wrong: the module's own
     * baseline anchors live in R8's short packages (`c7.b`, `d7.a`, `D0.d`, `A9.f`, `i9.c`, `Ba.z`,
     * `n7.e$a`), so the guard rejected the very classes it was meant to protect. It is a denylist of
     * platform/bundled-library packages instead: those must never be hooked, while host classes (however
     * R8 names them) are fine. Shape verification and the ambiguity rule cover "wrong host class".
     */
    fun isRelocatableHostClass(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith(".") &&
            PLATFORM_PREFIXES.none { prefix -> name.startsWith(prefix) }

    /** Encodes one process report in the same shape [MelodyAnchorReport.decode] understands. */
    fun encodeProcess(report: MelodyAnchorProcessReport): String =
        MelodyAnchorReport.encode(
            MelodyAnchorReport(
                installId = "-",
                hostPackage = "-",
                version = null,
                processes = listOf(report),
            ),
        )

    /** Decodes the `anchors` extra; `null` when the payload is missing or malformed. */
    fun decodeProcess(text: String?): MelodyAnchorProcessReport? =
        MelodyAnchorReport.decode(text)?.processes?.firstOrNull()

    /** Packages that are never a Melody anchor: the platform, the stdlib and bundled third-party libs. */
    private val PLATFORM_PREFIXES: List<String> = listOf(
        "android.",
        "androidx.",
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "dalvik.",
        "org.",
        "com.google.",
        "com.squareup.",
        "okhttp3.",
        "okio.",
        "com.bumptech.",
        "com.facebook.",
        "com.airbnb.",
        "com.jakewharton.",
    )
}
