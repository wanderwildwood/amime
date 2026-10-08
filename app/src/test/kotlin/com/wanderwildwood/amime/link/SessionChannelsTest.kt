package com.wanderwildwood.amime.link

import com.wanderwildwood.amime.protocol.AdvType
import com.wanderwildwood.amime.protocol.Channels
import com.wanderwildwood.amime.protocol.Cmd
import com.wanderwildwood.amime.protocol.Err
import com.wanderwildwood.amime.protocol.Frame
import com.wanderwildwood.amime.protocol.FrameWriter
import com.wanderwildwood.amime.protocol.Push
import com.wanderwildwood.amime.protocol.Resp
import com.wanderwildwood.amime.protocol.Sizes
import com.wanderwildwood.amime.protocol.TxtType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionChannelsTest {

    private lateinit var transport: RecordingTransport
    private lateinit var session: Session
    private lateinit var heard: Heard

    private class Heard : Session.Listener {
        val channels = mutableListOf<Frame.ChannelInfo>()
        var loaded = 0
        val channelMessages = mutableListOf<Frame.ChannelMessageReceived>()
        var channelSent = 0
        val channelSendFailures = mutableListOf<Int>()
        val set = mutableListOf<Pair<Int, String>>()
        val setFailures = mutableListOf<Int>()
        val failures = mutableListOf<Int>()
        val sent = mutableListOf<Frame.Sent>()

        override fun onChannel(info: Frame.ChannelInfo) { channels += info }
        override fun onChannelsLoaded() { loaded++ }
        override fun onChannelMessage(message: Frame.ChannelMessageReceived) { channelMessages += message }
        override fun onChannelSent() { channelSent++ }
        override fun onChannelSendFailed(code: Int) { channelSendFailures += code }
        override fun onChannelSet(index: Int, name: String, secret: ByteArray) { set += index to name }
        override fun onChannelSetFailed(index: Int, code: Int) { setFailures += index }
        override fun onFailed(code: Int) { failures += code }
        override fun onSent(sent: Frame.Sent, awaitingAck: Boolean) { this.sent += sent }
    }

    @Before
    fun setUp() {
        transport = RecordingTransport()
        heard = Heard()
        session = Session(transport, heard)
        session.start()
        transport.clear()
    }

    private fun connect(slots: Int) {
        session.onFrame(deviceInfo(slots))
        session.onFrame(selfInfo())
    }

    // ---- reading the slots ----

    /** The radio's outgoing queue holds four frames, so forty questions at once would mostly go unanswered. */
    @Test
    fun `slots are read one at a time, each after the last has answered`() {
        connect(slots = 3)
        assertEquals(listOf(Cmd.GET_CHANNEL), transport.opcodes())
        assertEquals(0, transport.sent.last()[1].toInt())

        session.onFrame(channelInfo(0, "Public", Channels.PUBLIC_KEY))
        assertEquals(1, transport.sent.last()[1].toInt())
        session.onFrame(channelInfo(1, "", ByteArray(16)))
        assertEquals(2, transport.sent.last()[1].toInt())
        assertEquals(0, heard.loaded)

        session.onFrame(channelInfo(2, "#test", Channels.hashtagKey("#test")))
        assertEquals(3, transport.sent.size)
        assertEquals(1, heard.loaded)
        assertEquals(listOf("Public", "", "#test"), heard.channels.map { it.name })
    }

    @Test
    fun `a slot that errors does not stop the walk`() {
        connect(slots = 2)
        session.onFrame(error(Err.NOT_FOUND))
        assertEquals(1, transport.sent.last()[1].toInt())
        assertTrue("a refused slot is not a refused message", heard.failures.isEmpty())
    }

    @Test
    fun `a radio without channels is loaded at once`() {
        connect(slots = 0)
        assertEquals(1, heard.loaded)
        assertTrue(transport.sent.isEmpty())
    }

    // ---- the queue ----

    /**
     * Before channels were read, a channel message came off the queue as an unknown frame and
     * nothing asked for the next one, so every message behind it waited until a reconnect.
     */
    @Test
    fun `a channel message is passed on and the drain goes on`() {
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        transport.clear()
        session.onFrame(channelMessage(0, "Ada: morning"))

        assertEquals("Ada: morning", heard.channelMessages.single().text)
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())
    }

    @Test
    fun `a channel datagram is skipped and the drain goes on`() {
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        transport.clear()
        session.onFrame(byteArrayOf(Resp.CHANNEL_DATA_RECV.toByte(), 0, 0, 0, 0, 0))
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())
    }

    @Test
    fun `an unknown reply mid-drain asks for the next rather than stalling`() {
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        transport.clear()
        session.onFrame(byteArrayOf(0x3E, 1, 2))
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())
    }

    // ---- whose answer is it ----

    @Test
    fun `an OK settles a channel message`() {
        session.sendChannelMessage(0, "hello", 1)
        session.onFrame(byteArrayOf(Resp.OK.toByte()))
        assertEquals(1, heard.channelSent)
    }

    /** It used to be blamed on whichever direct message was oldest. */
    @Test
    fun `a refused channel message is not a refused direct message`() {
        session.sendChannelMessage(5, "hello", 1)
        session.sendMessage(ByteArray(Sizes.PUB_KEY_PREFIX), "hi", 1)
        session.onFrame(error(Err.NOT_FOUND))

        assertEquals(listOf(Err.NOT_FOUND), heard.channelSendFailures)
        assertTrue(heard.failures.isEmpty())

        session.onFrame(sent())
        assertEquals("the direct message still gets its own answer", 1, heard.sent.size)
    }

    @Test
    fun `an announce's OK is not taken for a channel message's`() {
        session.advertise()
        session.sendChannelMessage(0, "hello", 1)
        session.onFrame(byteArrayOf(Resp.OK.toByte()))
        assertEquals(0, heard.channelSent)
        session.onFrame(byteArrayOf(Resp.OK.toByte()))
        assertEquals(1, heard.channelSent)
    }

    @Test
    fun `a slot written is reported with what was written`() {
        session.setChannel(3, "#test", Channels.hashtagKey("#test"))
        session.onFrame(byteArrayOf(Resp.OK.toByte()))
        assertEquals(listOf(3 to "#test"), heard.set)
    }

    @Test
    fun `a slot refused is reported against its index`() {
        session.leaveChannel(7)
        session.onFrame(error(Err.NOT_FOUND))
        assertEquals(listOf(7), heard.setFailures)
        assertTrue(heard.failures.isEmpty())
    }

    /** A login's `Sent` is not a message's, and used to settle one. */
    @Test
    fun `a login's Sent is not passed on as a message's`() {
        session.login(ByteArray(Sizes.PUB_KEY), "pw")
        session.onFrame(sent())
        assertTrue(heard.sent.isEmpty())
    }

    // ---- frames, shaped as the firmware shapes them ----

    private fun build(vararg parts: Any): ByteArray {
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

    private fun u32(value: Long) = ByteArray(4) { ((value shr (it * 8)) and 0xFF).toByte() }

    private fun error(code: Int) = byteArrayOf(Resp.ERR.toByte(), code.toByte())

    private fun sent() = build(Resp.SENT, 0, u32(0x99), u32(5000))

    private fun deviceInfo(slots: Int) = build(
        Resp.DEVICE_INFO, 13, 175, slots, u32(0), ByteArray(12), ByteArray(40), ByteArray(20),
    )

    private fun selfInfo() = build(
        Resp.SELF_INFO, AdvType.CHAT, 22, 22, ByteArray(Sizes.PUB_KEY),
        u32(0), u32(0), 0, 0, 0, 0, u32(910_525), u32(62_500), 7, 5, "hollow-node",
    )

    private fun channelInfo(index: Int, name: String, secret: ByteArray) =
        build(Resp.CHANNEL_INFO, index, name.toByteArray().copyOf(32), secret)

    private fun channelMessage(index: Int, text: String) = build(
        Resp.CHANNEL_MSG_RECV_V3, 20, 0, 0, index, 1, TxtType.PLAIN, u32(1), text,
    )
}
