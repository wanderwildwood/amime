package com.wanderwildwood.amime.meshtastic

import com.wanderwildwood.amime.link.RadioBackend
import com.wanderwildwood.amime.link.RadioKind
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
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

    private var lora: Config.LoRaConfig? = null
    private var firmware = ""
    private var draining = false
    private var again = false
    private var emptyReads = 0
    private var nextId = if (firstPacketId == 0) 1 else firstPacketId
    private val nodes = mutableMapOf<Long, MtNode>()
    private val channels = mutableMapOf<Int, org.meshtastic.proto.Channel>()
    private val seen = ArrayDeque<Int>()

    // ---- driving it ----

    override fun start() {
        ready = false
        channels.clear()
        emptyReads = 0
        transport.write(ToRadio(want_config_id = configId).encode())
        drain()
    }

    /** FromNum changed: something is waiting. */
    fun onFromNum() = drain()

    /** The answer to a [MtTransport.read]. */
    fun onRead(bytes: ByteArray) {
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
        val message = runCatching { FromRadio.ADAPTER.decode(bytes) }.getOrNull()
        if (message == null) listener.onMalformed() else handle(message)
        transport.read(0)
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
            if (ready) emitChannel(c)
        }
        m.config?.lora?.let {
            lora = it
            listener.onRegion(it.region.value)
        }
        m.metadata?.let {
            firmware = it.firmware_version
            if (isTooOld(firmware)) listener.onTooOld(firmware)
        }
        m.packet?.let(::handlePacket)
        m.config_complete_id?.let { id ->
            if (id == configId && !ready) {
                ready = true
                channels.values.sortedBy { it.index }.forEach(::emitChannel)
                listener.onChannelsLoaded()
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
        lora?.modem_preset?.name?.split('_')?.joinToString("") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            ?: "LongFast"

    private fun handleNodeInfo(info: NodeInfo) {
        val num = info.num.toLong() and MASK
        val old = nodes[num] ?: MtNode(num)
        val pos = info.position
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

            PortNum.ROUTING_APP -> {
                if (d.request_id == 0) return
                val routing = runCatching { Routing.ADAPTER.decode(d.payload) }.getOrNull() ?: return
                val error = routing.error_reason?.value ?: 0
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
        val admin = AdminMessage(set_config = Config(lora = current.copy(region = region)))
        val packet = MeshPacket(
            to = myNum.toInt(),
            id = nextPacketId(),
            want_ack = true,
            decoded = Data(portnum = PortNum.ADMIN_APP, payload = admin.encodeByteString()),
        )
        transport.write(ToRadio(packet = packet).encode())
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
