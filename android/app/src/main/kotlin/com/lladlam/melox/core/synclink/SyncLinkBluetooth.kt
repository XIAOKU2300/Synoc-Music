package com.lladlam.melox.core.synclink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Classic Bluetooth side of SyncLink, mirroring the official Android client: list bonded devices,
 * run a classic inquiry (startDiscovery + ACTION_FOUND, skipping "uncategorized" class 0x1F00),
 * then open RFCOMM directly — connecting to an unbonded DAP makes the system show its pairing dialog.
 */
object SyncLinkBluetooth {
    /** Same set the official app asks for: nearby devices on 12+, location for inquiry results. */
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Enough to list bonded devices and open RFCOMM. */
    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** Enough to run an inquiry and receive ACTION_FOUND. */
    fun canScan(context: Context): Boolean {
        val location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN) && location
        } else {
            location
        }
    }

    /** Before Android 12 inquiry results are silently dropped while system location is off. */
    fun locationServiceOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun isEnabled(context: Context): Boolean =
        runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toSlDevice(): SlDevice =
        SlDevice(name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address, host = address, port = 0, bluetooth = true)

    /** Bonded devices, Shanling-looking names first. */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<SlDevice> {
        if (!canConnect(context)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices.orEmpty().map { it.toSlDevice() }.sortedForDisplay()
        }.getOrDefault(emptyList())
    }

    fun List<SlDevice>.sortedForDisplay(): List<SlDevice> =
        sortedWith(compareByDescending<SlDevice> { looksLikeShanling(it.name) }.thenBy { it.name.lowercase() })

    fun looksLikeShanling(name: String): Boolean {
        val n = name.lowercase()
        return "shanling" in n || "山灵" in n || Regex("^(m\\d|ua\\d|h\\d|ec |ec-|cd\\d|et\\d|eh\\d|ct\\d|scd)").containsMatchIn(n)
    }

    /** Classic inquiry; emits each named, categorized device once. Ends after [durationMs]. */
    @SuppressLint("MissingPermission")
    fun discover(context: Context, durationMs: Long = 12_000): Flow<SlDevice> = callbackFlow {
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled || !canScan(context)) {
            close()
            return@callbackFlow
        }
        val app = context.applicationContext
        val seen = HashSet<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        @Suppress("DEPRECATION")
                        val cls = intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
                        if (cls?.majorDeviceClass == BluetoothClass.Device.Major.UNCATEGORIZED) return
                        val sl = device.toSlDevice()
                        // Unnamed devices show up as bare MACs; wait for ACTION_NAME_CHANGED instead.
                        if (sl.name == sl.host) return
                        if (seen.add(sl.host)) trySend(sl)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started = runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }.getOrDefault(false)
        if (!started) close()
        val timer = launch {
            delay(durationMs)
            close()
        }
        awaitClose {
            timer.cancel()
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { adapter.cancelDiscovery() }
        }
    }
}
