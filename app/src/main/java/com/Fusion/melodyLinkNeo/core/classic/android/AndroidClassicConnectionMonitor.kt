package com.fusion.melodyLinkNeo.core.classic.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.fusion.melodyLinkNeo.core.classic.api.ClassicConnectionMonitor
import com.fusion.melodyLinkNeo.core.classic.api.ClassicDevice
import com.fusion.melodyLinkNeo.core.classic.api.ClassicLinkEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android backend for [ClassicConnectionMonitor] (HANDOFF_AUTO_SESSION.md §6 step 1).
 *
 * `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the implicit-broadcast exemption list and
 * are the primary open/close edge. They are **not** the only source: on ROMs that never deliver the
 * ACL broadcasts to app receivers (observed on ColorOS, HANDOFF_AUTO_SESSION.md §2/§8) the
 * A2DP/HEADSET `ACTION_CONNECTION_STATE_CHANGED` broadcasts supply the same edge, so both are folded
 * into [events]. The profile proxies additionally answer "what is connected right now" for the
 * catch-up pass, and `ACTION_STATE_CHANGED` reports a whole-adapter power off.
 *
 * Everything is wrapped in `runCatching` and reports through logcat - a revoked permission or a
 * Bluetooth stack that refuses a profile proxy must never crash the monitor's owner.
 */
class AndroidClassicConnectionMonitor(context: Context) : ClassicConnectionMonitor {
    private val appContext: Context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mutableEvents = MutableSharedFlow<ClassicLinkEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ClassicLinkEvent> = mutableEvents.asSharedFlow()

    private val mutableAdapterOff = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val adapterOff: Flow<Unit> = mutableAdapterOff.asSharedFlow()

    @Volatile
    private var ownerScope: CoroutineScope? = null

    @Volatile
    private var receiver: BroadcastReceiver? = null

    override fun start(scope: CoroutineScope) {
        if (receiver != null) return
        ownerScope = scope
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val registered = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        emitAdapterOff()
                    }
                    return
                }
                val event = intent.toLinkEvent() ?: return
                emitLink(event)
            }
        }
        runCatching {
            // RECEIVER_EXPORTED is deliberate. The Bluetooth ACL / profile broadcasts are sent by
            // the Bluetooth *system app* (`com.android.bluetooth`), not by `system_server`, and
            // RECEIVER_NOT_EXPORTED silently drops those on Android 14+ (the flag is documented for
            // broadcasts from the system UID / own app). Nothing is exposed by it: the payload is
            // validated downstream - a link event can only ever act on a MAC that already matches a
            // bonded classic device and an enabled device package.
            ContextCompat.registerReceiver(appContext, registered, filter, ContextCompat.RECEIVER_EXPORTED)
            receiver = registered
            Log.i(TAG, "auto.session.monitor_started actions=${filter.countActions()}")
        }.onFailure { Log.w(TAG, "auto.session.monitor_register_failed: ${it.message}", it) }
    }

    override fun stop() {
        val current = receiver ?: return
        receiver = null
        ownerScope = null
        runCatching { appContext.unregisterReceiver(current) }
            .onFailure { Log.w(TAG, "auto.session.monitor_unregister_failed: ${it.message}", it) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connectedDevices(): List<ClassicDevice> = withContext(Dispatchers.Main) {
        runCatching {
            val adapter = adapter ?: return@runCatching emptyList()
            if (!adapter.isEnabled) return@runCatching emptyList()
            val a2dp = profileDevices(BluetoothProfile.A2DP)
            val headset = profileDevices(BluetoothProfile.HEADSET)
            (a2dp + headset).distinctBy { it.address }
        }.getOrElse { failure ->
            Log.w(TAG, "auto.session.connected_devices_failed: ${failure.message}", failure)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun profileDevices(profile: Int): List<ClassicDevice> =
        withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val adapter = adapter
                if (adapter == null) {
                    continuation.resume(emptyList())
                    return@suspendCancellableCoroutine
                }
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val devices = runCatching {
                            proxy.connectedDevices.orEmpty().mapNotNull { device ->
                                val address = runCatching { device.address }.getOrNull()
                                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                ClassicDevice(
                                    address = address,
                                    name = runCatching { device.name }.getOrNull(),
                                    bonded = true,
                                )
                            }
                        }.getOrDefault(emptyList())
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        if (continuation.isActive) continuation.resume(devices)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }
                val started = runCatching {
                    adapter.getProfileProxy(appContext, listener, profile)
                }.getOrDefault(false)
                if (!started && continuation.isActive) continuation.resume(emptyList())
            }
        } ?: emptyList()

    private fun Intent.toLinkEvent(): ClassicLinkEvent? {
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            -> when (getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothProfile.STATE_CONNECTED -> true
                BluetoothProfile.STATE_DISCONNECTED -> false
                // CONNECTING/DISCONNECTING are not an edge; wait for the settled state.
                else -> return null
            }
            else -> return null
        }
        val device = getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return null
        val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return ClassicLinkEvent(
            address = address,
            name = runCatching { device.name }.getOrNull(),
            connected = connected,
        )
    }

    /** Hands the transition to the owner's scope so collectors never run on the main thread. */
    private fun emitLink(event: ClassicLinkEvent) {
        val target = ownerScope
        if (target == null) mutableEvents.tryEmit(event) else target.launch { mutableEvents.emit(event) }
    }

    private fun emitAdapterOff() {
        val target = ownerScope
        if (target == null) mutableAdapterOff.tryEmit(Unit) else target.launch { mutableAdapterOff.emit(Unit) }
    }

    private companion object {
        const val TAG = "BtRemixAutoSession"
        const val PROFILE_TIMEOUT_MS = 3_000L
    }
}
