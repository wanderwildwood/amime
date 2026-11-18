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

    // ---- what has been read ----

    @Test
    fun `read marks survive being written and read back`() {
        val conversations = mapOf(ridge to listOf(message(1), message(2)))
        val readUpTo = mapOf(ridge to 2L, hollow to 7L)

        val restored = MessageLog.decode(MessageLog.encode(conversations, readUpTo))

        assertEquals(readUpTo, restored.readUpTo)
        assertEquals(conversations, restored.conversations)
    }

    /**
     * A read mark has three fields where a message has eight, so a version that knows
     * nothing about them skips them for being short. That is why they could be added without
     * a new format number, and it is worth a test rather than a comment.
     */
    @Test
    fun `a read mark is not mistaken for a message`() {
        val restored = MessageLog.decode(
            MessageLog.encode(emptyMap(), mapOf(ridge to 4L)),
        )

        assertTrue(restored.conversations.isEmpty())
        assertEquals(mapOf(ridge to 4L), restored.readUpTo)
        // Numbering is not advanced by something that is not a message.
        assertEquals(1L, restored.nextId)
    }

    @Test
    fun `a log with no read marks reads as nothing read`() {
        val restored = MessageLog.decode(
            MessageLog.encode(mapOf(ridge to listOf(message(1)))),
        )
        assertEquals(emptyMap<List<Byte>, Long>(), restored.readUpTo)
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

    // ---- the earlier format ----

    /** How a Meshtastic channel's thread key was made before it went through Channel.threadKey. */
    private fun formerMeshtasticKey(name: String, psk: okio.ByteString): List<Byte> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("meshtastic-channel\u0000".encodeToByteArray())
        digest.update(name.encodeToByteArray())
        digest.update(0)
        digest.update(psk.toByteArray())
        return digest.digest().take(16)
    }

    private fun hex(key: List<Byte>) = key.joinToString("") { "%02x".format(it) }

    private fun line(key: List<Byte>, id: Long, text: String) =
        "${hex(key)}\t$id\t0\t${1_700_000_000L + id}\tACKNOWLEDGED\t-\t-\t$text\n"

    @Test
    fun `an earlier log finds every thread under its new key and keeps no channel's secret`() {
        val secret = List<Byte>(16) { (0x40 + it).toByte() }
        val psk = okio.ByteString.of(1, 2, 3)
        val formerHikers = formerMeshtasticKey("Hikers", psk)
        val formerSlot = "meshtastic-ch".encodeToByteArray().toList() + listOf<Byte>(0, 0, 2)
        val old = "amime-messages 1\n" +
            "read\t${hex(secret)}\t1\n" +
            "read\t${hex(ridge)}\t4\n" +
            line(secret, 1, "Ada Whitlock: on the ridge") +
            line(formerHikers, 2, "Tomas Reyes: at the hut") +
            line(formerSlot, 3, "early") +
            line(ridge, 4, "direct")

        val restored = MessageLog.decode(old)

        val core = Channel(0, "Private", secret)
        assertTrue(restored.former)
        assertEquals("Ada Whitlock: on the ridge", restored.conversations.getValue(core.key).single().text)
        assertEquals(
            "Tomas Reyes: at the hut",
            restored.conversations.getValue(
                com.wanderwildwood.amime.meshtastic.MtSession.channelKey("Hikers", psk),
            ).single().text,
        )
        assertEquals(
            "early",
            restored.conversations.getValue(com.wanderwildwood.amime.meshtastic.MtSession.channelKey(2)).single().text,
        )
        assertEquals("direct", restored.conversations.getValue(ridge).single().text)
        assertEquals(mapOf(core.key to 1L, ridge to 4L), restored.readUpTo)
        assertEquals(4, restored.conversations.size)

        val rewritten = MessageLog.encode(restored.conversations, restored.readUpTo)
        assertTrue(!rewritten.contains(hex(secret)))
        assertTrue(rewritten.startsWith("amime-messages 2\n"))
        assertEquals(restored.conversations, MessageLog.decode(rewritten).conversations)
        assertTrue(!MessageLog.decode(rewritten).former)
    }

    @Test
    fun `an earlier log on disk is read, not set aside`() {
        val file = File(folder.root, "conversations")
        file.writeText("amime-messages 1\n" + line(ridge, 1, "kept"))
        val log = MessageLog(file)

        val restored = log.read()

        assertEquals("kept", restored.conversations.getValue(ridge).single().text)
        assertTrue(restored.former)
        assertTrue(!log.unreadable.exists())
    }

    @Test
    fun `a channel thread key is not the channel's secret`() {
        val secret = List<Byte>(16) { 0x33 }
        val channel = Channel(1, "#test", secret)
        assertEquals(16, channel.key.size)
        assertTrue(channel.key != secret)
        assertEquals(channel.key, Channel(4, "#test", secret).key)
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

    @Test
    fun `a log this version cannot read is kept aside, not written over`() {
        val file = File(folder.root, "conversations")
        file.writeText("amime-messages 3\nsomething newer\n")
        val log = MessageLog(file)

        assertEquals(emptyMap<List<Byte>, List<Message>>(), log.read().conversations)
        log.write(mapOf(ridge to listOf(message(1, "first"))))

        assertEquals("amime-messages 3\nsomething newer\n", log.unreadable.readText())
        assertEquals(1, log.read().conversations.getValue(ridge).size)
    }

    @Test
    fun `a second log this version cannot read is kept aside beside the first`() {
        val file = File(folder.root, "conversations")
        val log = MessageLog(file)
        log.unreadable.writeText("amime-messages 3\nolder\n")
        file.writeText("amime-messages 4\nnewer\n")

        log.read()
        log.write(mapOf(ridge to listOf(message(1, "first"))))

        assertEquals("amime-messages 3\nolder\n", log.unreadable.readText())
        assertEquals("amime-messages 4\nnewer\n", File(folder.root, "conversations.unreadable-2").readText())
    }

    @Test
    fun `a log that cannot be opened is neither moved aside nor written over`() {
        // A folder in its place: there, but readText fails on it.
        val file = File(folder.root, "conversations")
        assertTrue(file.mkdir())
        val log = MessageLog(file)

        assertEquals(emptyMap<List<Byte>, List<Message>>(), log.read().conversations)
        assertTrue(file.isDirectory)
        assertTrue(!log.unreadable.exists())
        assertTrue(runCatching { log.write(mapOf(ridge to listOf(message(1)))) }.isFailure)
        assertTrue(file.isDirectory)
    }

    @Test
    fun `every log kept aside is listed, the first one first, and nothing else`() {
        val file = File(folder.root, "conversations")
        val log = MessageLog(file)
        assertEquals(emptyList<File>(), log.setAside())

        log.write(mapOf(ridge to listOf(message(1))))
        log.unreadable.writeText("amime-messages 3\nolder\n")
        File(folder.root, "conversations.unreadable-10").writeText("x")
        File(folder.root, "conversations.unreadable-2").writeText("y")
        File(folder.root, "conversations.unreadable-note").writeText("not one")

        assertEquals(
            listOf("conversations.unreadable", "conversations.unreadable-2", "conversations.unreadable-10"),
            log.setAside().map { it.name },
        )
    }
}
