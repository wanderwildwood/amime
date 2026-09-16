package com.wanderwildwood.amime.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    /**
     * The documentation lists 0x03 as "send text message". It is not: 3 is
     * CMD_SEND_CHANNEL_TXT_MSG, and a client built to the doc broadcasts every private
     * message to a group channel. This test exists to fail if anyone ever "corrects" the
     * opcode back to what the doc says.
     */
    @Test
    fun `a direct message uses opcode 2, not the channel opcode 3`() {
        val frame = Commands.sendTextMessage(
            recipientPrefix = ByteArray(6) { (it + 1).toByte() },
            text = "hi",
            timestamp = 0,
        )
        assertEquals(Cmd.SEND_TXT_MSG, frame.u8(0))
        assertEquals(2, frame.u8(0))
        assertTrue("opcode 3 is the channel command", frame.u8(0) != Cmd.SEND_CHANNEL_TXT_MSG)
    }

    @Test
    fun `a text message lays out type, attempt, timestamp, prefix, then text`() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 1, 2, 3)
        val frame = Commands.sendTextMessage(
            recipientPrefix = prefix,
            text = "ok",
            timestamp = 0x01020304,
            attempt = 2,
        )
        assertEquals("02 00 02 04 03 02 01 aa bb cc 01 02 03 6f 6b", frame.hex())
        // The firmware requires at least 14 bytes before it will look at a message.
        assertTrue("frame is ${frame.size} bytes, firmware wants >= 14", frame.size >= 14)
    }

    @Test
    fun `addressing takes a six-byte prefix and refuses a whole key`() {
        val whole = ByteArray(Sizes.PUB_KEY)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Commands.sendTextMessage(whole, "hi", 0)
        }
        assertTrue(failure.message!!.contains("6-byte key prefix"))
    }

    /** The version in byte 1 is the whole point of this frame. */
    @Test
    fun `device query carries the protocol version this app understands`() {
        assertArrayEquals(byteArrayOf(22, 13), Commands.deviceQuery())
        assertEquals(13, APP_PROTOCOL_VERSION)
    }

    @Test
    fun `app start reserves seven bytes before the name`() {
        val frame = Commands.appStart("amime")
        assertEquals(Cmd.APP_START, frame.u8(0))
        assertArrayEquals(ByteArray(7), frame.copyOfRange(1, 8))
        assertEquals("amime", String(frame, 8, frame.size - 8))
    }

    /**
     * The radio only learns the app's protocol version from a device query, so sending
     * anything before it means the radio answers as though the app were version 0.
     */
    @Test
    fun `the handshake queries the device before announcing the app`() {
        val frames = Commands.handshake()
        assertEquals(2, frames.size)
        assertEquals(Cmd.DEVICE_QUERY, frames[0].u8(0))
        assertEquals(Cmd.APP_START, frames[1].u8(0))
    }

    @Test
    fun `get contacts omits since when there is nothing to sync from`() {
        assertArrayEquals(byteArrayOf(4), Commands.getContacts())
        assertEquals("04 04 03 02 01", Commands.getContacts(0x01020304).hex())
    }

    @Test
    fun `single-byte commands are a single byte`() {
        assertArrayEquals(byteArrayOf(10), Commands.syncNextMessage())
        assertArrayEquals(byteArrayOf(20), Commands.getBattAndStorage())
    }
}
