package com.Fusion.Btremix.melody.hook.injection

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.hook.Reflect

/**
 * Finds the Bluetooth address of a host object (M3.4 plan §4, suppression layers 2/3).
 *
 * `BRClientDevice` and `BaseBRConnection` are re-obfuscated every release, so neither their names nor
 * their field names can be relied on. What the host *cannot* rename is the framework types it holds -
 * the `BluetoothDevice` of the connection and the `BluetoothSocket` it opened - so the address is read
 * through the field type, with a last-resort scan for any `String` that looks like a MAC address.
 *
 * Returning `null` means "not ours": the caller then lets the official implementation run.
 */
internal object MelodyHostAddress {

    fun of(target: Any?): String? {
        if (target == null) return null

        Reflect.readFieldOfType(target, BluetoothDevice::class.java)?.let { candidate ->
            val address = runCatching { (candidate as BluetoothDevice).address }.getOrNull()
            address?.let(MelodyMac::normalize)?.takeIf(MelodyMac::isMacAddress)?.let { return it }
        }

        Reflect.readFieldOfType(target, BluetoothSocket::class.java)?.let { candidate ->
            val address = runCatching { (candidate as BluetoothSocket).remoteDevice?.address }.getOrNull()
            address?.let(MelodyMac::normalize)?.takeIf(MelodyMac::isMacAddress)?.let { return it }
        }

        return Reflect.readStringFieldWhere(target, MelodyMac::isMacAddress)?.let(MelodyMac::normalize)
    }
}
