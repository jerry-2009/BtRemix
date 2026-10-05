package com.fusion.melodyLinkNeo.core.bluetooth.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristicProperty
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleConnection
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDescriptor
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleError
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleException
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleManager
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleService
import com.fusion.melodyLinkNeo.core.bluetooth.api.ConnectionState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidBleManager(context: Context) : BleManager {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val activeScan = AtomicReference<ScanCallback?>(null)

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<BleScanResult> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw BleException(BleError.BluetoothUnavailable("Bluetooth LE is unavailable or disabled"))
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toBleScanResult())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toBleScanResult()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException(BleError.Gatt(errorCode, "BLE scan failed (code=$errorCode)")))
            }
        }
        if (!activeScan.compareAndSet(null, callback)) {
            throw BleException(BleError.InvalidState("A BLE scan is already active"))
        }
        try {
            scanner.startScan(callback)
        } catch (error: SecurityException) {
            activeScan.compareAndSet(callback, null)
            close(BleException(BleError.PermissionDenied(error.message ?: "Bluetooth scan permission denied")))
        } catch (error: RuntimeException) {
            activeScan.compareAndSet(callback, null)
            close(BleException(BleError.Unknown("Unable to start BLE scan", error)))
        }
        awaitClose {
            if (activeScan.compareAndSet(callback, null)) runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        val callback = activeScan.getAndSet(null) ?: return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (error: SecurityException) {
            throw BleException(BleError.PermissionDenied(error.message ?: "Bluetooth scan permission denied"))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BleDevice): BleConnection {
        val bluetoothAdapter = adapter
            ?: throw BleException(BleError.BluetoothUnavailable("Bluetooth adapter is unavailable"))
        if (!bluetoothAdapter.isEnabled) {
            throw BleException(BleError.BluetoothUnavailable("Bluetooth is disabled"))
        }
        val remoteDevice = try {
            bluetoothAdapter.getRemoteDevice(device.address)
        } catch (error: IllegalArgumentException) {
            throw BleException(BleError.DeviceUnavailable("Invalid Bluetooth address: ${device.address}"))
        } catch (error: SecurityException) {
            throw BleException(BleError.PermissionDenied(error.message ?: "Bluetooth connect permission denied"))
        }
        return withTimeout(OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val connection = AndroidBleConnection(remoteDevice)
                connection.beginConnect(continuation)
                try {
                    connection.attachGatt(
                        remoteDevice.connectGatt(appContext, false, connection, BluetoothDevice.TRANSPORT_LE),
                    )
                } catch (error: SecurityException) {
                    continuation.resumeWithException(
                        BleException(BleError.PermissionDenied(error.message ?: "Bluetooth connect permission denied")),
                    )
                } catch (error: RuntimeException) {
                    continuation.resumeWithException(BleException(BleError.Unknown("Unable to connect to device", error)))
                }
                continuation.invokeOnCancellation { connection.disconnectQuietly() }
            }
        }
    }

    private fun ScanResult.toBleScanResult(): BleScanResult {
        val record = scanRecord
        val manufacturer = buildMap {
            record?.manufacturerSpecificData?.let { sparse ->
                for (index in 0 until sparse.size()) put(sparse.keyAt(index), sparse.valueAt(index).clone())
            }
        }
        val resultDevice = device
        return BleScanResult(
            device = BleDevice(
                id = resultDevice.address,
                address = resultDevice.address,
                name = record?.deviceName ?: runCatching { resultDevice.name }.getOrNull(),
            ),
            rssi = rssi,
            manufacturerData = manufacturer,
            serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid },
        )
    }

    private companion object {
        const val OPERATION_TIMEOUT_MS = 15_000L
    }
}

private class AndroidBleConnection(private val device: BluetoothDevice) : BluetoothGattCallback(), BleConnection {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    override val state = mutableState
    private val operationMutex = Mutex()
    private val notificationEvents = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 32)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicReference<CompletableDeferred<GattResult>?>(null)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var services: Map<Pair<UUID, UUID>, BluetoothGattCharacteristic> = emptyMap()
    @Volatile private var connectContinuation: kotlinx.coroutines.CancellableContinuation<BleConnection>? = null

    fun beginConnect(continuation: kotlinx.coroutines.CancellableContinuation<BleConnection>) {
        connectContinuation = continuation
    }

    fun attachGatt(value: BluetoothGatt?) {
        if (value == null) {
            connectContinuation?.takeIf { it.isActive }?.resumeWithException(
                BleException(BleError.DeviceUnavailable("The device did not accept the connection")),
            )
        } else {
            gatt = value
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            mutableState.value = ConnectionState.Error(BleError.Gatt(status))
            connectContinuation?.takeIf { it.isActive }?.resumeWithException(BleException(BleError.Gatt(status)))
            connectContinuation = null
            gatt.close()
            this.gatt = null
            cleanupScope.coroutineContext[Job]?.cancel()
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                mutableState.value = ConnectionState.Connected
                connectContinuation?.takeIf { it.isActive }?.resume(this)
                connectContinuation = null
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                mutableState.value = ConnectionState.Disconnected
                pending.getAndSet(null)?.completeExceptionally(
                    BleException(BleError.DeviceUnavailable("The BLE connection was lost")),
                )
                gatt.close()
                this.gatt = null
                cleanupScope.coroutineContext[Job]?.cancel()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        complete(GattResult(status = status))
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        complete(GattResult(status = status, data = characteristic.value?.clone()))
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        complete(GattResult(status = status, data = value.clone()))
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        complete(GattResult(status = status))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        complete(GattResult(status = status))
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        characteristic.value?.let { notificationEvents.tryEmit(characteristic.uuid to it.clone()) }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        notificationEvents.tryEmit(characteristic.uuid to value.clone())
    }

    override suspend fun discoverServices(): List<BleService> {
        ensureConnected()
        mutableState.value = ConnectionState.DiscoveringServices
        perform("discover services") { current -> current.discoverServices() }
        services = emptyMap()
        val discovered = checkGatt()?.services.orEmpty().map { service ->
            BleService(
                uuid = service.uuid,
                isPrimary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
                characteristics = service.characteristics.map { characteristic ->
                    BleCharacteristic(
                        serviceUuid = service.uuid,
                        uuid = characteristic.uuid,
                        properties = BleCharacteristicProperty.fromAndroidProperties(characteristic.properties),
                        descriptors = characteristic.descriptors.map {
                            BleDescriptor(characteristic.uuid, it.uuid)
                        },
                    ).also { services = services + ((service.uuid to characteristic.uuid) to characteristic) }
                },
            )
        }
        mutableState.value = ConnectionState.Ready
        return discovered
    }

    override suspend fun read(characteristic: BleCharacteristic): ByteArray {
        requireProperty(characteristic, BleCharacteristicProperty.READ)
        val native = findCharacteristic(characteristic)
        return perform("read characteristic") { current -> current.readCharacteristic(native) }
            .data ?: throw BleException(BleError.Gatt(BluetoothGatt.GATT_FAILURE, "Read returned no data"))
    }

    @Suppress("DEPRECATION")
    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
        val required = if (withResponse) BleCharacteristicProperty.WRITE else BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE
        requireProperty(characteristic, required)
        val native = findCharacteristic(characteristic)
        perform("write characteristic") { current ->
            native.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            native.value = data.clone()
            current.writeCharacteristic(native)
        }
    }

    override fun notifications(characteristic: BleCharacteristic): Flow<ByteArray> = callbackFlow {
        val native = findCharacteristic(characteristic)
        if (BleCharacteristicProperty.NOTIFY !in characteristic.properties &&
            BleCharacteristicProperty.INDICATE !in characteristic.properties
        ) throw BleException(BleError.InvalidState("Characteristic does not support notifications or indications"))
        val descriptor = native.getDescriptor(CCCD_UUID)
            ?: throw BleException(BleError.InvalidState("Client configuration descriptor is missing"))
        val enabled = BleCharacteristicProperty.NOTIFY in characteristic.properties
        perform("enable notifications") { current ->
            if (!current.setCharacteristicNotification(native, true)) return@perform false
            @Suppress("DEPRECATION")
            run {
                descriptor.value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                current.writeDescriptor(descriptor)
            }
        }
        val collector = launch { notificationEvents.filter { it.first == characteristic.uuid }.collect { trySend(it.second) } }
        val stateWatcher = launch {
            mutableState.filter { it == ConnectionState.Disconnected || it is ConnectionState.Error }.first()
            close(BleException(BleError.DeviceUnavailable("The BLE connection was closed")))
        }
        awaitClose {
            collector.cancel()
            stateWatcher.cancel()
            cleanupScope.launch {
                runCatching {
                    perform("disable notifications") { current ->
                        if (!current.setCharacteristicNotification(native, false)) return@perform false
                        @Suppress("DEPRECATION")
                        run {
                            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            current.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }
    }

    override suspend fun disconnect() {
        val current = gatt ?: return
        mutableState.value = ConnectionState.Disconnecting
        runCatching { current.disconnect() }
        current.close()
        gatt = null
        mutableState.value = ConnectionState.Disconnected
        cleanupScope.coroutineContext[Job]?.cancel()
    }

    fun disconnectQuietly() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        mutableState.value = ConnectionState.Disconnected
        cleanupScope.coroutineContext[Job]?.cancel()
    }

    private suspend fun perform(operation: String, start: (BluetoothGatt) -> Boolean): GattResult {
        ensureConnected()
        return operationMutex.withLock {
            val current = checkGatt()
            val deferred = CompletableDeferred<GattResult>()
            if (!pending.compareAndSet(null, deferred)) {
                throw BleException(BleError.InvalidState("Another GATT operation is active"))
            }
            try {
                if (!start(current)) throw BleException(BleError.Gatt(BluetoothGatt.GATT_FAILURE, "Unable to start $operation"))
                val result = withTimeout(OPERATION_TIMEOUT_MS) { deferred.await() }
                if (result.status != BluetoothGatt.GATT_SUCCESS) {
                    throw BleException(BleError.Gatt(result.status, "$operation failed (status=${result.status})"))
                }
                result
            } catch (error: SecurityException) {
                throw BleException(BleError.PermissionDenied(error.message ?: "Bluetooth permission denied"))
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val timeout = BleError.Timeout("Timed out while attempting to $operation")
                mutableState.value = ConnectionState.Error(timeout)
                runCatching { current.disconnect(); current.close() }
                gatt = null
                throw BleException(timeout)
            } finally {
                pending.compareAndSet(deferred, null)
            }
        }
    }

    private fun complete(result: GattResult) {
        pending.get()?.complete(result)
    }

    private fun findCharacteristic(model: BleCharacteristic): BluetoothGattCharacteristic {
        ensureConnected()
        return services[model.serviceUuid to model.uuid]
            ?: throw BleException(BleError.InvalidState("Discover services before using a characteristic"))
    }

    private fun requireProperty(characteristic: BleCharacteristic, property: BleCharacteristicProperty) {
        if (property !in characteristic.properties) {
            throw BleException(BleError.InvalidState("Characteristic does not support ${property.name.lowercase()}"))
        }
    }

    private fun ensureConnected() {
        if (mutableState.value !in setOf(
                ConnectionState.Connected,
                ConnectionState.DiscoveringServices,
                ConnectionState.Ready,
            )
        ) {
            throw BleException(BleError.InvalidState("BLE connection is not ready (${mutableState.value})"))
        }
    }

    private fun checkGatt(): BluetoothGatt = gatt
        ?: throw BleException(BleError.InvalidState("BLE connection is closed"))

    private data class GattResult(val status: Int, val data: ByteArray? = null)

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val OPERATION_TIMEOUT_MS = 15_000L
    }
}
