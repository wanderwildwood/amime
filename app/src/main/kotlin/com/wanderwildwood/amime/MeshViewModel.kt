package com.wanderwildwood.amime

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.amime.ble.BleTransport
import com.wanderwildwood.amime.ble.RadioScanner
import com.wanderwildwood.amime.link.Session
import com.wanderwildwood.amime.mesh.MeshState
import com.wanderwildwood.amime.mesh.MeshStore
import com.wanderwildwood.amime.mesh.Message
import com.wanderwildwood.amime.mesh.MessageLog
import com.wanderwildwood.amime.mesh.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the one radio connection and turns it into state a screen can collect.
 *
 * The store, the session and the transport each know only their own job; this is the only
 * place that knows all three, and the only place that touches Android.
 */
class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow(false)

    /**
     * True once the phone has been asked to pair with a radio it does not know.
     *
     * Worth saying on the screen rather than leaving to the system dialog: the PIN it asks
     * for is the radio's, and where the radio has a screen it is a different six digits after
     * every power cut. Somebody who does not know that tries the phone's own PIN and then
     * concludes the app is broken.
     */
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val log = MessageLog(File(getApplication<Application>().filesDir, "conversations"))

    /**
     * The conversations waiting to be written.
     *
     * A [MutableStateFlow] rather than a queue because it conflates: a burst of twenty
     * messages coming off the radio at once leaves one write to do, which is the right
     * number. The whole log is rewritten each time, which is cheap at the size this gets to
     * and leaves nothing to go wrong that an append could not also get wrong.
     */
    private val toSave = MutableStateFlow<Map<List<Byte>, List<Message>>?>(null)

    private val store = MeshStore(onChange = {
        _state.value = it
        toSave.value = it.conversations
    })

    private val listener = object : Session.Listener by store {
        override fun onProtocolProblem(problem: Session.Problem) {
            _problem.value = when (problem) {
                Session.Problem.HANDSHAKE_SKIPPED -> say(R.string.problem_old_format)
                Session.Problem.FRAME_TRUNCATED -> say(R.string.problem_truncated)
                // Harmless: a frame this app has no use for, from a firmware that has more
                // to say than this one asks about.
                Session.Problem.UNKNOWN_FRAME -> null
            }
        }
    }

    private lateinit var session: Session

    private val transport = BleTransport(
        context = application,
        listener = object : BleTransport.Listener {
            override fun onReady() {
                _pairing.value = false
                session.start()
                session.syncContacts()
                session.refreshBattery()
            }

            override fun onFrame(frame: ByteArray) = session.onFrame(frame)

            override fun onDisconnected() = store.onDisconnected()

            override fun onPairingRequired() {
                _pairing.value = true
            }

            override fun onError(message: String) {
                _problem.value = message
            }
        },
    )

    init {
        session = Session(transport, listener)

        // What survived the last time the process went away. The radio does not keep a copy:
        // a message it has handed over is a message it no longer has.
        val restored = log.read()
        store.restore(restored.conversations, restored.nextId)

        viewModelScope.launch(Dispatchers.IO) {
            var written = restored.conversations
            toSave.filterNotNull().collect { conversations ->
                if (conversations == written) return@collect
                runCatching { log.write(conversations) }
                    .onSuccess { written = conversations }
                    // Worth saying rather than logging: it means this session's messages are
                    // the only copy, and there will be nothing to read tomorrow.
                    .onFailure { _problem.value = say(R.string.problem_not_saving) }
            }
        }
    }

    private fun say(@StringRes line: Int): String = getApplication<Application>().getString(line)

    private val adapter: BluetoothAdapter?
        get() = (getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanner by lazy { RadioScanner(adapter) }

    private val _radios = MutableStateFlow<List<Radio>>(emptyList())

    /** Radios to choose from: the ones already paired, then whatever a scan turns up. */
    val radios: StateFlow<List<Radio>> = _radios.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    data class Radio(
        val device: BluetoothDevice,
        val name: String,
        val bonded: Boolean,
        val rssi: Int? = null,
    )

    /**
     * Look for radios.
     *
     * Bonded ones are listed first and without waiting, because the usual case is the radio
     * this phone already knows. The scan is for the first time, and for a radio that has been
     * reset and forgotten this phone — which looks exactly like a radio that was never here.
     */
    fun findRadios() {
        // A fresh look clears the last complaint: whatever it was, this is the answer to
        // whether it is still true.
        _problem.value = null

        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.name?.startsWith(MESHCORE_PREFIX) == true }
            .map { Radio(it, it.name ?: it.address, bonded = true) }
        _radios.value = bonded

        _scanning.value = true
        scanner.start(
            // The scan stops itself after a few seconds. Without this the screen would go on
            // saying it was looking for as long as the app was open, and the button that
            // starts another one would never come back.
            done = { ending ->
                _scanning.value = false
                when (ending) {
                    RadioScanner.Ending.FINISHED -> Unit
                    RadioScanner.Ending.NO_BLUETOOTH ->
                        _problem.value = say(R.string.problem_bluetooth_off)
                    RadioScanner.Ending.REFUSED ->
                        _problem.value = say(R.string.problem_scan_refused)
                }
            },
        ) { device, name, rssi ->
            if (_radios.value.any { it.device.address == device.address }) return@start
            _radios.value = _radios.value + Radio(
                device = device,
                name = name ?: device.address,
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                rssi = rssi,
            )
        }
    }

    fun stopScanning() {
        scanner.stop()
        _scanning.value = false
    }

    fun connect(device: BluetoothDevice) {
        stopScanning()
        transport.connect(device)
    }

    /**
     * Start administering a repeater.
     *
     * The password is the node's, not this phone's, and a repeater that nobody has touched
     * still has the firmware default.
     */
    fun beginAdmin(person: Person, password: String) {
        store.beginLogin(person)
        session.login(person.publicKey.toByteArray(), password)
    }

    /** Send one CLI line to the node being administered. */
    fun sendCommand(command: String) {
        val admin = state.value.admin ?: return
        store.recordCommand(command)
        session.sendCliCommand(admin.person.prefix.toByteArray(), command)
    }

    fun endAdmin() {
        state.value.admin?.let { session.logout(it.person.publicKey.toByteArray()) }
        store.endAdmin()
    }

    fun send(person: Person, text: String, now: Long = System.currentTimeMillis() / 1000) {
        val prefix = person.prefix.toByteArray()
        store.recordSent(person.prefix, text, now)
        session.sendMessage(prefix, text, now)
    }

    /** Put away the last thing that went wrong, once it has been read. */
    fun dismissProblem() {
        _problem.value = null
    }

    override fun onCleared() {
        scanner.stop()
        transport.disconnect()
        super.onCleared()
    }

    private companion object {
        /** What the firmware puts in front of a node's name when it advertises. */
        const val MESHCORE_PREFIX = "MeshCore-"
    }
}
