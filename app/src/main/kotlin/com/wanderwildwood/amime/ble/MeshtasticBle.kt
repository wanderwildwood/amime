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

    private val queue = OperationQueue(
        onStartFailed = { fail(R.string.ble_refused) },
        onStarted = { attempt ->
            handler.postDelayed(
                { if (queue.abandonIfStuck(attempt)) fail(R.string.ble_no_answer) },
                PATIENCE_MS,
            )
        },
    )

    fun connect(device: BluetoothDevice) {
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
            queue.enqueue { connection.readCharacteristic(characteristic) }
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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linked = true
                    queue.clear()
                    queue.enqueue { gatt.requestMtu(MtUuids.DESIRED_MTU) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.clear()
                    toRadio = null
                    fromRadio = null
                    gatt.close()
                    if (this@MeshtasticBle.gatt === gatt) this@MeshtasticBle.gatt = null
                    if (!linked) fail(R.string.ble_could_not_connect, status)
                    linked = false
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            queue.completeCurrent()
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // The service list is the final word on which radio this is.
            val service = gatt.getService(MtUuids.SERVICE)
            if (service == null) {
                if (gatt.getService(NordicUart.SERVICE) != null) listener.onOtherKind()
                else fail(R.string.ble_no_service)
                return
            }
            toRadio = service.getCharacteristic(MtUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MtUuids.FROM_RADIO)
            val fromNum = service.getCharacteristic(MtUuids.FROM_NUM)
            if (toRadio == null || fromRadio == null || fromNum == null) {
                fail(R.string.ble_missing_characteristic)
                return
            }
            gatt.setCharacteristicNotification(fromNum, true)
            val cccd = fromNum.getDescriptor(NordicUart.CCCD)
            if (cccd == null) {
                fail(R.string.ble_no_cccd)
                return
            }
            queue.enqueue {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            queue.completeCurrent()
            if (descriptor.uuid != NordicUart.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onReady()
            else fail(R.string.ble_notifications_refused, status)
        }

        @Suppress("DEPRECATION") // The API 33 overload that passes the value does not exist here.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            queue.completeCurrent()
            if (characteristic.uuid != MtUuids.FROM_RADIO) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onRead(characteristic.value?.copyOf() ?: ByteArray(0))
            } else {
                fail(R.string.ble_read_failed, status)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            queue.completeCurrent()
            if (status != BluetoothGatt.GATT_SUCCESS) fail(R.string.ble_write_failed, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MtUuids.FROM_NUM) listener.onFromNum()
        }
    }

    private fun fail(@StringRes reason: Int, vararg values: Any) {
        listener.onError(context.getString(reason, *values))
    }

    private companion object {
        const val PATIENCE_MS = 10_000L
    }
}
