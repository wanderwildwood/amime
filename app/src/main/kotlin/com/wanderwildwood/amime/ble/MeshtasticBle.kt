package com.wanderwildwood.amime.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.meshtastic.MtTransport
import com.wanderwildwood.amime.meshtastic.MtUuids

/**
 * The Meshtastic client protocol over Bluetooth Low Energy.
 *
 * Meshtastic shares MeshCore's lessons — bond first, one GATT operation at a time, writes
 * with a response — and differs in shape: the app writes ToRadio, is notified of a counter
 * on FromNum, and reads FromRadio one message at a time. The MTU asked for is 512.
 * Android handles the pairing dialog; on a node with a screen the PIN is shown on it.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is the caller's to hold; see the manifest.
class MeshtasticBle(
    private val context: Context,
    private val listener: Listener,
) : MtTransport {

    interface Listener {
        /** Bonded, big enough and listening: the handshake can begin. */
        fun onReady()

        /** A FromRadio read came back; empty means nothing is waiting. */
        fun onRead(bytes: ByteArray)

        /** FromNum changed: something is waiting. */
        fun onFromNum()

        /** A read never called back and was given up on; false when the session gave up too. */
        fun onReadStalled(): Boolean = false

        fun onDisconnected()
        fun onPairingRequired() {}
        fun onError(message: String)

        /** The radio turned out to be MeshCore after all. */
        fun onOtherKind()
    }

    private var gatt: BluetoothGatt? = null
    private var linked = false
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bondReceiver: BroadcastReceiver? = null
    private var device: BluetoothDevice? = null
    private var retriesUsed = 0
    private var reconnecting = false

    /** What the operation now in flight is, for the log and for the stall watchdog. */
    private var inFlight = "none"

    private fun hex4(b: ByteArray?): String =
        if (b == null) "null" else b.take(4).joinToString("") { "%02x".format(it) }

    private fun log(text: String) {
        Log.i(LOG, text)
    }

    private val queue: OperationQueue = OperationQueue(
        onStartFailed = { fail(R.string.ble_refused) },
        onStarted = { attempt ->
            log("queue: start #$attempt $inFlight (depth ${queue.depth})")
            val watchdog = Runnable {
                if (!queue.isInFlight(attempt)) return@Runnable
                val what = inFlight
                log("timeout: #$attempt still waiting for $what after ${PATIENCE_MS} ms")
                if (queue.abandonIfStuck(attempt)) {
                    if (what == "read FromRadio" && listener.onReadStalled()) {
                        log("timeout: read abandoned, session asked to read again")
                    } else {
                        fail(R.string.ble_no_answer)
                    }
                }
            }
            watchdogs[attempt] = watchdog
            handler.postDelayed(watchdog, PATIENCE_MS)
        },
        onFinished = { attempt -> watchdogs.remove(attempt)?.let { handler.removeCallbacks(it) } },
    )

    /** The pending stall watchdog of each attempt in flight; cancelled when its operation ends. */
    private val watchdogs = HashMap<Long, Runnable>()

    fun connect(device: BluetoothDevice) {
        log("connect: bond state ${device.bondState}")
        this.device = device
        retriesUsed = 0
        reconnecting = false
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            openGatt(device)
        } else {
            listener.onPairingRequired()
            awaitBond(device)
            device.createBond()
        }
    }

    fun disconnect() {
        unregisterBondReceiver()
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        toRadio = null
        fromRadio = null
        reconnecting = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    override fun write(toRadio: ByteArray) {
        val characteristic = this.toRadio ?: return fail(R.string.ble_not_connected)
        val connection = gatt ?: return
        queue.enqueue {
            inFlight = "write ToRadio"
            log("issue: write ToRadio ${toRadio.size} bytes")
            @Suppress("DEPRECATION") // The Android 13 overload does not exist on API 31.
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = toRadio
                connection.writeCharacteristic(characteristic)
            }
        }
    }

    override fun read(delayMs: Long) {
        val action = Runnable {
            val characteristic = fromRadio ?: return@Runnable
            val connection = gatt ?: return@Runnable
            queue.enqueue {
                inFlight = "read FromRadio"
                val started = connection.readCharacteristic(characteristic)
                log("issue: read FromRadio -> $started")
                started
            }
        }
        if (delayMs <= 0) action.run() else handler.postDelayed(action, delayMs)
    }

    private fun awaitBond(device: BluetoothDevice) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> {
                        unregisterBondReceiver()
                        openGatt(device)
                    }

                    BluetoothDevice.BOND_NONE -> {
                        unregisterBondReceiver()
                        fail(R.string.ble_pairing_failed)
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
    }

    private fun openGatt(device: BluetoothDevice) {
        linked = false
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState inFlight=$inFlight depth=${queue.depth}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linked = true
                    queue.clear()
                    queue.enqueue {
                        inFlight = "MTU"
                        val started = gatt.requestMtu(MtUuids.DESIRED_MTU)
                        log("issue: requestMtu ${MtUuids.DESIRED_MTU} -> $started")
                        started
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.clear()
                    toRadio = null
                    fromRadio = null
                    gatt.close()
                    if (this@MeshtasticBle.gatt === gatt) this@MeshtasticBle.gatt = null
                    val again = device
                    if (reconnecting && again != null) {
                        reconnecting = false
                        openGatt(again)
                        return
                    }
                    if (!linked) fail(R.string.ble_could_not_connect, status)
                    linked = false
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
            queue.completeCurrent()
            discoverFresh(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            gatt.services.forEach { svc ->
                log("  service ${svc.uuid}: " + svc.characteristics.joinToString { it.uuid.toString() })
            }
            // The service list is the final word on which radio this is.
            val service = gatt.getService(MtUuids.SERVICE)
            if (service == null) {
                if (gatt.getService(NordicUart.SERVICE) != null) listener.onOtherKind()
                else if (!retryStale(gatt, null)) failStale(R.string.ble_no_service)
                return
            }
            toRadio = service.getCharacteristic(MtUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MtUuids.FROM_RADIO)
            val fromNum = service.getCharacteristic(MtUuids.FROM_NUM)
            if (toRadio == null || fromRadio == null || fromNum == null) {
                if (!retryStale(gatt, null)) failStale(R.string.ble_missing_characteristic)
                return
            }
            gatt.setCharacteristicNotification(fromNum, true)
            val cccd = fromNum.getDescriptor(NordicUart.CCCD)
            if (cccd == null) {
                fail(R.string.ble_no_cccd)
                return
            }
            queue.enqueue {
                inFlight = "CCCD write"
                log("issue: write CCCD on FromNum")
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("onDescriptorWrite ${descriptor.uuid} status=$status")
            queue.completeCurrent()
            if (descriptor.uuid != NordicUart.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onReady()
            else if (!retryStale(gatt, status)) {
                if (GattCachePolicy.decide(status, retriesUsed) == GattCachePolicy.Verdict.GIVE_UP_WITH_HINT) {
                    failStale(R.string.ble_notifications_refused, status)
                } else {
                    fail(R.string.ble_notifications_refused, status)
                }
            }
        }

        @Suppress("DEPRECATION") // The API 33 overload that passes the value does not exist here.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicRead ${characteristic.uuid} status=$status len=${characteristic.value?.size} first4=${hex4(characteristic.value)}")
            queue.completeCurrent()
            if (characteristic.uuid != MtUuids.FROM_RADIO) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onRead(characteristic.value?.copyOf() ?: ByteArray(0))
            } else {
                fail(R.string.ble_read_failed, status)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicWrite ${characteristic.uuid} status=$status")
            queue.completeCurrent()
            if (status != BluetoothGatt.GATT_SUCCESS) fail(R.string.ble_write_failed, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            log("onCharacteristicChanged ${characteristic.uuid} value=${hex4(characteristic.value)} inFlight=$inFlight")
            if (characteristic.uuid == MtUuids.FROM_NUM) listener.onFromNum()
        }
    }

    /** Drops the phone's cached service list, then asks for a fresh one. */
    private fun discoverFresh(gatt: BluetoothGatt) {
        refreshCache(gatt)
        handler.postDelayed({
            val current = this.gatt === gatt
            log("discoverServices after ${REFRESH_WAIT_MS} ms (current=$current)")
            if (current) gatt.discoverServices()
        }, REFRESH_WAIT_MS)
    }

    /** True when a refresh-and-reconnect was started; the caller then stops. */
    private fun retryStale(gatt: BluetoothGatt, status: Int?): Boolean {
        if (GattCachePolicy.decide(status, retriesUsed) != GattCachePolicy.Verdict.RETRY) return false
        if (device == null) return false
        retriesUsed++
        queue.clear()
        refreshCache(gatt)
        reconnecting = true
        gatt.disconnect()
        return true
    }

    /** The hidden BluetoothGatt.refresh(): unsupported API, so a failure is only logged. */
    private fun refreshCache(gatt: BluetoothGatt) {
        try {
            val ok = gatt.javaClass.getMethod("refresh").invoke(gatt)
            Log.i(TAG, "GATT cache refresh: $ok")
        } catch (e: Exception) {
            Log.w(TAG, "GATT cache refresh unavailable", e)
        }
    }

    private fun failStale(@StringRes reason: Int, vararg values: Any) {
        listener.onError(context.getString(reason, *values) + "\n" + context.getString(R.string.ble_forget_radio))
    }

    private fun fail(@StringRes reason: Int, vararg values: Any) {
        listener.onError(context.getString(reason, *values))
    }

    private companion object {
        const val PATIENCE_MS = 10_000L
        const val REFRESH_WAIT_MS = 600L
        const val TAG = "MeshtasticBle"
        const val LOG = "MtBle"
    }
}
