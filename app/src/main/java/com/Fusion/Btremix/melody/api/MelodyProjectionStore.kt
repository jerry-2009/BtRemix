package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.json.JsonWriter

/**
 * Cold-start cache for the provider injection (MELODY_BRIDGE_SPEC §5.5, M3.3 plan "冷启动缓存").
 *
 * `MelodyAliveProvider` can be asked about supported devices before BtRemix is running, and the panel
 * can be opened while the bridge is momentarily disconnected. The host process therefore persists the
 * last known managed set plus the full projection envelope of every managed MAC, so the injection can
 * still answer with the values BtRemix last published.
 *
 * The bookkeeping (version gating, round trip, staleness, last-write-wins) is pure Kotlin with an
 * injected clock; the Android half (`melody/hook/bridge/MelodyProjectionPreferences`) only moves the
 * serialised document in and out of `SharedPreferences`.
 */
class MelodyProjectionStore(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private var version: Int = 0
    private var updatedAtMs: Long = 0L
    private val macs = LinkedHashSet<String>()
    private val envelopes = LinkedHashMap<String, String>()


    /** Wire version of the cached envelopes; a mismatch means "ignore the cache" (M3 plan §2.2). */
    @Synchronized
    fun version(): Int = version

    @Synchronized
    fun updatedAtMs(): Long = updatedAtMs

    @Synchronized
    fun managedMacs(): List<String> = macs.toList()

    @Synchronized
    fun envelope(mac: String): String? = envelopes[MelodyMac.normalize(mac)]

    @Synchronized
    fun contains(mac: String): Boolean = envelopes.containsKey(MelodyMac.normalize(mac))

    @Synchronized
    fun isEmpty(): Boolean = macs.isEmpty() && envelopes.isEmpty()

    /** `true` when the cached document is older than [maxAgeMs]; an empty cache is always stale. */
    @Synchronized
    fun isStale(maxAgeMs: Long): Boolean {
        if (isEmpty()) return true
        return clock() - updatedAtMs > maxAgeMs
    }

    @Synchronized
    fun ageMs(): Long = if (isEmpty()) -1L else (clock() - updatedAtMs).coerceAtLeast(0L)

    /**
     * Parses a previously persisted document. Returns `false` (and leaves the store empty) when the
     * text is absent, corrupt or written by a different envelope [expectedVersion].
     */
    @Synchronized
    fun load(json: String?, expectedVersion: Int): Boolean {
        clear()
        if (json.isNullOrBlank()) return false
        val root = runCatching { JsonParser.parse(json) as? JsonValue.Object }.getOrNull() ?: return false
        val storedVersion = (root.values["version"] as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: return false
        if (storedVersion != expectedVersion) return false
        version = storedVersion
        updatedAtMs = (root.values["updatedAt"] as? JsonValue.NumberValue)?.raw?.toLongOrNull() ?: 0L
        (root.values["macs"] as? JsonValue.Array)?.values?.forEach { value ->
            (value as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank)
                ?.let { macs += MelodyMac.normalize(it) }
        }
        (root.values["envelopes"] as? JsonValue.Object)?.values?.forEach { (mac, value) ->
            if (value is JsonValue.Object) envelopes[MelodyMac.normalize(mac)] = JsonWriter.write(value)
        }
        return true
    }

    /** Serialises the cache; the envelope JSON is embedded as a nested object so the document stays valid. */
    @Synchronized
    fun toJson(): String {
        val envelopeNodes = LinkedHashMap<String, JsonValue>()
        envelopes.forEach { (mac, json) ->
            runCatching { JsonParser.parse(json) as? JsonValue.Object }.getOrNull()
                ?.let { envelopeNodes[mac] = it }
        }
        val document = JsonValue.Object(
            linkedMapOf(
                "version" to JsonValue.NumberValue(version.toString()),
                "updatedAt" to JsonValue.NumberValue(updatedAtMs.toString()),
                "macs" to JsonValue.Array(macs.map { JsonValue.StringValue(it) }),
                "envelopes" to JsonValue.Object(envelopeNodes),
            ),
        )
        return JsonWriter.write(document)
    }

    /** Replaces the managed list; keeps the envelopes of MACs that are still managed. */
    @Synchronized
    fun noteManagedMacs(macs: Collection<String>, version: Int) {
        val next = macs.map { MelodyMac.normalize(it) }.filter(String::isNotEmpty).distinct()
        this.macs.clear()
        this.macs += next
        envelopes.keys.retainAll(next.toSet())
        touch(version)
    }

    /** Stores one projection envelope; also considers [mac] managed. */
    @Synchronized
    fun put(mac: String, envelopeJson: String, version: Int) {
        val key = MelodyMac.normalize(mac)
        if (key.isEmpty() || envelopeJson.isBlank()) return
        envelopes[key] = envelopeJson
        macs += key
        touch(version)
    }

    @Synchronized
    fun clear() {
        version = 0
        updatedAtMs = 0L
        macs.clear()
        envelopes.clear()
    }

    private fun touch(nextVersion: Int) {
        version = nextVersion
        updatedAtMs = clock()
    }
}
