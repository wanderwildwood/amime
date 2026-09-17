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

    /**
     * The command that stops a node with a screen inventing a new PIN on every boot.
     */
    @Test
    fun `setting the device pin sends four little-endian bytes`() {
        assertEquals("25 cd 9d 0e 00", Commands.setDevicePin(957901).hex())
        assertEquals(Cmd.SET_DEVICE_PIN, Commands.setDevicePin(123456).u8(0))
        // 0 hands the choice back to the firmware, and is explicitly allowed.
        assertEquals("25 00 00 00 00", Commands.setDevicePin(0).hex())
    }

    @Test
    fun `a pin the radio would refuse is refused here first`() {
        listOf(1, 99999, 1000000, -1).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { Commands.setDevicePin(bad) }
        }
    }

    /**
     * The four numbers that decide who a node can hear, and the reason the units matter: the
     * firmware reads frequency as kHz and bandwidth as Hz out of the same frame.
     */
    @Test
    fun `radio params are frequency in kHz then bandwidth in Hz`() {
        // The USA/Canada recommended preset.
        val frame = Commands.setRadioParams(
            frequencyKhz = 910_525,
            bandwidthHz = 62_500,
            spreadingFactor = 7,
            codingRate = 5,
        )
        assertEquals("0b bd e4 0d 00 24 f4 00 00 07 05 00", frame.hex())
        assertEquals(Cmd.SET_RADIO_PARAMS, frame.u8(0))
        assertEquals(12, frame.size)
    }

    @Test
    fun `client repeating is off unless asked for`() {
        assertEquals(0, Commands.setRadioParams(910_525, 62_500, 7, 5).last().toInt())
        assertEquals(1, Commands.setRadioParams(910_525, 62_500, 7, 5, repeat = true).last().toInt())
    }

    /** Each bound is the firmware's own; sending outside it earns an error frame instead. */
    @Test
    fun `parameters the radio would reject are refused here first`() {
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(149_999, 62_500, 7, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(2_500_001, 62_500, 7, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 6_999, 7, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 500_001, 7, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 62_500, 4, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 62_500, 13, 5) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 62_500, 7, 4) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setRadioParams(910_525, 62_500, 7, 9) }
    }

    /**
     * The asymmetry that would otherwise cost an afternoon: a message is addressed by six
     * bytes and a login by thirty-two, and getting it wrong returns a bare not-found.
     */
    @Test
    fun `a login is addressed by the whole key, not the prefix`() {
        val key = ByteArray(Sizes.PUB_KEY) { (it + 1).toByte() }
        val frame = Commands.sendLogin(key, "password")
        assertEquals(Cmd.SEND_LOGIN, frame.u8(0))
        assertArrayEquals(key, frame.copyOfRange(1, 1 + Sizes.PUB_KEY))
        assertEquals("password", String(frame, 1 + Sizes.PUB_KEY, frame.size - 1 - Sizes.PUB_KEY))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            Commands.sendLogin(ByteArray(Sizes.PUB_KEY_PREFIX), "password")
        }
        assertTrue(failure.message!!.contains("whole"))
    }

    @Test
    fun `logout also takes the whole key`() {
        val key = ByteArray(Sizes.PUB_KEY) { 7 }
        assertEquals(Cmd.LOGOUT, Commands.logout(key).u8(0))
        assertEquals(1 + Sizes.PUB_KEY, Commands.logout(key).size)
        assertThrows(IllegalArgumentException::class.java) { Commands.logout(ByteArray(6)) }
    }

    /**
     * A CLI command is an ordinary text message wearing a different type byte. If that type
     * ever went out as PLAIN, the repeater would file an administrative command as chatter.
     */
    @Test
    fun `a CLI command is a text message marked as CLI data`() {
        val frame = Commands.sendCliCommand(ByteArray(6) { 0x11 }, "get freq")
        assertEquals(Cmd.SEND_TXT_MSG, frame.u8(0))
        assertEquals(TxtType.CLI_DATA, frame.u8(1))
        assertEquals("get freq", String(frame, 13, frame.size - 13))
    }

    @Test
    fun `single-byte commands are a single byte`() {
        assertArrayEquals(byteArrayOf(10), Commands.syncNextMessage())
        assertArrayEquals(byteArrayOf(20), Commands.getBattAndStorage())
    }
}
