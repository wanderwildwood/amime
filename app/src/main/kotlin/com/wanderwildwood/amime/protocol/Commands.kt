package com.wanderwildwood.amime.protocol

/**
 * Frames this app sends to the radio.
 *
 * Each returns the bytes of one frame. On BLE a frame is one write to the RX characteristic
 * and nothing else delimits it, so nothing here adds a length or a terminator.
 */
object Commands {

    /**
     * Ask what the radio is, and — the part that matters — tell it what this app understands.
     *
     * The firmware records [APP_PROTOCOL_VERSION] from byte 1 of *this* frame and nowhere
     * else. Until it arrives the radio treats the app as version 0 and answers
     * [Cmd.SYNC_NEXT_MESSAGE] with the legacy [Resp.CONTACT_MSG_RECV] layout, which has no
     * SNR and a different offset for every field after it. So this is sent first, before
     * [appStart], and skipping it does not fail loudly — it just yields messages that parse
     * to nonsense.
     */
    fun deviceQuery(appVersion: Int = APP_PROTOCOL_VERSION): ByteArray =
        FrameWriter(2).u8(Cmd.DEVICE_QUERY).u8(appVersion).build()

    /**
     * Announce the app and get the node's own identity back.
     *
     * Bytes 1 through 7 are reserved and read but unused; the name begins at byte 8. The
     * firmware NUL-terminates the name by writing over the byte after the frame, so a name
     * long enough to fill the buffer is its problem, not ours.
     */
    fun appStart(appName: String = "amime"): ByteArray =
        FrameWriter().u8(Cmd.APP_START).zeros(7).text(appName).build()

    /**
     * The contact list, optionally only what changed.
     *
     * With [since] the radio sends just the contacts whose `lastMod` is newer, which on a
     * node holding a hundred of them is the difference between a sync and a stall. The reply
     * is [Resp.CONTACTS_START], then one [Resp.CONTACT] per contact, then
     * [Resp.END_OF_CONTACTS].
     */
    fun getContacts(since: Long? = null): ByteArray =
        FrameWriter(5).u8(Cmd.GET_CONTACTS).apply { since?.let { u32(it) } }.build()

    /**
     * Send plain text to one contact.
     *
     * [recipientPrefix] is the first six bytes of their public key, not the whole key — see
     * [Sizes.PUB_KEY_PREFIX]. [timestamp] is seconds, and the radio uses it for replay
     * protection, so re-sending the same text with the same timestamp is dropped rather than
     * delivered twice. [attempt] counts retries of the same message.
     */
    fun sendTextMessage(
        recipientPrefix: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int = 0,
        txtType: Int = TxtType.PLAIN,
    ): ByteArray {
        require(recipientPrefix.size == Sizes.PUB_KEY_PREFIX) {
            "a recipient is addressed by a ${Sizes.PUB_KEY_PREFIX}-byte key prefix, " +
                "got ${recipientPrefix.size}"
        }
        return FrameWriter()
            .u8(Cmd.SEND_TXT_MSG)
            .u8(txtType)
            .u8(attempt)
            .u32(timestamp)
            .bytes(recipientPrefix)
            .text(text)
            .build()
    }

    /**
     * Take one message off the radio's queue.
     *
     * The radio holds messages that arrived while nothing was connected and hands them over
     * one at a time; this is called repeatedly until [Resp.NO_MORE_MESSAGES] comes back. A
     * [Push.MSG_WAITING] is only a tickle — it carries no message and says nothing about how
     * many are queued.
     */
    fun syncNextMessage(): ByteArray = FrameWriter(1).u8(Cmd.SYNC_NEXT_MESSAGE).build()

    /** Battery and storage. Answered by [Resp.BATT_AND_STORAGE]. */
    fun getBattAndStorage(): ByteArray = FrameWriter(1).u8(Cmd.GET_BATT_AND_STORAGE).build()

    /**
     * Set the radio's frequency, bandwidth and coding.
     *
     * These four numbers are what decides who you can hear. Every node on a mesh must match on
     * all of them; a node with the right frequency and the wrong spreading factor is as deaf as
     * one on another band, and it looks from the inside exactly like an empty mesh.
     *
     * Note the units, which are not the same on both: [frequencyKhz] is **kHz** and
     * [bandwidthHz] is **Hz**, matching what the radio reports back in
     * [Frame.SelfInfo.frequencyKhz] and [Frame.SelfInfo.bandwidthHz]. The firmware accepts
     * 150000-2500000 kHz, 7000-500000 Hz, spreading factor 5-12 and coding rate 5-8.
     *
     * Unlike [setDevicePin] this takes effect at once — the firmware reconfigures the radio
     * before it answers — and is saved, so it survives a restart.
     *
     * [repeat] turns on client-side repeating and is off here: repeating is a repeater's job,
     * and a companion that relays is the thing MeshCore's design exists to avoid.
     */
    fun setRadioParams(
        frequencyKhz: Int,
        bandwidthHz: Int,
        spreadingFactor: Int,
        codingRate: Int,
        repeat: Boolean = false,
    ): ByteArray {
        require(frequencyKhz in 150_000..2_500_000) { "frequency out of range: $frequencyKhz kHz" }
        require(bandwidthHz in 7_000..500_000) { "bandwidth out of range: $bandwidthHz Hz" }
        require(spreadingFactor in 5..12) { "spreading factor out of range: $spreadingFactor" }
        require(codingRate in 5..8) { "coding rate out of range: $codingRate" }
        return FrameWriter(12)
            .u8(Cmd.SET_RADIO_PARAMS)
            .u32(frequencyKhz.toLong())
            .u32(bandwidthHz.toLong())
            .u8(spreadingFactor)
            .u8(codingRate)
            .u8(if (repeat) 1 else 0)
            .build()
    }

    /**
     * Fix the radio's Bluetooth pairing PIN.
     *
     * Worth doing once on any node with a screen. Where the PIN preference is unset **and the
     * board has a display**, the firmware invents a fresh six-digit PIN on every boot and
     * shows it only on that screen — so the PIN is different after every power cut, and a
     * headless reconnect has nothing to type. Setting it explicitly stops that.
     *
     * [pin] must be six digits, or 0 to hand the choice back to the firmware. Anything else
     * is refused with [Err.ILLEGAL_ARG].
     *
     * ⚠ The radio stores this but goes on using the PIN it picked at boot: the active one is
     * computed once at startup. **It takes effect after the radio restarts**, not now.
     */
    fun setDevicePin(pin: Int): ByteArray {
        require(pin == 0 || pin in 100000..999999) {
            "a device PIN is six digits, or 0 to let the radio choose; got $pin"
        }
        return FrameWriter(5).u8(Cmd.SET_DEVICE_PIN).u32(pin.toLong()).build()
    }

    /**
     * Log in to a repeater or room server, which is what lets you administer one.
     *
     * ⚠ This takes the **whole 32-byte public key**, not the six-byte prefix a message is
     * addressed by. The firmware looks the contact up on the full key here and on the prefix
     * there, and passing the wrong one gets [Err.NOT_FOUND] rather than anything explanatory.
     *
     * A repeater's password is `"password"` until somebody changes it, which is worth doing
     * before one goes up somewhere that needs a ladder: anyone in radio range can log in to
     * an untouched one.
     *
     * The reply is a [Resp.SENT], then later a [Push.LOGIN_SUCCESS] or [Push.LOGIN_FAIL] when
     * the far end answers over the air — which on a mesh can be many seconds.
     */
    fun sendLogin(publicKey: ByteArray, password: String): ByteArray {
        require(publicKey.size == Sizes.PUB_KEY) {
            "a login is addressed by the whole ${Sizes.PUB_KEY}-byte key, got ${publicKey.size}"
        }
        return FrameWriter().u8(Cmd.SEND_LOGIN).bytes(publicKey).text(password).build()
    }

    /** Drop the logged-in connection. Also takes the whole key. */
    fun logout(publicKey: ByteArray): ByteArray {
        require(publicKey.size == Sizes.PUB_KEY) {
            "a logout is addressed by the whole ${Sizes.PUB_KEY}-byte key, got ${publicKey.size}"
        }
        return FrameWriter().u8(Cmd.LOGOUT).bytes(publicKey).build()
    }

    /**
     * Send one CLI command to a node you are logged in to.
     *
     * It goes out as an ordinary text message with [TxtType.CLI_DATA] rather than by some
     * separate path, and the answer comes back as an ordinary received message with the same
     * type — so a console is a conversation that happens to be with a machine.
     *
     * Two things differ from a plain message and both are the firmware's doing: it replaces
     * the timestamp with its own RTC, to avoid tripping replay protection on a command sent
     * twice, and **no acknowledgement is expected**, so silence here is not delivery failure.
     */
    fun sendCliCommand(recipientPrefix: ByteArray, command: String, attempt: Int = 0): ByteArray =
        sendTextMessage(
            recipientPrefix = recipientPrefix,
            text = command,
            timestamp = 0, // replaced by the radio's own clock for CLI data
            attempt = attempt,
            txtType = TxtType.CLI_DATA,
        )

    /**
     * Throw away the route the radio has been using to reach a contact.
     *
     * Takes the **whole 32-byte key**, like a login and unlike a message. The radio sets the
     * contact's path back to unknown, which means the next message to them floods instead of
     * following a route that may no longer exist — a repeater that moved, or a node that
     * went away — and a reply to that flood teaches it the new one.
     *
     * ⚠ The firmware does **not** bump the contact's `lastmod` when it does this: the comment
     * in `MyMesh.cpp` says the app already has this version of the contact. So a sync for
     * what has changed will never report it, and an app that waits to be told will go on
     * drawing a route that has been thrown away. Whoever sends this updates their own copy.
     *
     * Answered with an OK, or with a not-found for a contact the radio does not have.
     */
    fun resetPath(publicKey: ByteArray): ByteArray {
        require(publicKey.size == Sizes.PUB_KEY) {
            "a path is reset by the whole ${Sizes.PUB_KEY}-byte key, got ${publicKey.size}"
        }
        return FrameWriter(1 + Sizes.PUB_KEY).u8(Cmd.RESET_PATH).bytes(publicKey).build()
    }

    /**
     * Ask what is in one of the radio's channel slots.
     *
     * Answered with [Resp.CHANNEL_INFO] for any index below the count in
     * [Frame.DeviceInfo.maxGroupChannels] — an unused slot answers too, with an empty name and
     * a key of zeros — and a not-found above it.
     */
    fun getChannel(index: Int): ByteArray {
        require(index in 0..255) { "a channel index is one byte, got $index" }
        return FrameWriter(2).u8(Cmd.GET_CHANNEL).u8(index).build()
    }

    /**
     * Put a channel into a slot, or empty the slot.
     *
     * The frame is the index, a 32-byte name padded with NULs, and a 16-byte key: exactly
     * 50 bytes. ⚠ The firmware decides which form it has been sent **by length** — at 66 bytes
     * or more it takes the frame for a 256-bit key and refuses it outright — so the key is
     * never padded out to the 32 bytes the radio stores.
     *
     * An empty [name] with a key of zeros is how a channel is left: there is no delete, only a
     * slot written back to what an unused one looks like. Answered with an OK, or a not-found
     * for an index past the radio's last slot. The radio saves it at once.
     */
    fun setChannel(index: Int, name: String, secret: ByteArray): ByteArray {
        require(index in 0..255) { "a channel index is one byte, got $index" }
        require(secret.size == Channels.SECRET) {
            "a channel key is ${Channels.SECRET} bytes, got ${secret.size}"
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= Channels.MAX_NAME_BYTES) {
            "a channel name fits in ${Channels.MAX_NAME_BYTES} bytes, got ${nameBytes.size}"
        }
        return FrameWriter(2 + 32 + Channels.SECRET)
            .u8(Cmd.SET_CHANNEL)
            .u8(index)
            .bytes(nameBytes.copyOf(32))
            .bytes(secret)
            .build()
    }

    /**
     * Say something on a channel.
     *
     * This is opcode 3, the one MeshCore's documentation calls the direct message. The radio
     * puts this node's name and a colon in front of the text itself (`sendGroupMessage` in
     * `BaseChatMesh.cpp`), so the text goes without one. ⚠ Name, colon and text together are
     * cut **silently** at [Sizes.MAX_TEXT] — unlike a direct message, which is refused — so a
     * caller has to leave room for the name.
     *
     * Answered with a bare OK, not a [Resp.SENT]: a channel has no acknowledgement, so there is
     * nothing further to wait for.
     */
    fun sendChannelMessage(index: Int, text: String, timestamp: Long): ByteArray {
        require(index in 0..255) { "a channel index is one byte, got $index" }
        return FrameWriter()
            .u8(Cmd.SEND_CHANNEL_TXT_MSG)
            .u8(TxtType.PLAIN) // the only type the firmware accepts on a channel
            .u8(index)
            .u32(timestamp)
            .text(text)
            .build()
    }

    /** Re-advertise this node so others can find it. */
    fun sendSelfAdvert(flood: Boolean = false): ByteArray =
        FrameWriter(2).u8(Cmd.SEND_SELF_ADVERT).u8(if (flood) 1 else 0).build()

    /**
     * The opening exchange, in the order the firmware needs it.
     *
     * Kept as a list rather than a comment because the order is not obvious and getting it
     * wrong is silent — see [deviceQuery].
     */
    fun handshake(appName: String = "amime"): List<ByteArray> =
        listOf(deviceQuery(), appStart(appName))
}
