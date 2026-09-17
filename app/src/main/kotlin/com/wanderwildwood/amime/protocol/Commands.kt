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
