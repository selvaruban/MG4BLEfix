package com.example.mg4blefix.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

object BtCleanupManager {

    private const val TAG = "BtCleanupManager"

    /**
     * Retrieves all bonded Bluetooth devices matching targetName (e.g. "MG").
     */
    @SuppressLint("MissingPermission")
    fun getMatchingBondedDevices(context: Context, targetName: String): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return try {
            val bondedDevices = adapter.bondedDevices ?: emptySet()
            bondedDevices.filter { device ->
                try {
                    val name = device.name
                    name != null && name.contains(targetName, ignoreCase = true)
                } catch (_: SecurityException) {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing bonded Bluetooth devices", e)
            emptyList()
        }
    }

    /**
     * Unpairs duplicate / stale Bluetooth devices matching targetName,
     * keeping only the most recently bonded or active device.
     * Returns the count of removed bonds.
     */
    @SuppressLint("MissingPermission")
    fun cleanupStaleDevices(context: Context, targetName: String): Int {
        val matchingDevices = getMatchingBondedDevices(context, targetName)
        if (matchingDevices.size <= 1) {
            Log.d(TAG, "No duplicate devices found for '$targetName' (Count: ${matchingDevices.size})")
            return 0
        }

        var removedCount = 0
        // Unpair all except the last matching device in the list (the most recent bond)
        for (i in 0 until matchingDevices.size - 1) {
            val device = matchingDevices[i]
            if (unpairDevice(device)) {
                removedCount++
            }
        }
        Log.i(TAG, "Successfully cleaned up $removedCount stale '$targetName' Bluetooth devices")
        return removedCount
    }

    /**
     * Uses reflection to invoke hidden removeBond API on BluetoothDevice.
     */
    @SuppressLint("MissingPermission")
    fun unpairDevice(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            val success = method.invoke(device) as? Boolean ?: false
            if (success) {
                Log.i(TAG, "Successfully invoked removeBond for ${device.address}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpair Bluetooth device", e)
            false
        }
    }
}
