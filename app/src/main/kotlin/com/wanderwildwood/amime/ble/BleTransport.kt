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
import com.wanderwildwood.amime.link.Transport

/**
 * The companion protocol over Bluetooth Low Energy.
 *
 * Three things about the radio shape this class, and all three are quiet failures if ignored.
 *
 * **The characteristics require an authenticated, encrypted link.** The firmware marks both
 * with MITM permissions, so the phone has to be *bonded*, not merely connected. Android will
 * start pairing by itself when an unbonded app touches such a characteristic, but the
 * operation that triggered it is often lost rather than retried — most visibly the
 * notification-enable, which then leaves a connection that looks healthy and never delivers a
 * message. So bonding is done deliberately and up front, and nothing touches a characteristic
 * until it has completed.
 *
 * **A frame is one characteristic value.** The firmware never chunks, so the negotiated MTU
 * has to hold the largest frame whole — see [NordicUart.DESIRED_MTU].
 *
 * **One GATT operation at a time.** See [OperationQueue].
 *
 * Callbacks arrive on a binder thread and are passed straight through; [listener] is
 * responsible for getting them wherever it needs them.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is the caller's to hold; see the manifest.
class BleTransport(
    private val context: Context,
    private val listener: Listener,
) : Transport {

    interface Listener {
        /** The link is bonded, big enough and listening. Frames can be sent now. */
        fun onReady()

        /** A whole frame arrived. */
        fun onFrame(frame: ByteArray)

        /** The link went away, whether asked to or not. */
        fun onDisconnected()

        /**
         * Pairing is being asked for. The PIN is the radio's, not the phone's: 123456 unless
         * it has been changed, and there is no way to read the real one before bonding
         * because reading it needs a bonded link.
         */
        fun onPairingRequired() {}

        /** Something went wrong that stopped the link coming up. */
        fun onError(stage: String, status: Int)
    }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val queue = OperationQueue { listener.onError("operation refused by the stack", -1) }

    private var bondReceiver: BroadcastReceiver? = null

    /** Connect, bonding first when the radio is not already paired. */
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
        queue.clear()
        rx = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    /**
     * Frames are written without a response.
     *
     * The radio's RX characteristic is write-only and does not acknowledge at the ATT layer;
     * asking for a response on a characteristic that offers no such property fails the write
     * outright. The queue still serialises them, because the stack still only accepts one at
     * a time.
     */
    override fun send(frame: ByteArray) {
        val characteristic = rx ?: run {
            listener.onError("not connected", -1)
            return
        }
        val connection = gatt ?: return
        queue.enqueue {
            @Suppress("DEPRECATION") // The Android 13 overload does not exist on the Kompakt's API 31.
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = frame
                connection.writeCharacteristic(characteristic)
            }
        }
    }

    // ---- bonding ----

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
                        // A refused or mistyped PIN lands here, and so does a radio that has
                        // forgotten this phone while the phone still remembers it.
                        listener.onError("pairing failed or was refused", -1)
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        )
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
    }

    // ---- gatt ----

    private fun openGatt(device: BluetoothDevice) {
        // TRANSPORT_LE is not the default for a dual-mode device, and letting the stack
        // choose sends the connection over BR/EDR, where none of this exists.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // MTU before service discovery: a larger MTU changes nothing about the
                    // services, and asking afterwards means the first frames go out at 20
                    // bytes and come back cut off.
                    queue.clear()
                    queue.enqueue { gatt.requestMtu(NordicUart.DESIRED_MTU) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.clear()
                    rx = null
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // A refused request is survivable — the link still works, it just cannot carry a
            // long frame whole, and a short frame decodes to Frame.Malformed rather than to
            // something plausible and wrong.
            if (mtu < MIN_USABLE_MTU) listener.onError("MTU stayed at $mtu", status)
            queue.completeCurrent()
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(NordicUart.SERVICE)
            if (service == null) {
                listener.onError("no Nordic UART service on this device", status)
                return
            }
            rx = service.getCharacteristic(NordicUart.RX)
            val tx = service.getCharacteristic(NordicUart.TX)
            if (rx == null || tx == null) {
                listener.onError("the UART service is missing a characteristic", status)
                return
            }

            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(NordicUart.CCCD)
            if (cccd == null) {
                // setCharacteristicNotification alone only tells the local stack to stop
                // discarding notifications; without the descriptor write the radio was never
                // asked to send any, and the connection looks fine and stays silent.
                listener.onError("no CCCD on the notify characteristic", status)
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue.completeCurrent()
            if (descriptor.uuid != NordicUart.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onReady()
            } else {
                listener.onError("could not enable notifications", status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue.completeCurrent()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("frame not written", status)
            }
        }

        @Suppress("DEPRECATION") // The API 33 overload that passes the value does not exist here.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != NordicUart.TX) return
            // Copied on the way out: the array behind a characteristic is reused by the stack
            // for the next notification, so anything holding it sees its own message change.
            characteristic.value?.let { listener.onFrame(it.copyOf()) }
        }
    }

    private companion object {
        /** Enough for the largest frame the firmware will send, plus three bytes of ATT. */
        const val MIN_USABLE_MTU = 179
    }
}
