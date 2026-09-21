package com.wanderwildwood.amime.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val ridge = List<Byte>(6) { 0x11 }
    private val hollow = List<Byte>(6) { 0x22 }

    private fun message(
        id: Long,
        text: String = "on the ridge",
        mine: Boolean = true,
        delivery: Delivery = Delivery.ACKNOWLEDGED,
        snr: Float? = null,
        direct: Boolean? = null,
    ) = Message(
        id = id,
        text = text,
        mine = mine,
        timestamp = 1_700_000_000L + id,
        delivery = delivery,
        snr = snr,
        direct = direct,
    )

    // ---- the shape on disk ----

    @Test
    fun `a conversation survives being written and read back`() {
        val conversations = mapOf(
            ridge to listOf(
                message(1, "going up now"),
                message(2, "heard you", mine = false, snr = -7.5f, direct = false),
            ),
            hollow to listOf(message(3, "nothing here", delivery = Delivery.NO_ACK_EXPECTED)),
        )

        val restored = MessageLog.decode(MessageLog.encode(conversations))

        assertEquals(conversations, restored.conversations)
    }

    @Test
    fun `numbering picks up after the highest id that was written`() {
        val restored = MessageLog.decode(
            MessageLog.encode(mapOf(ridge to listOf(message(4), message(9)))),
        )
        assertEquals(10L, restored.nextId)
    }

    @Test
    fun `an empty log starts the numbering at one`() {
        assertEquals(1L, MessageLog.decode(MessageLog.encode(emptyMap())).nextId)
    }

    @Test
    fun `a tab or a newline in a message does not become a field or a line`() {
        val awkward = "one\ttwo\nthree\\four\r"
        val restored = MessageLog.decode(
            MessageLog.encode(mapOf(ridge to listOf(message(1, awkward)))),
        )
        assertEquals(awkward, restored.conversations.getValue(ridge).single().text)
    }

    // ---- what a restart means for a message in flight ----

    @Test
    fun `a message still in flight comes back unresolved rather than waiting`() {
        val inFlight = mapOf(
            ridge to listOf(
                message(1, delivery = Delivery.SENDING),
                message(2, delivery = Delivery.AWAITING_ACK),
            ),
        )

        val restored = MessageLog.decode(MessageLog.encode(inFlight))

        assertTrue(
            restored.conversations.getValue(ridge).all { it.delivery == Delivery.UNRESOLVED },
        )
    }

    @Test
    fun `a settled message keeps the state it settled in`() {
        val settled = mapOf(
            ridge to listOf(
                message(1, delivery = Delivery.REFUSED),
                message(2, delivery = Delivery.NO_ACK_EXPECTED),
                message(3, delivery = Delivery.ACKNOWLEDGED),
            ),
        )

        val restored = MessageLog.decode(MessageLog.encode(settled))

        assertEquals(
            listOf(Delivery.REFUSED, Delivery.NO_ACK_EXPECTED, Delivery.ACKNOWLEDGED),
            restored.conversations.getValue(ridge).map { it.delivery },
        )
    }

    // ---- reading something that is not this ----

    @Test
    fun `a file from some other program is read as nothing at all`() {
        assertEquals(emptyMap<List<Byte>, List<Message>>(), MessageLog.decode("hello\n").conversations)
    }

    @Test
    fun `a line that makes no sense is skipped and the rest is kept`() {
        val good = MessageLog.encode(mapOf(ridge to listOf(message(1, "kept"))))
        val damaged = good.trimEnd() + "\nnot a message at all\n"

        val restored = MessageLog.decode(damaged)

        assertEquals("kept", restored.conversations.getValue(ridge).single().text)
    }

    @Test
    fun `an absent signal reading stays absent rather than becoming zero`() {
        val restored = MessageLog.decode(
            MessageLog.encode(mapOf(ridge to listOf(message(1, mine = false, snr = null)))),
        )
        assertNull(restored.conversations.getValue(ridge).single().snr)
    }

    // ---- the file itself ----

    @Test
    fun `what is written is what is read`() {
        val file = File(folder.root, "conversations")
        val log = MessageLog(file)
        val conversations = mapOf(ridge to listOf(message(1, "up the hollow")))

        log.write(conversations)

        assertEquals(conversations, MessageLog(file).read().conversations)
    }

    @Test
    fun `a log that has never been written reads as empty`() {
        val log = MessageLog(File(folder.root, "not-there"))
        assertEquals(emptyMap<List<Byte>, List<Message>>(), log.read().conversations)
        assertEquals(1L, log.read().nextId)
    }

    @Test
    fun `a second write replaces the first and leaves nothing behind`() {
        val file = File(folder.root, "conversations")
        val log = MessageLog(file)

        log.write(mapOf(ridge to listOf(message(1, "first"))))
        log.write(mapOf(ridge to listOf(message(1, "first"), message(2, "second"))))

        assertEquals(2, MessageLog(file).read().conversations.getValue(ridge).size)
        // The temporary file the write goes through is not left lying beside the real one.
        assertEquals(listOf("conversations"), folder.root.list()?.sorted())
    }
}
