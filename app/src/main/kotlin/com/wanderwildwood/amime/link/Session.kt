package com.wanderwildwood.amime.link

import com.wanderwildwood.amime.protocol.Channels
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

        /**
         * The radio refused a direct message or a contacts request — or something this class
         * cannot place, which before replies were tracked was everything.
         */
        fun onFailed(code: Int) {}

        /** One of the radio's channel slots, as it reported it — empty ones included. */
        fun onChannel(info: Frame.ChannelInfo) {}

        /** Every slot has been asked about, so a free one can be told from an unknown one. */
        fun onChannelsLoaded() {}

        /** Somebody said something on a channel this radio holds. */
        fun onChannelMessage(message: Frame.ChannelMessageReceived) {}

        /** The radio took a channel message and sent it. There is no acknowledgement on a channel. */
        fun onChannelSent() {}

        /** The radio would not send a channel message. */
        fun onChannelSendFailed(code: Int) {}

        /**
         * A channel slot was written. An empty [name] with a key of zeros is a slot emptied,
         * which is what leaving a channel is.
         */
        fun onChannelSet(index: Int, name: String, secret: ByteArray) {}

        /** The radio would not write that slot. */
        fun onChannelSetFailed(index: Int, code: Int) {}

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

    /**
     * Commands sent whose answer has not come back yet, oldest first.
     *
     * The radio answers every command within the same call that handles it
     * (`handleCmdFrame` in `MyMesh.cpp`), so answers come back in the order the commands went
     * out. Most answers say what they answer, but three do not — a bare OK, a bare error and a
     * `Sent` — and a channel message, a channel slot written, an announce and a direct
     * message can all be in flight together. Without this a refused channel was blamed on
     * whichever direct message happened to be oldest.
     *
     * An answer settles the first entry that could have produced it, and anything still ahead
     * of that entry lost its answer somewhere — the link drops a frame it has given up on — so
     * it is let go rather than left to take the next answer that comes.
     */
    private val inFlight = ArrayDeque<Awaiting>()

    private sealed interface Awaiting {
        /** A direct message: answered by `Sent`. */
        data object Message : Awaiting

        /** A login or a CLI line: answered by `Sent`, and nobody is waiting on it here. */
        data object OtherSent : Awaiting

        /** A contacts request: answered by the start of the list. */
        data object Contacts : Awaiting

        /** A channel message: answered by an OK. */
        data object ChannelMessage : Awaiting

        /** A channel slot written: answered by an OK. */
        class ChannelSet(val index: Int, val name: String, val secret: ByteArray) : Awaiting

        /** A channel slot asked about: answered by its contents. */
        class ChannelGet(val index: Int) : Awaiting

        /** An announce, a route reset, a logout, a radio setting: answered by an OK. */
        data object OtherOk : Awaiting
    }

    /** How many channel slots the radio has, for walking them once after connecting. */
    private var channelSlots = 0
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
        inFlight.clear()
        channelSlots = 0
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
        send(Awaiting.Contacts, Commands.getContacts(since))
    }

    /**
     * Send plain text to a contact, addressed by the six-byte prefix of its key.
     *
     * [attempt] is 0 for a first send. The firmware keeps only its low two bits in the
     * ordinary slot and hides anything above 3 at the tail of the payload, which costs two
     * bytes of the text — so a caller that goes past 3 has to leave room for it, and this
     * app does not go past 3.
     */
    fun sendMessage(
        recipientPrefix: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int = 0,
    ) {
        send(Awaiting.Message, Commands.sendTextMessage(recipientPrefix, text, timestamp, attempt))
    }

    /**
     * Say something on the channel in slot [index].
     *
     * The radio puts this node's name in front, so [text] goes as typed.
     */
    fun sendChannelMessage(index: Int, text: String, timestamp: Long) =
        send(Awaiting.ChannelMessage, Commands.sendChannelMessage(index, text, timestamp))

    /** Put a channel in slot [index]. The radio saves it straight away. */
    fun setChannel(index: Int, name: String, secret: ByteArray) =
        send(Awaiting.ChannelSet(index, name, secret), Commands.setChannel(index, name, secret))

    /**
     * Leave the channel in slot [index].
     *
     * There is no delete in the protocol: the slot is written back to what an unused one
     * holds, an empty name and a key of zeros, which is also how a free slot is recognised.
     */
    fun leaveChannel(index: Int) = setChannel(index, "", ByteArray(Channels.SECRET))

    /**
     * Ask about every channel slot, one at a time.
     *
     * One at a time because the radio's queue of frames waiting to go out over Bluetooth holds
     * four (`FRAME_QUEUE_SIZE` in `esp32/SerialBLEInterface.h`) and drops what does not fit, so
     * forty questions sent at once would get a handful of answers.
     */
    private fun loadChannels(slots: Int) {
        channelSlots = slots
        if (slots > 0) askChannel(0) else listener.onChannelsLoaded()
    }

    private fun askChannel(index: Int) = send(Awaiting.ChannelGet(index), Commands.getChannel(index))

    private fun afterChannel(index: Int) {
        if (index + 1 < channelSlots) askChannel(index + 1) else listener.onChannelsLoaded()
    }

    private fun send(awaiting: Awaiting, frame: ByteArray) {
        inFlight.addLast(awaiting)
        transport.send(frame)
    }

    /** The first command still waiting that [answers] could belong to, letting go of any ahead of it. */
    private fun settle(answers: (Awaiting) -> Boolean): Awaiting? {
        val at = inFlight.indexOfFirst(answers)
        if (at < 0) return null
        repeat(at) { lost(inFlight.removeFirst()) }
        return inFlight.removeFirst()
    }

    /** A command whose answer never came. Only a walk of the slots needs to go on regardless. */
    private fun lost(awaiting: Awaiting) {
        if (awaiting is Awaiting.ChannelGet) afterChannel(awaiting.index)
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
    fun advertise(flood: Boolean = true) = send(Awaiting.OtherOk, Commands.sendSelfAdvert(flood))

    /**
     * Forget the route to a contact, so the next message to them finds its own way.
     *
     * The radio does this and says OK, and then never mentions it again — it does not touch
     * the contact's `lastmod`, so a sync will not report it. The caller has to put its own
     * copy right.
     */
    fun resetPath(publicKey: ByteArray) = send(Awaiting.OtherOk, Commands.resetPath(publicKey))

    /** Ask for battery and storage. */
    fun refreshBattery() = transport.send(Commands.getBattAndStorage())

    /**
     * Change which mesh this radio is on.
     *
     * Takes effect immediately and is saved. Every node that is to hear this one has to match
     * all four numbers, so this is the setting that decides whether the mesh exists at all.
     */
    fun setRadioParams(frequencyKhz: Int, bandwidthHz: Int, spreadingFactor: Int, codingRate: Int) =
        send(
            Awaiting.OtherOk,
            Commands.setRadioParams(frequencyKhz, bandwidthHz, spreadingFactor, codingRate),
        )

    /** Fix the Bluetooth pairing PIN. Takes effect when the radio next restarts. */
    fun setDevicePin(pin: Int) = send(Awaiting.OtherOk, Commands.setDevicePin(pin))

    /**
     * Log in to a repeater so it will take commands.
     *
     * Addressed by the whole public key, unlike a message. The answer comes back over the
     * air, so expect seconds rather than milliseconds, and expect nothing at all if the node
     * is out of range.
     */
    fun login(publicKey: ByteArray, password: String) =
        send(Awaiting.OtherSent, Commands.sendLogin(publicKey, password))

    fun logout(publicKey: ByteArray) = send(Awaiting.OtherOk, Commands.logout(publicKey))

    /**
     * Send one CLI command to a node already logged in to.
     *
     * No acknowledgement is expected for these, so the only sign it worked is the answer.
     */
    fun sendCliCommand(recipientPrefix: ByteArray, command: String) =
        send(Awaiting.OtherSent, Commands.sendCliCommand(recipientPrefix, command))

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
                loadChannels(device?.maxGroupChannels ?: 0)
            }

            is Frame.ContactsStart -> {
                settle { it == Awaiting.Contacts }
                syncingContacts = true
            }

            is Frame.ChannelInfo -> {
                val asked = settle { it is Awaiting.ChannelGet && it.index == frame.index }
                listener.onChannel(frame)
                if (asked != null) afterChannel(frame.index)
            }

            is Frame.ChannelMessageReceived -> {
                if (frame.snr.isNaN()) listener.onProtocolProblem(Problem.HANDSHAKE_SKIPPED)
                listener.onChannelMessage(frame)
                draining = true
                transport.send(Commands.syncNextMessage())
            }

            // Not read, but it came off the queue, and the queue has more behind it.
            is Frame.ChannelDataReceived -> {
                draining = true
                transport.send(Commands.syncNextMessage())
            }

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
                // A login's or a CLI line's `Sent` is nobody's message. With nothing tracked
                // at all it is passed on as it always was.
                val whose = settle { it == Awaiting.Message || it == Awaiting.OtherSent }
                if (whose == Awaiting.OtherSent) return
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
                // An error says nothing about what it answers, but answers come in order, so
                // it belongs to the oldest command still waiting.
                when (val whose = inFlight.removeFirstOrNull()) {
                    is Awaiting.ChannelGet -> afterChannel(whose.index)
                    is Awaiting.ChannelSet -> listener.onChannelSetFailed(whose.index, frame.code)
                    Awaiting.ChannelMessage -> listener.onChannelSendFailed(frame.code)
                    Awaiting.OtherOk, Awaiting.OtherSent -> Unit
                    Awaiting.Message, Awaiting.Contacts, null -> {
                        // A refused contacts request leaves no iterator running, so the flag
                        // has to come back down or every later sync is silently dropped here.
                        if (syncingContacts) syncingContacts = false
                        listener.onFailed(frame.code)
                    }
                }
            }

            is Frame.Malformed -> {
                listener.onProtocolProblem(Problem.FRAME_TRUNCATED)
                // A truncated contact frame ends the list as far as the radio is concerned,
                // but not as far as this class is concerned unless the flag is cleared.
                if (frame.code == Resp.END_OF_CONTACTS) syncingContacts = false
            }

            is Frame.Unhandled -> {
                listener.onProtocolProblem(Problem.UNKNOWN_FRAME)
                // Mid-drain, a reply this app cannot read is most likely the next thing off
                // the queue, of a kind newer than this app. Stopping here would leave the
                // drain marked as running with nothing asking, and every later message
                // would wait on the radio until the next connection. Asking once more costs
                // at worst a "no more".
                if (draining && frame.code < 0x80) transport.send(Commands.syncNextMessage())
            }

            is Frame.LoginSucceeded -> listener.onLoggedIn(frame)

            is Frame.LoginFailed -> listener.onLoginRefused(frame.senderPrefix.toList())

            is Frame.PacketHeard -> listener.onPacketHeard(frame)

            is Frame.BattAndStorage -> listener.onBattery(frame)

            Frame.Ok -> when (
                val whose = settle {
                    it == Awaiting.ChannelMessage || it is Awaiting.ChannelSet || it == Awaiting.OtherOk
                }
            ) {
                Awaiting.ChannelMessage -> listener.onChannelSent()
                is Awaiting.ChannelSet -> listener.onChannelSet(whose.index, whose.name, whose.secret)
                else -> Unit
            }

            Frame.Disabled -> Unit
        }
    }
}
