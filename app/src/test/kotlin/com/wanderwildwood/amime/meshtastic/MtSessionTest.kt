package com.wanderwildwood.amime.meshtastic

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

private const val ME = 0x11
private const val ANN = 0x22
private const val CONFIG_ID = 0x4D4D

/** A radio that answers reads from a script, and starts the handshake script when asked. */
private class FakeRadio(var firmware: String = "2.7.26.54e0d8d") : MtTransport {
    val written = mutableListOf<ToRadio>()
    val waiting = ArrayDeque<ByteArray>()
    val delays = mutableListOf<Long>()
    var emptyFirst = 0
    var reads = 0
    var duringRead: (() -> Unit)? = null
    var configComplete = CONFIG_ID
    lateinit var session: MtSession

    val lora = Config.LoRaConfig(
        region = Config.LoRaConfig.RegionCode.UNSET,
        hop_limit = 5,
        tx_power = 20,
        bandwidth = 250,
        spread_factor = 11,
        use_preset = true,
        modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
    )

    override fun write(toRadio: ByteArray) {
        val t = ToRadio.ADAPTER.decode(toRadio)
        written += t
        if (t.want_config_id != null) handshake().forEach { waiting += it.encode() }
    }

    override fun read(delayMs: Long) {
        delays += delayMs
        reads++
        duringRead?.let { duringRead = null; it() }
        if (emptyFirst > 0) { emptyFirst--; session.onRead(ByteArray(0)); return }
        session.onRead(waiting.removeFirstOrNull() ?: ByteArray(0))
    }

    fun handshake(): List<FromRadio> = listOf(
        FromRadio(my_info = MyNodeInfo(my_node_num = ME)),
        FromRadio(node_info = NodeInfo(num = ME, user = User(long_name = "Mine", short_name = "MI"))),
        FromRadio(metadata = DeviceMetadata(firmware_version = firmware)),
        FromRadio(channel = Channel(index = 0, role = Channel.Role.PRIMARY, settings = ChannelSettings(psk = okio.ByteString.of(1)))),
        FromRadio(channel = Channel(index = 1, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "ops"))),
        FromRadio(channel = Channel(index = 2, role = Channel.Role.DISABLED)),
        FromRadio(config = Config(lora = lora)),
        FromRadio(config = Config(device = Config.DeviceConfig())),
        FromRadio(
            node_info = NodeInfo(
                num = ANN, last_heard = 1000, snr = 5.5f, hops_away = 1,
                user = User(long_name = "Ann", short_name = "AN"),
                device_metrics = DeviceMetrics(battery_level = 80),
                position = Position(latitude_i = 355951000, longitude_i = -825515000),
            ),
        ),
        FromRadio(config_complete_id = configComplete),
    )
}

private class Events : MtSession.Listener {
    val log = mutableListOf<String>()
    val nodes = mutableMapOf<Long, MtNode>()
    val channels = mutableListOf<Triple<Int, String, Boolean>>()
    val texts = mutableListOf<List<Any?>>()
    val acks = mutableListOf<Triple<Int, Boolean, Int>>()
    var region: Int? = null
    var name: String? = null
    var oldFirmware: String? = null
    var selfPosition: Pair<Double, Double>? = null

    override fun onReady(name: String, firmware: String) { log += "ready"; this.name = name }
    override fun onNode(node: MtNode) { nodes[node.num] = node }
    override fun onSelfPosition(latitude: Double, longitude: Double) { selfPosition = latitude to longitude }
    override fun onChannel(index: Int, name: String, enabled: Boolean, primary: Boolean) { channels += Triple(index, name, enabled) }
    override fun onChannelsLoaded() { log += "channels" }
    override fun onRegion(region: Int) { this.region = region }
    override fun onText(from: Long, channel: Int?, text: String, time: Long, snr: Float?, hops: Int?) {
        texts += listOf(from, channel, text, hops)
    }
    override fun onAck(packetId: Int, fromDestination: Boolean, error: Int) { acks += Triple(packetId, fromDestination, error) }
    override fun onTooOld(firmware: String) { oldFirmware = firmware }
}

private fun rig(radio: FakeRadio = FakeRadio()): Triple<FakeRadio, Events, MtSession> {
    val events = Events()
    val session = MtSession(radio, events, clock = { 5000 })
    radio.session = session
    return Triple(radio, events, session)
}

private fun text(from: Int, to: Int, channel: Int, words: String, id: Int = 7, hopStart: Int = 3, hopLimit: Int = 2) =
    FromRadio(
        packet = MeshPacket(
            from = from, to = to, channel = channel, id = id, rx_snr = 4f,
            hop_start = hopStart, hop_limit = hopLimit,
            decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = words.encodeUtf8()),
        ),
    ).encode()

class MtSessionTest {

    @Test
    fun handshake_asks_first_and_is_ready_only_at_config_complete() {
        val (radio, events, session) = rig()
        session.start()
        assertEquals(CONFIG_ID, radio.written.first().want_config_id)
        assertEquals(listOf("channels", "ready"), events.log)
        assertTrue(session.ready)
        assertEquals("Mine", events.name)
        assertEquals(ME.toLong(), session.myNum)
    }

    @Test
    fun not_ready_when_the_nonce_does_not_match() {
        val radio = FakeRadio().also { it.configComplete = 99 }
        val (_, events, session) = rig(radio)
        session.start()
        assertFalse(session.ready)
        assertTrue(events.log.isEmpty())
    }

    @Test
    fun channels_are_named_and_the_default_one_is_its_preset() {
        val (_, events, session) = rig()
        session.start()
        assertEquals(
            listOf(Triple(0, "LongFast", true), Triple(1, "ops", true), Triple(2, "Channel 2", false)),
            events.channels,
        )
    }

    @Test
    fun nodes_come_with_name_battery_snr_hops_and_position_and_our_own_is_left_out() {
        val (_, events, session) = rig()
        session.start()
        assertNull(events.nodes[ME.toLong()])
        val ann = events.nodes[ANN.toLong()]!!
        assertEquals("Ann", ann.longName)
        assertEquals(80, ann.battery)
        assertEquals(5.5f, ann.snr)
        assertEquals(1, ann.hops)
        assertEquals(1000L, ann.lastHeard)
        assertEquals(35.5951, ann.latitude!!, 1e-6)
        assertEquals(-82.5515, ann.longitude!!, 1e-6)
    }

    @Test
    fun region_and_lora_config_are_reported() {
        val (_, events, session) = rig()
        session.start()
        assertEquals(0, events.region)
    }

    @Test
    fun an_empty_read_before_config_complete_is_retried_not_believed() {
        val radio = FakeRadio().also { it.emptyFirst = 3 }
        val (_, _, session) = rig(radio)
        session.start()
        assertTrue(session.ready)
        assertEquals(3, radio.delays.count { it == 250L })
    }

    @Test
    fun the_drain_reads_until_empty_and_a_notify_wakes_it_again() {
        val (radio, events, session) = rig()
        session.start()
        val readsAfterHandshake = radio.reads
        radio.waiting += text(ANN, 0xFFFFFFFF.toInt(), 1, "hello")
        session.onFromNum()
        assertEquals(listOf(listOf(ANN.toLong(), 1, "hello", 1)), events.texts)
        // one read for the text, one empty read that ends the drain
        assertEquals(2, radio.reads - readsAfterHandshake)
    }

    @Test
    fun a_notify_during_a_drain_makes_one_more_pass() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += text(ANN, 0xFFFFFFFF.toInt(), 0, "one", id = 1)
        radio.duringRead = {
            // lands after the empty read would have ended the drain
            session.onFromNum()
            radio.waiting += text(ANN, 0xFFFFFFFF.toInt(), 0, "two", id = 2)
        }
        session.onFromNum()
        assertEquals(listOf("one", "two"), events.texts.map { it[2] })
    }

    @Test
    fun direct_messages_are_for_us_and_others_are_not() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += text(ANN, ME, 0, "to me", id = 1)
        radio.waiting += text(ANN, 0x99, 0, "to someone else", id = 2)
        radio.waiting += text(ME, 0xFFFFFFFF.toInt(), 0, "my own echo", id = 3)
        radio.waiting += text(ANN, ME, 0, "to me", id = 1) // the same packet again
        session.onFromNum()
        assertEquals(listOf(listOf(ANN.toLong(), null, "to me", 1)), events.texts)
    }

    @Test
    fun hops_are_unknown_when_the_sender_did_not_say() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += text(ANN, ME, 0, "old node", hopStart = 0, hopLimit = 3)
        session.onFromNum()
        assertNull(events.texts.single()[3])
    }

    @Test
    fun multibyte_text_survives() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += text(ANN, ME, 0, "こんにちは 🙂")
        session.onFromNum()
        assertEquals("こんにちは 🙂", events.texts.single()[2])
    }

    @Test
    fun positions_after_config_complete_give_the_same_result_as_positions_in_node_info() {
        val (radio, events, session) = rig()
        session.start()
        val inNodeInfo = events.nodes[ANN.toLong()]!!
        // 2.8 style: a new node, its position only arrives as a later POSITION_APP packet
        val bob = 0x33
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = bob, to = 0xFFFFFFFF.toInt(), id = 50,
                decoded = Data(
                    portnum = PortNum.POSITION_APP,
                    payload = Position(latitude_i = 355951000, longitude_i = -825515000).encodeByteString(),
                ),
            ),
        ).encode()
        session.onFromNum()
        val replayed = events.nodes[bob.toLong()]!!
        assertEquals(inNodeInfo.latitude, replayed.latitude)
        assertEquals(inNodeInfo.longitude, replayed.longitude)
    }

    @Test
    fun telemetry_updates_the_battery() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = ANN, to = 0xFFFFFFFF.toInt(), id = 60,
                decoded = Data(
                    portnum = PortNum.TELEMETRY_APP,
                    payload = Telemetry(device_metrics = DeviceMetrics(battery_level = 42)).encodeByteString(),
                ),
            ),
        ).encode()
        session.onFromNum()
        assertEquals(42, events.nodes[ANN.toLong()]!!.battery)
        assertEquals("Ann", events.nodes[ANN.toLong()]!!.longName)
    }

    @Test
    fun our_own_fix_is_reported_for_distances() {
        val radio = FakeRadio()
        val (_, events, session) = rig(radio)
        session.start()
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = ME, to = 0xFFFFFFFF.toInt(), id = 61,
                decoded = Data(
                    portnum = PortNum.POSITION_APP,
                    payload = Position(latitude_i = 350000000, longitude_i = -800000000).encodeByteString(),
                ),
            ),
        ).encode()
        session.onFromNum()
        assertEquals(35.0 to -80.0, events.selfPosition)
    }

    @Test
    fun sending_to_a_channel_is_a_broadcast_without_an_ack() {
        val (radio, _, session) = rig()
        session.start()
        radio.written.clear()
        session.sendChannel(1, "hello there", 0)
        val p = radio.written.single().packet!!
        assertEquals(-1, p.to)
        assertEquals(1, p.channel)
        assertFalse(p.want_ack)
        assertNotEquals(0, p.id)
        assertEquals(PortNum.TEXT_MESSAGE_APP, p.decoded!!.portnum)
        assertEquals("hello there", p.decoded!!.payload.utf8())
    }

    @Test
    fun sending_to_a_node_asks_for_an_ack_and_the_destination_ack_settles_it() {
        val (radio, events, session) = rig()
        session.start()
        radio.written.clear()
        val id = session.sendText(ANN.toLong(), 0, "hi Ann")
        val p = radio.written.single().packet!!
        assertEquals(ANN, p.to)
        assertTrue(p.want_ack)
        assertEquals(id, p.id)
        assertTrue(session.waitingOn(id))

        radio.waiting += ack(from = ANN, requestId = id, error = Routing.Error.NONE)
        session.onFromNum()
        assertEquals(listOf(Triple(id, true, 0)), events.acks)
        assertFalse(session.waitingOn(id))
    }

    @Test
    fun an_ack_from_a_repeater_is_not_delivery_and_a_nak_carries_its_reason() {
        val (radio, events, session) = rig()
        session.start()
        val id = session.sendText(ANN.toLong(), 0, "hi")
        radio.waiting += ack(from = 0x77, requestId = id, error = Routing.Error.NONE)
        session.onFromNum()
        assertEquals(Triple(id, false, 0), events.acks.last())
        assertTrue(session.waitingOn(id))

        radio.waiting += ack(from = ANN, requestId = id, error = Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY)
        session.onFromNum()
        assertEquals(Triple(id, true, 39), events.acks.last())
    }

    @Test
    fun region_change_sends_the_whole_lora_config_with_only_the_region_changed() {
        val (radio, _, session) = rig()
        session.start()
        radio.written.clear()
        assertTrue(session.setRegion(Config.LoRaConfig.RegionCode.US.value))
        val p = radio.written.single().packet!!
        assertEquals(ME, p.to)
        assertEquals(PortNum.ADMIN_APP, p.decoded!!.portnum)
        val sent = AdminMessage.ADAPTER.decode(p.decoded!!.payload).set_config!!.lora!!
        assertEquals(radio.lora.copy(region = Config.LoRaConfig.RegionCode.US), sent)
        assertEquals(5, sent.hop_limit)
        assertEquals(20, sent.tx_power)
    }

    @Test
    fun region_cannot_be_set_before_the_config_has_been_read() {
        val (radio, _, session) = rig()
        assertFalse(session.setRegion(1))
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun firmware_older_than_2_5_is_said_to_be_too_old() {
        val (_, events, session) = rig(FakeRadio(firmware = "2.3.15.abc"))
        session.start()
        assertEquals("2.3.15.abc", events.oldFirmware)
        assertTrue(MtSession.isTooOld("1.2.0"))
        assertFalse(MtSession.isTooOld("2.5.0.x"))
        assertFalse(MtSession.isTooOld("2.7.26.54e0d8d"))
        assertFalse(MtSession.isTooOld(""))
    }

    @Test
    fun garbage_from_the_radio_is_reported_and_the_drain_goes_on() {
        val (radio, events, session) = rig()
        session.start()
        radio.waiting += byteArrayOf(0x0a, 0x7f) // a length that runs off the end
        radio.waiting += text(ANN, ME, 0, "after", id = 70)
        session.onFromNum()
        assertEquals("after", events.texts.single()[2])
    }

    @Test
    fun keys_round_trip_and_a_channel_key_cannot_be_a_node_key() {
        assertEquals(0xDEADBEEFL, MtSession.numOf(MtSession.keyOf(0xDEADBEEFL)))
        assertEquals(4, MtSession.keyOf(1).size)
        assertEquals(16, MtSession.channelKey(3).size)
        assertNotEquals(MtSession.channelKey(0), MtSession.channelKey(1))
    }

    private fun ack(from: Int, requestId: Int, error: Routing.Error): ByteArray = FromRadio(
        packet = MeshPacket(
            from = from, to = ME, id = 900 + from,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                request_id = requestId,
                payload = Routing(error_reason = error).encodeByteString(),
            ),
        ),
    ).encode()

    /** A transport whose reads never answer on their own: the test plays the callbacks. */
    private class Silent : MtTransport {
        var reads = 0
        var writes = 0
        override fun write(toRadio: ByteArray) { writes++ }
        override fun read(delayMs: Long) { reads++ }
    }

    @Test
    fun a_drain_left_over_from_a_dropped_link_does_not_block_the_next_connection() {
        val t = Silent()
        val session = MtSession(t, Events(), clock = { 5000 })
        session.start()
        assertEquals(1, t.reads) // the read that the dropped link never answered
        session.start() // reconnect on the same session
        assertEquals(2, t.reads)
        assertEquals(2, t.writes)
    }

    @Test
    fun a_read_that_never_called_back_is_asked_for_again_a_few_times() {
        val t = Silent()
        val session = MtSession(t, Events(), clock = { 5000 })
        session.start()
        repeat(3) { assertTrue(session.onReadStalled()) }
        assertEquals(4, t.reads)
        assertFalse(session.onReadStalled())
        assertEquals(4, t.reads)
    }
}
