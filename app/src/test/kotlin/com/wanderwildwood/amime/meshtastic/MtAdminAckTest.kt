package com.wanderwildwood.amime.meshtastic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import okio.ByteString.Companion.toByteString
import java.util.Random

/**
 * The radio drops a packet whose (sender, id) it has seen lately, and it remembers across
 * reconnects, so ids must not repeat; and a settings write is only "sent" once the radio's
 * routing answer says so.
 */
class MtAdminAckTest {

    private class Radio : MtTransport {
        val written = mutableListOf<ToRadio>()
        val waiting = ArrayDeque<ByteArray>()
        lateinit var session: MtSession
        override fun write(toRadio: ByteArray) {
            val t = ToRadio.ADAPTER.decode(toRadio)
            written += t
            if (t.want_config_id != null) {
                listOf(
                    FromRadio(my_info = MyNodeInfo(my_node_num = 0x11)),
                    FromRadio(node_info = NodeInfo(num = 0x11, user = User(long_name = "Mine", short_name = "MI"))),
                    FromRadio(metadata = DeviceMetadata(firmware_version = "2.7.26.x")),
                    FromRadio(channel = Channel(index = 0, role = Channel.Role.PRIMARY)),
                    FromRadio(config = Config(lora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US, hop_limit = 3))),
                    FromRadio(config_complete_id = 0x4D4D),
                ).forEach { waiting += it.encode() }
            }
        }
        override fun read(delayMs: Long) = session.onRead(waiting.removeFirstOrNull() ?: ByteArray(0))
        fun ids() = written.mapNotNull { it.packet?.id }
    }

    private class Heard : MtSession.Listener {
        var configs: MtConfigs? = null
        val reports = mutableListOf<MtAdminReport>()
        override fun onSettings(configs: MtConfigs) { this.configs = configs }
        override fun onAdminReport(report: MtAdminReport) { reports += report }
    }

    private class Rig(seed: Long = 1) {
        var now = 0L
        val radio = Radio()
        val heard = Heard()
        val rnd = Random(seed)
        val s = MtSession(radio, heard, random = { rnd.nextInt() }, millis = { now })
        init { radio.session = s; s.start(); radio.written.clear() }
        fun answer(id: Int, e: Routing.Error) = s.onRead(
            FromRadio(
                packet = MeshPacket(
                    from = 0x11, to = 0x11, id = 5,
                    decoded = Data(portnum = PortNum.ROUTING_APP, request_id = id, payload = Routing(error_reason = e).encodeByteString()),
                ),
            ).encode(),
        )
        fun lastId() = radio.written.last().packet!!.id
        fun last() = heard.reports.last()
    }

    @Test
    fun sessions_do_not_start_at_the_same_id_and_ids_never_repeat_inside_one() {
        val starts = (1L..50L).map { seed -> Rig(seed).also { it.s.sendText(0x22, 0, "x") }.radio.ids().first() }
        assertEquals(50, starts.toSet().size)
        assertTrue(starts.none { it == 0 })
        val r = Rig(9)
        repeat(2000) { r.s.sendText(0x22, 0, "x") }
        assertEquals(2000, r.radio.ids().toSet().size)
    }

    @Test
    fun ids_wrap_past_zero_without_using_it_and_a_zero_draw_is_not_used() {
        val wrap = MtSession(Radio(), Heard(), random = { -2 })
        val ids = (1..4).map { wrap.sendText(0x22, 0, "x") }
        assertEquals(listOf(-2, -1, 1, 2), ids)
        val zero = MtSession(Radio(), Heard(), random = { 0 })
        assertEquals(1, zero.sendText(0x22, 0, "x"))
    }

    @Test
    fun a_write_is_waiting_until_the_radio_answers_then_the_row_changes_and_it_says_saved() {
        val r = Rig()
        val hop = MtFields.get("lora.hop_limit")!!
        assertTrue(r.s.change(hop, 4) is MtFields.Applied.Change)
        assertEquals(MtAdminReport.State.PENDING, r.last().state)
        assertEquals(3, r.s.configs.lora!!.hop_limit)
        r.answer(r.lastId(), Routing.Error.NONE)
        assertEquals(MtAdminReport.State.SAVED, r.last().state)
        assertTrue(r.last().label.contains("4"))
        assertEquals(4, r.s.configs.lora!!.hop_limit)
        assertEquals(4, r.heard.configs!!.lora!!.hop_limit)
        assertFalse(r.s.adminWaiting())
    }

    @Test
    fun no_answer_times_out_and_the_row_keeps_its_old_value() {
        val r = Rig()
        r.s.change(MtFields.get("lora.hop_limit")!!, 4)
        r.now = MtSession.ADMIN_TIMEOUT_MS - 1
        assertTrue(r.s.expireAdmin())
        assertEquals(MtAdminReport.State.PENDING, r.last().state)
        r.now = MtSession.ADMIN_TIMEOUT_MS
        assertFalse(r.s.expireAdmin())
        assertEquals(MtAdminReport.State.NO_ANSWER, r.last().state)
        assertEquals(3, r.s.configs.lora!!.hop_limit)
        // a late answer to a write already given up on changes nothing
        r.answer(r.lastId(), Routing.Error.NONE)
        assertEquals(3, r.s.configs.lora!!.hop_limit)
        assertEquals(MtAdminReport.State.NO_ANSWER, r.last().state)
    }

    @Test
    fun a_refusal_is_reported_with_its_reason_and_nothing_changes() {
        val r = Rig()
        r.s.change(MtFields.get("lora.hop_limit")!!, 4)
        r.answer(r.lastId(), Routing.Error.NO_CHANNEL)
        assertEquals(MtAdminReport.State.REFUSED, r.last().state)
        assertEquals(Routing.Error.NO_CHANNEL.value, r.last().error)
        assertEquals(3, r.s.configs.lora!!.hop_limit)
    }

    @Test
    fun a_link_that_drops_before_the_answer_is_neither_failure_nor_success() {
        val r = Rig()
        r.s.setRegion(Config.LoRaConfig.RegionCode.EU_868.value)
        r.s.onLinkDown()
        assertEquals(MtAdminReport.State.DROPPED, r.last().state)
        assertTrue(r.last().restarts)
        assertFalse(r.s.adminWaiting())
    }

    @Test
    fun two_quick_changes_in_one_section_do_not_undo_each_other() {
        val r = Rig()
        r.s.change(MtFields.get("lora.hop_limit")!!, 4)
        val first = r.lastId()
        r.s.change(MtFields.get("lora.tx_power")!!, 17)
        val sent = AdminMessage.ADAPTER.decode(r.radio.written.last().packet!!.decoded!!.payload).set_config!!.lora!!
        assertEquals(4, sent.hop_limit)
        assertEquals(17, sent.tx_power)
        r.answer(first, Routing.Error.NONE)
        assertEquals(MtAdminReport.State.PENDING, r.last().state)
        r.answer(r.lastId(), Routing.Error.NONE)
        assertEquals(MtAdminReport.State.SAVED, r.last().state)
        assertEquals(17, r.s.configs.lora!!.tx_power)
        assertEquals(4, r.s.configs.lora!!.hop_limit)
    }

    @Test
    fun every_writer_wants_an_ack_uses_a_fresh_id_and_waits_for_the_answer() {
        val r = Rig()
        val key = ByteArray(16) { it.toByte() }.toByteString()
        assertTrue(r.s.setRegion(Config.LoRaConfig.RegionCode.EU_868.value))
        r.s.addChannel("ops", key)
        r.s.saveChannel(0, "main", null, false, false)
        r.s.change(MtFields.get("owner.long_name")!!, "Renamed")
        r.s.clearFixedPosition()
        val admin = r.radio.written.mapNotNull { it.packet }.filter { it.decoded?.portnum == PortNum.ADMIN_APP }
        assertTrue(admin.size >= 4)
        assertTrue(admin.all { it.want_ack && it.id != 0 })
        assertEquals(admin.size, admin.map { it.id }.toSet().size)
        // set_config, set_channel, set_owner are all tracked: each stays waiting until answered
        val kinds = admin.map { AdminMessage.ADAPTER.decode(it.decoded!!.payload) }
        val writes = admin.filterIndexed { i, _ -> kinds[i].get_channel_request == null }
        writes.forEach { assertTrue(r.s.adminWaiting()); r.answer(it.id, Routing.Error.NONE) }
        assertFalse(r.s.adminWaiting())
        assertEquals(MtAdminReport.State.SAVED, r.last().state)
    }

    @Test
    fun a_refused_channel_write_puts_the_slot_back() {
        val r = Rig()
        val key = ByteArray(16) { it.toByte() }.toByteString()
        val slot = r.s.addChannel("ops", key)
        assertEquals(1, slot)
        assertTrue(r.s.channelInfos().any { it.index == 1 && it.enabled })
        val setId = r.radio.written.mapNotNull { it.packet }
            .first { AdminMessage.ADAPTER.decode(it.decoded!!.payload).set_channel != null }.id
        r.answer(setId, Routing.Error.NO_CHANNEL)
        assertEquals(MtAdminReport.State.REFUSED, r.last().state)
        assertTrue(r.s.channelInfos().none { it.index == 1 && it.enabled })
    }

    @Test
    fun ids_differ_between_two_connections_of_the_same_app() {
        val a = Rig(1)
        val b = Rig(2)
        a.s.change(MtFields.get("lora.hop_limit")!!, 4)
        b.s.change(MtFields.get("lora.hop_limit")!!, 4)
        assertNotEquals(a.lastId(), b.lastId())
    }
}
