package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.protocol.AdvType

/**
 * Someone, or something, on the mesh.
 *
 * Identified by the six-byte prefix the radio addresses messages with, not by the full public
 * key, because the prefix is the only form that arrives on a received message.
 */
data class Person(
    val prefix: List<Byte>,
    /**
     * The whole 32-byte key.
     *
     * Kept as well as [prefix] because the two are not interchangeable at the protocol level:
     * a message is addressed by the prefix and a login by the whole key, and the firmware
     * answers the wrong one with a bare not-found.
     */
    val publicKey: List<Byte> = emptyList(),
    val name: String,
    val type: Int,
    /**
     * Whether the radio knows a route to this node, or has only ever heard it by flood.
     *
     * Drawn as the difference between a solid and a dotted border rather than said in words:
     * it is the house rule for provisional, and it is true of a contact often enough that a
     * line of text about it would be furniture.
     */
    val pathKnown: Boolean,
    val lastHeard: Long,
) {
    /**
     * What to show when a node has advertised no name.
     *
     * Some fraction of any contact list is nodes that have never sent one, and an empty row
     * is worse than a short hexadecimal one: the reader can at least match the latter against
     * a node's own screen.
     */
    val label: String
        get() = name.ifBlank { prefix.joinToString("") { "%02x".format(it) } }

    val isRepeater: Boolean get() = type == AdvType.REPEATER
}

/** How far a message this app sent has actually got. */
enum class Delivery {
    /** Handed to the radio; the radio has not answered yet. */
    SENDING,

    /** The radio took it and an acknowledgement is expected. Drawn provisional. */
    AWAITING_ACK,

    /** The far end acknowledged it. */
    ACKNOWLEDGED,

    /**
     * The radio took it and said no acknowledgement is coming.
     *
     * Not a failure and not a pending state, which is why it is neither of the two above: a
     * dotted border that can never resolve would be a claim that something is still in
     * progress.
     */
    NO_ACK_EXPECTED,

    /** The radio refused it. */
    REFUSED,

    /**
     * The radio's own estimate of how long an acknowledgement could take has passed.
     *
     * Not a failure: an acknowledgement that arrives late is still accepted and still moves
     * this to [ACKNOWLEDGED]. It is the end of *waiting*, which is a different thing, and it
     * has to end somewhere — the firmware notices its own timeout and tells the app nothing
     * (`onSendTimeout()` is an empty function), so if this app does not keep the time then
     * nobody does and the row waits for ever.
     */
    UNANSWERED,

    /**
     * Read back from the log still in flight, which means it never landed anywhere.
     *
     * The answer it was waiting for travelled while this app was not running, and nothing
     * will arrive now to settle it either way. It is not pending — nothing is pending once
     * the process that was waiting has gone — and it is not a failure, because the radio may
     * well have sent it. It is the one state where the honest thing to say is that nobody
     * here knows.
     */
    UNRESOLVED,
}

data class Message(
    val id: Long,
    val text: String,
    val mine: Boolean,
    val timestamp: Long,
    val delivery: Delivery,
    /** Signal-to-noise for a received message, or null when the radio did not say. */
    val snr: Float? = null,
    /** Whether a received message arrived direct rather than through repeaters. */
    val direct: Boolean? = null,
    /**
     * When the radio's estimate of how long an acknowledgement could take runs out.
     *
     * Set only while [Delivery.AWAITING_ACK], from the estimate the radio hands back with
     * the send — it knows the airtime, the spreading factor and how many hops the path is,
     * and this app knows none of those.
     */
    val awaitingUntil: Long? = null,
)

data class Conversation(
    val person: Person,
    val messages: List<Message> = emptyList(),
)

data class MeshState(
    val nodeName: String? = null,
    val people: List<Person> = emptyList(),
    val conversations: Map<List<Byte>, List<Message>> = emptyMap(),
    /**
     * The newest message already read in each thread.
     *
     * Kept per thread rather than as a flag on each message so that opening a conversation
     * is one entry to write rather than a walk over everything in it.
     */
    val readUpTo: Map<List<Byte>, Long> = emptyMap(),
    val batteryMillivolts: Int? = null,
    /** The node being administered, if a login has been attempted. */
    val admin: Admin? = null,
    /**
     * Everything the radio has heard off the air this session, readable or not.
     *
     * Kept separately from [people] because the two answer different questions. A contact
     * means somebody sent a readable advert; this means the antenna is picking *anything* up.
     * On a site survey the second is the one that tells you whether to keep walking.
     */
    val heard: Heard = Heard(),
    /** True once the radio has answered the handshake and the app can send. */
    val ready: Boolean = false,
) {
    /**
     * How many messages in this thread arrived and have not been read.
     *
     * Only messages from the other end count. A thread whose only contents are things you
     * sent has nothing waiting in it, which is what the row used to claim about every
     * conversation anybody had ever opened.
     */
    fun unreadCount(prefix: List<Byte>): Int {
        val read = readUpTo[prefix] ?: 0L
        return conversations[prefix].orEmpty().count { !it.mine && it.id > read }
    }

    fun conversationWith(prefix: List<Byte>): Conversation? =
        people.firstOrNull { it.prefix == prefix }
            ?.let { Conversation(it, conversations[prefix].orEmpty()) }
}


/** What the radio has picked up off the air, regardless of whether any of it was readable. */
data class Heard(
    val packets: Int = 0,
    /** The strongest thing heard, by SNR. Null until something arrives. */
    val bestSnr: Float? = null,
    val bestRssi: Int? = null,
) {
    fun plus(snr: Float, rssi: Int): Heard = Heard(
        packets = packets + 1,
        bestSnr = if (bestSnr == null || snr > bestSnr) snr else bestSnr,
        bestRssi = if (bestRssi == null || rssi > bestRssi) rssi else bestRssi,
    )
}


/** One line of an administration session with a repeater. */
data class ConsoleLine(val text: String, val fromUs: Boolean)

/**
 * A logged-in session with a repeater or room server.
 *
 * A repeater has no Bluetooth of its own — the firmware that makes one has no companion
 * interface at all — so once it is up a pole this is the only way to reach it that does not
 * involve a ladder.
 */
data class Admin(
    val person: Person,
    val state: State = State.LOGGING_IN,
    val lines: List<ConsoleLine> = emptyList(),
    /** False for a guest login, which connects and is then refused almost everything. */
    val isAdmin: Boolean = false,
) {
    enum class State {
        /** Sent, and waiting on an answer that travels over the air. */
        LOGGING_IN,
        IN,

        /** Refused. The node does not say whether it was the password or a full client table. */
        REFUSED,
    }
}
