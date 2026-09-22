package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.protocol.AdvType
import com.wanderwildwood.amime.protocol.Frame
import com.wanderwildwood.amime.protocol.OUT_PATH_UNKNOWN
import com.wanderwildwood.amime.protocol.PATH_LEN_DIRECT
import com.wanderwildwood.amime.protocol.Sizes
import com.wanderwildwood.amime.protocol.TxtType
import com.wanderwildwood.amime.protocol.pathBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshStoreTest {

    private lateinit var store: MeshStore

    private val ridge = List<Byte>(6) { 0x11 }
    private val hollow = List<Byte>(6) { 0x22 }

    private var clock = 1_000L

    @Before
    fun setUp() {
        clock = 1_000L
        store = MeshStore(now = { clock })
    }

    // ---- people ----

    @Test
    fun `a contact becomes a person keyed by its prefix`() {
        store.onContact(contact("ridge-node", key = 0x11))
        val person = store.state.people.single()
        assertEquals("ridge-node", person.label)
        assertEquals(ridge, person.prefix)
    }

    @Test
    fun `a second advert from the same node updates rather than duplicates`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.onContact(contact("ridge-node north", key = 0x11))

        assertEquals(1, store.state.people.size)
        assertEquals("ridge-node north", store.state.people.single().label)
    }

    /**
     * A node that has advertised no name still has to be reachable in a list. Showing an
     * empty row would leave nothing to match against the node's own screen.
     */
    @Test
    fun `a nameless node falls back to its key prefix`() {
        store.onContact(contact("", key = 0x11))
        assertEquals("111111111111", store.state.people.single().label)
    }

    /**
     * No route known means the radio floods to reach it, which the border shows as dotted.
     *
     * ⚠ This test used to pass `pathLen = 0` for "no path" and assert exactly the opposite of
     * what the firmware means, which is how the bug it was written to catch survived: zero is
     * a route of no hops — a neighbour — and a contact nobody has a route to carries
     * [OUT_PATH_UNKNOWN]. Both of the common cases came out backwards.
     */
    @Test
    fun `a node with no route is provisional and a neighbour is not`() {
        store.onContact(contact("far", key = 0x11, pathLen = OUT_PATH_UNKNOWN))
        assertFalse(store.state.people.single().pathKnown)

        // Zero hops: close enough to hear directly, and a settled route.
        store.onContact(contact("far", key = 0x11, pathLen = 0))
        assertTrue(store.state.people.single().pathKnown)

        store.onContact(contact("far", key = 0x11, pathLen = 2))
        assertTrue(store.state.people.single().pathKnown)
    }

    /**
     * The radio keeps a fixed number of contacts and overwrites the oldest. A row left behind
     * for somebody it has forgotten offers to write to an address it will answer with a flat
     * not-found.
     */
    @Test
    fun `a contact the radio dropped leaves the list but not the log`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "still here", 1)

        store.onContactDeleted(ridge)

        assertTrue(store.state.people.isEmpty())
        assertEquals(1, store.state.conversations.getValue(ridge).size)
    }

    @Test
    fun `dropping one contact leaves the others alone`() {
        store.onContact(contact("ridge", key = 0x11))
        store.onContact(contact("hollow", key = 0x22))

        store.onContactDeleted(ridge)

        assertEquals("hollow", store.state.people.single().label)
    }

    /**
     * The empty screen says "since connecting", and a survey is the one place that number is
     * read as a measurement rather than as decoration.
     */
    @Test
    fun `what the antenna heard does not carry across a reconnect`() {
        store.onPacketHeard(packetHeard(snr = -6f, rssi = -90))
        store.onPacketHeard(packetHeard(snr = -3f, rssi = -80))
        assertEquals(2, store.state.heard.packets)

        store.onDisconnected()

        assertEquals(0, store.state.heard.packets)
        assertNull(store.state.heard.bestSnr)
    }

    @Test
    fun `the strongest reading is kept while one connection lasts`() {
        store.onPacketHeard(packetHeard(snr = -6f, rssi = -90))
        store.onPacketHeard(packetHeard(snr = -12f, rssi = -100))

        assertEquals(2, store.state.heard.packets)
        assertEquals(-6f, store.state.heard.bestSnr!!, 0.01f)
    }

    // ---- waiting for an acknowledgement, and when to stop ----

    /**
     * The firmware sees its own send time out and tells the app nothing —
     * `MyMesh::onSendTimeout()` is an empty function — so if this does not keep the time, a
     * message waits for an answer for as long as the app is open.
     */
    @Test
    fun `waiting ends when the radio said it would`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "are you there", 1)
        store.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 30_000), awaitingAck = true)
        assertEquals(Delivery.AWAITING_ACK, only(ridge).delivery)

        clock += 29_000
        store.expireAwaitingAcks()
        assertEquals(Delivery.AWAITING_ACK, only(ridge).delivery)

        clock += 2_000
        store.expireAwaitingAcks()
        assertEquals(Delivery.UNANSWERED, only(ridge).delivery)
    }

    /**
     * Giving up on an answer is not the same as deciding it will never come. The hash stays
     * on the books so a late one still settles the message properly.
     */
    @Test
    fun `an acknowledgement after the window still counts`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "are you there", 1)
        store.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 30_000), awaitingAck = true)
        clock += 31_000
        store.expireAwaitingAcks()
        assertEquals(Delivery.UNANSWERED, only(ridge).delivery)

        store.onDelivered(0x99, roundTripMs = 45_000)

        assertEquals(Delivery.ACKNOWLEDGED, only(ridge).delivery)
    }

    /** A repaint every few seconds with nothing changed in it is flicker for no news. */
    @Test
    fun `nothing expiring changes nothing`() {
        var changes = 0
        val quiet = MeshStore(onChange = { changes++ }, now = { clock })
        quiet.onContact(contact("ridge", key = 0x11))
        quiet.recordSent(ridge, "hello", 1)
        quiet.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 30_000), awaitingAck = true)
        val before = changes

        quiet.expireAwaitingAcks()
        quiet.expireAwaitingAcks()

        assertEquals(before, changes)
    }

    @Test
    fun `a message the radio expects no answer for never starts waiting`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "help", 1)
        store.onSent(sentAwaiting(ack = 0, estimatedTimeoutMs = 30_000), awaitingAck = false)

        assertEquals(Delivery.NO_ACK_EXPECTED, only(ridge).delivery)
        assertNull(only(ridge).awaitingUntil)
        assertFalse(store.hasAwaitingAcks())
    }

    // ---- sending it again ----

    @Test
    fun `sending again reuses the row rather than adding one`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "anyone there", 1)
        store.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 10), awaitingAck = true)
        clock += 100
        store.expireAwaitingAcks()

        val attempt = store.recordResend(ridge, only(ridge).id)

        assertEquals(1, attempt)
        val message = only(ridge)
        assertEquals(Delivery.SENDING, message.delivery)
        assertEquals(1, message.attempt)
        assertNull(message.awaitingUntil)
        assertEquals("anyone there", message.text)
    }

    /**
     * Above three the firmware stops keeping the attempt in its own byte and hides it at the
     * tail of the payload, which costs two bytes of the message and refuses outright if the
     * text is within two of the limit.
     */
    @Test
    fun `the attempt number stops at three`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "anyone there", 1)
        val id = only(ridge).id
        repeat(6) { store.recordResend(ridge, id) }

        assertEquals(3, only(ridge).attempt)
    }

    @Test
    fun `the next answer settles a resent message`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "anyone there", 1)
        store.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 10), awaitingAck = true)
        clock += 100
        store.expireAwaitingAcks()
        store.recordResend(ridge, only(ridge).id)

        store.onSent(sentAwaiting(ack = 0xAA, estimatedTimeoutMs = 30_000), awaitingAck = true)
        store.onDelivered(0xAA, roundTripMs = 900)

        assertEquals(Delivery.ACKNOWLEDGED, only(ridge).delivery)
    }

    /** Each attempt has its own acknowledgement, and an early one still settles the row. */
    @Test
    fun `an answer to the first try still settles a resent message`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "anyone there", 1)
        store.onSent(sentAwaiting(ack = 0x99, estimatedTimeoutMs = 10), awaitingAck = true)
        clock += 100
        store.expireAwaitingAcks()
        store.recordResend(ridge, only(ridge).id)

        store.onDelivered(0x99, roundTripMs = 60_000)

        assertEquals(Delivery.ACKNOWLEDGED, only(ridge).delivery)
    }

    @Test
    fun `there is nothing to resend in a thread that does not exist`() {
        assertNull(store.recordResend(ridge, 1))
    }

    // ---- what is waiting to be read ----

    /**
     * The row used to say "has messages" whenever a thread was not empty, which included
     * every thread anybody had ever written in — so it said it about a conversation where
     * the only thing in it was your own message, for ever.
     */
    @Test
    fun `your own messages are not waiting to be read`() {
        store.onContact(contact("ridge", key = 0x11))
        store.recordSent(ridge, "anyone there", 1)

        assertEquals(0, store.state.unreadCount(ridge))
    }

    @Test
    fun `a message that arrives is waiting until the thread is opened`() {
        store.onContact(contact("ridge", key = 0x11))
        store.onMessage(received("here", key = 0x11, snr = -4f))
        store.onMessage(received("and here", key = 0x11, snr = -4f))
        assertEquals(2, store.state.unreadCount(ridge))

        store.markRead(ridge)

        assertEquals(0, store.state.unreadCount(ridge))
    }

    @Test
    fun `one that arrives after the thread was read is waiting again`() {
        store.onContact(contact("ridge", key = 0x11))
        store.onMessage(received("here", key = 0x11, snr = -4f))
        store.markRead(ridge)

        store.onMessage(received("still here", key = 0x11, snr = -4f))

        assertEquals(1, store.state.unreadCount(ridge))
    }

    @Test
    fun `reading one thread leaves another alone`() {
        store.onContact(contact("ridge", key = 0x11))
        store.onContact(contact("hollow", key = 0x22))
        store.onMessage(received("from the ridge", key = 0x11, snr = -4f))
        store.onMessage(received("from the hollow", key = 0x22, snr = -4f))

        store.markRead(ridge)

        assertEquals(0, store.state.unreadCount(ridge))
        assertEquals(1, store.state.unreadCount(hollow))
    }

    @Test
    fun `marking an empty thread read changes nothing`() {
        var changes = 0
        val quiet = MeshStore(onChange = { changes++ }, now = { clock })
        quiet.onContact(contact("ridge", key = 0x11))
        val before = changes

        quiet.markRead(ridge)

        assertEquals(before, changes)
    }

    /**
     * The radio changes the contact and leaves its `lastmod` alone on purpose, so nothing
     * ever arrives to correct a border still drawn over a route that was thrown away. The
     * app's own copy is put right when the command goes out or not at all.
     */
    @Test
    fun `forgetting a route leaves the contact without one`() {
        store.onContact(contact("ridge", key = 0x11, pathLen = 3))
        store.onContact(contact("hollow", key = 0x22, pathLen = 3))
        assertTrue(store.state.people.first { it.label == "ridge" }.pathKnown)

        store.forgetRoute(ridge)

        assertFalse(store.state.people.first { it.label == "ridge" }.pathKnown)
        // The other contact's route is not this one's business.
        assertTrue(store.state.people.first { it.label == "hollow" }.pathKnown)
    }

    /**
     * A contact list on a community mesh is mostly not people. Each kind wants something
     * different done with it, and a row that does not say which invites a conversation with
     * a machine that will never answer.
     */
    @Test
    fun `each kind of node is distinguishable from a person`() {
        store.onContact(contact("ridge", key = 0x11, type = AdvType.REPEATER))
        store.onContact(contact("the tavern", key = 0x22, type = AdvType.ROOM))
        store.onContact(contact("rain gauge", key = 0x33, type = AdvType.SENSOR))
        store.onContact(contact("somebody", key = 0x44, type = AdvType.CHAT))

        val byName = store.state.people.associateBy { it.label }
        assertTrue(byName.getValue("ridge").isRepeater)
        assertTrue(byName.getValue("the tavern").isRoom)
        assertTrue(byName.getValue("rain gauge").isSensor)
        val person = byName.getValue("somebody")
        assertFalse(person.isRepeater || person.isRoom || person.isSensor)
    }

    // ---- receiving ----

    @Test
    fun `a received message lands in the sender's thread`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.onMessage(received("on my way", key = 0x11, snr = -6.5f))

        val thread = store.state.conversationWith(ridge)!!.messages
        assertEquals("on my way", thread.single().text)
        assertFalse(thread.single().mine)
        assertEquals(-6.5f, thread.single().snr!!, 0.001f)
    }

    /** NaN is the radio declining to say, not a reading of zero. */
    @Test
    fun `an unreported SNR is absent rather than zero`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.onMessage(received("older", key = 0x11, snr = Float.NaN))

        val message = only(ridge)
        assertNull("NaN must not become 0f", message.snr)
        // The control on the line above: a real reading does come through.
        store.onMessage(received("newer", key = 0x11, snr = -6.5f))
        assertEquals(-6.5f, store.state.conversationWith(ridge)!!.messages.last().snr!!, 0.001f)
    }

    // ---- sending, and how far it got ----

    @Test
    fun `a sent message shows immediately as sending`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)

        val message = store.state.conversationWith(ridge)!!.messages.single()
        assertEquals(Delivery.SENDING, message.delivery)
        assertTrue(message.mine)
    }

    @Test
    fun `the radio accepting it makes it provisional, and an ack settles it`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)

        store.onSent(sent(0xABCD), awaitingAck = true)
        assertEquals(Delivery.AWAITING_ACK, only(ridge).delivery)

        store.onDelivered(0xABCD, roundTripMs = 1200)
        assertEquals(Delivery.ACKNOWLEDGED, only(ridge).delivery)
    }

    /**
     * An ack of zero is the radio saying none is coming. Leaving such a message provisional
     * would show a pending state that can never resolve.
     */
    @Test
    fun `a send with no ack expected is not left looking pending`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)

        store.onSent(sent(0), awaitingAck = false)
        assertEquals(Delivery.NO_ACK_EXPECTED, only(ridge).delivery)
    }

    /**
     * A `Sent` frame carries no reference to the message that produced it. The radio answers
     * commands in the order it gets them, so the oldest unanswered send is the right one —
     * and if that ever stops holding, two messages swap their delivery states.
     */
    @Test
    fun `two sends in flight are answered oldest first`() {
        store.onContact(contact("ridge-node", key = 0x11))
        val first = store.recordSent(ridge, "first", timestamp = 1)
        val second = store.recordSent(ridge, "second", timestamp = 2)

        store.onSent(sent(0xAAAA), awaitingAck = true)
        store.onSent(sent(0xBBBB), awaitingAck = true)
        store.onDelivered(0xBBBB, roundTripMs = 10)

        assertEquals(Delivery.AWAITING_ACK, message(ridge, first).delivery)
        assertEquals(Delivery.ACKNOWLEDGED, message(ridge, second).delivery)
    }

    @Test
    fun `sends to different people are still answered in order`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.onContact(contact("hollow-node", key = 0x22))
        store.recordSent(ridge, "to her", timestamp = 1)
        store.recordSent(hollow, "to him", timestamp = 2)

        store.onSent(sent(0), awaitingAck = false)

        assertEquals(Delivery.NO_ACK_EXPECTED, only(ridge).delivery)
        assertEquals("the second is still unanswered", Delivery.SENDING, only(hollow).delivery)
    }

    @Test
    fun `a refusal marks the message rather than being swallowed`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)

        store.onFailed(code = 2)
        assertEquals(Delivery.REFUSED, only(ridge).delivery)
    }

    /** A refusal of some other command must not mark an unrelated message. */
    @Test
    fun `a refusal with nothing in flight changes no message`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)
        store.onSent(sent(0xABCD), awaitingAck = true)

        store.onFailed(code = 2)
        assertEquals(Delivery.AWAITING_ACK, only(ridge).delivery)
    }

    @Test
    fun `a repeated acknowledgement does not move a settled message`() {
        store.onContact(contact("ridge-node", key = 0x11))
        store.recordSent(ridge, "hello", timestamp = 1)
        store.onSent(sent(0xABCD), awaitingAck = true)
        store.onDelivered(0xABCD, roundTripMs = 5)
        store.onDelivered(0xABCD, roundTripMs = 5)

        assertEquals(Delivery.ACKNOWLEDGED, only(ridge).delivery)
        assertEquals(1, store.state.conversationWith(ridge)!!.messages.size)
    }

    // ---- the rest ----

    /**
     * Heard packets are counted apart from contacts on purpose: on a survey, "the antenna is
     * picking something up" and "somebody sent a readable advert" are different answers.
     */
    @Test
    fun `heard packets are tallied with the best signal, not the last`() {
        assertEquals(0, store.state.heard.packets)
        assertNull(store.state.heard.bestSnr)

        store.onPacketHeard(Frame.PacketHeard(-12f, -110, ByteArray(0)))
        store.onPacketHeard(Frame.PacketHeard(-4.5f, -95, ByteArray(0)))
        store.onPacketHeard(Frame.PacketHeard(-20f, -120, ByteArray(0)))

        assertEquals(3, store.state.heard.packets)
        assertEquals(-4.5f, store.state.heard.bestSnr!!, 0.001f)
        assertEquals(-95, store.state.heard.bestRssi)
    }

    @Test
    fun `hearing packets does not invent a contact`() {
        store.onPacketHeard(Frame.PacketHeard(0f, -90, ByteArray(0)))
        assertEquals(0, store.state.people.size)
        assertEquals(1, store.state.heard.packets)
    }

    // ---- administering a repeater ----

    private fun repeater(): Person {
        store.onContact(contact("ridge-node", key = 0x11, type = AdvType.REPEATER))
        return store.state.people.single()
    }

    @Test
    fun `a login is pending until the node answers over the air`() {
        store.beginLogin(repeater())
        assertEquals(Admin.State.LOGGING_IN, store.state.admin!!.state)

        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 1))
        assertEquals(Admin.State.IN, store.state.admin!!.state)
        assertTrue(store.state.admin!!.isAdmin)
    }

    /** A guest login connects and is then refused almost everything, which is not the same. */
    @Test
    fun `permissions of zero is a guest, not an administrator`() {
        store.beginLogin(repeater())
        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 0))
        assertEquals(Admin.State.IN, store.state.admin!!.state)
        assertFalse(store.state.admin!!.isAdmin)
    }

    @Test
    fun `a refusal is recorded rather than left looking slow`() {
        store.beginLogin(repeater())
        store.onLoginRefused(List(6) { 0x11 })
        assertEquals(Admin.State.REFUSED, store.state.admin!!.state)
    }

    @Test
    fun `the console keeps what was asked and what came back, in order`() {
        store.beginLogin(repeater())
        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 1))

        store.recordCommand("get freq")
        store.onCliResponse(List(6) { 0x11 }, "> 910.525")

        val lines = store.state.admin!!.lines
        assertEquals(2, lines.size)
        assertTrue(lines[0].fromUs)
        assertEquals("get freq", lines[0].text)
        assertFalse(lines[1].fromUs)
        assertEquals("> 910.525", lines[1].text)
    }

    /** Another node's CLI chatter must not land in this console. */
    @Test
    fun `a response from somebody else is ignored`() {
        store.beginLogin(repeater())
        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 1))
        store.onCliResponse(List(6) { 0x77 }, "not for us")
        assertTrue(store.state.admin!!.lines.isEmpty())
    }

    @Test
    fun `losing the radio ends the administration session`() {
        store.beginLogin(repeater())
        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 1))
        store.onDisconnected()
        assertNull(store.state.admin)
    }

    /** A CLI answer is not chatter and must not become a conversation. */
    @Test
    fun `a CLI response does not create a message thread`() {
        store.beginLogin(repeater())
        store.onLoggedIn(Frame.LoginSucceeded(ByteArray(6) { 0x11 }, permissions = 1))
        store.onCliResponse(List(6) { 0x11 }, "> ok")
        assertTrue(store.state.conversations.isEmpty())
    }

    @Test
    fun `battery is reported in millivolts as the radio gives it`() {
        store.onBattery(Frame.BattAndStorage(4310, null, null))
        assertEquals(4310, store.state.batteryMillivolts)
    }

    @Test
    fun `the session becoming ready is what lets the screen send`() {
        assertFalse(store.state.ready)
        store.onReady(selfInfo("wndr-node"))
        assertTrue(store.state.ready)
        assertEquals("wndr-node", store.state.nodeName)

        store.onDisconnected()
        assertFalse(store.state.ready)
    }

    @Test
    fun `every fold is published`() {
        val seen = mutableListOf<MeshState>()
        val watched = MeshStore(onChange = { seen += it })
        watched.onContact(contact("ridge-node", key = 0x11))
        watched.onMessage(received("hi", key = 0x11, snr = 1f))

        assertEquals(2, seen.size)
        assertEquals(1, seen.last().people.size)
    }

    // ---- builders ----

    private fun only(prefix: List<Byte>) =
        store.state.conversationWith(prefix)!!.messages.single()

    private fun message(prefix: List<Byte>, id: Long) =
        store.state.conversationWith(prefix)!!.messages.first { it.id == id }

    private fun contact(
        name: String,
        key: Int,
        // What the firmware puts on a contact it has just created, so it is what a test that
        // does not care about routes should be handed.
        pathLen: Int = OUT_PATH_UNKNOWN,
        type: Int = AdvType.CHAT,
    ) = Frame.Contact(
        publicKey = ByteArray(Sizes.PUB_KEY) { key.toByte() },
        type = type,
        flags = 0,
        outPathLen = pathLen,
        outPath = ByteArray(pathBytes(pathLen)),
        name = name,
        lastAdvert = 1,
        latitude = 0,
        longitude = 0,
        lastMod = 1,
        isNewAdvert = false,
    )

    private fun received(text: String, key: Int, snr: Float) = Frame.MessageReceived(
        snr = snr,
        senderPrefix = ByteArray(Sizes.PUB_KEY_PREFIX) { key.toByte() },
        pathLength = PATH_LEN_DIRECT,
        txtType = TxtType.PLAIN,
        senderTimestamp = 1,
        text = text,
    )

    private fun packetHeard(snr: Float, rssi: Int) = Frame.PacketHeard(
        snr = snr,
        rssi = rssi,
        bytes = ByteArray(0),
    )

    private fun sentAwaiting(ack: Long, estimatedTimeoutMs: Long) = Frame.Sent(
        byFlood = false,
        expectedAck = ack,
        estimatedTimeoutMs = estimatedTimeoutMs,
    )

    private fun sent(ack: Long) = Frame.Sent(
        byFlood = false,
        expectedAck = ack,
        estimatedTimeoutMs = 5000,
    )

    private fun selfInfo(name: String) = Frame.SelfInfo(
        advertType = AdvType.CHAT,
        txPowerDbm = 22,
        maxTxPowerDbm = 30,
        publicKey = ByteArray(Sizes.PUB_KEY),
        latitude = 0,
        longitude = 0,
        multiAcks = 0,
        advertLocationPolicy = 0,
        telemetryModes = 0,
        manualAddContacts = false,
        frequencyKhz = 910_525,
        bandwidthHz = 250_000,
        spreadingFactor = 11,
        codingRate = 5,
        name = name,
    )
}
