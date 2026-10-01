package com.Fusion.Btremix.core.permissions

/** Platform independent permission contract. The Android adapter supplies the actual requests. */
interface BluetoothPermissionManager {
    fun requiredPermissions(sdkInt: Int): Set<String>
    fun status(sdkInt: Int, isGranted: (String) -> Boolean): PermissionStatus
}

enum class PermissionStatus {
    Granted,
    Missing,
}

class AndroidBluetoothPermissionManager : BluetoothPermissionManager {
    override fun requiredPermissions(sdkInt: Int): Set<String> = when {
        sdkInt >= 31 -> setOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        sdkInt >= 23 -> setOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptySet()
    }

    override fun status(sdkInt: Int, isGranted: (String) -> Boolean): PermissionStatus =
        if (requiredPermissions(sdkInt).all(isGranted)) PermissionStatus.Granted else PermissionStatus.Missing
}

