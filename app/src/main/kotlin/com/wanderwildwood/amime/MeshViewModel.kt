package com.wanderwildwood.amime

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.wanderwildwood.amime.ble.BleTransport
import com.wanderwildwood.amime.ble.NordicUart
import com.wanderwildwood.amime.link.Session
import com.wanderwildwood.amime.mesh.MeshState
import com.wanderwildwood.amime.mesh.MeshStore
import com.wanderwildwood.amime.mesh.Person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the one radio connection and turns it into state a screen can collect.
 *
 * The store, the session and the transport each know only their own job; this is the only
 * place that knows all three, and the only place that touches Android.
 */
class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val _pin = MutableStateFlow<Int?>(null)

    /** The PIN to type when the phone asks to pair, once the radio has told us its own. */
    val pin: StateFlow<Int?> = _pin.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val store = MeshStore(onChange = { _state.value = it })

    private val listener = object : Session.Listener by store {
        override fun onDevice(info: com.wanderwildwood.amime.protocol.Frame.DeviceInfo) {
            // The radio's own PIN, which is only readable once bonded — so this is for the
            // next time, and for telling someone which number their radio actually wants.
            _pin.value = info.blePin.toInt()
        }

        override fun onProtocolProblem(problem: Session.Problem) {
            _problem.value = when (problem) {
                Session.Problem.HANDSHAKE_SKIPPED ->
                    "The radio is answering in an older format; messages may be unreadable."
                Session.Problem.FRAME_TRUNCATED ->
                    "Part of a message was lost between the radio and the phone."
                Session.Problem.UNKNOWN_FRAME -> null
            }
        }
    }

    private lateinit var session: Session

    private val transport = BleTransport(
        context = application,
        listener = object : BleTransport.Listener {
            override fun onReady() {
                session.start()
                session.syncContacts()
            }

            override fun onFrame(frame: ByteArray) = session.onFrame(frame)

            override fun onDisconnected() = store.onDisconnected()

            override fun onPairingRequired() {
                _pin.value = _pin.value ?: NordicUart.DEFAULT_PIN
            }

            override fun onError(stage: String, status: Int) {
                _problem.value = if (status >= 0) "$stage ($status)" else stage
            }
        },
    )

    init {
        session = Session(transport, listener)
    }

    /**
     * Radios this phone has already paired with.
     *
     * Bonded devices only, deliberately: scanning is a second permission, a second failure
     * mode and a list of everything in the room, and the phone has to be bonded to this radio
     * before it can do anything with it anyway.
     */
    fun pairedRadios(): List<BluetoothDevice> {
        val manager = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
    }

    fun connect(device: BluetoothDevice) = transport.connect(device)

    fun send(person: Person, text: String, now: Long = System.currentTimeMillis() / 1000) {
        val prefix = person.prefix.toByteArray()
        store.recordSent(person.prefix, text, now)
        session.sendMessage(prefix, text, now)
    }

    fun dismissProblem() {
        _problem.value = null
    }

    override fun onCleared() {
        transport.disconnect()
        super.onCleared()
    }
}
