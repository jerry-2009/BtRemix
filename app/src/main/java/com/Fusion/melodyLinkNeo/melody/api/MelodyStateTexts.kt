package com.fusion.melodyLinkNeo.melody.api

/**
 * The display text of every scalar state in a session snapshot (M4.3c).
 *
 * The「高级功能」rows read their live value from the same snapshot the rest of the panel uses, but the
 * panel hook only has the envelope (no Definition, no `enumValues`). Decoding the snapshot to plain
 * text at record time keeps the row backfill a pure cache read and keeps the value formatting in one
 * JVM-tested place. Structured values (`Items` / `Fields` / `Bytes`) are skipped: a self-built row
 * only shows scalars, and a missing entry just leaves the row's summary alone (fail-open).
 */
object MelodyStateTexts {

    fun ofSnapshot(snapshot: MelodySnapshot): Map<String, String> {
        if (snapshot.state.isEmpty) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in snapshot.state.keySet()) {
            val entry = snapshot.state.getBundle(key) ?: continue
            textOf(MelodyBundleCodec.decodeEntry(entry).value)?.let { out[key] = it }
        }
        return out
    }

    /** The display form of one wire value, or `null` when it is not a scalar. */
    fun textOf(value: WireValue): String? = when (value) {
        is WireValue.Bool -> value.value.toString()
        is WireValue.Int32 -> value.value.toString()
        is WireValue.Int64 -> value.value.toString()
        is WireValue.Float32 -> formatDouble(value.value.toDouble())
        is WireValue.Float64 -> formatDouble(value.value)
        is WireValue.Text -> value.value
        is WireValue.Bytes, is WireValue.Items, is WireValue.Fields -> null
    }

    /**
     * `17.0` reads as `17`, which is what a state declared as an integer-like value should show; any
     * real fraction keeps its decimals.
     */
    private fun formatDouble(value: Double): String =
        if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
