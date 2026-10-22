package com.wanderwildwood.amime.link

/** Which firmware a radio runs. Decided by the Bluetooth service it offers, never by its name alone. */
enum class RadioKind { MESHCORE, MESHTASTIC }

/**
 * What the screens need from whichever radio is connected.
 *
 * The two radios speak different protocols over different Bluetooth services, but they hold
 * the same kinds of thing: people, channels and threads. Each backend folds what its radio
 * says into the same [com.wanderwildwood.amime.mesh.MeshStore] — that store, and the state it
 * publishes, are the nodes, channels and messages flows — and takes the same sends.
 *
 * Anything only one radio has stays on that radio's own class (repeater consoles on
 * [Session], the region on the Meshtastic session).
 */
interface RadioBackend {
    val kind: RadioKind

    /** The link is up and listening: begin the handshake. */
    fun start()

    /**
     * Say [text] to one person. [now] is seconds since the epoch; [attempt] counts resends
     * (MeshCore mixes it into the packet).
     */
    fun sendDirect(to: List<Byte>, publicKey: List<Byte>, text: String, now: Long, attempt: Int = 0)

    /** Say [text] on the channel in slot [index]. */
    fun sendChannel(index: Int, text: String, now: Long)
}

/** The existing MeshCore [Session] behind the common interface. Adds no behaviour of its own. */
class MeshCoreBackend(private val session: Session) : RadioBackend {
    override val kind: RadioKind get() = RadioKind.MESHCORE

    override fun start() {
        session.start()
        session.syncContacts()
        session.refreshBattery()
    }

    override fun sendDirect(
        to: List<Byte>,
        publicKey: List<Byte>,
        text: String,
        now: Long,
        attempt: Int,
    ) = session.sendMessage(to.toByteArray(), text, now, attempt)

    override fun sendChannel(index: Int, text: String, now: Long) =
        session.sendChannelMessage(index, text, now)
}
