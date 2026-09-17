package com.wanderwildwood.amime.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames are built here the way `MyMesh.cpp` builds them — same order, same widths, same
 * padding — so that a layout drifting apart from the firmware shows up as a failure rather
 * than as a message that reads like noise on the phone.
 */
class DecoderTest {

    private fun frame(vararg parts: Any): ByteArray {
        val out = FrameWriter()
        parts.forEach { part ->
            when (part) {
                is Int -> out.u8(part)
                is ByteArray -> out.bytes(part)
                is String -> out.text(part)
                else -> error("unsupported part $part")
            }
        }
        return out.build()
    }

    private fun u32(value: Long) = FrameWriter(4).u32(value).build()
    private fun i32(value: Int) = FrameWriter(4).i32(value).build()
    private fun u16(value: Int) = FrameWriter(2).u8(value and 0xFF).u8(value shr 8).build()

    /** A fixed-width text field, NUL-padded the way the firmware pads one. */
    private fun field(text: String, width: Int) =
        text.toByteArray().copyOf(width)

    // ---- the short frames ----

    @Test
    fun `the empty frame is malformed rather than a crash`() {
        assertEquals(Frame.Malformed(-1, 0), Decoder.decode(ByteArray(0)))
    }

    @Test
    fun `plain acknowledgements decode`() {
        assertEquals(Frame.Ok, Decoder.decode(byteArrayOf(0)))
        assertEquals(Frame.Failed(Err.NOT_FOUND), Decoder.decode(byteArrayOf(1, 2)))
        assertEquals(Frame.Disabled, Decoder.decode(byteArrayOf(15)))
        assertEquals(Frame.NoMoreMessages, Decoder.decode(byteArrayOf(10)))
    }

    /**
     * A tickle and nothing more. If this ever decodes to something carrying a count, the
     * sync loop that drains the queue has been built on a promise the radio never made.
     */
    @Test
    fun `messages-waiting carries nothing`() {
        assertEquals(Frame.MessagesWaiting, Decoder.decode(byteArrayOf(0x83.toByte())))
    }

    @Test
    fun `an unknown code is kept whole instead of dropped`() {
        val unknown = byteArrayOf(0x7E, 1, 2, 3)
        val decoded = Decoder.decode(unknown) as Frame.Unhandled
        assertEquals(0x7E, decoded.code)
        assertArrayEquals(unknown, decoded.bytes)
    }

    @Test
    fun `a truncated frame reports itself rather than reading past the end`() {
        // A SELF_INFO that stops halfway through the public key.
        val decoded = Decoder.decode(byteArrayOf(5, 1, 20, 22) + ByteArray(10))
        assertEquals(Frame.Malformed(Resp.SELF_INFO, 14), decoded)
    }

    // ---- device info ----

    @Test
    fun `device info doubles the halved contact capacity`() {
        val f = frame(
            Resp.DEVICE_INFO,
            13,                       // firmware version code
            50,                       // MAX_CONTACTS / 2
            8,                        // group channels
            u32(123456),              // BLE pin
            field("24 Aug 2026", 12),
            field("Elecrow", 40),
            field("v1.16.0", 20),
            0,                        // not a repeater
            1,                        // path hash mode
        )
        val info = Decoder.decode(f) as Frame.DeviceInfo
        assertEquals(13, info.firmwareVersionCode)
        assertEquals("the wire carries half the real capacity", 100, info.maxContacts)
        assertEquals(123456L, info.blePin)
        assertEquals("Elecrow", info.manufacturer)
        assertEquals("v1.16.0", info.firmwareVersion)
        assertFalse(info.isRepeater)
        assertEquals(1, info.pathHashMode)
    }

    /** The default, and the one that has to be typed into Android's pairing dialog. */
    @Test
    fun `the BLE pin comes back from the device itself`() {
        val f = frame(
            Resp.DEVICE_INFO, 13, 50, 8, u32(123456),
            field("", 12), field("", 40), field("", 20),
        )
        assertEquals(123456L, (Decoder.decode(f) as Frame.DeviceInfo).blePin)
    }

    // ---- self info ----

    @Test
    fun `self info reads the radio settings and the node name`() {
        val key = ByteArray(Sizes.PUB_KEY) { it.toByte() }
        val f = frame(
            Resp.SELF_INFO,
            AdvType.CHAT,
            22,                 // tx power
            30,                 // max tx power
            key,
            i32(35_595_000),    // latitude, micro-degrees
            i32(-82_550_000),   // longitude
            0, 0, 0, 0,
            u32(910_525),       // frequency, kHz
            u32(250_000),       // bandwidth, Hz
            11,                 // spreading factor
            5,                  // coding rate
            "wndr-node",
        )
        val self = Decoder.decode(f) as Frame.SelfInfo
        assertEquals(AdvType.CHAT, self.advertType)
        assertEquals(22, self.txPowerDbm)
        assertArrayEquals(key, self.publicKey)
        assertEquals(35_595_000, self.latitude)
        assertEquals(-82_550_000, self.longitude)
        // Deliberately different units: MHz*1000 for one, kHz*1000 for the other.
        assertEquals(910_525L, self.frequencyKhz)
        assertEquals(250_000L, self.bandwidthHz)
        assertEquals(11, self.spreadingFactor)
        assertEquals("wndr-node", self.name)
    }

    // ---- contacts ----

    private fun contactFrame(
        code: Int,
        name: String,
        pathLen: Int = 0,
        lastMod: Long = 100,
    ) = frame(
        code,
        ByteArray(Sizes.PUB_KEY) { 0x11 },
        AdvType.CHAT,
        0,
        pathLen,
        ByteArray(Sizes.MAX_PATH) { 0x22 },
        field(name, Sizes.NAME),
        u32(50),
        i32(0),
        i32(0),
        u32(lastMod),
    )

    @Test
    fun `a contact frame is 148 bytes and the name is read out of the middle`() {
        val f = contactFrame(Resp.CONTACT, "ridge-node")
        assertEquals(148, f.size)
        val contact = Decoder.decode(f) as Frame.Contact
        assertEquals("ridge-node", contact.name)
        assertEquals(100L, contact.lastMod)
        assertFalse(contact.isNewAdvert)
    }

    /**
     * A name filling all 32 bytes has no NUL after it. Reading to the terminator would walk
     * into the timestamp behind it.
     */
    @Test
    fun `a name that fills the field is not over-read`() {
        val full = "x".repeat(Sizes.NAME)
        val contact = Decoder.decode(contactFrame(Resp.CONTACT, full)) as Frame.Contact
        assertEquals(full, contact.name)
        assertEquals(Sizes.NAME, contact.name.length)
    }

    @Test
    fun `only the used part of the path is kept`() {
        val contact = Decoder.decode(contactFrame(Resp.CONTACT, "n", pathLen = 3)) as Frame.Contact
        assertEquals(3, contact.outPath.size)
        val flooded = Decoder.decode(contactFrame(Resp.CONTACT, "n", pathLen = 0)) as Frame.Contact
        assertEquals(0, flooded.outPath.size)
    }

    /** Same 148 bytes, different meaning: someone new turned up by themselves. */
    @Test
    fun `an unprompted advert is the same layout but marked`() {
        val contact = Decoder.decode(contactFrame(Push.NEW_ADVERT, "stranger")) as Frame.Contact
        assertTrue(contact.isNewAdvert)
        assertEquals("stranger", contact.name)
    }

    @Test
    fun `a contact is addressed by the first six bytes of its key`() {
        val contact = Decoder.decode(contactFrame(Resp.CONTACT, "n")) as Frame.Contact
        assertEquals(Sizes.PUB_KEY_PREFIX, contact.prefix.size)
        assertArrayEquals(ByteArray(6) { 0x11 }, contact.prefix)
    }

    @Test
    fun `the list is bracketed by a count and a lastmod`() {
        assertEquals(
            Frame.ContactsStart(7),
            Decoder.decode(frame(Resp.CONTACTS_START, u32(7))),
        )
        assertEquals(
            Frame.ContactsEnd(900),
            Decoder.decode(frame(Resp.END_OF_CONTACTS, u32(900))),
        )
    }

    // ---- messages ----

    @Test
    fun `a received message divides the SNR back down by four`() {
        val f = frame(
            Resp.CONTACT_MSG_RECV_V3,
            (-26).toByte().toInt() and 0xFF,  // -6.5 dB, times four
            0, 0,
            ByteArray(6) { 0x11 },
            PATH_LEN_DIRECT,
            TxtType.PLAIN,
            u32(1_757_000_000),
            "on my way",
        )
        val msg = Decoder.decode(f) as Frame.MessageReceived
        assertEquals(-6.5f, msg.snr, 0.001f)
        assertTrue(msg.cameDirect)
        assertEquals("on my way", msg.text)
        assertEquals(1_757_000_000L, msg.senderTimestamp)
        assertNull(msg.signedSenderPrefix)
    }

    @Test
    fun `a flooded message reports the hops it took`() {
        val f = frame(
            Resp.CONTACT_MSG_RECV_V3, 20, 0, 0, ByteArray(6), 2, TxtType.PLAIN,
            u32(1), "via two",
        )
        val msg = Decoder.decode(f) as Frame.MessageReceived
        assertFalse(msg.cameDirect)
        assertEquals(2, msg.pathLength)
    }

    /**
     * A signed message carries four bytes of sender prefix before the text. Treating them as
     * text is what puts rubbish on the front of the message.
     */
    @Test
    fun `a signed message keeps its four extra bytes out of the text`() {
        val f = frame(
            Resp.CONTACT_MSG_RECV_V3, 20, 0, 0, ByteArray(6), PATH_LEN_DIRECT,
            TxtType.SIGNED_PLAIN,
            u32(1),
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            "signed text",
        )
        val msg = Decoder.decode(f) as Frame.MessageReceived
        assertEquals("signed text", msg.text)
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            msg.signedSenderPrefix,
        )
    }

    /**
     * What the radio sends when it was never told what this app understands. The offsets are
     * all different, so decoding one with the v3 reader yields a plausible-looking wrong
     * answer rather than an error — which is why the legacy layout is parsed rather than
     * refused.
     */
    @Test
    fun `the pre-v3 layout is read with its own offsets and no SNR`() {
        val f = frame(
            Resp.CONTACT_MSG_RECV,
            ByteArray(6) { 0x11 },
            PATH_LEN_DIRECT,
            TxtType.PLAIN,
            u32(42),
            "older",
        )
        val msg = Decoder.decode(f) as Frame.MessageReceived
        assertEquals("older", msg.text)
        assertEquals(42L, msg.senderTimestamp)
        assertTrue("there is no SNR in the legacy frame", msg.snr.isNaN())
    }

    // ---- the rest ----

    /**
     * The one frame that answers "is there anybody out there" when the contact list cannot:
     * the radio logs every raw packet before it tries to parse it.
     */
    @Test
    fun `a heard packet carries real SNR and RSSI`() {
        val f = frame(
            Push.LOG_RX_DATA,
            (-30).toByte().toInt() and 0xFF,   // -7.5 dB, times four
            (-96).toByte().toInt() and 0xFF,   // dBm, already signed
            byteArrayOf(0x11, 0x22, 0x33),
        )
        val heard = Decoder.decode(f) as Frame.PacketHeard
        assertEquals(-7.5f, heard.snr, 0.001f)
        assertEquals(-96, heard.rssi)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), heard.bytes)
    }

    @Test
    fun `a heard packet with no payload still reports its signal`() {
        val heard = Decoder.decode(frame(Push.LOG_RX_DATA, 20, (-70).toByte().toInt() and 0xFF))
        assertEquals(Frame.PacketHeard(5f, -70, ByteArray(0)), heard)
    }

    @Test
    fun `a login answer says whether it granted administration`() {
        val prefix = ByteArray(Sizes.PUB_KEY_PREFIX) { 0x11 }
        val guest = Decoder.decode(frame(Push.LOGIN_SUCCESS, 0, prefix)) as Frame.LoginSucceeded
        assertFalse("permissions of zero is a guest", guest.isAdmin)
        assertNull("the legacy frame carries no clock", guest.serverTimestamp)

        val full = frame(Push.LOGIN_SUCCESS, 1, prefix, u32(1_757_000_000), 3, 13)
        val admin = Decoder.decode(full) as Frame.LoginSucceeded
        assertTrue(admin.isAdmin)
        assertEquals(1_757_000_000L, admin.serverTimestamp)
        assertEquals(3, admin.aclPermissions)
        assertEquals(13, admin.firmwareLevel)
    }

    @Test
    fun `a refused login says who refused and nothing else`() {
        val f = frame(Push.LOGIN_FAIL, 0, ByteArray(Sizes.PUB_KEY_PREFIX) { 0x22 })
        val failed = Decoder.decode(f) as Frame.LoginFailed
        assertArrayEquals(ByteArray(6) { 0x22 }, failed.senderPrefix)
    }

    @Test
    fun `a send is accepted, which is not the same as delivered`() {
        val sent = Decoder.decode(frame(Resp.SENT, 1, u32(0xABCD), u32(9000))) as Frame.Sent
        assertTrue(sent.byFlood)
        assertEquals(0xABCDL, sent.expectedAck)
        assertEquals(9000L, sent.estimatedTimeoutMs)
    }

    @Test
    fun `an ack of zero means no confirmation is ever coming`() {
        val sent = Decoder.decode(frame(Resp.SENT, 0, u32(0), u32(0))) as Frame.Sent
        assertEquals(0L, sent.expectedAck)
        assertFalse(sent.byFlood)
    }

    @Test
    fun `a confirmation carries the hash it answers and the round trip`() {
        val confirmed =
            Decoder.decode(frame(Push.SEND_CONFIRMED, u32(0xABCD), u32(1500))) as Frame.SendConfirmed
        assertEquals(0xABCDL, confirmed.ackHash)
        assertEquals(1500L, confirmed.roundTripMs)
    }

    @Test
    fun `battery comes with storage, or without it on older firmware`() {
        val full = Decoder.decode(
            frame(Resp.BATT_AND_STORAGE, u16(4310), u32(120), u32(1024)),
        ) as Frame.BattAndStorage
        assertEquals(4310, full.batteryMillivolts)
        assertEquals(120L, full.storageUsedKb)
        assertEquals(1024L, full.storageTotalKb)

        val bare = Decoder.decode(frame(Resp.BATT_AND_STORAGE, u16(4310))) as Frame.BattAndStorage
        assertEquals(4310, bare.batteryMillivolts)
        assertNull(bare.storageUsedKb)
    }
}
