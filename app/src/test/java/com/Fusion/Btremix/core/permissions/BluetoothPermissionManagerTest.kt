package com.Fusion.Btremix.core.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionManagerTest {
    private val manager = AndroidBluetoothPermissionManager()

    @Test
    fun requiredPermissions_useNearbyDevicesPermissionsOnAndroid12AndNewer() {
        assertEquals(
            setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            manager.requiredPermissions(31),
        )
    }

    @Test
    fun requiredPermissions_useLocationOnAndroid11AndOlder() {
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION), manager.requiredPermissions(30))
        assertTrue(manager.requiredPermissions(22).isEmpty())
    }

    @Test
    fun status_requiresEveryPermission() {
        val permissions = manager.requiredPermissions(35)

        assertEquals(PermissionStatus.Granted, manager.status(35) { true })
        assertEquals(PermissionStatus.Missing, manager.status(35) { it != permissions.first() })
    }
}
