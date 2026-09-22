package com.wanderwildwood.amime.link

import com.wanderwildwood.amime.protocol.AdvType
import com.wanderwildwood.amime.protocol.Cmd
import com.wanderwildwood.amime.protocol.Err
import com.wanderwildwood.amime.protocol.Frame
import com.wanderwildwood.amime.protocol.PATH_LEN_DIRECT
import com.wanderwildwood.amime.protocol.Push
import com.wanderwildwood.amime.protocol.Resp
import com.wanderwildwood.amime.protocol.Sizes
import com.wanderwildwood.amime.protocol.TxtType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionTest {

    private lateinit var transport: RecordingTransport
    private lateinit var session: Session
    private lateinit var heard: Heard

    private class Heard : Session.Listener {
        val messages = mutableListOf<Frame.MessageReceived>()
        val contacts = mutableListOf<Frame.Contact>()
        val deleted = mutableListOf<List<Byte>>()
        var contactsFull = 0
        val problems = mutableListOf<Session.Problem>()
        val delivered = mutableListOf<Long>()
        val sent = mutableListOf<Pair<Frame.Sent, Boolean>>()
        val failures = mutableListOf<Int>()
        var syncedSince: Long? = null
        var ready: Frame.SelfInfo? = null
        var deviceInfo: Frame.DeviceInfo? = null

        override fun onDevice(info: Frame.DeviceInfo) { deviceInfo = info }
        override fun onReady(self: Frame.SelfInfo) { ready = self }
        override fun onContact(contact: Frame.Contact) { contacts += contact }
        override fun onContactDeleted(prefix: List<Byte>) { deleted += prefix }
        override fun onContactsFull() { contactsFull++ }
        override fun onContactsSynced(since: Long) { syncedSince = since }
        override fun onMessage(message: Frame.MessageReceived) { messages += message }
        override fun onSent(sent: Frame.Sent, awaitingAck: Boolean) { this.sent += sent to awaitingAck }
        override fun onDelivered(ackHash: Long, roundTripMs: Long) { delivered += ackHash }
        override fun onFailed(code: Int) { failures += code }
        override fun onProtocolProblem(problem: Session.Problem) { problems += problem }
    }

    @Before
    fun setUp() {
        transport = RecordingTransport()
        heard = Heard()
        session = Session(transport, heard)
    }

    // ---- handshake ----

    /**
     * The radio reads the app's protocol version only out of the device query, so the order
     * is load-bearing and silent when wrong.
     */
    @Test
    fun `starting queries the device before announcing the app`() {
        session.start()
        assertEquals(listOf(Cmd.DEVICE_QUERY, Cmd.APP_START), transport.opcodes())
    }

    @Test
    fun `a pre-v3 message means the handshake did not land`() {
        session.start()
        session.onFrame(legacyMessage("older"))

        assertTrue(heard.problems.contains(Session.Problem.HANDSHAKE_SKIPPED))
        assertEquals("the message is still delivered", 1, heard.messages.size)
    }

    @Test
    fun `a v3 message raises no complaint`() {
        session.start()
        session.onFrame(message("fine"))
        assertFalse(heard.problems.contains(Session.Problem.HANDSHAKE_SKIPPED))
    }

    @Test
    fun `starting again forgets what the last connection said`() {
        session.start()
        session.onFrame(selfInfo("wndr-node"))
        assertEquals("wndr-node", session.self?.name)

        session.start()
        assertEquals(null, session.self)
    }

    // ---- draining the queue ----

    /**
     * The tickle carries nothing, so the only way to empty the queue is to keep asking. If
     * this ever stops after one, messages sit on the radio until something else prods it.
     */
    @Test
    fun `a tickle starts a drain that continues until the radio says stop`() {
        session.start()
        transport.clear()

        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())

        session.onFrame(message("one"))
        session.onFrame(message("two"))
        assertEquals(
            listOf(Cmd.SYNC_NEXT_MESSAGE, Cmd.SYNC_NEXT_MESSAGE, Cmd.SYNC_NEXT_MESSAGE),
            transport.opcodes(),
        )

        transport.clear()
        session.onFrame(byteArrayOf(Resp.NO_MORE_MESSAGES.toByte()))
        assertTrue("nothing more should be asked for", transport.sent.isEmpty())
        assertEquals(2, heard.messages.size)
    }

    @Test
    fun `a second tickle mid-drain does not start a second drain`() {
        session.start()
        transport.clear()

        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))

        assertEquals(
            "one request in flight is enough",
            listOf(Cmd.SYNC_NEXT_MESSAGE),
            transport.opcodes(),
        )
    }

    @Test
    fun `a tickle after a drain finished starts a new one`() {
        session.start()
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        session.onFrame(byteArrayOf(Resp.NO_MORE_MESSAGES.toByte()))
        transport.clear()

        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())
    }

    // ---- delivery ----

    @Test
    fun `an ack of zero is reported as nothing to wait for`() {
        session.start()
        session.onFrame(sent(expectedAck = 0))

        assertEquals(1, heard.sent.size)
        assertFalse("no confirmation is coming", heard.sent.single().second)
    }

    @Test
    fun `a confirmation is matched to the send that expected it`() {
        session.start()
        session.onFrame(sent(expectedAck = 0xABCD))
        assertTrue(heard.sent.single().second)

        session.onFrame(confirmed(0xABCD))
        assertEquals(listOf(0xABCDL), heard.delivered)
    }

    /** The firmware notes that the same ack can arrive more than once. */
    @Test
    fun `a repeated confirmation is not a second delivery`() {
        session.start()
        session.onFrame(sent(expectedAck = 0xABCD))
        session.onFrame(confirmed(0xABCD))
        session.onFrame(confirmed(0xABCD))

        assertEquals(listOf(0xABCDL), heard.delivered)
    }

    @Test
    fun `a confirmation for something never sent is ignored`() {
        session.start()
        session.onFrame(confirmed(0x1234))
        assertTrue(heard.delivered.isEmpty())
    }

    // ---- contacts ----

    @Test
    fun `a sync runs once and reports what to ask from next time`() {
        session.start()
        transport.clear()

        session.syncContacts()
        assertEquals(listOf(Cmd.GET_CONTACTS), transport.opcodes())
        assertTrue(session.syncingContacts)

        session.onFrame(contactsStart(2))
        session.onFrame(contact("ridge-node", lastMod = 40))
        session.onFrame(contact("hollow-node", lastMod = 90))
        session.onFrame(contactsEnd(90))

        assertFalse(session.syncingContacts)
        assertEquals(2, heard.contacts.size)
        assertEquals(90L, heard.syncedSince)
    }

    /**
     * The border on the people list is drawn from whether the radio knows a route, and a
     * route is learnt mid-session rather than at connection time. Without this the picture
     * is of the moment the app connected and never changes.
     */
    @Test
    fun `a learnt route fetches the contact that changed`() {
        session.start()
        session.syncContacts()
        session.onFrame(contactsStart(1))
        session.onFrame(contact("ridge-node", lastMod = 90))
        session.onFrame(contactsEnd(90))
        transport.clear()

        session.onFrame(pathUpdated())

        assertEquals(listOf(Cmd.GET_CONTACTS), transport.opcodes())
        // Asked for what changed after the last sync, not for the whole list again. The
        // radio's filter is strictly greater, so this cannot fetch the same contact twice.
        assertEquals(90L, transport.sent.single().let { u32At(it, 1) })
    }

    @Test
    fun `a route learnt while a sync is running does not interrupt it`() {
        session.start()
        session.syncContacts()
        session.onFrame(contactsStart(1))
        transport.clear()

        session.onFrame(pathUpdated())

        assertEquals(emptyList<Int>(), transport.opcodes())
    }

    /**
     * The push carries the whole key; a contact is addressed by its first six bytes, and the
     * six are what the rest of the app has to match against.
     */
    @Test
    fun `a dropped contact is reported by the six bytes it is addressed by`() {
        session.onFrame(bytes(Push.CONTACT_DELETED, ByteArray(Sizes.PUB_KEY) { 0x11 }))

        assertEquals(listOf(List(Sizes.PUB_KEY_PREFIX) { 0x11.toByte() }), heard.deleted)
    }

    @Test
    fun `a full contact table is passed on rather than counted as an unknown frame`() {
        session.onFrame(byteArrayOf(Push.CONTACTS_FULL.toByte()))

        assertEquals(1, heard.contactsFull)
        assertEquals(emptyList<Session.Problem>(), heard.problems)
    }

    /**
     * The firmware holds one contacts iterator and answers a second request with a bad-state
     * error, so asking again mid-sync loses the answer rather than getting a fresher one.
     */
    @Test
    fun `a second sync request during a sync is not sent`() {
        session.start()
        transport.clear()

        session.syncContacts()
        session.syncContacts()

        assertEquals(listOf(Cmd.GET_CONTACTS), transport.opcodes())
    }

    /** Otherwise one refusal means this class never asks for contacts again. */
    @Test
    fun `a refused sync releases the flag`() {
        session.start()
        session.syncContacts()
        session.onFrame(byteArrayOf(Resp.ERR.toByte(), Err.BAD_STATE.toByte()))

        assertFalse(session.syncingContacts)
        assertEquals(listOf(Err.BAD_STATE), heard.failures)

        transport.clear()
        session.syncContacts()
        assertEquals(listOf(Cmd.GET_CONTACTS), transport.opcodes())
    }

    @Test
    fun `an unprompted advert is a contact without being a sync`() {
        session.start()
        session.onFrame(contact("stranger", lastMod = 5, code = Push.NEW_ADVERT))

        assertEquals(1, heard.contacts.size)
        assertTrue(heard.contacts.single().isNewAdvert)
        assertFalse(session.syncingContacts)
    }

    // ---- frames that are not right ----

    @Test
    fun `a truncated frame is reported as a protocol problem`() {
        session.start()
        session.onFrame(byteArrayOf(Resp.SELF_INFO.toByte(), 1, 2))
        assertEquals(listOf(Session.Problem.FRAME_TRUNCATED), heard.problems)
    }

    @Test
    fun `an unknown frame is noted and does not stop the session`() {
        session.start()
        session.onFrame(byteArrayOf(0x7E, 1, 2))
        assertEquals(listOf(Session.Problem.UNKNOWN_FRAME), heard.problems)

        transport.clear()
        session.onFrame(byteArrayOf(Push.MSG_WAITING.toByte()))
        assertEquals(listOf(Cmd.SYNC_NEXT_MESSAGE), transport.opcodes())
    }

    // ---- frame builders, shaped the way the firmware shapes them ----

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach {
            when (it) {
                is Int -> out.write(it and 0xFF)
                is ByteArray -> out.write(it)
                is String -> out.write(it.toByteArray())
                else -> error("unsupported $it")
            }
        }
        return out.toByteArray()
    }

    private fun u32(value: Long) = ByteArray(4) { ((value shr (it * 8)) and 0xFF).toByte() }

    private fun message(text: String) = bytes(
        Resp.CONTACT_MSG_RECV_V3, 20, 0, 0, ByteArray(Sizes.PUB_KEY_PREFIX),
        PATH_LEN_DIRECT, TxtType.PLAIN, u32(1), text,
    )

    private fun legacyMessage(text: String) = bytes(
        Resp.CONTACT_MSG_RECV, ByteArray(Sizes.PUB_KEY_PREFIX),
        PATH_LEN_DIRECT, TxtType.PLAIN, u32(1), text,
    )

    private fun sent(expectedAck: Long) =
        bytes(Resp.SENT, 0, u32(expectedAck), u32(5000))

    private fun confirmed(ack: Long) = bytes(Push.SEND_CONFIRMED, u32(ack), u32(1200))

    private fun contactsStart(count: Long) = bytes(Resp.CONTACTS_START, u32(count))

    /** The whole key and nothing else, which is all this push carries. */
    private fun pathUpdated() = bytes(Push.PATH_UPDATED, ByteArray(Sizes.PUB_KEY) { 0x11 })

    private fun u32At(frame: ByteArray, at: Int): Long =
        (0..3).fold(0L) { acc, i -> acc or ((frame[at + i].toLong() and 0xFF) shl (i * 8)) }

    private fun contactsEnd(lastMod: Long) = bytes(Resp.END_OF_CONTACTS, u32(lastMod))

    private fun contact(name: String, lastMod: Long, code: Int = Resp.CONTACT) = bytes(
        code,
        ByteArray(Sizes.PUB_KEY) { 0x11 },
        AdvType.CHAT, 0, 0,
        ByteArray(Sizes.MAX_PATH),
        name.toByteArray().copyOf(Sizes.NAME),
        u32(1), u32(0), u32(0), u32(lastMod),
    )

    private fun selfInfo(name: String) = bytes(
        Resp.SELF_INFO, AdvType.CHAT, 22, 30,
        ByteArray(Sizes.PUB_KEY),
        u32(0), u32(0), 0, 0, 0, 0,
        u32(910_525), u32(250_000), 11, 5,
        name,
    )
}
