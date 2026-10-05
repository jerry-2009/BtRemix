package com.fusion.melodyLinkNeo

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Ignore
import org.junit.Test

/**
 * Manual hardware probe for the WF-1000XM3 control channel (not part of the regular suite).
 *
 * It runs the initialization handshake and then the V1 *and* V2 encodings of the battery / ANC
 * queries, decoding every reply into `type/seq/payload`. Recipe and expected outcome:
 * `HANDOFF_SONY_XM3_BATTERY_ANC.md` §7.
 */
@Ignore("Manual hardware probe; remove this annotation to run it.")
class SonySppProbeTest {
    private var sequence = 0

    @Test
    fun probeFeaturePayloadTypes() {
        val adapter = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.bondedDevices.firstOrNull { it.name?.contains("WF-1000XM3") == true } ?: return

        val socket = open(device) ?: return
        val received = ByteArrayOutputStream()
        var pending = ByteArray(0)
        val reader = Thread {
            val buffer = ByteArray(256)
            while (true) {
                val read = runCatching { socket.inputStream.read(buffer) }.getOrElse { -1 }
                if (read <= 0) break
                synchronized(received) { received.write(buffer, 0, read) }
                pending += buffer.copyOf(read)
                val frames = splitFrames(pending)
                pending = frames.remainder
                frames.complete.forEach { frame ->
                    val type = frame[1].toInt() and 0xff
                    val seq = frame[2].toInt() and 0xff
                    Log.i(TAG, "  <- ${describe(frame)}")
                    if (type == 0x0C || type == 0x0E) {
                        runCatching { socket.outputStream.write(ack(seq)); socket.outputStream.flush() }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        try {
            command(socket, "GET protocol.info", 0x0C, byteArrayOf(0x00, 0x00))
            command(socket, "GET device.info(fw)", 0x0C, byteArrayOf(0x04, 0x02))
            command(socket, "GET support T1", 0x0C, byteArrayOf(0x06, 0x00))
            command(socket, "GET support T2", 0x0E, byteArrayOf(0x06, 0x00))
            command(socket, "tag end-of-get", 0x0C, byteArrayOf(0x00))
            // MelodyLink V1 command numbers: this unit advertises the V1 RFCOMM UUID (96cc203e).
            command(socket, "V1 COMMON_GET_BATTERY_LEVEL DUAL", 0x0C, byteArrayOf(0x10, 0x01))
            command(socket, "V1 COMMON_GET_BATTERY_LEVEL CASE", 0x0C, byteArrayOf(0x10, 0x02))
            command(socket, "V1 NC_ASM_GET_PARAM asm=0x02", 0x0C, byteArrayOf(0x66, 0x02))
            // V2 command numbers, for comparison.
            command(socket, "V2 POWER_GET_STATUS DUAL", 0x0C, byteArrayOf(0x22, 0x01))
            command(socket, "V2 NCASM_GET_PARAM asm=0x11", 0x0C, byteArrayOf(0x66, 0x11))
            command(socket, "V2 NCASM_GET_PARAM asm=0x17", 0x0C, byteArrayOf(0x66, 0x17))
            command(socket, "EQEBB_GET inquiry=0x01", 0x0C, byteArrayOf(0x56, 0x01))
            // 0xE6's second byte is the parameter id: 0x01 = V2 upscaling (3-byte reply),
            // 0x02 = V1 upscaling (4-byte reply). The V1 device must answer 0x02.
            command(socket, "AUDIO_GET param=0x01 (V2 selector)", 0x0C, byteArrayOf(0xE6.toByte(), 0x01))
            command(socket, "AUDIO_GET param=0x02 (V1 upscaling)", 0x0C, byteArrayOf(0xE6.toByte(), 0x02))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun command(socket: BluetoothSocket, label: String, messageType: Int, payload: ByteArray) {
        Log.i(TAG, "-> $label payload=${hex(payload)}")
        val frame = frame(messageType, nextSequence(), payload)
        runCatching { socket.outputStream.write(frame); socket.outputStream.flush() }
        // MelodyLink waits 5 s for a response payload; the peer can answer late.
        Thread.sleep(5_000)
    }

    private fun describe(frame: ByteArray): String {
        val inner = unescape(frame.copyOfRange(1, frame.size - 1))
        if (inner.size < 7) return "raw=${hex(frame)}"
        val type = inner[0].toInt() and 0xff
        val seq = inner[1].toInt() and 0xff
        val length = ((inner[2].toInt() and 0xff) shl 24) or ((inner[3].toInt() and 0xff) shl 16) or
            ((inner[4].toInt() and 0xff) shl 8) or (inner[5].toInt() and 0xff)
        val payload = if (length > 0 && inner.size >= 6 + length) inner.copyOfRange(6, 6 + length) else ByteArray(0)
        val name = when (type) {
            0x01 -> "ACK"
            0x0C -> "COMMAND_1"
            0x0E -> "COMMAND_2"
            else -> "type=0x%02X".format(type)
        }
        return "$name seq=$seq len=$length payload=${hex(payload)} raw=${hex(frame)}"
    }

    private fun unescape(bytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xff
            if (value == 0x3D) {
                val next = bytes.getOrNull(index + 1) ?: break
                out += ((next.toInt() and 0xff) or 0x10).toByte()
                index += 2
            } else {
                out += bytes[index]
                index++
            }
        }
        return out.toByteArray()
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence = 1 - sequence
        return current
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private data class Frames(val complete: List<ByteArray>, val remainder: ByteArray)

    private fun splitFrames(bytes: ByteArray): Frames {
        val complete = mutableListOf<ByteArray>()
        var start = -1
        bytes.forEachIndexed { index, raw ->
            val value = raw.toInt() and 0xff
            if (start < 0) {
                if (value == 0x3E) start = index
            } else if (value == 0x3C) {
                complete += bytes.copyOfRange(start, index + 1)
                start = -1
            }
        }
        return Frames(complete, if (start >= 0) bytes.copyOfRange(start, bytes.size) else ByteArray(0))
    }

    private fun frame(messageType: Int, sequence: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(
            messageType.toByte(), sequence.toByte(),
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        ) + payload
        val checksum = body.fold(0) { sum, byte -> (sum + (byte.toInt() and 0xff)) and 0xff }
        return byteArrayOf(0x3E) + escape(body) + escape(byteArrayOf(checksum.toByte())) + byteArrayOf(0x3C)
    }

    private fun ack(receivedSequence: Int): ByteArray = frame(0x01, (1 - receivedSequence) and 0xff, ByteArray(0))

    private fun escape(bytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(bytes.size)
        bytes.forEach { raw ->
            val value = raw.toInt() and 0xff
            if (value == 0x3E || value == 0x3C || value == 0x3D) {
                out += 0x3D.toByte()
                out += (value and 0xEF).toByte()
            } else {
                out += raw
            }
        }
        return out.toByteArray()
    }

    private fun open(device: BluetoothDevice): BluetoothSocket? {
        repeat(4) { attempt ->
            val socket = runCatching {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }.getOrNull() ?: return null
            try {
                socket.connect()
                Log.i(TAG, "connected on attempt ${attempt + 1}")
                return socket
            } catch (error: Exception) {
                Log.i(TAG, "connect attempt ${attempt + 1} failed: ${error.message}")
                runCatching { socket.close() }
                Thread.sleep(400)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "SppProbe"
        val SPP_UUID: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")
    }
}
