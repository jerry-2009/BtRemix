package com.Fusion.Btremix.definition.packages

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Builds a valid Definition JSON document accepted by the existing definition validator. */
internal fun definitionJson(
    id: String = "vendor.device",
    version: String = "1.0.0",
    displayName: String = "Vendor Device",
): String = """
    {
      "manifest": {
        "id": "$id",
        "displayName": "$displayName",
        "version": "$version",
        "capabilities": ["battery"],
        "matchers": [{ "type": "namePrefix", "value": "Vendor" }]
      },
      "states": { "battery": { "type": "integer", "default": 50 } },
      "actions": { "battery.refresh": { "displayName": "Refresh" } },
      "ui": { "children": [{ "type": "value", "state": "battery" }] }
    }
""".trimIndent()

internal fun packageJson(
    id: String = "vendor.device",
    version: String = "1.0.0",
    entry: String = "definition.json",
    packageFormat: Int = DevicePackageFormat.CURRENT,
): String = """
    { "packageFormat": $packageFormat, "id": "$id", "version": "$version", "entry": "$entry" }
""".trimIndent()

/**
 * Builds a stored (uncompressed) ZIP with full control over entry names, so tests can create
 * archives that [java.util.zip.ZipOutputStream] would refuse, such as duplicate entries.
 */
internal fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    val crc = CRC32()
    var offset = 0

    entries.forEach { (name, data) ->
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        crc.reset()
        crc.update(data)
        val crcValue = crc.value

        val local = ByteArrayOutputStream()
        local.writeLe32(0x04034b50)
        local.writeLe16(20)
        local.writeLe16(0)
        local.writeLe16(0) // stored
        local.writeLe16(0)
        local.writeLe16(0)
        local.writeLe32(crcValue)
        local.writeLe32(data.size.toLong())
        local.writeLe32(data.size.toLong())
        local.writeLe16(nameBytes.size)
        local.writeLe16(0)
        local.write(nameBytes)
        local.write(data)
        val localBytes = local.toByteArray()
        out.write(localBytes)

        central.writeLe32(0x02014b50)
        central.writeLe16(20)
        central.writeLe16(20)
        central.writeLe16(0)
        central.writeLe16(0) // stored
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe32(crcValue)
        central.writeLe32(data.size.toLong())
        central.writeLe32(data.size.toLong())
        central.writeLe16(nameBytes.size)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe32(0)
        central.writeLe32(offset.toLong())
        central.write(nameBytes)

        offset += localBytes.size
    }

    val centralBytes = central.toByteArray()
    val end = ByteArrayOutputStream()
    end.writeLe32(0x06054b50)
    end.writeLe16(0)
    end.writeLe16(0)
    end.writeLe16(entries.size)
    end.writeLe16(entries.size)
    end.writeLe32(centralBytes.size.toLong())
    end.writeLe32(offset.toLong())
    end.writeLe16(0)

    out.write(centralBytes)
    out.write(end.toByteArray())
    return out.toByteArray()
}

internal fun validPackageBytes(
    id: String = "vendor.device",
    version: String = "1.0.0",
    entry: String = "definition.json",
    extra: List<Pair<String, ByteArray>> = emptyList(),
): ByteArray {
    val entries = buildList {
        add("package.json" to packageJson(id = id, version = version, entry = entry).toByteArray())
        add(entry to definitionJson(id = id, version = version).toByteArray())
        addAll(extra)
    }
    return storedZip(*entries.toTypedArray())
}

private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun ByteArrayOutputStream.writeLe32(value: Long) {
    write((value and 0xff).toInt())
    write(((value ushr 8) and 0xff).toInt())
    write(((value ushr 16) and 0xff).toInt())
    write(((value ushr 24) and 0xff).toInt())
}
