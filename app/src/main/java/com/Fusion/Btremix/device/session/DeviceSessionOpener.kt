package com.Fusion.Btremix.device.session

import com.Fusion.Btremix.core.bluetooth.BleRepository
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.TransportType
import com.Fusion.Btremix.definition.packages.DevicePackageRegistry
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.device.runtime.ProtocolSession
import java.util.UUID

/**
 * The single place that turns "a device the user tapped" into a live [ProtocolSession].
 *
 * Both the BLE Explorer and the Device Center need the same rules: SPP definitions open an RFCOMM
 * socket, BLE definitions attach to a GATT connection, and a definition-less device gets one chance
 * at connect-time service matching before it is handed to the UI. Keeping this out of the ViewModels
 * also guarantees the [SessionRegistry] is the only owner of live sessions (D-UI-3).
 */
class DeviceSessionOpener(
    private val ble: BleRepository,
    private val rfcomm: RfcommManager,
    private val runtime: DeviceRuntime,
    private val sessionFactory: DefinitionSessionFactory,
    private val packages: DevicePackageRegistry,
) {
    /**
     * Acquires the shared session for the device identified by [mac], opening it on first use.
     *
     * The caller stays in the device/UI layer and never has to construct a `BleDevice`; that type
     * belongs to `core.bluetooth`, which the product UI is not allowed to import (D-UI-4).
     */
    suspend fun acquire(
        registry: SessionRegistry,
        mac: String,
        name: String?,
        definition: LoadedDeviceDefinition?,
    ): ProtocolSession = registry.acquire(SessionRegistry.normalize(mac)) {
        val device = BleDevice(id = mac, name = name, address = mac)
        open(device, definition)
    }

    /**
     * Opens the session described by [mac]/[definition] **without** registering it in a
     * [SessionRegistry].
     *
     * The auto-connect connector (HANDOFF_AUTO_SESSION.md §6 step 3) owns exactly one registry
     * reference per device and takes it through [SessionManager.holdOrOpen], so it must not let this
     * opener register a second one. Keeping the neutral `BleDevice` construction here means the
     * connector stays free of `core.bluetooth` types, exactly like [acquire].
     */
    suspend fun openSession(
        mac: String,
        name: String?,
        definition: LoadedDeviceDefinition?,
    ): ProtocolSession = open(BleDevice(id = mac, name = name, address = mac), definition)

    private suspend fun open(device: BleDevice, definition: LoadedDeviceDefinition?): ProtocolSession {
        val transport = definition?.protocol?.transport
        if (transport != null && transport.type == TransportType.RFCOMM) {
            val connection = rfcomm.connect(device.address, UUID.fromString(transport.service))
            return sessionFactory.openStream(device, connection, definition)
        }
        val connection = ble.connect(device)
        if (definition != null) return sessionFactory.open(device, connection, definition)
        // Only the advertised name/address was known at scan time; give service matching a chance.
        val plain = runtime.open(device, connection)
        val gattMatch = packages.match(plain.services.value)
        return gattMatch?.let { sessionFactory.attach(plain, it.devicePackage.definition) } ?: plain
    }

    /** True when the definition drives an RFCOMM (SPP) link rather than GATT. */
    fun isStreaming(definition: LoadedDeviceDefinition?): Boolean =
        definition?.protocol?.transport?.type == TransportType.RFCOMM

    /** The GATT view of a session, or `null` for byte-stream sessions. */
    fun gattSession(session: ProtocolSession): DeviceSession? = session as? DeviceSession
}
