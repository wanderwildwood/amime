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
    /**
     * The clock, injected so that a test can hold it still.
     *
     * The only thing this class times is how long to go on calling a message unanswered,
     * which is the radio's estimate rather than a figure of our own.
     */
    private val now: () -> Long = { System.currentTimeMillis() },
) : Session.Listener {

    var state: MeshState = MeshState()
        private set

    private var nextMessageId = 1L

    /**
     * Put back what was on disk, before anything is connected.
     *
     * The numbering picks up where it left off rather than starting again, because an id is
     * what an acknowledgement is matched against within a session and a repeat would settle
     * the wrong message.
     */
    fun restore(
        conversations: Map<List<Byte>, List<Message>>,
        nextId: Long,
        readUpTo: Map<List<Byte>, Long> = emptyMap(),
    ) {
        nextMessageId = maxOf(nextMessageId, nextId)
        update { copy(conversations = conversations, readUpTo = readUpTo) }
    }

    /**
     * Everything in this thread has now been seen.
     *
     * Called on the way into a conversation and again on the way out, because anything that
     * arrived while it was open was read as it landed.
     */
    fun markRead(prefix: List<Byte>) {
        val newest = state.conversations[prefix].orEmpty().maxOfOrNull { it.id } ?: return
        if (state.readUpTo[prefix] == newest) return
        update { copy(readUpTo = readUpTo + (prefix to newest)) }
    }

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

    /**
     * Mark a contact as having no known route.
     *
     * Kept in step by hand because the radio will not say it again: it changes the contact
     * and leaves `lastmod` alone on purpose, so nothing arrives later to correct a border
     * still drawn solid over a route that has been thrown away.
     */
    fun forgetRoute(prefix: List<Byte>) = update {
        copy(
            people = people.map {
                if (it.prefix == prefix) it.copy(pathKnown = false) else it
            },
        )
    }

    /**
     * Forget everything from a connection that has gone.
     *
     * Including what the antenna heard. The screen says "since connecting" and that has to
     * stay true: a count carried across a reconnect is two measurements added together and
     * labelled as one, which on a site survey is the number the whole exercise turns on.
     */
    fun onDisconnected() = update {
        copy(ready = false, admin = null, heard = Heard())
    }

    /** Begin administering a repeater. The answer comes back over the air, so this waits. */
    fun beginLogin(person: Person) = update {
        copy(admin = Admin(person = person, state = Admin.State.LOGGING_IN))
    }

    /** Record a command as sent, so the console shows it before any answer arrives. */
    fun recordCommand(command: String) = update {
        val current = admin ?: return@update this
        copy(admin = current.copy(lines = current.lines + ConsoleLine(command, fromUs = true)))
    }

    fun endAdmin() = update { copy(admin = null) }

    // ---- what the radio says ----

    override fun onReady(self: Frame.SelfInfo) = update {
        copy(nodeName = self.name, ready = true)
    }

    override fun onContact(contact: Frame.Contact) {
        val prefix = contact.prefix.toList()
        val person = Person(
            prefix = prefix,
            publicKey = contact.publicKey.toList(),
            name = contact.name,
            type = contact.type,
            pathKnown = contact.pathKnown,
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

    /**
     * Take somebody off the list the radio has forgotten.
     *
     * Their conversation is left where it is rather than deleted: the messages were ours and
     * reading them back does no harm, and the contact may advertise again within the hour.
     * What goes is the row that would otherwise offer to write to somebody the radio would
     * answer with a flat not-found.
     */
    override fun onContactDeleted(prefix: List<Byte>) = update {
        copy(people = people.filterNot { it.prefix == prefix })
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
            // The radio works this out from the airtime, the spreading factor and the length
            // of the path; none of which is known here, so it is not second-guessed.
            awaitingUntil = if (awaitingAck) now() + sent.estimatedTimeoutMs else null,
        )
    }

    /**
     * An acknowledgement, however late.
     *
     * A message the clock already gave up on is still settled by one arriving afterwards:
     * the expiry ends the waiting, not the possibility, and the hash stays in [awaiting] for
     * exactly this reason.
     */
    override fun onDelivered(ackHash: Long, roundTripMs: Long) {
        val target = awaiting.remove(ackHash) ?: return
        setDelivery(target, Delivery.ACKNOWLEDGED)
    }

    /** Whether anything is still inside the window the radio gave it. */
    fun hasAwaitingAcks(): Boolean = state.conversations.values.any { thread ->
        thread.any { it.delivery == Delivery.AWAITING_ACK }
    }

    /**
     * Stop waiting on anything whose window has closed.
     *
     * Silent when nothing has expired — on a panel that repaints in full, a state object
     * handed out every few seconds with nothing changed in it is a screenful of flicker for
     * no news.
     */
    fun expireAwaitingAcks() {
        val deadline = now()
        val expired = state.conversations.mapValues { (_, thread) ->
            thread.map {
                if (it.delivery == Delivery.AWAITING_ACK &&
                    it.awaitingUntil != null &&
                    it.awaitingUntil <= deadline
                ) {
                    it.copy(delivery = Delivery.UNANSWERED, awaitingUntil = null)
                } else {
                    it
                }
            }
        }
        if (expired != state.conversations) update { copy(conversations = expired) }
    }

    override fun onFailed(code: Int) {
        // A refusal answers the oldest unanswered send, the same way a success does. Where
        // there is none, the refusal belongs to some other command and no message is wrong.
        val target = unanswered.removeFirstOrNull() ?: return
        setDelivery(target, Delivery.REFUSED)
    }

    override fun onLoggedIn(login: Frame.LoginSucceeded) = update {
        val current = admin ?: return@update this
        if (login.senderPrefix.toList() != current.person.prefix) return@update this
        copy(admin = current.copy(state = Admin.State.IN, isAdmin = login.isAdmin))
    }

    override fun onLoginRefused(from: List<Byte>) = update {
        val current = admin ?: return@update this
        if (from != current.person.prefix) return@update this
        copy(admin = current.copy(state = Admin.State.REFUSED))
    }

    override fun onCliResponse(from: List<Byte>, text: String) = update {
        val current = admin ?: return@update this
        if (from != current.person.prefix) return@update this
        copy(admin = current.copy(lines = current.lines + ConsoleLine(text, fromUs = false)))
    }

    override fun onPacketHeard(packet: Frame.PacketHeard) = update {
        copy(heard = heard.plus(packet.snr, packet.rssi))
    }

    override fun onBattery(battery: Frame.BattAndStorage) = update {
        copy(batteryMillivolts = battery.batteryMillivolts)
    }

    // ---- plumbing ----

    private fun setDelivery(
        target: Pair<List<Byte>, Long>,
        delivery: Delivery,
        awaitingUntil: Long? = null,
    ) {
        val (prefix, id) = target
        update {
            val thread = conversations[prefix] ?: return@update this
            copy(
                conversations = conversations + (
                    prefix to thread.map {
                        if (it.id == id) {
                            it.copy(delivery = delivery, awaitingUntil = awaitingUntil)
                        } else {
                            it
                        }
                    }
                    ),
            )
        }
    }

    private inline fun update(block: MeshState.() -> MeshState) {
        state = state.block()
        onChange(state)
    }
}
