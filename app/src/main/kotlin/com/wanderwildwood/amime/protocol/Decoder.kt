package com.wanderwildwood.amime.protocol

/**
 * Turns a frame off the radio into a [Frame].
 *
 * Nothing here throws on a frame it does not recognise or cannot fit. A radio a firmware
 * version ahead of this app will send codes it has never heard of, and a client that treats
 * that as a fault is a client that stops working on an update it did not ask for. Unknown
 * codes come back as [Frame.Unhandled] and short ones as [Frame.Malformed]; both are worth
 * logging and neither is worth dropping the connection over.
 */
object Decoder {

    fun decode(frame: ByteArray): Frame {
        if (frame.isEmpty()) return Frame.Malformed(code = -1, length = 0)
        val code = frame.u8(0)

        fun short() = Frame.Malformed(code, frame.size)

        return when (code) {
            Resp.OK -> Frame.Ok
            Resp.ERR -> if (frame.size >= 2) Frame.Failed(frame.u8(1)) else short()
            Resp.DISABLED -> Frame.Disabled
            Resp.NO_MORE_MESSAGES -> Frame.NoMoreMessages
            Push.MSG_WAITING -> Frame.MessagesWaiting

            Resp.DEVICE_INFO -> if (frame.size >= 80) deviceInfo(frame) else short()
            Resp.SELF_INFO -> if (frame.size >= 58) selfInfo(frame) else short()
            Resp.SENT -> if (frame.size >= 10) sent(frame) else short()

            Resp.CONTACTS_START ->
                if (frame.size >= 5) Frame.ContactsStart(frame.u32(1)) else short()
            Resp.END_OF_CONTACTS ->
                if (frame.size >= 5) Frame.ContactsEnd(frame.u32(1)) else short()
            Resp.CONTACT ->
                if (frame.size >= CONTACT_FRAME_SIZE) contact(frame, isNewAdvert = false)
                else short()
            Push.NEW_ADVERT ->
                if (frame.size >= CONTACT_FRAME_SIZE) contact(frame, isNewAdvert = true)
                else short()

            Resp.CONTACT_MSG_RECV_V3 -> if (frame.size >= 16) messageV3(frame) else short()
            Resp.CONTACT_MSG_RECV -> if (frame.size >= 13) messageLegacy(frame) else short()

            Resp.BATT_AND_STORAGE -> if (frame.size >= 3) battery(frame) else short()

            Push.LOGIN_SUCCESS -> if (frame.size >= 8) loginSucceeded(frame) else short()
            Push.LOGIN_FAIL ->
                if (frame.size >= 8) {
                    Frame.LoginFailed(frame.copyOfRange(2, 2 + Sizes.PUB_KEY_PREFIX))
                } else {
                    short()
                }

            Push.LOG_RX_DATA ->
                if (frame.size >= 3) {
                    Frame.PacketHeard(
                        snr = frame.i8(1) / 4f,
                        rssi = frame.i8(2),
                        bytes = frame.copyOfRange(3, frame.size),
                    )
                } else {
                    short()
                }

            Push.SEND_CONFIRMED ->
                if (frame.size >= 9) Frame.SendConfirmed(frame.u32(1), frame.u32(5)) else short()

            else -> Frame.Unhandled(code, frame)
        }
    }

    /**
     * Two layouts share this code. The eight-byte one is what a legacy repeater sends and
     * carries nothing but the prefix; the longer one adds the server's clock and its firmware
     * level. Reading the longer fields off a short frame is why the length is checked rather
     * than assumed.
     */
    private fun loginSucceeded(f: ByteArray) = Frame.LoginSucceeded(
        permissions = f.u8(1),
        senderPrefix = f.copyOfRange(2, 2 + Sizes.PUB_KEY_PREFIX),
        serverTimestamp = if (f.size >= 12) f.u32(8) else null,
        aclPermissions = if (f.size >= 13) f.u8(12) else null,
        firmwareLevel = if (f.size >= 14) f.u8(13) else null,
    )

    private fun deviceInfo(f: ByteArray) = Frame.DeviceInfo(
        firmwareVersionCode = f.u8(1),
        // Halved by the firmware to fit a byte. 50 on the wire means a node holding 100.
        maxContacts = f.u8(2) * 2,
        maxGroupChannels = f.u8(3),
        blePin = f.u32(4),
        buildDate = f.strz(8, 12),
        manufacturer = f.strz(20, 40),
        firmwareVersion = f.strz(60, 20),
        // Both appear only on later firmware. Absent is not false, but it is the safer read
        // of "this node does not tell us", and the length check above allows the frame to end
        // before either of them.
        isRepeater = f.size > 80 && f.u8(80) == 1,
        pathHashMode = if (f.size > 81) f.u8(81) else 0,
    )

    private fun selfInfo(f: ByteArray) = Frame.SelfInfo(
        advertType = f.u8(1),
        txPowerDbm = f.u8(2),
        maxTxPowerDbm = f.u8(3),
        publicKey = f.copyOfRange(4, 4 + Sizes.PUB_KEY),
        latitude = f.i32(36),
        longitude = f.i32(40),
        multiAcks = f.u8(44),
        advertLocationPolicy = f.u8(45),
        telemetryModes = f.u8(46),
        manualAddContacts = f.u8(47) == 1,
        frequencyKhz = f.u32(48),
        bandwidthHz = f.u32(52),
        spreadingFactor = f.u8(56),
        codingRate = f.u8(57),
        // The name has no length and no terminator: it is whatever is left of the frame.
        name = f.tail(58),
    )

    private fun sent(f: ByteArray) = Frame.Sent(
        byFlood = f.u8(1) == 1,
        expectedAck = f.u32(2),
        estimatedTimeoutMs = f.u32(6),
    )

    private fun contact(f: ByteArray, isNewAdvert: Boolean): Frame.Contact {
        val pathLen = f.u8(35)
        return Frame.Contact(
            publicKey = f.copyOfRange(1, 1 + Sizes.PUB_KEY),
            type = f.u8(33),
            flags = f.u8(34),
            // The path field is always 64 bytes on the wire; only the first pathLen of them
            // mean anything, and a node reached by flood reports 0 rather than a short path.
            outPath = f.copyOfRange(36, 36 + pathLen.coerceIn(0, Sizes.MAX_PATH)),
            name = f.strz(100, Sizes.NAME),
            lastAdvert = f.u32(132),
            latitude = f.i32(136),
            longitude = f.i32(140),
            lastMod = f.u32(144),
            isNewAdvert = isNewAdvert,
        )
    }

    private fun messageV3(f: ByteArray): Frame.MessageReceived {
        val txtType = f.u8(11)
        // A signed message puts four bytes of sender prefix between the header and the text.
        // Reading them as text is what produces the mojibake at the front of a signed message.
        val signed = txtType == TxtType.SIGNED_PLAIN && f.size >= 20
        return Frame.MessageReceived(
            snr = f.i8(1) / 4f,
            senderPrefix = f.copyOfRange(4, 4 + Sizes.PUB_KEY_PREFIX),
            pathLength = f.u8(10),
            txtType = txtType,
            senderTimestamp = f.u32(12),
            signedSenderPrefix = if (signed) f.copyOfRange(16, 20) else null,
            text = f.tail(if (signed) 20 else 16),
        )
    }

    /**
     * The pre-v3 layout, which a radio sends when it has not been told what this app
     * understands. It has no SNR, so there is nothing honest to report for it.
     *
     * Seeing one of these in practice means [Commands.deviceQuery] was not sent first.
     */
    private fun messageLegacy(f: ByteArray): Frame.MessageReceived {
        val txtType = f.u8(8)
        val signed = txtType == TxtType.SIGNED_PLAIN && f.size >= 17
        return Frame.MessageReceived(
            snr = Float.NaN,
            senderPrefix = f.copyOfRange(1, 1 + Sizes.PUB_KEY_PREFIX),
            pathLength = f.u8(7),
            txtType = txtType,
            senderTimestamp = f.u32(9),
            signedSenderPrefix = if (signed) f.copyOfRange(13, 17) else null,
            text = f.tail(if (signed) 17 else 13),
        )
    }

    private fun battery(f: ByteArray) = Frame.BattAndStorage(
        batteryMillivolts = f.u16(1),
        // Older firmware answers with the voltage and nothing else.
        storageUsedKb = if (f.size >= 7) f.u32(3) else null,
        storageTotalKb = if (f.size >= 11) f.u32(7) else null,
    )

    /** 1 code + 32 key + 3 + 64 path + 32 name + 4 + 4 + 4 + 4. */
    private const val CONTACT_FRAME_SIZE = 148
}
