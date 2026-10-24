package com.Fusion.Btremix

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Ignore
import org.junit.Test

/** Manual hardware probe for the Xiaomi TargetInfo query used by Redmi Buds 6. */
@Ignore("Manual hardware probe; remove this annotation to run it.")
class XiaomiSppProbeTest {
    @Test
    fun probeTargetInfoRequests() {
        val adapter = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.bondedDevices.firstOrNull { it.name == "Redmi Buds 6" } ?: return
        val socket = open(device) ?: return
        val received = ByteArrayOutputStream()
        val reader = Thread {
            val buffer = ByteArray(1024)
            while (true) {
                val count = runCatching { socket.inputStream.read(buffer) }.getOrElse { -1 }
                if (count <= 0) break
                synchronized(received) { received.write(buffer, 0, count) }
                Log.i(TAG, "<- ${hex(buffer.copyOf(count))}")
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            probe(socket, "S0-request-mask", "FE DC BA C4 02 00 05 00 FF FF FF FF EF")
            probe(socket, "S0-battery-mask", "FE DC BA C4 02 00 05 00 00 00 00 80 EF")
            probe(socket, "S0B-mibuds-vector", "FE DC BA C4 02 00 05 0B FF FF FF FF EF 4F")
            probe(socket, "S0-target7-all", "FE DC BA C7 02 00 05 00 FF FF FF FF EF")
            probe(socket, "S0-target7-battery", "FE DC BA C7 02 00 05 00 00 00 00 80 EF")
            Thread.sleep(1_000)
        } finally {
            runCatching { socket.close() }
            reader.join(500)
        }
        Log.i(TAG, "totalRX=${synchronized(received) { received.size() }}")
    }

    private fun probe(socket: BluetoothSocket, label: String, frameHex: String) {
        val frame = frameHex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Log.i(TAG, "-> $label ${hex(frame)}")
        runCatching {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }.onFailure { Log.w(TAG, "write failed", it) }
        Thread.sleep(2_000)
    }

    private fun open(device: BluetoothDevice): BluetoothSocket? {
        CHANNELS.forEach { uuid ->
            listOf(false, true).forEach { insecure ->
                val socket = runCatching {
                    if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
                    else device.createRfcommSocketToServiceRecord(uuid)
                }.getOrNull() ?: return@forEach
                runCatching {
                    socket.connect()
                    Log.i(TAG, "connected ${uuid}${if (insecure) " insecure" else ""}")
                    return socket
                }.onFailure {
                    Log.i(TAG, "connect failed: ${it.message}")
                    runCatching { socket.close() }
                }
            }
        }
        return null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private companion object {
        const val TAG = "XiaomiSppProbe"
        val CHANNELS = listOf(
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("00001101-0000-1000-8000-008584D01810"),
        )
    }
}
