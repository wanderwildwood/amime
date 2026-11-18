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
import com.wanderwildwood.amime.BuildConfig
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

    /** Debug builds only, as on the MeshCore path (BleTransport): a release build logs nothing. */
    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.i(LOG, text)
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
                        // A read's late answer can only finish a read: anything else in flight
                        // ignores it (OperationQueue.completeCurrent(kind)).
                        log("timeout: read abandoned, session asked to read again")
                    } else {
                        dropLink(R.string.ble_no_answer)
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
        // A link left from an earlier attempt is closed first: the radio takes one client at
        // a time, and a GATT left open keeps its registration and its place on the radio.
        disconnect()
        this.device = device
        retriesUsed = 0
        reconnecting = false
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            openGatt(device)
        } else {
            listener.onPairingRequired()
            awaitBond(device)
            if (!device.createBond()) {
                // Refused outright: no bond broadcast is coming to say so.
                unregisterBondReceiver()
                fail(R.string.ble_pairing_failed)
            }
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
        queue.enqueue(OP_WRITE) {
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
            queue.enqueue(OP_READ) {
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
            val current = this@MeshtasticBle.gatt
            if (current != null && current !== gatt) {
                // A link replaced by a newer one: its news is not this link's, and clearing
                // the queue or saying the link went would break the one now in use.
                log("onConnectionStateChange from an earlier link, closed")
                gatt.disconnect()
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linked = true
                    queue.clear()
                    queue.enqueue(OP_MTU) {
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
            if (!queue.completeCurrent(OP_MTU)) return
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
                else if (!retryStale(gatt, null)) failSetup(R.string.ble_no_service, stale = true)
                return
            }
            toRadio = service.getCharacteristic(MtUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MtUuids.FROM_RADIO)
            val fromNum = service.getCharacteristic(MtUuids.FROM_NUM)
            if (toRadio == null || fromRadio == null || fromNum == null) {
                if (!retryStale(gatt, null)) failSetup(R.string.ble_missing_characteristic, stale = true)
                return
            }
            gatt.setCharacteristicNotification(fromNum, true)
            val cccd = fromNum.getDescriptor(NordicUart.CCCD)
            if (cccd == null) {
                failSetup(R.string.ble_no_cccd)
                return
            }
            queue.enqueue(OP_DESCRIPTOR) {
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
            if (!queue.completeCurrent(OP_DESCRIPTOR)) return
            if (descriptor.uuid != NordicUart.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onReady()
            else if (!retryStale(gatt, status)) {
                if (GattCachePolicy.decide(status, retriesUsed) == GattCachePolicy.Verdict.GIVE_UP_WITH_HINT) {
                    failSetup(R.string.ble_notifications_refused, status, stale = true)
                } else {
                    failSetup(R.string.ble_notifications_refused, status)
                }
            }
        }

        @Suppress("DEPRECATION") // The API 33 overload that passes the value does not exist here.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicRead ${characteristic.uuid} status=$status len=${characteristic.value?.size} first4=${hex4(characteristic.value)}")
            // A late read finishes nothing else, but what it read is still the radio's next
            // packet and is passed on.
            queue.completeCurrent(OP_READ)
            if (characteristic.uuid != MtUuids.FROM_RADIO) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onRead(characteristic.value?.copyOf() ?: ByteArray(0))
            } else {
                // The read is over but nothing came of it, so the session's drain would wait
                // for good and nothing more would be read on this link. It is asked to read
                // again, as after a stall, on the main thread where it lives; past its few
                // tries the link is closed and said to be gone.
                handler.post {
                    if (this@MeshtasticBle.gatt !== gatt) return@post
                    if (listener.onReadStalled()) log("read failed ($status), session asked to read again")
                    else dropLink(R.string.ble_read_failed, status)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicWrite ${characteristic.uuid} status=$status")
            if (!queue.completeCurrent(OP_WRITE)) return
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
            if (BuildConfig.DEBUG) Log.i(TAG, "GATT cache refresh: $ok")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "GATT cache refresh unavailable", e)
        }
    }

    /** An operation that never called back: closed and said to be gone, as in BleTransport. */
    private fun dropLink(@StringRes reason: Int, vararg values: Any) {
        val wasLinked = gatt != null
        disconnect()
        linked = false
        fail(reason, *values)
        if (wasLinked) listener.onDisconnected()
    }

    /**
     * A link that cannot be set up is closed before saying why, as in BleTransport: left
     * open, it holds the radio's one connection and the next attempt cannot get in.
     */
    private fun failSetup(@StringRes reason: Int, vararg values: Any, stale: Boolean = false) {
        disconnect()
        linked = false
        if (stale) failStale(reason, *values) else fail(reason, *values)
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
        const val OP_MTU = "mtu"
        const val OP_READ = "read"
        const val OP_DESCRIPTOR = "descriptor"
        const val OP_WRITE = "write"
    }
}
