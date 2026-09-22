package com.wanderwildwood.amime.link

import com.wanderwildwood.amime.protocol.Commands
import com.wanderwildwood.amime.protocol.Decoder
import com.wanderwildwood.amime.protocol.Frame
import com.wanderwildwood.amime.protocol.Resp
import com.wanderwildwood.amime.protocol.Sizes
import com.wanderwildwood.amime.protocol.TxtType

/**
 * Drives the companion protocol over a [Transport].
 *
 * Deliberately has no coroutines, no threads and no Android in it: frames go in through
 * [onFrame] and commands come out through the transport, so the ordering rules the firmware
 * cares about can be tested without a radio or a phone. Everything that knows about
 * Bluetooth lives in the transport.
 *
 * The session is not thread-safe and expects to be driven from one place — on Android, the
 * same callback thread the GATT delivers notifications on.
 */
class Session(
    private val transport: Transport,
    private val listener: Listener,
    private val appName: String = "amime",
) {

    interface Listener {
        /** The radio said what it is. Carries the BLE PIN it will actually ask for. */
        fun onDevice(info: Frame.DeviceInfo) {}

        /** The radio said who it is. After this the session is usable. */
        fun onReady(self: Frame.SelfInfo) {}

        /** One contact, either from a list sync or an unprompted advert. */
        fun onContact(contact: Frame.Contact) {}

        /** The radio dropped a contact to make room, and can no longer address them. */
        fun onContactDeleted(prefix: List<Byte>) {}

        /** The radio's contact table is full, so nobody new will appear until it is not. */
        fun onContactsFull() {}

        /**
         * A contact sync finished. [since] is what to pass to the next [syncContacts] so the
         * radio only sends what changed.
         */
        fun onContactsSynced(since: Long) {}

        /** A message arrived. */
        fun onMessage(message: Frame.MessageReceived) {}

        /**
         * A node answered a CLI command.
         *
         * Kept apart from [onMessage] because it is not one: it arrives by the same path and
         * with the same frame, but it is a machine answering a question, and putting it in a
         * conversation would mix the two.
         */
        fun onCliResponse(from: List<Byte>, text: String) {}

        /** A repeater or room server let us in. */
        fun onLoggedIn(login: Frame.LoginSucceeded) {}

        /** A repeater or room server did not. It does not say why. */
        fun onLoginRefused(from: List<Byte>) {}

        /**
         * A message this app sent was accepted by the radio. [awaitingAck] is false when no
         * acknowledgement will ever come, which is not a failure — see [Frame.Sent].
         */
        fun onSent(sent: Frame.Sent, awaitingAck: Boolean) {}

        /** A message this app sent was acknowledged by the far end. */
        fun onDelivered(ackHash: Long, roundTripMs: Long) {}

        /**
         * The radio heard a packet off the air. Says nothing about who or what — only that
         * something transmitted within earshot, and how strongly it arrived.
         */
        fun onPacketHeard(packet: Frame.PacketHeard) {}

        /** Battery, and storage when the firmware reports it. */
        fun onBattery(battery: Frame.BattAndStorage) {}

        /** The radio refused a command. */
        fun onFailed(code: Int) {}

        /**
         * Something is wrong with how this app is talking to the radio, as opposed to
         * something being wrong out in the mesh. Worth surfacing rather than logging.
         */
        fun onProtocolProblem(problem: Problem) {}
    }

    enum class Problem {
        /**
         * A pre-v3 message frame arrived, which the radio only sends to an app it thinks is
         * version 0. It means the device query did not reach it, or reached it too late.
         */
        HANDSHAKE_SKIPPED,

        /** A frame arrived shorter than its own opening byte claims — usually a small MTU. */
        FRAME_TRUNCATED,

        /** The radio sent a code this app does not know. Harmless, but worth counting. */
        UNKNOWN_FRAME,
    }

    var device: Frame.DeviceInfo? = null
        private set

    var self: Frame.SelfInfo? = null
        private set

    /** True between a contacts request and its end-of-list frame. */
    var syncingContacts: Boolean = false
        private set

    private var draining = false
    private var mostRecentLastMod = 0L

    /**
     * What to ask for next time, so a sync fetches changes rather than the whole list.
     *
     * The radio's filter is strictly greater than this, so handing back what it reported
     * cannot fetch the same contact twice.
     */
    private var syncedSince = 0L
    private val awaitingAcks = mutableSetOf<Long>()

    /**
     * Open the conversation.
     *
     * The device query goes first and is not optional: it is the only frame that tells the
     * radio which protocol version this app speaks, and without it every later message comes
     * back in a layout with different offsets. Sending [Commands.appStart] first and the
     * query afterwards is not equivalent — by then the first messages have already been
     * queued in the wrong shape.
     */
    fun start() {
        device = null
        self = null
        draining = false
        syncingContacts = false
        syncedSince = 0L
        awaitingAcks.clear()
        Commands.handshake(appName).forEach(transport::send)
    }

    /**
     * Ask for the contact list, or only what changed since [since].
     *
     * Ignored while a sync is already running: the firmware keeps one iterator and answers a
     * second request with a bad-state error rather than queueing it.
     */
    fun syncContacts(since: Long? = null) {
        if (syncingContacts) return
        syncingContacts = true
        transport.send(Commands.getContacts(since))
    }

    /** Send plain text to a contact, addressed by the six-byte prefix of its key. */
    fun sendMessage(recipientPrefix: ByteArray, text: String, timestamp: Long) {
        transport.send(Commands.sendTextMessage(recipientPrefix, text, timestamp))
    }

    /**
     * Tell the mesh this radio is here.
     *
     * Nothing else does. A companion node has **no advert timer at all** — unlike a repeater,
     * which re-advertises every couple of minutes by default — and `createSelfAdvert` is
     * reachable only from a button on the node's own screen or from this command. A radio
     * that never advertises is a radio nobody can add as a contact and nobody can write to,
     * however well it hears them.
     *
     * [flood] sends it through the mesh rather than to whoever is directly in earshot. That
     * is the one that gets you into a stranger's contact list on the far side of a repeater,
     * and it is a transmission the whole mesh carries, so it belongs behind a deliberate
     * press rather than on a timer of our own.
     */
    fun advertise(flood: Boolean = true) = transport.send(Commands.sendSelfAdvert(flood))

    /** Ask for battery and storage. */
    fun refreshBattery() = transport.send(Commands.getBattAndStorage())

    /**
     * Change which mesh this radio is on.
     *
     * Takes effect immediately and is saved. Every node that is to hear this one has to match
     * all four numbers, so this is the setting that decides whether the mesh exists at all.
     */
    fun setRadioParams(frequencyKhz: Int, bandwidthHz: Int, spreadingFactor: Int, codingRate: Int) =
        transport.send(
            Commands.setRadioParams(frequencyKhz, bandwidthHz, spreadingFactor, codingRate),
        )

    /** Fix the Bluetooth pairing PIN. Takes effect when the radio next restarts. */
    fun setDevicePin(pin: Int) = transport.send(Commands.setDevicePin(pin))

    /**
     * Log in to a repeater so it will take commands.
     *
     * Addressed by the whole public key, unlike a message. The answer comes back over the
     * air, so expect seconds rather than milliseconds, and expect nothing at all if the node
     * is out of range.
     */
    fun login(publicKey: ByteArray, password: String) =
        transport.send(Commands.sendLogin(publicKey, password))

    fun logout(publicKey: ByteArray) = transport.send(Commands.logout(publicKey))

    /**
     * Send one CLI command to a node already logged in to.
     *
     * No acknowledgement is expected for these, so the only sign it worked is the answer.
     */
    fun sendCliCommand(recipientPrefix: ByteArray, command: String) =
        transport.send(Commands.sendCliCommand(recipientPrefix, command))

    /** One whole frame, as it came off the radio. */
    fun onFrame(bytes: ByteArray) = handle(Decoder.decode(bytes))

    private fun handle(frame: Frame) {
        when (frame) {
            is Frame.DeviceInfo -> {
                device = frame
                listener.onDevice(frame)
            }

            is Frame.SelfInfo -> {
                self = frame
                listener.onReady(frame)
            }

            is Frame.ContactsStart -> syncingContacts = true

            is Frame.Contact -> {
                if (frame.lastMod > mostRecentLastMod) mostRecentLastMod = frame.lastMod
                listener.onContact(frame)
            }

            is Frame.ContactsEnd -> {
                syncingContacts = false
                // The radio's own answer is authoritative here; the running maximum is only
                // a fallback for a firmware that reports zero.
                val since = if (frame.mostRecentLastMod > 0) {
                    frame.mostRecentLastMod
                } else {
                    mostRecentLastMod
                }
                syncedSince = since
                listener.onContactsSynced(since)
            }

            /*
             * A route appeared where there was none. The screen draws that difference — a
             * solid border where the radio knows a way, a dotted one where it only floods —
             * so without this the border is a picture of what was true at the moment of
             * connecting and stays that way until the app is restarted.
             *
             * The contact's own `lastmod` was bumped before this frame was sent, so asking
             * for what has changed brings back this one contact and little else.
             */
            is Frame.PathUpdated -> syncContacts(syncedSince)

            is Frame.ContactDeleted ->
                listener.onContactDeleted(
                    frame.publicKey.copyOf(Sizes.PUB_KEY_PREFIX).toList(),
                )

            Frame.ContactsFull -> listener.onContactsFull()

            // A tickle with nothing in it. The queue is drained by asking repeatedly, and
            // one request is enough to start: each message answered asks for the next.
            is Frame.MessagesWaiting -> if (!draining) {
                draining = true
                transport.send(Commands.syncNextMessage())
            }

            is Frame.MessageReceived -> {
                if (frame.snr.isNaN()) listener.onProtocolProblem(Problem.HANDSHAKE_SKIPPED)
                if (frame.txtType == TxtType.CLI_DATA) {
                    listener.onCliResponse(frame.senderPrefix.toList(), frame.text)
                } else {
                    listener.onMessage(frame)
                }
                // Keep pulling until the radio says the queue is empty. A message can also
                // arrive without a preceding tickle, so this starts the drain either way.
                draining = true
                transport.send(Commands.syncNextMessage())
            }

            is Frame.NoMoreMessages -> draining = false

            is Frame.Sent -> {
                val awaiting = frame.expectedAck != 0L
                if (awaiting) awaitingAcks += frame.expectedAck
                listener.onSent(frame, awaiting)
            }

            is Frame.SendConfirmed -> {
                // The same acknowledgement can arrive more than once; only the first is a
                // delivery, the rest are repeats of one.
                if (awaitingAcks.remove(frame.ackHash)) {
                    listener.onDelivered(frame.ackHash, frame.roundTripMs)
                }
            }

            is Frame.Failed -> {
                // A refused contacts request leaves no iterator running, so the flag has to
                // come back down or every later sync is silently dropped by this class.
                if (syncingContacts) syncingContacts = false
                listener.onFailed(frame.code)
            }

            is Frame.Malformed -> {
                listener.onProtocolProblem(Problem.FRAME_TRUNCATED)
                // A truncated contact frame ends the list as far as the radio is concerned,
                // but not as far as this class is concerned unless the flag is cleared.
                if (frame.code == Resp.END_OF_CONTACTS) syncingContacts = false
            }

            is Frame.Unhandled -> listener.onProtocolProblem(Problem.UNKNOWN_FRAME)

            is Frame.LoginSucceeded -> listener.onLoggedIn(frame)

            is Frame.LoginFailed -> listener.onLoginRefused(frame.senderPrefix.toList())

            is Frame.PacketHeard -> listener.onPacketHeard(frame)

            is Frame.BattAndStorage -> listener.onBattery(frame)

            Frame.Ok, Frame.Disabled -> Unit
        }
    }
}
