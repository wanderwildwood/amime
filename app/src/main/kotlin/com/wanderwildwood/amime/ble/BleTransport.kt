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
import android.util.Log
import androidx.annotation.StringRes
import com.wanderwildwood.amime.BuildConfig
import com.wanderwildwood.amime.R
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

        /**
         * Something went wrong, said in words rather than in a code.
         *
         * Finished here rather than in the listener because this is the layer that knows
         * what the numbers mean, and because a sentence assembled from an English fragment
         * and a status cannot be translated by anybody.
         */
        fun onError(message: String)
    }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val queue = OperationQueue { fail(R.string.ble_refused) }

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
     * Frames are written **with** a response, and this is not a free choice.
     *
     * The firmware creates the RX characteristic with `PROPERTY_WRITE` alone — no
     * `PROPERTY_WRITE_NR`. Writing without a response therefore goes out as an ATT Write
     * Command, which the radio is entitled to drop on a characteristic that never offered
     * that property, and does. Nothing reports it: a Write Command has no acknowledgement to
     * fail, so Android calls back `GATT_SUCCESS` within a millisecond or two and the frame is
     * simply gone. The tell is the timing — a real Write Request takes tens of milliseconds,
     * a dropped Write Command takes one.
     */
    override fun send(frame: ByteArray) {
        val characteristic = rx ?: run {
            fail(R.string.ble_not_connected)
            return
        }
        val connection = gatt ?: return
        // A value longer than the link will carry is not rejected by the stack; it is cut
        // down to the MTU and written, and what arrives at the radio is a frame that ends
        // mid-field. Refusing here is the only place this can be said rather than guessed
        // at later from a reply that makes no sense.
        val room = negotiatedMtu - ATT_HEADER
        if (negotiatedMtu > 0 && frame.size > room) {
            fail(R.string.ble_frame_too_long, frame.size, room)
            return
        }
        log { "send ${frame.size} bytes: ${frame.joinToString(" ") { "%02x".format(it) }}" }
        queue.enqueue {
            @Suppress("DEPRECATION") // The Android 13 overload does not exist on the Kompakt's API 31.
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
                        fail(R.string.ble_pairing_failed)
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
                    log { "connected status=$status" }
                    // MTU before service discovery: a larger MTU changes nothing about the
                    // services, and asking afterwards means the first frames go out at 20
                    // bytes and come back cut off.
                    queue.clear()
                    queue.enqueue { gatt.requestMtu(NordicUart.DESIRED_MTU) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log { "disconnected status=$status" }
                    queue.clear()
                    rx = null
                    // Closing is not optional and not the same as disconnecting. Android
                    // allows a limited number of GATT client registrations per process, and
                    // a connection that ends without close() keeps its one forever. Leak
                    // enough of them — which a few reconnects will do — and every later
                    // attempt fails with status 133, a number that names no cause and sends
                    // you looking at the radio.
                    gatt.close()
                    if (this@BleTransport.gatt === gatt) this@BleTransport.gatt = null
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log { "mtu=$mtu status=$status" }
            // A refused request is survivable — the link still works, it just cannot carry a
            // long frame whole, and a short frame decodes to Frame.Malformed rather than to
            // something plausible and wrong.
            if (mtu < MIN_USABLE_MTU) fail(R.string.ble_small_mtu, mtu)
            negotiatedMtu = mtu
            queue.completeCurrent()
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log { "services=${gatt.services.map { it.uuid }} status=$status" }
            val service = gatt.getService(NordicUart.SERVICE)
            if (service == null) {
                fail(R.string.ble_no_service)
                return
            }
            rx = service.getCharacteristic(NordicUart.RX)
            val tx = service.getCharacteristic(NordicUart.TX)
            if (rx == null || tx == null) {
                fail(R.string.ble_missing_characteristic)
                return
            }

            // Read the notify characteristic before doing anything else with it.
            //
            // Not for the value, which is discarded. The firmware sets `deviceConnected` in
            // exactly one place — `onAuthenticationComplete` — and `writeFrame` refuses to
            // send while it is false. Its receive path has no such guard, so an unauthenticated
            // app gets a connection that accepts every command, processes it, and drops every
            // reply without a word. Touching a characteristic marked ENC_MITM is what makes
            // Android elevate the link, so this read is the thing that makes the radio talk.
            queue.enqueue { gatt.readCharacteristic(tx) }

            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(NordicUart.CCCD)
            if (cccd == null) {
                // setCharacteristicNotification alone only tells the local stack to stop
                // discarding notifications; without the descriptor write the radio was never
                // asked to send any, and the connection looks fine and stays silent.
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

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log { "read ${characteristic.uuid} status=$status bond=${gatt.device.bondState}" }
            queue.completeCurrent()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            log { "descriptorWrite ${descriptor.uuid} status=$status" }
            queue.completeCurrent()
            if (descriptor.uuid != NordicUart.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onReady()
            } else {
                fail(R.string.ble_notifications_refused, status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log { "wrote status=$status" }
            queue.completeCurrent()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(R.string.ble_write_failed, status)
            }
        }

        @Suppress("DEPRECATION") // The API 33 overload that passes the value does not exist here.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != NordicUart.TX) return
            log {
                "notify ${characteristic.value?.size} bytes: " +
                    characteristic.value?.joinToString(" ") { "%02x".format(it) }
            }
            // Copied on the way out: the array behind a characteristic is reused by the stack
            // for the next notification, so anything holding it sees its own message change.
            characteristic.value?.let { listener.onFrame(it.copyOf()) }
        }
    }

    private var negotiatedMtu: Int = 0

    private fun fail(@StringRes reason: Int, vararg values: Any) {
        listener.onError(context.getString(reason, *values))
    }

    private companion object {
        /**
         * Enough to carry a contact frame, the largest this app has to receive whole.
         *
         * Not 179. The radio asks for 176 and will not agree to more — `BLEDevice::setMTU`
         * is handed `MAX_FRAME_SIZE` — so a threshold above that would report a fault on
         * every healthy connection. Worth knowing that 176 of MTU is 173 of payload while
         * the firmware's own maximum frame is 176, so a maximal frame cannot in fact fit;
         * nothing this app asks for comes close, and a short one decodes to
         * `Frame.Malformed` rather than to something plausible and wrong.
         */
        const val MIN_USABLE_MTU = 151

        /** Three bytes of ATT opcode and handle come off every MTU before any payload. */
        const val ATT_HEADER = 3
    }
}

private const val TAG = "amime.ble"

/**
 * A line of diagnostics, in a debug build and nowhere else.
 *
 * These lines are how the protocol was worked out and they are worth keeping, but two of
 * them print whole frames — which is to say the text of every message sent and received. A
 * released build writing that to the log would be handing anyone with a cable a copy of the
 * conversation, from an app whose whole claim is that there is no copy anywhere else.
 *
 * Taking a lambda rather than a string means the hex is not even assembled in a release
 * build, where the whole call inlines away to nothing.
 */
private inline fun log(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i(TAG, message())
}
