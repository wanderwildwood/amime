package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.protocol.Channels
import com.wanderwildwood.amime.protocol.Frame
import com.wanderwildwood.amime.protocol.TxtType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshStoreChannelsTest {

    private lateinit var store: MeshStore
    private val public = Channels.PUBLIC_KEY.toList()
    private val test = Channels.hashtagKey("#test").toList()

    @Before
    fun setUp() {
        store = MeshStore(now = { 0L })
    }

    private fun slot(index: Int, name: String, secret: List<Byte>) =
        store.onChannel(Frame.ChannelInfo(index, name, secret.toByteArray()))

    private fun empty(index: Int) = store.onChannel(Frame.ChannelInfo(index, "", ByteArray(16)))

    private fun said(index: Int, text: String) = store.onChannelMessage(
        Frame.ChannelMessageReceived(
            snr = 5f, channelIndex = index, pathLength = 2, txtType = TxtType.PLAIN,
            senderTimestamp = 1, text = text,
        ),
    )

    @Test
    fun `slots become channels and free slots, in order`() {
        slot(0, "Public", public)
        empty(1)
        slot(2, "#test", test)
        empty(3)
        store.onChannelsLoaded()

        assertEquals(listOf("Public", "#test"), store.state.channels.map { it.name })
        assertEquals(listOf(1, 3), store.state.freeChannelSlots)
        assertTrue(store.state.channels.first().isPublic)
        assertTrue(store.state.channelsLoaded)
    }

    @Test
    fun `a channel message is kept under the channel's key, words as they came`() {
        slot(2, "#test", test)
        said(2, "Ada Whitlock: the road is clear")

        val message = store.state.conversations[test]!!.single()
        assertEquals("Ada Whitlock: the road is clear", message.text)
        assertFalse(message.mine)
        assertEquals(1, store.state.unreadCount(test))
    }

    /** The queue drains while the slots are still being read. */
    @Test
    fun `a message ahead of its slot waits for it`() {
        said(2, "Tomas Reyes: early")
        assertTrue(store.state.conversations[test].isNullOrEmpty())

        slot(2, "#test", test)
        assertEquals("Tomas Reyes: early", store.state.conversations[test]!!.single().text)
    }

    @Test
    fun `a message for a slot the radio does not have is dropped once all are read`() {
        store.onChannelsLoaded()
        said(9, "nobody: here")
        slot(9, "#late", Channels.hashtagKey("#late").toList())
        assertTrue(store.state.conversations.isEmpty())
    }

    @Test
    fun `a channel message sent is settled by the radio taking it`() {
        slot(0, "Public", public)
        store.recordChannelSent(public, "hello", 1)
        assertEquals(Delivery.SENDING, store.state.conversations[public]!!.single().delivery)

        store.onChannelSent()
        assertEquals(Delivery.SENT, store.state.conversations[public]!!.single().delivery)
    }

    @Test
    fun `a channel message refused says so`() {
        store.recordChannelSent(public, "hello", 1)
        store.onChannelSendFailed(2)
        assertEquals(Delivery.REFUSED, store.state.conversations[public]!!.single().delivery)
    }

    /** Otherwise it would say "Sending" until the app was closed. */
    @Test
    fun `a channel message still unanswered at a disconnect is unresolved`() {
        store.recordChannelSent(public, "hello", 1)
        store.onDisconnected()
        assertEquals(Delivery.UNRESOLVED, store.state.conversations[public]!!.single().delivery)
        assertTrue(store.state.channels.isEmpty())
        assertFalse(store.state.channelsLoaded)
    }

    @Test
    fun `joining fills a free slot and leaving frees it, keeping the messages`() {
        empty(1)
        store.onChannelSet(1, "#test", test.toByteArray())
        assertEquals("#test", store.state.channels.single().name)
        assertTrue(store.state.freeChannelSlots.isEmpty())

        said(1, "Ada Whitlock: hi")
        store.onChannelSet(1, "", ByteArray(16))
        assertTrue(store.state.channels.isEmpty())
        assertEquals(listOf(1), store.state.freeChannelSlots)
        assertEquals(1, store.state.conversations[test]!!.size)
    }

    @Test
    fun `a channel thread survives the log`() {
        slot(0, "Public", public)
        said(0, "Ada Whitlock: kept")
        store.recordChannelSent(public, "mine", 2)
        store.onChannelSent()

        val back = MessageLog.decode(MessageLog.encode(store.state.conversations))
        val thread = back.conversations[public]!!
        assertEquals(listOf("Ada Whitlock: kept", "mine"), thread.map { it.text })
        assertEquals(Delivery.SENT, thread.last().delivery)
    }
}
