package com.fusion.melodyLinkNeo

import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.classic.android.AndroidRfcommManager
import com.fusion.melodyLinkNeo.definition.packages.DevicePackageReader
import com.fusion.melodyLinkNeo.definition.packages.DevicePackageValidator
import com.fusion.melodyLinkNeo.definition.session.DefinitionSessionFactory
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hardware acceptance test for the classic-Bluetooth path (SPP.7).
 *
 * It drives the **production** code path end to end: the shipped device package is validated by the
 * real reader/validator, the SPP socket is opened by `AndroidRfcommManager`, the definition is bound
 * by `DefinitionSessionFactory.openStream`, and the declarative actions are executed.
 *
 * Manual precondition: push the sample package to the app's external files directory first.
 *   adb push samples/sony.wf1000xm3-1.0.0.dcpkg /sdcard/Android/data/com.fusion.melodyLinkNeo/files/
 */
class SonyXm3SppSessionTest {
    @Test
    fun opensShippedPackageOverSppAndRunsDeclarativeActions(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val earbuds = adapter.bondedDevices.firstOrNull { it.name?.contains("WF-1000XM3") == true }
        assumeTrue("no bonded WF-1000XM3 on this device", earbuds != null)
        val packageFile = File(context.getExternalFilesDir(null), PACKAGE_NAME)
        assumeTrue("push $PACKAGE_NAME to ${packageFile.parent} first", packageFile.isFile)

        val definition = DevicePackageValidator().validate(DevicePackageReader().read(packageFile)).definition
        val serviceUuid = UUID.fromString(requireNotNull(definition.protocol.transport).service)
        Log.i(TAG, "definition=${definition.id} v${definition.manifest.version} sppService=$serviceUuid")

        val clock = Clock.systemUTC()
        val manager = AndroidRfcommManager(context)
        val connection = manager.connect(earbuds!!.address, serviceUuid)
        Log.i(TAG, "spp socket connected to ${earbuds.address}")

        val session = DefinitionSessionFactory(DefaultDeviceRuntime(clock), Dispatchers.IO, clock)
            .openStream(BleDevice(earbuds.address, earbuds.name, earbuds.address), connection, definition)
        try {
            Log.i(TAG, "lifecycle=${session.lifecycle.value}")
            delay(INIT_SETTLE_MS)
            Log.i(TAG, "state after initialize=${stateOf(session)}")

            ACTIONS.forEach { action ->
                Log.i(TAG, "action ${action.id} -> ${session.execute(action)}")
                delay(ACTION_SETTLE_MS)
                Log.i(TAG, "  state=${stateOf(session)}")
            }
        } finally {
            session.close()
        }
    }

    private fun stateOf(session: com.fusion.melodyLinkNeo.device.runtime.ProtocolSession): String =
        session.state.entries.value.entries.joinToString { "${it.key}=${it.value.value}(from ${it.value.source})" }

    private companion object {
        const val TAG = "Xm3Spp"
        const val PACKAGE_NAME = "sony.wf1000xm3-1.0.0.dcpkg"
        const val INIT_SETTLE_MS = 3_000L
        const val ACTION_SETTLE_MS = 2_000L

        // `protocol.initialize` already reads battery/ANC, so the expected state after the settle
        // delay is left/right/case percentages plus a noise-control mode and ambient level.
        val ACTIONS = listOf(
            DeviceAction("battery.refresh"),
            DeviceAction("battery.case.refresh"),
            DeviceAction("anc.refresh"),
            DeviceAction("anc.setMode", mapOf("mode" to StateValue.StringValue("anc"))),
            DeviceAction("anc.setLevel", mapOf("value" to StateValue.IntValue(12))),
            DeviceAction("eq.set", mapOf("preset" to StateValue.StringValue("bright"))),
            DeviceAction("upscaling.set", mapOf("value" to StateValue.BooleanValue(true))),
            DeviceAction("upscaling.set", mapOf("value" to StateValue.BooleanValue(false))),
        )
    }
}
