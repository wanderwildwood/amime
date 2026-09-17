package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.link.Session
import com.wanderwildwood.amime.protocol.Frame

/**
 * Folds what the radio says into something a screen can draw.
 *
 * Pure: no Android, no coroutines, no clock of its own. State comes out through [onChange]
 * after every fold, so the caller decides what thread that lands on and how it reaches
 * Compose.
 */
class MeshStore(
    private val onChange: (MeshState) -> Unit = {},
) : Session.Listener {

    var state: MeshState = MeshState()
        private set

    private var nextMessageId = 1L

    /**
     * Sends handed to the radio that it has not answered yet, oldest first.
     *
     * The radio answers command frames in the order it receives them — `handleCmdFrame`
     * replies within the same call — so the first unanswered send is the one a `Sent` frame
     * belongs to. That is the whole correlation, and it is why this is a queue rather than a
     * map: a `Sent` frame carries no reference to the message that produced it.
     */
    private val unanswered = ArrayDeque<Pair<List<Byte>, Long>>()

    /** Acknowledgement hashes the radio is waiting on, against the message each belongs to. */
    private val awaiting = mutableMapOf<Long, Pair<List<Byte>, Long>>()

    // ---- what this app does ----

    /**
     * Record a message as sent before the radio has said anything about it.
     *
     * Returns its local id. The message appears immediately in [Delivery.SENDING], because on
     * a mesh the gap between pressing send and hearing back is long enough that a thread
     * which showed nothing would look broken.
     */
    fun recordSent(prefix: List<Byte>, text: String, timestamp: Long): Long {
        val id = nextMessageId++
        unanswered.addLast(prefix to id)
        val message = Message(
            id = id,
            text = text,
            mine = true,
            timestamp = timestamp,
            delivery = Delivery.SENDING,
        )
        update {
            copy(conversations = conversations + (prefix to (conversations[prefix].orEmpty() + message)))
        }
        return id
    }

    /** Forget everything from a connection that has gone. */
    fun onDisconnected() = update { copy(ready = false) }

    // ---- what the radio says ----

    override fun onReady(self: Frame.SelfInfo) = update {
        copy(nodeName = self.name, ready = true)
    }

    override fun onContact(contact: Frame.Contact) {
        val prefix = contact.prefix.toList()
        val person = Person(
            prefix = prefix,
            name = contact.name,
            type = contact.type,
            // An empty path means the radio has no route and reaches this node by flooding.
            pathKnown = contact.outPath.isNotEmpty(),
            lastHeard = contact.lastAdvert,
        )
        update {
            val existing = people.indexOfFirst { it.prefix == prefix }
            copy(
                people = if (existing >= 0) {
                    people.toMutableList().apply { this[existing] = person }
                } else {
                    people + person
                },
            )
        }
    }

    override fun onMessage(message: Frame.MessageReceived) {
        val prefix = message.senderPrefix.toList()
        val received = Message(
            id = nextMessageId++,
            text = message.text,
            mine = false,
            timestamp = message.senderTimestamp,
            delivery = Delivery.ACKNOWLEDGED,
            // NaN is the radio declining to say, not a reading of zero.
            snr = message.snr.takeUnless { it.isNaN() },
            direct = message.cameDirect,
        )
        update {
            copy(conversations = conversations + (prefix to (conversations[prefix].orEmpty() + received)))
        }
    }

    override fun onSent(sent: Frame.Sent, awaitingAck: Boolean) {
        val target = unanswered.removeFirstOrNull() ?: return
        if (awaitingAck) awaiting[sent.expectedAck] = target
        setDelivery(
            target,
            if (awaitingAck) Delivery.AWAITING_ACK else Delivery.NO_ACK_EXPECTED,
        )
    }

    override fun onDelivered(ackHash: Long, roundTripMs: Long) {
        val target = awaiting.remove(ackHash) ?: return
        setDelivery(target, Delivery.ACKNOWLEDGED)
    }

    override fun onFailed(code: Int) {
        // A refusal answers the oldest unanswered send, the same way a success does. Where
        // there is none, the refusal belongs to some other command and no message is wrong.
        val target = unanswered.removeFirstOrNull() ?: return
        setDelivery(target, Delivery.REFUSED)
    }

    override fun onPacketHeard(packet: Frame.PacketHeard) = update {
        copy(heard = heard.plus(packet.snr, packet.rssi))
    }

    override fun onBattery(battery: Frame.BattAndStorage) = update {
        copy(batteryMillivolts = battery.batteryMillivolts)
    }

    // ---- plumbing ----

    private fun setDelivery(target: Pair<List<Byte>, Long>, delivery: Delivery) {
        val (prefix, id) = target
        update {
            val thread = conversations[prefix] ?: return@update this
            copy(
                conversations = conversations + (
                    prefix to thread.map { if (it.id == id) it.copy(delivery = delivery) else it }
                    ),
            )
        }
    }

    private inline fun update(block: MeshState.() -> MeshState) {
        state = state.block()
        onChange(state)
    }
}
