package com.wanderwildwood.amime.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * Finds MeshCore radios that this phone has not met before.
 *
 * The app cannot work with a radio until the phone is bonded to it, and it cannot bond to one
 * it has not discovered — so listing only bonded devices, which is what this did first, works
 * for every radio except the one you are setting up. Hence a scan.
 *
 * Filtered on the UART service the firmware advertises, so this returns radios rather than
 * everything in the room: a MeshCore node puts the service UUID in its advertisement, which
 * is the one thing that reliably tells it apart from a pair of headphones.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_SCAN is the caller's to hold; see the manifest.
class RadioScanner(private val adapter: BluetoothAdapter?) {

    fun interface Found {
        fun onRadio(device: BluetoothDevice, name: String?, rssi: Int)
    }

    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    val isScanning: Boolean get() = callback != null

    /**
     * Scan for [durationMs], reporting each radio once.
     *
     * A scan that runs forever is a scan Android eventually throttles to nothing — after
     * thirty minutes of continuous scanning the stack silently stops delivering results — and
     * it is a real cost in battery on a phone whose whole point is lasting. So it stops.
     */
    fun start(durationMs: Long = DEFAULT_DURATION_MS, found: Found) {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        stop()

        val seen = mutableSetOf<String>()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!seen.add(device.address)) return
                // The advertised name is not always in the scan record on the first packet,
                // so the device's own name is the fallback rather than the other way round.
                found.onRadio(device, result.scanRecord?.deviceName ?: device.name, result.rssi)
            }
        }
        callback = scanCallback

        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NordicUart.SERVICE)).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCallback,
        )
        handler.postDelayed(::stop, durationMs)
    }

    fun stop() {
        val scanCallback = callback ?: return
        callback = null
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 12_000L
    }
}
