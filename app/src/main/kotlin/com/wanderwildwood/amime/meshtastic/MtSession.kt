package com.wanderwildwood.amime.meshtastic

import android.util.Log
import com.wanderwildwood.amime.link.RadioBackend
import com.wanderwildwood.amime.link.RadioKind
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.AdminMessage
import okio.ByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

/** What the session needs of the Bluetooth link underneath it. */
interface MtTransport {
    /** Write one ToRadio message. */
    fun write(toRadio: ByteArray)

    /**
     * Ask for one read of FromRadio, after [delayMs]. The answer comes back through
     * [MtSession.onRead]; an empty answer means nothing is waiting.
     */
    fun read(delayMs: Long = 0)
}

/** One node as this app keeps it, merged from the handshake and from later packets. */
data class MtNode(
    val num: Long,
    val longName: String = "",
    val shortName: String = "",
    /** Seconds since the epoch, 0 when never heard. */
    val lastHeard: Long = 0,
    /** 0..100, 101 when powered from outside; null when unknown. */
    val battery: Int? = null,
    val snr: Float? = null,
    /** Hops the last packet took, or null when the radio could not tell. */
    val hops: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val label: String get() = longName.ifBlank { shortName }.ifBlank { "!%08x".format(num) }
}

/**
 * Drives the Meshtastic client protocol over an [MtTransport].
 *
 * Like the MeshCore session it has no Android, no coroutines and no threads in it, so the
 * handshake and the read loop can be tested with a fake radio. It is driven from one place.
 *
 * Meshtastic differs from MeshCore in how data arrives: the radio notifies a counter, and
 * the app then reads FromRadio over and over until an empty read says it is drained. During
 * the handshake the firmware holds the notifications back, so an empty read before the
 * handshake is complete is retried rather than believed.
 */
class MtSession(
    private val transport: MtTransport,
    private val listener: Listener,
    /** Seconds since the epoch. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val configId: Int = 0x4D4D,
    firstPacketId: Int = 0x1000,
) : RadioBackend {

    interface Listener {
        /** The handshake is complete. [name] is this node's own long name. */
        fun onReady(name: String, firmware: String) {}

        /** A node, new or changed. Never this radio's own node. */
        fun onNode(node: MtNode) {}

        /** This radio's own position, when it has a fix. */
        fun onSelfPosition(latitude: Double, longitude: Double) {}

        /** One channel slot: an empty [name] is the default channel, shown as its preset. */
        fun onChannel(index: Int, name: String, enabled: Boolean, primary: Boolean) {}

        /** All the channels the handshake carries have arrived. */
        fun onChannelsLoaded() {}

        /** The radio's LoRa region, as the enum value; 0 means none chosen yet. */
        fun onRegion(region: Int) {}

        /**
         * A text arrived. [channel] is the slot for a channel message and null for a direct
         * one. [hops] is null when the sender's radio did not say how far it had come.
         */
        fun onText(from: Long, channel: Int?, text: String, time: Long, snr: Float?, hops: Int?) {}

        /**
         * The answer to a message sent here. [error] is a [Routing.Error] value, 0 for an
         * acknowledgement. [fromDestination] is false when it only means that a repeater
         * took the message on.
         */
        fun onAck(packetId: Int, fromDestination: Boolean, error: Int) {}

        /** The channel slots as the radio has them now, all of them, disabled ones included. */
        fun onChannelInfos(channels: List<MtChannelInfo>) {}

        /** The radio's settings, as read or as just changed. */
        fun onSettings(configs: MtConfigs) {}

        /** The radio answered a settings or channel change: true when it took it. */
        fun onAdminAnswer(ok: Boolean) {}

        /** The firmware is too old to be worked with. */
        fun onTooOld(firmware: String) {}

        /** Something arrived that could not be read. */
        fun onMalformed() {}
    }

    override val kind: RadioKind get() = RadioKind.MESHTASTIC

    /** This node's number, from MyNodeInfo. */
    var myNum: Long = 0
        private set
    var ready: Boolean = false
        private set

    /** What the radio has said of its settings; changed as the user changes them. */
    var configs: MtConfigs = MtConfigs()
        private set
    private val adminSent = mutableSetOf<Int>()
    private val lora: Config.LoRaConfig? get() = configs.lora
    private var firmware = ""
    private var draining = false
    private var again = false
    private var emptyReads = 0
    private var stalls = 0
    private var nextId = if (firstPacketId == 0) 1 else firstPacketId
    private val nodes = mutableMapOf<Long, MtNode>()
    private val channels = mutableMapOf<Int, org.meshtastic.proto.Channel>()
    private val seen = ArrayDeque<Int>()

    // ---- driving it ----

    override fun start() {
        ready = false
        channels.clear()
        emptyReads = 0
        // A drain left over from a link that dropped mid-read never gets its callback; if the
        // flag survived into this connection every drain() would see "already draining" and
        // not read at all.
        draining = false
        again = false
        stalls = 0
        note("want_config sent")
        transport.write(ToRadio(want_config_id = configId).encode())
        drain()
    }

    /** FromNum changed: something is waiting. */
    fun onFromNum() {
        note("FromNum notify (draining=$draining)")
        drain()
    }

    /**
     * A read was given up on because it never called back. Without this the loop would wait
     * for an answer that is not coming, and every later notify would only set [again].
     * Tries again a few times; returns false when it has given up.
     */
    fun onReadStalled(): Boolean {
        note("read stalled (#${stalls + 1})")
        draining = false
        again = false
        if (stalls >= MAX_STALLS) return false
        stalls++
        drain()
        return true
    }

    /** The answer to a [MtTransport.read]. */
    fun onRead(bytes: ByteArray) {
        note("read: ${bytes.size} bytes, ready=$ready, empty=$emptyReads")
        if (bytes.isEmpty()) {
            if (!ready && emptyReads < MAX_EMPTY_BEFORE_READY) {
                // The firmware is still working through the handshake, or has not yet seen
                // the request: nothing here is the end until config_complete says so.
                emptyReads++
                transport.read(RETRY_MS)
                return
            }
            draining = false
            if (again) {
                again = false
                drain()
            }
            return
        }
        emptyReads = 0
        stalls = 0
        val message = runCatching { FromRadio.ADAPTER.decode(bytes) }.getOrNull()
        if (message == null) listener.onMalformed() else {
            note("frame: ${kindOf(message)}")
            handle(message)
        }
        transport.read(0)
    }

    /** Which part of a FromRadio is set: the type only, never what it carries. */
    private fun kindOf(m: FromRadio): String = when {
        m.my_info != null -> "my_info"
        m.node_info != null -> "node_info"
        m.channel != null -> "channel"
        m.config != null -> "config"
        m.metadata != null -> "metadata"
        m.packet != null -> "packet"
        m.config_complete_id != null -> "config_complete"
        else -> "other"
    }

    private fun note(text: String) {
        try { Log.i("MtBle", "session: $text") } catch (_: Throwable) { }
    }

    private fun drain() {
        if (draining) {
            // A notify during a drain: the loop will notice by reading on, but a message
            // that landed just after the empty read would be missed, so go round once more.
            again = true
            return
        }
        draining = true
        transport.read(0)
    }

    // ---- what the radio says ----

    private fun handle(m: FromRadio) {
        m.my_info?.let { myNum = it.my_node_num.toLong() and MASK }
        m.node_info?.let(::handleNodeInfo)
        m.channel?.let { c ->
            channels[c.index] = c
            // The modem preset arrives in CONFIG, after the channels, and names the default
            // channel; so during the handshake they wait, and are told at the end.
            if (ready) {
                emitChannel(c)
                listener.onChannelInfos(channelInfos())
            }
        }
        m.config?.let { c ->
            configs = configs.with(c)
            c.lora?.let { listener.onRegion(it.region.value) }
            if (ready) listener.onSettings(configs)
        }
        m.moduleConfig?.let {
            configs = configs.with(it)
            if (ready) listener.onSettings(configs)
        }
        m.metadata?.let {
            firmware = it.firmware_version
            if (isTooOld(firmware)) listener.onTooOld(firmware)
        }
        m.packet?.let(::handlePacket)
        m.config_complete_id?.let { id ->
            if (id == configId && !ready) {
                note("config_complete")
                ready = true
                channels.values.sortedBy { it.index }.forEach(::emitChannel)
                listener.onChannelsLoaded()
                listener.onChannelInfos(channelInfos())
                listener.onSettings(configs)
                listener.onReady(nodes[myNum]?.label.orEmpty(), firmware)
            }
        }
    }

    private fun emitChannel(c: org.meshtastic.proto.Channel) {
        val name = c.settings?.name.orEmpty().ifEmpty {
            if (c.role == org.meshtastic.proto.Channel.Role.PRIMARY) presetName() else "Channel ${c.index}"
        }
        listener.onChannel(
            index = c.index,
            name = name,
            enabled = c.role != org.meshtastic.proto.Channel.Role.DISABLED,
            primary = c.role == org.meshtastic.proto.Channel.Role.PRIMARY,
        )
    }

    /** The default channel has no name of its own: it is shown as the modem preset, LongFast and so on. */
    private fun presetName(): String =
        lora?.modem_preset?.let { presetLabel(it) } ?: "LongFast"

    private fun handleNodeInfo(info: NodeInfo) {
        val num = info.num.toLong() and MASK
        val old = nodes[num] ?: MtNode(num)
        val pos = info.position
        if ((info.num.toLong() and MASK) == myNum && info.user != null) configs = configs.copy(owner = info.user)
        val node = old.copy(
            longName = info.user?.long_name?.takeIf { it.isNotEmpty() } ?: old.longName,
            shortName = info.user?.short_name?.takeIf { it.isNotEmpty() } ?: old.shortName,
            lastHeard = maxOf(old.lastHeard, info.last_heard.toLong() and MASK),
            battery = info.device_metrics?.battery_level ?: old.battery,
            snr = if (info.last_heard != 0) info.snr else old.snr,
            hops = info.hops_away ?: old.hops,
            latitude = pos?.latitude_i?.let { it / 1e7 } ?: old.latitude,
            longitude = pos?.longitude_i?.let { it / 1e7 } ?: old.longitude,
        )
        publish(node)
    }

    private fun publish(node: MtNode) {
        nodes[node.num] = node
        if (node.num == myNum) {
            if (node.latitude != null && node.longitude != null && (node.latitude != 0.0 || node.longitude != 0.0)) {
                listener.onSelfPosition(node.latitude, node.longitude)
            }
            // Our own row is the name of the screen, not a person in the list.
            return
        }
        listener.onNode(node)
    }

    private fun handlePacket(p: MeshPacket) {
        val d = p.decoded ?: return // encrypted for a key the radio does not hold: nothing to read
        val from = p.from.toLong() and MASK
        val to = p.to.toLong() and MASK
        val hops = if (p.hop_start != 0) (p.hop_start - p.hop_limit).coerceAtLeast(0) else null
        val heard = p.rx_time?.toLong()?.and(MASK)?.takeIf { it != 0L } ?: clock()
        when (d.portnum) {
            PortNum.TEXT_MESSAGE_APP -> {
                if (from == myNum || isRepeat(p.id)) return
                val text = d.payload.utf8()
                val snr = p.rx_snr.takeIf { it != 0f }
                if (to == BROADCAST) {
                    listener.onText(from, p.channel, text, heard, snr, hops)
                } else if (to == myNum) {
                    listener.onText(from, null, text, heard, snr, hops)
                }
                touch(from, heard, snr, hops)
            }

            PortNum.NODEINFO_APP -> {
                val user = runCatching { User.ADAPTER.decode(d.payload) }.getOrNull() ?: return
                val old = nodes[from] ?: MtNode(from)
                publish(
                    old.copy(
                        longName = user.long_name.ifEmpty { old.longName },
                        shortName = user.short_name.ifEmpty { old.shortName },
                        lastHeard = maxOf(old.lastHeard, heard),
                        snr = p.rx_snr.takeIf { it != 0f } ?: old.snr,
                        hops = hops ?: old.hops,
                    ),
                )
            }

            // 2.8 firmware replays cached positions as ordinary packets after the handshake,
            // 2.7 puts them in NodeInfo. Both end up here or in handleNodeInfo, and look the same.
            PortNum.POSITION_APP -> {
                val pos = runCatching { Position.ADAPTER.decode(d.payload) }.getOrNull() ?: return
                val lat = pos.latitude_i ?: return
                val lon = pos.longitude_i ?: return
                val old = nodes[from] ?: MtNode(from)
                publish(
                    old.copy(
                        latitude = lat / 1e7,
                        longitude = lon / 1e7,
                        lastHeard = maxOf(old.lastHeard, heard),
                    ),
                )
            }

            PortNum.TELEMETRY_APP -> {
                val t = runCatching { Telemetry.ADAPTER.decode(d.payload) }.getOrNull() ?: return
                val level = t.device_metrics?.battery_level ?: return
                val old = nodes[from] ?: MtNode(from)
                publish(old.copy(battery = level, lastHeard = maxOf(old.lastHeard, heard)))
            }

            PortNum.ADMIN_APP -> {
                // The radio answers our own request: from is its own number, or 0 when it
                // did not stamp one. An answer to a request we sent is believed whoever it
                // says it is from; anything else must come from this radio.
                val asked = readRequests.remove(d.request_id)
                if (asked == null && from != myNum && from != 0L) return
                val admin = runCatching { AdminMessage.ADAPTER.decode(d.payload) }.getOrNull()
                note("admin answer: from=${if (from == myNum) "self" else if (from == 0L) "none" else "other"} " +
                    "asked=${asked != null} ${if (admin?.get_channel_response != null) "channel" else "other"}")
                admin ?: return
                admin.get_channel_response?.let { got ->
                    // The slot asked for is the slot filled, whatever index the answer carries.
                    val c = if (asked != null && got.index != asked) got.copy(index = asked) else got
                    channels[c.index] = c
                    emitChannel(c)
                    listener.onChannelInfos(channelInfos())
                }
            }

            PortNum.ROUTING_APP -> {
                if (d.request_id == 0) return
                val routing = runCatching { Routing.ADAPTER.decode(d.payload) }.getOrNull() ?: return
                val error = routing.error_reason?.value ?: 0
                if (adminSent.remove(d.request_id)) {
                    listener.onAdminAnswer(error == 0)
                    return
                }
                val expected = pending[d.request_id]
                // An acknowledgement from anyone but the destination is a repeater saying it
                // took the message on, which is not the same as it arriving, so it keeps
                // waiting. A refusal is final whoever says it.
                val fromDestination = expected == null || expected == from
                if (fromDestination || error != 0) pending.remove(d.request_id)
                listener.onAck(d.request_id, fromDestination, error)
            }

            else -> Unit
        }
    }

    private fun touch(from: Long, time: Long, snr: Float?, hops: Int?) {
        val old = nodes[from] ?: return
        publish(old.copy(lastHeard = maxOf(old.lastHeard, time), snr = snr ?: old.snr, hops = hops ?: old.hops))
    }

    private fun isRepeat(id: Int): Boolean {
        if (id == 0) return false
        if (id in seen) return true
        seen.addLast(id)
        if (seen.size > 64) seen.removeFirst()
        return false
    }

    // ---- what this app says ----

    /** Where each message waiting for an acknowledgement is going. */
    private val pending = mutableMapOf<Int, Long>()

    /** Packet id of each channel read-back asked for, and the slot it was for. */
    private val readRequests = mutableMapOf<Int, Int>()

    /**
     * Send text to a node, or to a channel when [to] is [BROADCAST]. Returns the packet id,
     * which the acknowledgement quotes. The radio does all the encryption.
     */
    fun sendText(to: Long, channel: Int, text: String): Int {
        val id = nextPacketId()
        val broadcast = to == BROADCAST
        if (!broadcast) pending[id] = to
        val packet = MeshPacket(
            to = to.toInt(),
            channel = if (broadcast) channel else 0,
            id = id,
            want_ack = !broadcast,
            decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = text.encodeUtf8()),
        )
        transport.write(ToRadio(packet = packet).encode())
        return id
    }

    override fun sendDirect(to: List<Byte>, publicKey: List<Byte>, text: String, now: Long, attempt: Int) {
        sendText(numOf(to), 0, text)
    }

    override fun sendChannel(index: Int, text: String, now: Long) {
        sendText(BROADCAST, index, text)
    }

    /** Whether the last [sendText] to this node is still waiting for an answer. */
    fun waitingOn(packetId: Int): Boolean = packetId in pending

    /**
     * Set the LoRa region. Sends the whole current LoRa config with only the region changed,
     * because the radio replaces the block it is given and a partial one zeroes the rest.
     * The node saves and restarts, which drops the link. Returns false when the current
     * config has not been read yet.
     */
    fun setRegion(code: Int): Boolean {
        val current = lora ?: return false
        val region = Config.LoRaConfig.RegionCode.fromValue(code) ?: return false
        configs = configs.copy(lora = current.copy(region = region))
        sendAdmin(AdminMessage(set_config = Config(lora = configs.lora)))
        return true
    }

    /** Send an admin message to this node itself, asking for an answer. Returns the packet id. */
    internal fun sendAdmin(admin: AdminMessage, track: Boolean = true, wantResponse: Boolean = false): Int {
        val id = nextPacketId()
        if (track) adminSent += id
        val packet = MeshPacket(
            to = myNum.toInt(),
            id = id,
            want_ack = true,
            decoded = Data(portnum = PortNum.ADMIN_APP, payload = admin.encodeByteString(), want_response = wantResponse),
        )
        transport.write(ToRadio(packet = packet).encode())
        return id
    }

    // ---- settings ----

    /**
     * Change one setting. The whole section it lives in goes to the radio with only this
     * field changed; the radio replaces a section it is given. Nothing is sent when the
     * settings have not been read, when the value is refused, or when this is only shown.
     */
    fun change(field: Field, value: Any): MtFields.Applied {
        val result = if (!ready) MtFields.Applied.NotRead else MtFields.apply(field, value, configs)
        if (result is MtFields.Applied.Change) {
            configs = result.configs
            sendAdmin(result.admin)
            listener.onSettings(configs)
        }
        return result
    }

    /** Set the radio's fixed position and make it use it. The radio saves it. */
    fun setFixedPosition(latitude: Double, longitude: Double): Boolean {
        if (!ready) return false
        sendAdmin(AdminMessage(set_fixed_position = Position(latitude_i = Math.round(latitude * 1e7).toInt(), longitude_i = Math.round(longitude * 1e7).toInt(), time = (clock() and 0xFFFFFFFFL).toInt())))
        configs.position?.let { configs = configs.copy(position = it.copy(fixed_position = true)) }
        listener.onSettings(configs)
        return true
    }

    /** Stop using a fixed position. */
    fun clearFixedPosition(): Boolean {
        if (!ready) return false
        sendAdmin(AdminMessage(remove_fixed_position = true))
        configs.position?.let { configs = configs.copy(position = it.copy(fixed_position = false)) }
        listener.onSettings(configs)
        return true
    }

    // ---- channels ----

    /** All the channel slots, as the radio last said them. */
    fun channelInfos(): List<MtChannelInfo> =
        channels.values.sortedBy { it.index }.map { (it.settings ?: ChannelSettings()).info(it.index, it.role.value) }

    /** The channels that are on, as a [ChannelSet] to share. The radio settings ride along when [withLora]. */
    fun channelSet(withLora: Boolean): ChannelSet? {
        val on = channels.values.filter { it.role != org.meshtastic.proto.Channel.Role.DISABLED }.sortedBy { it.index }
        if (on.isEmpty()) return null
        return ChannelSet(
            settings = on.map { it.settings ?: ChannelSettings() },
            lora_config = if (withLora) lora else null,
        )
    }

    /** The first free slot after the primary, or -1 when all eight are taken. */
    fun freeSlot(): Int = (1 until MAX_CHANNELS).firstOrNull { i ->
        (channels[i]?.role ?: org.meshtastic.proto.Channel.Role.DISABLED) == org.meshtastic.proto.Channel.Role.DISABLED
    } ?: -1

    /**
     * Write channel [index]. Only what is given changes: a null [psk] keeps the key, and
     * everything the radio holds about the channel that this app does not show is kept too.
     * Asks the radio for the slot back afterwards so what is shown is what it holds.
     * Returns false when the slot is unknown or the name or key is not one the radio accepts.
     */
    fun saveChannel(
        index: Int,
        name: String,
        psk: ByteString?,
        uplink: Boolean,
        downlink: Boolean,
        role: org.meshtastic.proto.Channel.Role? = null,
    ): Boolean {
        if (!ready || index !in 0 until MAX_CHANNELS) return false
        if (name.toByteArray().size > CHANNEL_NAME_BYTES) return false
        val old = channels[index] ?: org.meshtastic.proto.Channel(index = index)
        val newRole = role ?: old.role
        if (index == 0 && newRole != org.meshtastic.proto.Channel.Role.PRIMARY) return false
        if (index != 0 && newRole == org.meshtastic.proto.Channel.Role.PRIMARY) return false
        val base = old.settings ?: ChannelSettings()
        val settings = base.copy(name = name, psk = psk ?: base.psk, uplink_enabled = uplink, downlink_enabled = downlink)
        return put(old.copy(index = index, settings = settings, role = newRole))
    }

    /**
     * Put a new secondary channel in the first free slot. Position sharing is switched off on
     * it: a channel from somebody else's link is not one to broadcast where you are on.
     * Returns the slot, or -1 when there is no room or the name or key is refused.
     */
    fun addChannel(name: String, psk: ByteString, uplink: Boolean = false, downlink: Boolean = false): Int {
        val slot = freeSlot()
        if (slot < 0 || !ready || name.toByteArray().size > CHANNEL_NAME_BYTES) return -1
        val settings = ChannelSettings(
            name = name, psk = psk, uplink_enabled = uplink, downlink_enabled = downlink,
            module_settings = org.meshtastic.proto.ModuleSettings(position_precision = 0),
        )
        return if (put(org.meshtastic.proto.Channel(index = slot, settings = settings, role = org.meshtastic.proto.Channel.Role.SECONDARY))) slot else -1
    }

    /** Turn a secondary channel off and forget its key. The main channel cannot be removed. */
    fun deleteChannel(index: Int): Boolean {
        if (!ready || index !in 1 until MAX_CHANNELS) return false
        val old = channels[index] ?: return false
        if (old.role == org.meshtastic.proto.Channel.Role.DISABLED) return false
        return put(org.meshtastic.proto.Channel(index = index, settings = ChannelSettings(), role = org.meshtastic.proto.Channel.Role.DISABLED))
    }

    private fun put(channel: org.meshtastic.proto.Channel): Boolean {
        channels[channel.index] = channel
        sendAdmin(AdminMessage(set_channel = channel))
        emitChannel(channel)
        listener.onChannelInfos(channelInfos())
        // Read it back: the shown list should be what the radio holds, not what was sent.
        val ask = sendAdmin(AdminMessage(get_channel_request = channel.index + 1), track = false, wantResponse = true)
        readRequests[ask] = channel.index
        return true
    }

    /** What importing the channels of a link would do, worked out without sending anything. */
    fun planImport(set: ChannelSet): ImportPlan {
        val presetOf = set.lora_config?.modem_preset?.let { presetLabel(it) } ?: presetName()
        val have = channels.values.filter { it.role != org.meshtastic.proto.Channel.Role.DISABLED }
        val free = (1 until MAX_CHANNELS).count { i ->
            (channels[i]?.role ?: org.meshtastic.proto.Channel.Role.DISABLED) == org.meshtastic.proto.Channel.Role.DISABLED
        }
        var room = free
        val items = set.settings.map { s ->
            val name = s.name.ifEmpty { presetOf }
            val same = have.any { h ->
                val hs = h.settings ?: ChannelSettings()
                hs.name.ifEmpty { presetName() } == name && hs.psk == s.psk
            }
            when {
                s.name.toByteArray().size > CHANNEL_NAME_BYTES -> ImportItem(name, s.psk, KeyKind.of(s.psk), ImportItem.State.REFUSED)
                same -> ImportItem(name, s.psk, KeyKind.of(s.psk), ImportItem.State.PRESENT)
                room > 0 -> { room--; ImportItem(name, s.psk, KeyKind.of(s.psk), ImportItem.State.ADD) }
                else -> ImportItem(name, s.psk, KeyKind.of(s.psk), ImportItem.State.NO_ROOM)
            }
        }
        return ImportPlan(items, set.lora_config)
    }

    /** Add what [plan] says to add. Returns how many channels went in. */
    fun applyImport(plan: ImportPlan): Int =
        plan.items.filter { it.state == ImportItem.State.ADD }.count { addChannel(it.name, it.psk) >= 0 }

    /**
     * Take the radio settings that came in a link: region, preset, bandwidth and the like.
     * What this radio's owner set for power, hops and transmitting is kept. Restarts the radio.
     */
    fun applyLoraFrom(incoming: Config.LoRaConfig): Boolean {
        val current = lora ?: return false
        if (!ready) return false
        val next = current.copy(
            region = if (incoming.region == Config.LoRaConfig.RegionCode.UNSET) current.region else incoming.region,
            use_preset = incoming.use_preset,
            modem_preset = incoming.modem_preset,
            bandwidth = incoming.bandwidth,
            spread_factor = incoming.spread_factor,
            coding_rate = incoming.coding_rate,
            frequency_offset = incoming.frequency_offset,
            channel_num = incoming.channel_num,
            override_frequency = incoming.override_frequency,
        )
        configs = configs.copy(lora = next)
        sendAdmin(AdminMessage(set_config = Config(lora = next)))
        listener.onSettings(configs)
        return true
    }

    private fun nextPacketId(): Int {
        val id = nextId
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        return id
    }

    companion object {
        const val BROADCAST = 0xFFFFFFFFL
        private const val MASK = 0xFFFFFFFFL
        private const val RETRY_MS = 250L
        private const val MAX_EMPTY_BEFORE_READY = 40
        private const val MAX_STALLS = 3

        /** How many channel slots the firmware has. */
        const val MAX_CHANNELS = 8

        fun presetLabel(p: Config.LoRaConfig.ModemPreset): String =
            p.name.split('_').joinToString("") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

        /** A node number as the four-byte key this app files a person under. */
        fun keyOf(num: Long): List<Byte> =
            listOf(24, 16, 8, 0).map { ((num shr it) and 0xFF).toByte() }

        fun numOf(key: List<Byte>): Long = key.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        /** The key a channel's thread is filed under: sixteen bytes, so never a person's. */
        fun channelKey(index: Int): List<Byte> =
            "meshtastic-ch".encodeToByteArray().toList().plus(listOf(0, 0, index.toByte()))

        /** Below 2.5 there is no PKI and hop counts cannot be trusted. */
        fun isTooOld(firmware: String): Boolean {
            val parts = firmware.split('.', '-').mapNotNull { it.toIntOrNull() }
            if (parts.size < 2) return false
            return parts[0] < 2 || (parts[0] == 2 && parts[1] < 5)
        }
    }
}

/** One channel in a link, and what importing it would do. */
data class ImportItem(val name: String, val psk: ByteString, val key: KeyKind, val state: State) {
    enum class State {
        /** Goes into a free slot as a secondary channel. */
        ADD,

        /** This radio already has a channel with this name and key. */
        PRESENT,

        /** All eight slots are in use. */
        NO_ROOM,

        /** The name is too long for the radio. */
        REFUSED,
    }
}

/** What a link would do: the channels, and the radio settings part, which is only ever applied on a second yes. */
data class ImportPlan(val items: List<ImportItem>, val lora: Config.LoRaConfig?) {
    val toAdd: Int get() = items.count { it.state == ImportItem.State.ADD }
}
