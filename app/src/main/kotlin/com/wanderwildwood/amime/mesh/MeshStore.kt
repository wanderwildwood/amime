package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.link.RadioKind
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
        // Also a channel's key: a thread is a thread, whoever is on the other end.
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

    /** Channel messages handed to the radio and not yet answered, oldest first. */
    private val unansweredOnChannels = ArrayDeque<Pair<List<Byte>, Long>>()

    /**
     * Channel messages that came off the radio before the slot they arrived on had been read.
     *
     * The queue on the radio is drained as soon as it says something is waiting, and the
     * slots are read one at a time over the same link, so the first messages after connecting
     * can easily arrive naming a slot this app has not heard about yet. They wait here for it.
     */
    private val beforeTheirChannel = mutableListOf<Frame.ChannelMessageReceived>()

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
     * Record a message said on a channel, before the radio has taken it. Returns its local id.
     *
     * Kept as typed. The radio sends it with this node's name in front, but in a thread of
     * one's own messages the name would say nothing.
     */
    fun recordChannelSent(key: List<Byte>, text: String, timestamp: Long): Long {
        val id = nextMessageId++
        unansweredOnChannels.addLast(key to id)
        val message = Message(
            id = id,
            text = text,
            mine = true,
            timestamp = timestamp,
            delivery = Delivery.SENDING,
        )
        update {
            copy(conversations = conversations + (key to (conversations[key].orEmpty() + message)))
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
     * Hand an existing message back to the radio.
     *
     * The same row rather than a new one: a thread showing the same sentence three times is
     * a worse account of what happened than one row that says it was tried three times.
     * Returns the attempt number to send it under, or null where there is nothing to resend.
     *
     * The acknowledgement hash of the earlier attempt is deliberately left on the books. Each
     * attempt has its own, the radio may yet answer either, and the first one arriving late
     * settles this message just as well as the newest would.
     */
    fun recordResend(prefix: List<Byte>, id: Long): Int? {
        val thread = state.conversations[prefix] ?: return null
        val message = thread.firstOrNull { it.id == id } ?: return null
        val attempt = (message.attempt + 1).coerceAtMost(MAX_ATTEMPT)
        unanswered.addLast(prefix to id)
        update {
            copy(
                conversations = conversations + (
                    prefix to thread.map {
                        if (it.id == id) {
                            it.copy(
                                delivery = Delivery.SENDING,
                                awaitingUntil = null,
                                attempt = attempt,
                            )
                        } else {
                            it
                        }
                    }
                    ),
            )
        }
        return attempt
    }

    /**
     * Forget everything from a connection that has gone.
     *
     * Including what the antenna heard. The screen says "since connecting" and that has to
     * stay true: a count carried across a reconnect is two measurements added together and
     * labelled as one, which on a site survey is the number the whole exercise turns on.
     */
    fun onDisconnected() {
        // Nothing will answer a channel message the radio had not yet taken. Left as it was,
        // it would say "Sending" until the app was closed.
        // The same for a direct message the radio never answered: and left in the queue, the
        // next connection's first answer would be matched to it instead of to its own send.
        strandUnanswered()
        beforeTheirChannel.clear()
        update {
            copy(
                ready = false,
                admin = null,
                heard = Heard(),
                region = null,
                mtChannels = emptyList(),
                mtConfigs = null,
                oldFirmware = null,
                channels = emptyList(),
                freeChannelSlots = emptyList(),
                channelsLoaded = false,
            )
        }
    }

    /** Every send still waiting on the radio's answer: none is coming, and each can be sent again. */
    private fun strandUnanswered() {
        val stranded = unansweredOnChannels.toList() + unanswered.toList()
        unansweredOnChannels.clear()
        unanswered.clear()
        stranded.forEach { setDelivery(it, Delivery.UNRESOLVED) }
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

    // ---- Meshtastic: the same state, filled without MeshCore's reply queues ----

    /** A Meshtastic radio has finished its handshake. */
    fun mtReady(name: String) = update {
        copy(nodeName = name.ifBlank { null }, ready = true, radio = RadioKind.MESHTASTIC, mtAdmin = null)
    }

    fun mtNode(person: Person) = update {
        val existing = people.indexOfFirst { it.prefix == person.prefix }
        copy(
            people = if (existing >= 0) {
                people.toMutableList().apply { this[existing] = person }
            } else {
                people + person
            },
        )
    }

    /**
     * One Meshtastic channel slot, filed under [key]: its name and key together, so a new
     * channel put in an old slot starts a thread of its own instead of inheriting the old one's.
     *
     * Messages kept under the slot's number (from before threads were keyed this way, or that
     * arrived before the slot was read) move into the channel now in that slot, once.
     */
    fun mtChannel(index: Int, name: String, enabled: Boolean, key: List<Byte>) = update {
        val others = channels.filterNot { it.index == index }
        if (!enabled) {
            copy(channels = others)
        } else {
            val bySlot = com.wanderwildwood.amime.meshtastic.MtSession.channelKey(index)
            val moved = conversations[bySlot]
            val withChannel = copy(channels = (others + Channel(index, name, key)).sortedBy { it.index })
            if (moved == null || bySlot == key) {
                withChannel
            } else {
                val read = listOfNotNull(readUpTo[bySlot], readUpTo[key]).maxOrNull()
                withChannel.copy(
                    conversations = conversations.filterKeys { it != bySlot } +
                        (key to (moved + conversations[key].orEmpty()).sortedBy { it.id }),
                    readUpTo = readUpTo.filterKeys { it != bySlot }.let { if (read == null) it else it + (key to read) },
                )
            }
        }
    }

    /**
     * Throw away one thread's messages, a person's or a channel's. The person or channel
     * stays; only what was said goes.
     *
     * A send still waiting on the radio's answer keeps its place in the reply queues: those
     * are matched by position, and the radio will still answer it. Its answer then finds no
     * row and settles nothing, instead of settling the next send in another thread.
     */
    fun deleteThread(key: List<Byte>) {
        if (key !in state.conversations && key !in state.readUpTo) return
        // A keyed map, not a queue: taking these out moves nobody else's answer.
        awaiting.values.removeAll { it.first == key }
        update { copy(
            // filterKeys, not minus: a key is a List, and minus would take it as a list of keys.
            conversations = conversations.filterKeys { it != key },
            readUpTo = readUpTo.filterKeys { it != key },
        ) }
    }

    fun mtChannelsLoaded() = update { copy(channelsLoaded = true) }

    fun mtChannelInfos(list: List<com.wanderwildwood.amime.meshtastic.MtChannelInfo>) = update { copy(mtChannels = list) }

    fun mtConfigs(configs: com.wanderwildwood.amime.meshtastic.MtConfigs) = update { copy(mtConfigs = configs) }

    /**
     * A write's progress. A new write (PENDING), or one with no owner yet, belongs to the screen
     * showing now; its later answers stay with the screen that sent it.
     */
    fun mtAdmin(report: com.wanderwildwood.amime.meshtastic.MtAdminReport?) = update {
        val fresh = report == null || report.state == com.wanderwildwood.amime.meshtastic.MtAdminReport.State.PENDING
        when {
            report == null -> copy(mtAdmin = null, mtAdminOwner = null)
            fresh || mtAdminOwner == null -> copy(mtAdmin = report, mtAdminOwner = noticeScreen)
            // The answer to a write made on a screen that has since been left: nobody is there to read it.
            mtAdminOwner != noticeScreen -> copy(mtAdmin = null, mtAdminOwner = null)
            else -> copy(mtAdmin = report)
        }
    }

    /** The screen on show changed: a notice that belongs to another screen is dropped. */
    fun noticeScreen(screen: NoticeScreen) = update {
        if (screen == noticeScreen) this
        else if (mtAdminOwner != null && mtAdminOwner != screen && mtAdmin?.state != com.wanderwildwood.amime.meshtastic.MtAdminReport.State.PENDING) {
            copy(noticeScreen = screen, mtAdmin = null, mtAdminOwner = null)
        } else copy(noticeScreen = screen)
    }

    fun mtRegion(region: Int) = update { copy(region = region, radio = RadioKind.MESHTASTIC) }

    fun mtHere(point: GeoPoint?) = update { copy(here = point) }

    fun mtTooOld(firmware: String) = update { copy(oldFirmware = firmware) }

    /** A text off the air, filed under [key]: a person's four bytes or a channel's key. */
    fun mtReceived(key: List<Byte>, text: String, time: Long, snr: Float?, direct: Boolean?) {
        val received = Message(
            id = nextMessageId++,
            text = text,
            mine = false,
            timestamp = time,
            delivery = Delivery.ACKNOWLEDGED,
            snr = snr,
            direct = direct,
        )
        update {
            copy(conversations = conversations + (key to (conversations[key].orEmpty() + received)))
        }
    }

    /** Record something this app sent, in [delivery] to begin with. Returns its local id. */
    fun mtSent(key: List<Byte>, text: String, time: Long, delivery: Delivery, until: Long? = null): Long {
        val id = nextMessageId++
        val message = Message(
            id = id, text = text, mine = true, timestamp = time,
            delivery = delivery, awaitingUntil = until,
        )
        update {
            copy(conversations = conversations + (key to (conversations[key].orEmpty() + message)))
        }
        return id
    }

    /**
     * A Meshtastic message handed to the radio again: the same row, waiting on an answer
     * until [until]. Returns the attempt number, or null where there is nothing to resend.
     *
     * Unlike [recordResend] it is not queued for the MeshCore radio's next reply: a
     * Meshtastic send is settled by its own packet id, never by whichever answer comes next.
     */
    fun mtResend(key: List<Byte>, id: Long, until: Long): Int? {
        val thread = state.conversations[key] ?: return null
        val message = thread.firstOrNull { it.id == id } ?: return null
        val attempt = (message.attempt + 1).coerceAtMost(MAX_ATTEMPT)
        update {
            copy(
                conversations = conversations + (
                    key to thread.map {
                        if (it.id == id) {
                            it.copy(
                                delivery = Delivery.AWAITING_ACK,
                                awaitingUntil = until,
                                attempt = attempt,
                                failure = null,
                            )
                        } else {
                            it
                        }
                    }
                    ),
            )
        }
        return attempt
    }

    /** Move a sent message to a new state, if it is still there. */
    fun mtSettle(key: List<Byte>, id: Long, delivery: Delivery, failure: Int? = null) {
        setDelivery(key to id, delivery)
        if (failure != null) {
            update {
                val thread = conversations[key] ?: return@update this
                copy(conversations = conversations + (key to thread.map {
                    if (it.id == id) it.copy(failure = failure) else it
                }))
            }
        }
    }

    // ---- what the radio says ----

    override fun onReady(self: Frame.SelfInfo) {
        // A new conversation with the radio: the session has forgotten every command it was
        // waiting on, so whatever is still queued here would only take a new send's answer.
        strandUnanswered()
        update {
            // A MeshCore radio after a Meshtastic one: its people were the other radio's nodes,
            // and its own contacts come right after this.
            val wasMeshtastic = radio == RadioKind.MESHTASTIC
            copy(
                nodeName = self.name,
                ready = true,
                radio = RadioKind.MESHCORE,
                people = if (wasMeshtastic) emptyList() else people,
            )
        }
    }

    /** A direct message the link never carried: it waits to be sent again. */
    override fun onMessageNotSent(place: Int) {
        if (place !in unanswered.indices) return
        setDelivery(unanswered.removeAt(place), Delivery.UNRESOLVED)
    }

    /** A channel message the link never carried. */
    override fun onChannelMessageNotSent(place: Int) {
        if (place !in unansweredOnChannels.indices) return
        setDelivery(unansweredOnChannels.removeAt(place), Delivery.UNRESOLVED)
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

    // ---- channels ----

    override fun onChannel(info: Frame.ChannelInfo) {
        val index = info.index
        update {
            if (info.isEmpty) {
                copy(
                    channels = channels.filterNot { it.index == index },
                    freeChannelSlots = (freeChannelSlots + index).distinct().sorted(),
                )
            } else {
                val channel = Channel(index, info.name, info.secret.toList())
                copy(
                    channels = (channels.filterNot { it.index == index } + channel)
                        .sortedBy { it.index },
                    freeChannelSlots = freeChannelSlots - index,
                )
            }
        }
        val waiting = beforeTheirChannel.filter { it.channelIndex == index }
        beforeTheirChannel.removeAll(waiting)
        waiting.forEach(::onChannelMessage)
    }

    /**
     * Every slot has been read. A message still waiting names a slot the radio does not have
     * — it was left while the message sat in the radio's queue — and has nowhere to go.
     */
    override fun onChannelsLoaded() {
        beforeTheirChannel.clear()
        update { copy(channelsLoaded = true) }
    }

    override fun onChannelSet(index: Int, name: String, secret: ByteArray) =
        onChannel(Frame.ChannelInfo(index, name, secret))

    override fun onChannelMessage(message: Frame.ChannelMessageReceived) {
        val channel = state.channels.firstOrNull { it.index == message.channelIndex }
        if (channel == null) {
            if (!state.channelsLoaded) beforeTheirChannel += message
            return
        }
        val received = Message(
            id = nextMessageId++,
            // "name: words", as the sender's radio wrote it. Split for showing, not here,
            // so that what is kept is exactly what arrived.
            text = message.text,
            mine = false,
            timestamp = message.senderTimestamp,
            delivery = Delivery.ACKNOWLEDGED,
            snr = message.snr.takeUnless { it.isNaN() },
            direct = message.cameDirect,
        )
        val key = channel.key
        update {
            copy(conversations = conversations + (key to (conversations[key].orEmpty() + received)))
        }
    }

    override fun onChannelSent() {
        val target = unansweredOnChannels.removeFirstOrNull() ?: return
        setDelivery(target, Delivery.SENT)
    }

    override fun onChannelSendFailed(code: Int) {
        val target = unansweredOnChannels.removeFirstOrNull() ?: return
        setDelivery(target, Delivery.REFUSED)
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

    companion object {
        /** How many messages a thread keeps; older ones are let go as new ones arrive. */
        const val MAX_PER_THREAD = 500

        /**
         * As far as the attempt number is taken.
         *
         * Above three the firmware stops keeping it in its own byte and hides it at the end
         * of the payload, which costs two bytes of the message and refuses to send at all if
         * the text is within two of the maximum. Three tries is where a person gives up
         * anyway, and the timestamp keeps each packet distinct regardless.
         */
        private const val MAX_ATTEMPT = 3
    }

    private inline fun update(block: MeshState.() -> MeshState) {
        state = capped(state.block())
        onChange(state)
    }

    /**
     * The newest [MAX_PER_THREAD] messages of each thread, and no more.
     *
     * A busy channel adds a message every few minutes for as long as the radio is on, and the
     * whole log is rewritten on every change; without a ceiling it grows until each write is
     * slow and the file is large. The oldest go first.
     */
    private fun capped(s: MeshState): MeshState {
        if (s.conversations.values.none { it.size > MAX_PER_THREAD }) return s
        return s.copy(
            conversations = s.conversations.mapValues { (_, thread) ->
                if (thread.size > MAX_PER_THREAD) thread.takeLast(MAX_PER_THREAD) else thread
            },
        )
    }
}
