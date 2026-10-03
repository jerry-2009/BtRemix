package com.Fusion.Btremix.core.classic.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.core.classic.api.RfcommConnection
import com.Fusion.Btremix.core.classic.api.RfcommException
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.core.transport.api.TransportState
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android RFCOMM/SPP backend.
 *
 * Only bonded devices are used: `createRfcommSocketToServiceRecord` succeeds only for a paired peer,
 * and classic discovery is deliberately avoided. Failures surface as [RfcommException].
 */
class AndroidRfcommManager(context: Context) : RfcommManager {
    private val appContext: Context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override suspend fun bondedDevices(): List<ClassicDevice> = runCatching {
        val adapter = adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        adapter.bondedDevices.orEmpty().map { device ->
            ClassicDevice(
                address = device.address,
                name = safeName(device),
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun connect(address: String, serviceUuid: UUID): RfcommConnection = withContext(Dispatchers.IO) {
        val adapter = adapter ?: throw RfcommException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw RfcommException("Bluetooth is turned off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            throw RfcommException("'$address' is not a valid Bluetooth address", error)
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw RfcommException("Pair ${safeName(device) ?: address} in system settings before connecting over SPP")
        }
        val label = safeName(device) ?: address

        // A missing SDP record makes the socket open fail with an opaque "socket failure occurred".
        // Refresh the service list first so an unsupported UUID is reported with the device's real
        // services instead of a raw stack error.
        val advertised = refreshServiceUuids(device)
        if (advertised.isNotEmpty() && serviceUuid !in advertised) {
            throw RfcommException(
                "$label does not publish SPP service $serviceUuid. " +
                    "It advertises: ${advertised.joinToString { it.toString() }}",
            )
        }

        // SPP channel setup is timing sensitive (the peer may still be releasing a previous channel),
        // so try a few times — secure first, then the insecure channel as a fallback.
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(CONNECT_RETRY_DELAY_MS)
            runCatching { adapter.cancelDiscovery() }
            listOf(false, true).forEach { insecure ->
                val socket = try {
                    if (insecure) device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                    else device.createRfcommSocketToServiceRecord(serviceUuid)
                } catch (error: SecurityException) {
                    throw RfcommException("Bluetooth permission is required to open an SPP connection", error)
                } catch (error: Exception) {
                    lastError = error
                    return@forEach
                }
                try {
                    socket.connect()
                    return@withContext AndroidRfcommConnection(device, socket)
                } catch (error: Exception) {
                    lastError = error
                    runCatching { socket.close() }
                }
            }
        }
        throw RfcommException(
            "SPP connection to $label failed after $CONNECT_ATTEMPTS attempts: " +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            lastError,
        )
    }

    /**
     * Asks the stack to re-run service discovery and returns the device's service list.
     *
     * `BluetoothDevice.uuids` is a cache that can be stale or empty, which is why a wrong UUID in a
     * device package is otherwise indistinguishable from a peer that is switched off.
     */
    private fun refreshServiceUuids(device: BluetoothDevice): List<UUID> {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val announced = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (announced?.address == device.address) latch.countDown()
            }
        }
        return runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_UUID),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                @Suppress("MissingPermission")
                device.fetchUuidsWithSdp()
                latch.await(SDP_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } finally {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            @Suppress("MissingPermission")
            device.uuids?.map { it.uuid } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    override suspend fun close() = Unit

    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }.getOrNull()

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 400L
        const val SDP_REFRESH_TIMEOUT_MS = 3_000L
    }
}

private class AndroidRfcommConnection(
    private val device: BluetoothDevice,
    private val socket: BluetoothSocket,
) : RfcommConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<TransportState>(TransportState.Connecting)
    private val chunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val writeMutex = Mutex()
    private val output: OutputStream = socket.outputStream
    private val input: InputStream = socket.inputStream

    override val address: String = device.address
    override val name: String? = runCatching { device.name }.getOrNull()
    override val state: StateFlow<TransportState> = mutableState.asStateFlow()

    init {
        mutableState.value = TransportState.Connected
        scope.launch {
            // Do not drain the socket before the framing layer subscribes: bytes read with no
            // subscriber would be dropped by the shared flow.
            chunks.subscriptionCount.first { it > 0 }
            readLoop()
        }
    }

    override fun incoming(): Flow<ByteArray> = chunks.asSharedFlow()

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                output.write(bytes)
                output.flush()
            } catch (error: Exception) {
                mutableState.value = TransportState.Error(error.message ?: "SPP write failed", error)
                throw RfcommException("SPP write failed: ${error.message}", error)
            }
        }
    }

    override suspend fun close() {
        scope.cancel()
        runCatching { socket.close() }
        mutableState.value = TransportState.Disconnected
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(1024)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (error: Exception) {
                mutableState.value = TransportState.Disconnected
                return
            }
            if (read < 0) {
                mutableState.value = TransportState.Disconnected
                return
            }
            if (read > 0) chunks.emit(buffer.copyOf(read))
        }
    }
}
