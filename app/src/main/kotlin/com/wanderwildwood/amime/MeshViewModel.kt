package com.wanderwildwood.amime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.amime.ble.BleTransport
import com.wanderwildwood.amime.ble.MeshtasticBle
import com.wanderwildwood.amime.link.MeshCoreBackend
import com.wanderwildwood.amime.link.RadioBackend
import com.wanderwildwood.amime.link.RadioKind
import com.wanderwildwood.amime.mesh.Delivery
import com.wanderwildwood.amime.mesh.GeoPoint
import com.wanderwildwood.amime.meshtastic.ChannelKeys
import com.wanderwildwood.amime.meshtastic.ChannelUrl
import com.wanderwildwood.amime.meshtastic.Field
import com.wanderwildwood.amime.meshtastic.ImportPlan
import com.wanderwildwood.amime.meshtastic.MtChannelInfo
import com.wanderwildwood.amime.meshtastic.MtConfigs
import com.wanderwildwood.amime.meshtastic.MtFields
import com.wanderwildwood.amime.meshtastic.MtNode
import com.wanderwildwood.amime.meshtastic.RadioKinds
import com.wanderwildwood.amime.meshtastic.MtSession
import com.wanderwildwood.amime.ble.RadioScanner
import com.wanderwildwood.amime.link.Session
import com.wanderwildwood.amime.mesh.Channel
import com.wanderwildwood.amime.mesh.MeshState
import com.wanderwildwood.amime.mesh.MeshStore
import com.wanderwildwood.amime.mesh.Message
import com.wanderwildwood.amime.mesh.MessageLog
import com.wanderwildwood.amime.mesh.Person
import com.wanderwildwood.amime.protocol.AdvType
import com.wanderwildwood.amime.protocol.Channels
import com.wanderwildwood.amime.protocol.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _connecting = MutableStateFlow<String?>(null)

    /**
     * The radio being connected to, from the tap until it answers.
     *
     * Bonding, a GATT connection, an MTU negotiation and a handshake sit between the two, and
     * several seconds is a normal time for them on E Ink. Without this the tap changes
     * nothing on the screen at all and the only thing left to do is tap it again.
     */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    private var handshakeWatch: Job? = null
    private var ackWatch: Job? = null
    private var batteryWatch: Job? = null

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
    private val toSave = MutableStateFlow<MeshState?>(null)

    private val store = MeshStore(onChange = {
        _state.value = it
        toSave.value = it
        // The radio has said who it is, which is the end of connecting and the end of
        // waiting to hear from it.
        if (it.ready) {
            handshakeWatch?.cancel()
            _connecting.value = null
        }
        watchAcks()
    })

    /**
     * Keep time on messages waiting for an acknowledgement.
     *
     * Somebody has to. The firmware notices its own send timeout and does nothing with it —
     * `onSendTimeout()` is an empty function in the companion build — so a message with no
     * answer would otherwise say it was waiting for one until the app was closed.
     *
     * One loop for all of them, and only while there is something to wait on: a repaint
     * every few seconds with nothing changed in it is a screenful of flicker on E Ink.
     */
    private fun watchAcks() {
        if (ackWatch?.isActive == true || !store.hasAwaitingAcks()) return
        ackWatch = viewModelScope.launch {
            while (store.hasAwaitingAcks()) {
                delay(ACK_CHECK_MS)
                store.expireAwaitingAcks()
            }
        }
    }

    private val listener = object : Session.Listener by store {
        override fun onContactsFull() {
            // Nothing new will appear until something is removed, and an app that says
            // nothing here looks exactly like an app on a mesh with nobody on it.
            _problem.value = say(R.string.problem_contacts_full)
        }

        override fun onChannelSetFailed(index: Int, code: Int) {
            _problem.value = say(R.string.problem_channel_refused)
        }

        override fun onCliCommandDropped(from: List<Byte>) {
            // Only that it happened: the command's text is someone else's and stays out of
            // the log, the same as message text does.
            if (BuildConfig.DEBUG) Log.i(TAG, "dropped a console command from another node")
        }

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

    /** Whichever radio is connected; MeshCore until a Meshtastic one is. */
    private lateinit var backend: RadioBackend
    private var kind = RadioKind.MESHCORE
    private val mtSession: MtSession by lazy { MtSession(mtTransport, mtListener) }

    /** Meshtastic sends waiting on an acknowledgement: packet id to (thread, local id). */
    private val outbox = mutableMapOf<Int, Pair<List<Byte>, Long>>()
    private val names = mutableMapOf<Long, String>()
    private var radioPosition: GeoPoint? = null
    private var typedPosition: GeoPoint? = loadTypedPosition()

    private val transport = BleTransport(
        context = application,
        listener = object : BleTransport.Listener {
            override fun onReady() {
                _pairing.value = false
                // The link is up; whether the radio will talk over it is the next question,
                // and it is a different one.
                watchForHandshake()
                backend.start()
                watchBattery()
            }

            override fun onFrame(frame: ByteArray) = session.onFrame(frame)

            override fun onDisconnected() {
                handshakeWatch?.cancel()
                batteryWatch?.cancel()
                _connecting.value = null
                store.onDisconnected()
            }

            override fun onPairingRequired() {
                _pairing.value = true
            }

            override fun onError(message: String) {
                // Whatever it was, the phone is not in the middle of pairing any more, and
                // it is not in the middle of connecting either. Leaving either line up would
                // have the screen describing something that has already stopped happening.
                _pairing.value = false
                _connecting.value = null
                handshakeWatch?.cancel()
                _problem.value = message
            }
        },
    )

    init {
        session = Session(transport, listener)
        backend = MeshCoreBackend(session)

        // What survived the last time the process went away. The radio does not keep a copy:
        // a message it has handed over is a message it no longer has.
        val restored = log.read()
        store.restore(restored.conversations, restored.nextId, restored.readUpTo)

        viewModelScope.launch(Dispatchers.IO) {
            var written = restored.conversations to restored.readUpTo
            toSave.filterNotNull().collect { saving ->
                val next = saving.conversations to saving.readUpTo
                if (next == written) return@collect
                runCatching { log.write(next.first, next.second) }
                    .onSuccess { written = next }
                    // Worth saying rather than logging: it means this session's messages are
                    // the only copy, and there will be nothing to read tomorrow.
                    .onFailure { _problem.value = say(R.string.problem_not_saving) }
            }
        }
    }

    private fun say(@StringRes line: Int, vararg args: Any): String =
        getApplication<Application>().getString(line, *args)

    /**
     * Whether the two Bluetooth permissions have actually been granted.
     *
     * Both or neither: the app cannot find a radio without SCAN and cannot talk to one
     * without CONNECT, so having one of them is the same as having none.
     */
    private fun mayUseBluetooth(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    ).all {
        ContextCompat.checkSelfPermission(getApplication(), it) == PackageManager.PERMISSION_GRANTED
    }

    /** Say so when the phone was asked and said no, rather than showing an empty room. */
    fun bluetoothRefused() {
        _problem.value = say(R.string.problem_no_permission)
    }

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
        val kind: RadioKind = RadioKind.MESHCORE,
    )

    /**
     * Look for radios.
     *
     * Bonded ones are listed first and without waiting, because the usual case is the radio
     * this phone already knows. The scan is for the first time, and for a radio that has been
     * reset and forgotten this phone — which looks exactly like a radio that was never here.
     */
    // Lint cannot see through [mayUseBluetooth] to the check inside it, and inlining the
    // check at all four call sites to satisfy it would be four copies of one question. Every
    // one of them is also inside a runCatching, so a permission revoked between the check and
    // the call costs a missing radio rather than a crash.
    @SuppressLint("MissingPermission")
    fun findRadios() {
        // A fresh look clears the last complaint: whatever it was, this is the answer to
        // whether it is still true.
        _problem.value = null

        // Asked for rather than assumed. Everything below throws SecurityException without
        // it, and a phone where it was refused would otherwise show an empty list and let
        // the reader conclude there was no radio in the room.
        if (!mayUseBluetooth()) {
            _problem.value = say(R.string.problem_no_permission)
            return
        }

        val bonded = runCatching {
            adapter?.bondedDevices.orEmpty()
                .filter { RadioKinds.byName(it.name) != null }
                .map { Radio(it, it.name ?: it.address, bonded = true, kind = RadioKinds.byName(it.name)!!) }
        }.getOrDefault(emptyList())
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
        ) { device, name, rssi, radioKind ->
            if (_radios.value.any { it.device.address == device.address }) return@start
            _radios.value = _radios.value + Radio(
                device = device,
                name = name ?: device.address,
                bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
                    .getOrDefault(false),
                rssi = rssi,
                kind = radioKind,
            )
        }
    }

    fun stopScanning() {
        scanner.stop()
        _scanning.value = false
    }

    /**
     * Connect to one radio.
     *
     * Takes the [Radio] rather than its device because the name has already been read once,
     * while the permission was being held for the scan that found it; reading it again here
     * is a second call that can be refused for no gain.
     */
    fun connect(radio: Radio) {
        stopScanning()
        _problem.value = null
        _connecting.value = radio.name
        kind = radio.kind
        lastDevice = radio.device
        if (kind == RadioKind.MESHTASTIC) {
            backend = mtSession
            names.clear()
            mtTransport.connect(radio.device)
        } else {
            backend = MeshCoreBackend(session)
            transport.connect(radio.device)
        }
    }

    /**
     * Give up waiting for the radio to say who it is.
     *
     * There is a state the firmware allows that looks exactly like a working connection from
     * here: an app that has not been authenticated gets a link that accepts every command,
     * processes it, and drops every reply — `deviceConnected` is set in one place and
     * `writeFrame` refuses while it is false. The GATT side is healthy, the handshake goes
     * out, and nothing ever comes back. Without this the screen would sit on the radio list
     * forever with no account of itself.
     */
    private fun watchForHandshake() {
        handshakeWatch?.cancel()
        handshakeWatch = viewModelScope.launch {
            delay(HANDSHAKE_PATIENCE_MS)
            if (!state.value.ready) {
                _connecting.value = null
                _problem.value = say(R.string.problem_no_answer)
                transport.disconnect()
                mtTransport.disconnect()
            }
        }
    }

    /**
     * Start administering a repeater.
     *
     * The password is the node's, not this phone's, and a repeater that nobody has touched
     * still has the firmware default.
     */
    fun beginAdmin(person: Person, password: String) {
        // A login is addressed by the whole key, and a contact that arrived without one
        // cannot be logged in to. The command would refuse the argument by throwing, which
        // on a press is a crash rather than an answer.
        if (person.publicKey.size != Sizes.PUB_KEY) {
            _problem.value = say(R.string.problem_no_key)
            return
        }
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

    /**
     * Throw away the route to somebody, when a message went out along it and nothing came
     * back. The next one floods and finds its own way.
     */
    fun forgetRoute(person: Person) {
        if (person.publicKey.size != Sizes.PUB_KEY) {
            _problem.value = say(R.string.problem_no_key)
            return
        }
        session.resetPath(person.publicKey.toByteArray())
        // The radio does not report this back — it leaves the contact's lastmod alone — so
        // the app's own copy is put right here or not at all.
        store.forgetRoute(person.prefix)
    }

    /** Everything in this thread has been seen, on the way in and again on the way out. */
    fun markRead(person: Person) = store.markRead(person.prefix)

    /**
     * Ask the radio how much battery it has left, now and then.
     *
     * It was asked once, at the moment of connecting, and the answer then sat in the bar for
     * as long as the app was open — which is fine at a desk and wrong on a walk, where the
     * reading is the one that matters and the walk is hours long.
     *
     * The state only reaches the screen when it changes, so a reading identical to the last
     * costs nothing: an equal value is not emitted and nothing repaints.
     */
    private fun watchBattery() {
        batteryWatch?.cancel()
        batteryWatch = viewModelScope.launch {
            while (true) {
                delay(BATTERY_INTERVAL_MS)
                if (!state.value.ready) return@launch
                if (kind == RadioKind.MESHCORE) session.refreshBattery()
            }
        }
    }

    /**
     * Send a message again, where the first one went out and nothing came back.
     *
     * Where the radio has a route to them, that route is thrown away first: a message that
     * went unanswered along a known path is most likely a path that no longer exists, and
     * sending the same way again would be the same silence twice. Without one it already
     * floods, and there is nothing to forget.
     */
    fun sendAgain(
        person: Person,
        message: Message,
        now: Long = System.currentTimeMillis() / 1000,
    ) {
        if (person.pathKnown && person.publicKey.size == Sizes.PUB_KEY) {
            session.resetPath(person.publicKey.toByteArray())
            store.forgetRoute(person.prefix)
        }
        val attempt = store.recordResend(person.prefix, message.id) ?: return
        session.sendMessage(person.prefix.toByteArray(), message.text, now, attempt)
    }

    /** Tell the mesh this radio is here, so that somebody can write to it. */
    fun announce() = session.advertise(flood = true)

    fun send(person: Person, text: String, now: Long = System.currentTimeMillis() / 1000) {
        if (kind == RadioKind.MESHTASTIC) {
            val until = System.currentTimeMillis() + MT_ACK_WAIT_MS
            val id = store.mtSent(person.prefix, text, now, Delivery.AWAITING_ACK, until)
            val packet = mtSession.sendText(MtSession.numOf(person.prefix), 0, text)
            outbox[packet] = person.prefix to id
            return
        }
        val prefix = person.prefix.toByteArray()
        store.recordSent(person.prefix, text, now)
        session.sendMessage(prefix, text, now)
    }

    /**
     * Join a channel from what was typed: a name alone for a hashtag channel, a name and a
     * key for a private one. Returns why not, as a line to show, or null once it has gone to
     * the radio — which answers in a moment, and the channel appears when it does.
     */
    fun joinChannel(name: String, key: String): String? {
        val join = Channels.join(name, key)
        if (join !is Channels.Join.Ok) {
            return say(
                when (join) {
                    Channels.Join.NameTooLong -> R.string.join_name_too_long
                    Channels.Join.BadKey -> R.string.join_bad_key
                    else -> R.string.join_no_name
                },
            )
        }
        val current = state.value
        // Already held: nothing to write, and a second slot with the same key would only
        // mean every message on it arrived under whichever slot the radio found first.
        if (current.channels.any { it.secret == join.secret.toList() }) return null
        val slot = current.freeChannelSlots.firstOrNull() ?: return say(R.string.join_no_room)
        session.setChannel(slot, join.name, join.secret)
        return null
    }

    /** Leave a channel. Its messages stay on this phone, as a forgotten contact's do. */
    fun leaveChannel(channel: Channel) = session.leaveChannel(channel.index)

    fun sendToChannel(channel: Channel, text: String, now: Long = System.currentTimeMillis() / 1000) {
        if (kind == RadioKind.MESHTASTIC) {
            // A channel has no acknowledgement; handed to the radio is as settled as it gets.
            store.mtSent(channel.key, text, now, Delivery.SENT)
            mtSession.sendChannel(channel.index, text, now)
            return
        }
        store.recordChannelSent(channel.key, text, now)
        session.sendChannelMessage(channel.index, text, now)
    }

    fun markRead(channel: Channel) = store.markRead(channel.key)

    /** Put away the last thing that went wrong, once it has been read. */
    fun dismissProblem() {
        _problem.value = null
    }

    override fun onCleared() {
        handshakeWatch?.cancel()
        ackWatch?.cancel()
        batteryWatch?.cancel()
        scanner.stop()
        transport.disconnect()
        mtTransport.disconnect()
        // The writer runs in this scope and the scope is about to be cancelled, so anything
        // that arrived in the last moment would go with it. A few kilobytes written on the
        // way out is the cheapest way to make the last message as safe as the rest.
        runCatching { log.write(state.value.conversations, state.value.readUpTo) }
        super.onCleared()
    }

    // ---- Meshtastic ----

    private val mtTransport: MeshtasticBle = MeshtasticBle(
        context = application,
        listener = object : MeshtasticBle.Listener {
            override fun onReady() {
                _pairing.value = false
                watchForHandshake()
                backend.start()
            }

            override fun onRead(bytes: ByteArray) = mtSession.onRead(bytes)

            override fun onFromNum() = mtSession.onFromNum()

            override fun onReadStalled(): Boolean = mtSession.onReadStalled()

            override fun onDisconnected() {
                handshakeWatch?.cancel()
                _connecting.value = null
                store.onDisconnected()
            }

            override fun onPairingRequired() {
                _pairing.value = true
            }

            override fun onError(message: String) {
                _pairing.value = false
                _connecting.value = null
                handshakeWatch?.cancel()
                _problem.value = message
            }

            override fun onOtherKind() {
                // The service list is the final word: this one speaks MeshCore.
                mtTransport.disconnect()
                kind = RadioKind.MESHCORE
                backend = MeshCoreBackend(session)
                lastDevice?.let { transport.connect(it) }
            }
        },
    )

    private var lastDevice: BluetoothDevice? = null

    private val mtListener: MtSession.Listener = object : MtSession.Listener {
        override fun onReady(name: String, firmware: String) {
            store.mtReady(name)
            (typedPosition ?: radioPosition)?.let(store::mtHere)
        }

        override fun onNode(node: MtNode) {
            names[node.num] = node.label
            store.mtNode(
                Person(
                    prefix = MtSession.keyOf(node.num),
                    name = node.label,
                    type = AdvType.CHAT,
                    pathKnown = node.hops != null,
                    lastHeard = node.lastHeard,
                    battery = node.battery,
                    snr = node.snr,
                    hops = node.hops,
                    latitude = node.latitude,
                    longitude = node.longitude,
                ),
            )
        }

        override fun onSelfPosition(latitude: Double, longitude: Double) {
            radioPosition = GeoPoint(latitude, longitude)
            if (typedPosition == null) store.mtHere(radioPosition)
        }

        override fun onChannel(index: Int, name: String, enabled: Boolean, primary: Boolean) =
            store.mtChannel(index, name, enabled)

        override fun onChannelsLoaded() = store.mtChannelsLoaded()

        override fun onRegion(region: Int) = store.mtRegion(region)

        override fun onChannelInfos(channels: List<MtChannelInfo>) = store.mtChannelInfos(channels)

        override fun onSettings(configs: MtConfigs) = store.mtConfigs(configs)

        override fun onText(from: Long, channel: Int?, text: String, time: Long, snr: Float?, hops: Int?) {
            if (channel == null) {
                store.mtReceived(MtSession.keyOf(from), text, time, snr, hops?.let { it == 0 })
            } else {
                // A channel thread shows "name: words", so the sender's name goes in front.
                val who = names[from] ?: "!%08x".format(from)
                store.mtReceived(MtSession.channelKey(channel), "$who: $text", time, snr, hops?.let { it == 0 })
            }
        }

        override fun onAck(packetId: Int, fromDestination: Boolean, error: Int) {
            val (key, id) = outbox[packetId] ?: return
            when {
                error != 0 -> {
                    outbox.remove(packetId)
                    store.mtSettle(key, id, Delivery.REFUSED, failure = error)
                }
                fromDestination -> {
                    outbox.remove(packetId)
                    store.mtSettle(key, id, Delivery.ACKNOWLEDGED)
                }
                // A repeater took it on; the message keeps waiting for the recipient.
            }
        }

        override fun onTooOld(firmware: String) {
            store.mtTooOld(firmware)
            _problem.value = say(R.string.problem_mt_old, firmware)
        }

        override fun onMalformed() {
            _problem.value = say(R.string.problem_truncated)
        }
    }

    /**
     * Set the radio's region. Returns false when the radio has not yet reported its LoRa
     * configuration. The node saves and restarts, which drops the link.
     */
    fun setRegion(code: Int): Boolean = mtSession.setRegion(code)

    private val _nearestFirst = MutableStateFlow(
        runCatching {
            getApplication<Application>().getSharedPreferences("position", Context.MODE_PRIVATE).getBoolean("nearest_first", false)
        }.getOrDefault(false),
    )

    /** Whether the people list puts the nearest first; off until asked. */
    val nearestFirst: StateFlow<Boolean> = _nearestFirst

    fun toggleNearestFirst() {
        val on = !_nearestFirst.value
        _nearestFirst.value = on
        getApplication<Application>().getSharedPreferences("position", Context.MODE_PRIVATE).edit().putBoolean("nearest_first", on).apply()
    }

    // ---- Meshtastic channels and settings: each is a pass-through to the session ----

    /** Change one setting; see [MtSession.change]. */
    fun changeSetting(field: Field, value: Any): MtFields.Applied = mtSession.change(field, value)

    fun saveChannel(index: Int, name: String, key: okio.ByteString?, uplink: Boolean, downlink: Boolean): Boolean =
        mtSession.saveChannel(index, name, key, uplink, downlink)

    /** Add a secondary channel in the first free slot; the slot, or -1. */
    fun addChannel(name: String, key: okio.ByteString, uplink: Boolean, downlink: Boolean): Int =
        mtSession.addChannel(name, key, uplink, downlink)

    fun deleteChannel(index: Int): Boolean = mtSession.deleteChannel(index)

    /** What a pasted or scanned link would do, or null when the text is not a channel link. */
    fun planImport(text: String): ImportPlan? = ChannelUrl.parse(text)?.let(mtSession::planImport)

    fun applyImport(plan: ImportPlan): Int = mtSession.applyImport(plan)

    fun applyLoraFrom(plan: ImportPlan): Boolean = plan.lora?.let(mtSession::applyLoraFrom) ?: false

    /** The link for this radio's channels, to draw as a QR code; null when none are read. */
    fun channelLink(withRadioSettings: Boolean): String? =
        mtSession.channelSet(withRadioSettings)?.let { ChannelUrl.encode(it, channelsOnly = !withRadioSettings) }

    fun randomKey(): okio.ByteString = ChannelKeys.random()

    fun setFixedPosition(point: GeoPoint): Boolean = mtSession.setFixedPosition(point.latitude, point.longitude)

    fun clearFixedPosition(): Boolean = mtSession.clearFixedPosition()

    /** The position typed in, if any (not the radio's own fix). */
    val typedHere: GeoPoint? get() = typedPosition

    /** Where the user says they are, for distances when the radio has no fix of its own. */
    fun setTypedPosition(point: GeoPoint?) {
        typedPosition = point
        getApplication<Application>().getSharedPreferences("position", Context.MODE_PRIVATE).edit().apply {
            if (point == null) clear() else {
                putString("lat", point.latitude.toString())
                putString("lon", point.longitude.toString())
            }
        }.apply()
        store.mtHere(point ?: radioPosition)
    }

    private fun loadTypedPosition(): GeoPoint? = runCatching {
        val prefs = getApplication<Application>().getSharedPreferences("position", Context.MODE_PRIVATE)
        val lat = prefs.getString("lat", null)?.toDouble()
        val lon = prefs.getString("lon", null)?.toDouble()
        if (lat != null && lon != null) GeoPoint(lat, lon) else null
    }.getOrNull()

    private companion object {
        const val TAG = "amime.mesh"

        /** How long a direct message may wait for its acknowledgement; the radio retries three times in that. */
        const val MT_ACK_WAIT_MS = 120_000L

        /** What the firmware puts in front of a node's name when it advertises. */
        const val MESHCORE_PREFIX = "MeshCore-"

        /**
         * How long to wait for the radio to answer the handshake.
         *
         * Generous on purpose: bonding, an MTU negotiation and service discovery all happen
         * first, and a phone that has just been woken is slower than one in hand.
         */
        const val HANDSHAKE_PATIENCE_MS = 12_000L

        /**
         * How often to look at what is still waiting.
         *
         * Coarse on purpose. The radio's estimates are in the tens of seconds for a direct
         * path and minutes for a flood, so a finer check would buy nothing and cost a
         * repaint.
         */
        const val ACK_CHECK_MS = 5_000L

        /**
         * How often to ask the radio about its battery.
         *
         * A cell does not move quickly and each ask is a frame each way, so this is coarse.
         * It exists for the walk rather than the desk.
         */
        const val BATTERY_INTERVAL_MS = 5 * 60_000L
    }
}
