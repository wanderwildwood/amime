package com.wanderwildwood.amime.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Channel keys, channel frames out, and channel frames in. */
class ChannelsTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

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

    // ---- keys ----

    /** `PUBLIC_GROUP_PSK` in `examples/companion_radio/MyMesh.cpp`, which every radio is given at boot. */
    @Test
    fun `Public's key is the firmware's own, decoded`() {
        val firmware = Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")
        assertArrayEquals(firmware, Channels.PUBLIC_KEY)
        assertTrue(Channels.isPublic(firmware))
    }

    /** The worked example in `docs/companion_protocol.md`, "Channel Types". */
    @Test
    fun `a hashtag key is the first sixteen bytes of sha256 over the name with its hash`() {
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", Channels.hashtagKey("#test").hex())
    }

    @Test
    fun `the hash is put on when it was left off, and space around the name is dropped`() {
        assertArrayEquals(Channels.hashtagKey("#test"), Channels.hashtagKey("test"))
        assertArrayEquals(Channels.hashtagKey("#test"), Channels.hashtagKey("  #test "))
        assertEquals("#meshavl", Channels.hashtagName("meshavl"))
    }

    /** Every other client hashes the name as typed; folding case here would be a different channel. */
    @Test
    fun `case is part of the name`() {
        assertFalse(Channels.hashtagKey("#Test").contentEquals(Channels.hashtagKey("#test")))
    }

    @Test
    fun `a private key is 32 hex characters, spaces allowed`() {
        val key = Channels.parseKey("00112233 44556677 8899aabb ccddeeff")!!
        assertEquals("00112233445566778899aabbccddeeff", key.hex())
        assertNull(Channels.parseKey("0011"))
        assertNull(Channels.parseKey("zz112233445566778899aabbccddeeff"))
    }

    @Test
    fun `a name alone joins a hashtag channel`() {
        val join = Channels.join("meshavl", "") as Channels.Join.Ok
        assertEquals("#meshavl", join.name)
        assertArrayEquals(Channels.hashtagKey("#meshavl"), join.secret)
    }

    @Test
    fun `a name and a key join a private channel under the name as typed`() {
        val join = Channels.join(" Valley ", "00112233445566778899aabbccddeeff") as Channels.Join.Ok
        assertEquals("Valley", join.name)
        assertEquals("00112233445566778899aabbccddeeff", join.secret.hex())
    }

    @Test
    fun `a join with nothing usable says why`() {
        assertEquals(Channels.Join.NoName, Channels.join(" # ", ""))
        assertEquals(Channels.Join.BadKey, Channels.join("valley", "123"))
        assertEquals(Channels.Join.NameTooLong, Channels.join("x".repeat(31), ""))
        assertTrue(Channels.join("x".repeat(30), "") is Channels.Join.Ok)
    }

    // ---- frames out ----

    @Test
    fun `asking about a slot is the opcode and the index`() {
        assertArrayEquals(byteArrayOf(31, 7), Commands.getChannel(7))
    }

    /**
     * Exactly 50 bytes. At 66 or more the firmware takes the frame for a 256-bit key and
     * refuses it, so the key must not be padded to the 32 bytes the radio stores.
     */
    @Test
    fun `setting a slot is index, a 32-byte name, then a 16-byte key`() {
        val key = Channels.hashtagKey("#test")
        val f = Commands.setChannel(2, "#test", key)
        assertEquals(50, f.size)
        assertEquals(Cmd.SET_CHANNEL, f.u8(0))
        assertEquals(2, f.u8(1))
        assertEquals("#test", f.strz(2, 32))
        assertEquals(0, f.u8(2 + 5)) // padded with NULs
        assertArrayEquals(key, f.copyOfRange(34, 50))
    }

    @Test
    fun `leaving writes an empty name and a key of zeros`() {
        val f = Commands.setChannel(3, "", ByteArray(16))
        assertTrue(f.copyOfRange(2, 50).all { it == 0.toByte() })
    }

    @Test
    fun `a slot will not take a key of the wrong size or a name that does not fit`() {
        assertThrows(IllegalArgumentException::class.java) { Commands.setChannel(1, "#a", ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setChannel(1, "x".repeat(32), ByteArray(16)) }
    }

    /** Opcode 3 — the one the documentation gives for a direct message. */
    @Test
    fun `a channel message is opcode 3, plain, the slot, the time, then the words`() {
        val f = Commands.sendChannelMessage(index = 1, text = "hi", timestamp = 0x01020304)
        assertEquals("0300010403020168" + "69", f.hex())
        assertEquals(Cmd.SEND_CHANNEL_TXT_MSG, f.u8(0))
    }

    // ---- frames in ----

    /** Laid out as `onChannelMessageRecv` in `MyMesh.cpp` lays it out for a v3 app. */
    @Test
    fun `a channel message carries its slot, and the sender inside the text`() {
        val f = frame(
            Resp.CHANNEL_MSG_RECV_V3, -26, 0, 0, 4, 2, TxtType.PLAIN,
            FrameWriter(4).u32(1_700_000_000).build(), "Tomas: the bridge is out",
        )
        val m = Decoder.decode(f) as Frame.ChannelMessageReceived
        assertEquals(-6.5f, m.snr, 0.001f)
        assertEquals(4, m.channelIndex)
        assertEquals(2, m.pathLength)
        assertFalse(m.cameDirect)
        assertEquals(1_700_000_000L, m.senderTimestamp)
        assertEquals("Tomas: the bridge is out", m.text)
    }

    @Test
    fun `the older channel layout has no signal reading`() {
        val f = frame(
            Resp.CHANNEL_MSG_RECV, 0, PATH_LEN_DIRECT, TxtType.PLAIN,
            FrameWriter(4).u32(5).build(), "Ada: hello",
        )
        val m = Decoder.decode(f) as Frame.ChannelMessageReceived
        assertTrue(m.snr.isNaN())
        assertEquals(0, m.channelIndex)
        assertTrue(m.cameDirect)
        assertEquals("Ada: hello", m.text)
    }

    @Test
    fun `a slot reports its name and key, and an unused one is empty`() {
        val key = Channels.hashtagKey("#test")
        val used = Decoder.decode(frame(Resp.CHANNEL_INFO, 1, "#test".toByteArray().copyOf(32), key))
            as Frame.ChannelInfo
        assertEquals(1, used.index)
        assertEquals("#test", used.name)
        assertArrayEquals(key, used.secret)
        assertFalse(used.isEmpty)

        val unused = Decoder.decode(frame(Resp.CHANNEL_INFO, 9, ByteArray(32), ByteArray(16)))
            as Frame.ChannelInfo
        assertTrue(unused.isEmpty)
    }

    @Test
    fun `a short slot report is malformed rather than misread`() {
        assertTrue(Decoder.decode(frame(Resp.CHANNEL_INFO, 1, ByteArray(10))) is Frame.Malformed)
    }
}
