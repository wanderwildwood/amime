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

    /** How a scan ended, which is the difference between "nothing there" and "never looked". */
    enum class Ending {
        /** It ran and then stopped, whether or not it found anything. */
        FINISHED,

        /** There was no adapter to scan with, or Bluetooth is switched off. */
        NO_BLUETOOTH,

        /**
         * Android refused to start it.
         *
         * Usually the five-scans-in-thirty-seconds limit, which is the platform's and not the
         * radio's. It looks identical to an empty room from the outside, which is why it is
         * a separate ending rather than a log line.
         */
        REFUSED,
    }

    private var callback: ScanCallback? = null
    private var whenDone: ((Ending) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    val isScanning: Boolean get() = callback != null

    /**
     * Scan for [durationMs], reporting each radio once, and call [done] when it ends.
     *
     * A scan that runs forever is a scan Android eventually throttles to nothing — after
     * thirty minutes of continuous scanning the stack silently stops delivering results — and
     * it is a real cost in battery on a phone whose whole point is lasting. So it stops.
     *
     * Because it stops by itself, it has to say so: a caller that only hears about starting
     * shows a scan that is still running long after the radio has gone quiet, and the button
     * that would start another one stays dead. [done] is called exactly once per [start],
     * including when there was no adapter to scan with.
     */
    fun start(durationMs: Long = DEFAULT_DURATION_MS, done: (Ending) -> Unit = {}, found: Found) {
        // A scan replaced by another one has not finished, it has been taken over, and the
        // new scan is what will report the end. Dropping the old callback before stopping
        // keeps a restart from announcing an ending that the caller is about to contradict.
        whenDone = null
        stop()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            // Bluetooth off, or no adapter at all. Nothing will be found and nothing will
            // stop it later, so the end of the scan is now.
            done(Ending.NO_BLUETOOTH)
            return
        }
        whenDone = done

        val seen = mutableSetOf<String>()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!seen.add(device.address)) return
                // The advertised name is not always in the scan record on the first packet,
                // so the device's own name is the fallback rather than the other way round.
                found.onRadio(device, result.scanRecord?.deviceName ?: device.name, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                // Nothing will arrive and nothing will stop, so this is the end of the scan.
                end(Ending.REFUSED)
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

    fun stop() = end(Ending.FINISHED)

    private fun end(how: Ending) {
        val scanCallback = callback ?: return
        callback = null
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        val done = whenDone
        whenDone = null
        done?.invoke(how)
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 12_000L
    }
}
