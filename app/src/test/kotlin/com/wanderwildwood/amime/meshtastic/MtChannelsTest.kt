package com.wanderwildwood.amime.meshtastic

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

private const val SELF = 0x11
private const val OTHER = 0x22
private const val NONCE = 0x4D4D

/** A radio with a scripted handshake whose channels and configs the test chooses. */
private class ChanRadio(
    val channelList: List<Channel>,
    val lora: Config.LoRaConfig = Config.LoRaConfig(
        region = Config.LoRaConfig.RegionCode.US, use_preset = true, hop_limit = 4, tx_power = 20, tx_enabled = true,
        modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
    ),
) : MtTransport {
    val written = mutableListOf<ToRadio>()
    val waiting = ArrayDeque<ByteArray>()
    lateinit var session: MtSession

    override fun write(toRadio: ByteArray) {
        val t = ToRadio.ADAPTER.decode(toRadio)
        written += t
        if (t.want_config_id != null) {
            val frames = mutableListOf(
                FromRadio(my_info = MyNodeInfo(my_node_num = SELF)),
                FromRadio(node_info = NodeInfo(num = SELF, user = User(long_name = "Mine", short_name = "MI", is_licensed = true))),
                FromRadio(metadata = DeviceMetadata(firmware_version = "2.7.26.x")),
            )
            channelList.forEach { frames += FromRadio(channel = it) }
            frames += FromRadio(config = Config(lora = lora))
            frames += FromRadio(config = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT, tzdef = "EST5EDT")))
            frames += FromRadio(config_complete_id = NONCE)
            frames.forEach { waiting += it.encode() }
        }
    }

    override fun read(delayMs: Long) = session.onRead(waiting.removeFirstOrNull() ?: ByteArray(0))

    /** The admin messages the app has sent, in order. */
    fun admins(): List<AdminMessage> = written.mapNotNull { it.packet }
        .filter { it.decoded?.portnum == PortNum.ADMIN_APP }
        .map { AdminMessage.ADAPTER.decode(it.decoded!!.payload) }

    fun adminPackets(): List<MeshPacket> = written.mapNotNull { it.packet }.filter { it.decoded?.portnum == PortNum.ADMIN_APP }
}

private class Seen : MtSession.Listener {
    var infos: List<MtChannelInfo> = emptyList()
    var configs: MtConfigs? = null
    val answers = mutableListOf<Boolean>()
    override fun onChannelInfos(channels: List<MtChannelInfo>) { infos = channels }
    override fun onSettings(configs: MtConfigs) { this.configs = configs }
    override fun onAdminAnswer(ok: Boolean) { answers += ok }
}

private val KEY_A = ByteArray(16) { (it + 1).toByte() }.toByteString()
private val KEY_B = ByteArray(32) { (it + 50).toByte() }.toByteString()

private fun slots(vararg filled: Channel): List<Channel> =
    (0 until 8).map { i -> filled.firstOrNull { it.index == i } ?: Channel(index = i, role = Channel.Role.DISABLED) }

private val PRIMARY = Channel(index = 0, role = Channel.Role.PRIMARY, settings = ChannelSettings(psk = ChannelKeys.DEFAULT))
private val OPS = Channel(
    index = 1, role = Channel.Role.SECONDARY,
    settings = ChannelSettings(
        name = "ops", psk = KEY_A, id = 77, uplink_enabled = true,
        module_settings = ModuleSettings(position_precision = 13, is_muted = true),
    ),
)

private fun rig(vararg filled: Channel): Triple<ChanRadio, Seen, MtSession> {
    val radio = ChanRadio(slots(*(if (filled.isEmpty()) arrayOf(PRIMARY, OPS) else filled)))
    val seen = Seen()
    val session = MtSession(radio, seen, clock = { 5000 }, configId = NONCE)
    radio.session = session
    session.start()
    radio.written.clear()
    return Triple(radio, seen, session)
}

class MtChannelsTest {

    @Test
    fun every_slot_is_listed_with_its_role_and_how_it_is_locked() {
        val (_, seen, _) = rig()
        assertEquals(8, seen.infos.size)
        assertEquals(listOf(1, 2, 0, 0, 0, 0, 0, 0), seen.infos.map { it.role })
        assertEquals(KeyKind.DEFAULT, seen.infos[0].key)
        assertEquals(KeyKind.OWN_128, seen.infos[1].key)
        assertEquals("ops", seen.infos[1].name)
        assertTrue(seen.infos[1].uplink)
        assertFalse(seen.infos[1].downlink)
        assertTrue(seen.infos[0].primary)
        assertFalse(seen.infos[2].enabled)
    }

    @Test
    fun a_new_channel_goes_in_the_first_free_slot_as_a_secondary_without_sharing_position() {
        val (radio, seen, session) = rig()
        val slot = session.addChannel("crew", KEY_B)
        assertEquals(2, slot)
        val set = radio.admins().first().set_channel!!
        assertEquals(2, set.index)
        assertEquals(Channel.Role.SECONDARY, set.role)
        assertEquals("crew", set.settings!!.name)
        assertEquals(KEY_B, set.settings!!.psk)
        assertEquals(0, set.settings!!.module_settings!!.position_precision)
        assertEquals(Channel.Role.SECONDARY.value, seen.infos[2].role)
        // and it asks for the slot back, wanting an answer
        val ask = radio.adminPackets().last()
        assertEquals(3, AdminMessage.ADAPTER.decode(ask.decoded!!.payload).get_channel_request)
        assertTrue(ask.decoded!!.want_response)
    }

    @Test
    fun the_slot_read_back_replaces_what_was_sent() {
        val (radio, seen, session) = rig()
        session.addChannel("crew", KEY_B)
        val back = Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "crew", psk = KEY_B, id = 9))
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = SELF, to = SELF, id = 500,
                decoded = Data(portnum = PortNum.ADMIN_APP, payload = AdminMessage(get_channel_response = back).encodeByteString()),
            ),
        ).encode()
        session.onFromNum()
        assertEquals("crew", seen.infos[2].name)
    }

    private fun answer(radio: ChanRadio, from: Int, requestId: Int, back: Channel) {
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = from, to = SELF, id = 0x6968985e,
                decoded = Data(portnum = PortNum.ADMIN_APP, request_id = requestId, payload = AdminMessage(get_channel_response = back).encodeByteString()),
            ),
        ).encode()
    }

    @Test
    fun the_radios_own_answer_to_the_read_back_fills_the_slot_with_its_own_key() {
        val (radio, seen, session) = rig()
        val key = ByteArray(32) { (it * 3 + 1).toByte() }.toByteString()
        assertEquals(2, session.addChannel("wndrtest", key))
        val ask = radio.adminPackets().last()
        answer(radio, SELF, ask.id, Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "wndrtest", psk = key)))
        session.onFromNum()
        assertEquals("wndrtest", seen.infos[2].name)
        assertEquals(Channel.Role.SECONDARY.value, seen.infos[2].role)
        assertEquals(KeyKind.of(key), seen.infos[2].key)
    }

    @Test
    fun an_answer_with_no_sender_to_our_request_is_believed() {
        val (radio, seen, session) = rig()
        session.addChannel("crew", KEY_B)
        answer(radio, 0, radio.adminPackets().last().id, Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "crew", psk = KEY_B, id = 4)))
        session.onFromNum()
        assertEquals("crew", seen.infos[2].name)
    }

    @Test
    fun the_slot_asked_for_is_the_slot_filled_even_when_the_answer_names_another_index() {
        val (radio, seen, session) = rig()
        session.addChannel("crew", KEY_B)
        // an answer that carries no index at all would land on the main channel
        answer(radio, SELF, radio.adminPackets().last().id, Channel(role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "crew", psk = KEY_B)))
        session.onFromNum()
        assertEquals("crew", seen.infos[2].name)
        assertEquals(Channel.Role.PRIMARY.value, seen.infos[0].role)
    }

    @Test
    fun an_admin_answer_from_another_node_is_not_believed() {
        val (radio, seen, session) = rig()
        val fake = Channel(index = 1, role = Channel.Role.DISABLED)
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = OTHER, to = SELF, id = 501,
                decoded = Data(portnum = PortNum.ADMIN_APP, payload = AdminMessage(get_channel_response = fake).encodeByteString()),
            ),
        ).encode()
        session.onFromNum()
        assertEquals(2, seen.infos[1].role)
    }

    @Test
    fun with_every_slot_taken_a_channel_is_not_added() {
        val full = (0 until 8).map { Channel(index = it, role = if (it == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY, settings = ChannelSettings(name = "c$it")) }
        val (radio, _, session) = rig(*full.toTypedArray())
        assertEquals(-1, session.freeSlot())
        assertEquals(-1, session.addChannel("more", KEY_A))
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun a_name_the_radio_would_cut_is_refused() {
        val (radio, _, session) = rig()
        assertEquals(-1, session.addChannel("twelve chars", KEY_A))
        assertFalse(session.saveChannel(1, "twelve chars", null, false, false))
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun editing_changes_what_was_asked_and_keeps_everything_else() {
        val (radio, _, session) = rig()
        assertTrue(session.saveChannel(1, "ops2", null, uplink = false, downlink = true))
        val set = radio.admins().first().set_channel!!
        assertEquals("ops2", set.settings!!.name)
        assertEquals(KEY_A, set.settings!!.psk) // key kept
        assertEquals(77, set.settings!!.id) // the rest kept
        assertEquals(13, set.settings!!.module_settings!!.position_precision)
        assertTrue(set.settings!!.module_settings!!.is_muted)
        assertFalse(set.settings!!.uplink_enabled)
        assertTrue(set.settings!!.downlink_enabled)
        assertEquals(Channel.Role.SECONDARY, set.role)
    }

    @Test
    fun editing_can_give_a_channel_a_new_key() {
        val (radio, seen, session) = rig()
        assertTrue(session.saveChannel(1, "ops", KEY_B, true, false))
        assertEquals(KEY_B, radio.admins().first().set_channel!!.settings!!.psk)
        assertEquals(KeyKind.OWN_256, seen.infos[1].key)
    }

    @Test
    fun the_main_channel_can_be_edited_but_not_turned_off_or_moved() {
        val (radio, _, session) = rig()
        assertTrue(session.saveChannel(0, "Main", null, false, false))
        assertEquals(Channel.Role.PRIMARY, radio.admins().first().set_channel!!.role)
        assertFalse(session.saveChannel(0, "Main", null, false, false, Channel.Role.DISABLED))
        assertFalse(session.saveChannel(1, "ops", null, false, false, Channel.Role.PRIMARY))
        assertFalse(session.deleteChannel(0))
    }

    @Test
    fun deleting_turns_the_slot_off_and_wipes_the_key() {
        val (radio, seen, session) = rig()
        assertTrue(session.deleteChannel(1))
        val set = radio.admins().first().set_channel!!
        assertEquals(1, set.index)
        assertEquals(Channel.Role.DISABLED, set.role)
        assertEquals(ByteString.EMPTY, set.settings!!.psk)
        assertEquals("", set.settings!!.name)
        assertEquals(0, seen.infos[1].role)
        assertFalse(session.deleteChannel(5)) // already off
    }

    @Test
    fun nothing_is_sent_before_the_handshake_is_done() {
        val radio = ChanRadio(slots(PRIMARY))
        val session = MtSession(radio, Seen(), configId = NONCE)
        radio.session = session
        assertEquals(-1, session.addChannel("x", KEY_A))
        assertFalse(session.saveChannel(1, "x", null, false, false))
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun a_link_is_planned_before_anything_is_sent() {
        val (radio, _, session) = rig()
        val set = ChannelSet(
            settings = listOf(
                ChannelSettings(psk = ChannelKeys.DEFAULT), // the default channel: already there
                ChannelSettings(name = "ops", psk = KEY_A), // same name and key: already there
                ChannelSettings(name = "ops", psk = KEY_B), // same name, other key: a different mesh
                ChannelSettings(name = "hike", psk = KEY_B),
                ChannelSettings(name = "far too long name", psk = KEY_B),
            ),
            lora_config = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.EU_868),
        )
        val plan = session.planImport(set)
        assertEquals(
            listOf(
                ImportItem.State.PRESENT, ImportItem.State.PRESENT, ImportItem.State.ADD,
                ImportItem.State.ADD, ImportItem.State.REFUSED,
            ),
            plan.items.map { it.state },
        )
        assertEquals("LongFast", plan.items[0].name)
        assertEquals(2, plan.toAdd)
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, plan.lora!!.region)
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun a_plan_runs_out_of_room() {
        val six = (0 until 7).map { Channel(index = it, role = if (it == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY, settings = ChannelSettings(name = "c$it", psk = KEY_A)) }
        val (_, _, session) = rig(*six.toTypedArray())
        val set = ChannelSet(settings = listOf(ChannelSettings(name = "n1", psk = KEY_B), ChannelSettings(name = "n2", psk = KEY_B)))
        assertEquals(listOf(ImportItem.State.ADD, ImportItem.State.NO_ROOM), session.planImport(set).items.map { it.state })
    }

    @Test
    fun applying_a_plan_adds_only_the_channels_and_not_the_radio_settings() {
        val (radio, seen, session) = rig()
        val set = ChannelSet(
            settings = listOf(ChannelSettings(name = "ops", psk = KEY_A), ChannelSettings(name = "hike", psk = KEY_B)),
            lora_config = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.EU_868),
        )
        assertEquals(1, session.applyImport(session.planImport(set)))
        val sent = radio.admins()
        assertEquals(listOf(2), sent.mapNotNull { it.set_channel?.index })
        assertTrue(sent.none { it.set_config != null })
        assertEquals("hike", seen.infos[2].name)
    }

    @Test
    fun the_radio_settings_in_a_link_change_only_the_modem_and_keep_power_and_hops() {
        val (radio, seen, session) = rig()
        val incoming = Config.LoRaConfig(
            region = Config.LoRaConfig.RegionCode.EU_868, use_preset = true,
            modem_preset = Config.LoRaConfig.ModemPreset.SHORT_FAST, hop_limit = 7, tx_power = 1, tx_enabled = false,
        )
        assertTrue(session.applyLoraFrom(incoming))
        val sent = radio.admins().single().set_config!!.lora!!
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, sent.region)
        assertEquals(Config.LoRaConfig.ModemPreset.SHORT_FAST, sent.modem_preset)
        assertEquals(4, sent.hop_limit)
        assertEquals(20, sent.tx_power)
        assertTrue(sent.tx_enabled)
        // shown only once the radio has said it took it
        assertEquals(null, seen.configs!!.lora?.takeIf { it.region == Config.LoRaConfig.RegionCode.EU_868 })
        session.onRead(
            org.meshtastic.proto.FromRadio(
                packet = org.meshtastic.proto.MeshPacket(
                    from = 0x11, to = 0x11, id = 3,
                    decoded = org.meshtastic.proto.Data(
                        portnum = org.meshtastic.proto.PortNum.ROUTING_APP,
                        request_id = radio.adminPackets().single().id,
                        payload = org.meshtastic.proto.Routing(error_reason = org.meshtastic.proto.Routing.Error.NONE).encodeByteString(),
                    ),
                ),
            ).encode(),
        )
        assertEquals(sent, seen.configs!!.lora)
    }

    @Test
    fun a_link_with_no_region_keeps_ours() {
        val (radio, _, session) = rig()
        session.applyLoraFrom(Config.LoRaConfig(use_preset = true, modem_preset = Config.LoRaConfig.ModemPreset.MEDIUM_FAST))
        assertEquals(Config.LoRaConfig.RegionCode.US, radio.admins().single().set_config!!.lora!!.region)
    }

    @Test
    fun the_channel_set_to_share_is_the_channels_that_are_on_main_first() {
        val (_, _, session) = rig()
        val full = session.channelSet(withLora = true)!!
        assertEquals(listOf("", "ops"), full.settings.map { it.name })
        assertEquals(KEY_A, full.settings[1].psk)
        assertEquals(Config.LoRaConfig.RegionCode.US, full.lora_config!!.region)
        assertNull(session.channelSet(withLora = false)!!.lora_config)
        // and the link made from it reads back as itself
        assertEquals(full, ChannelUrl.parse(ChannelUrl.encode(full)))
        val only = ChannelUrl.parse(ChannelUrl.encode(full, channelsOnly = true))!!
        assertNull(only.lora_config)
        assertEquals(full.settings, only.settings)
    }

    @Test
    fun the_admin_answer_is_passed_on() {
        val (radio, seen, session) = rig()
        session.addChannel("crew", KEY_B)
        val id = radio.adminPackets().first().id
        radio.waiting += FromRadio(
            packet = MeshPacket(
                from = SELF, to = SELF, id = 600,
                decoded = Data(
                    portnum = PortNum.ROUTING_APP, request_id = id,
                    payload = org.meshtastic.proto.Routing(error_reason = org.meshtastic.proto.Routing.Error.NONE).encodeByteString(),
                ),
            ),
        ).encode()
        session.onFromNum()
        assertEquals(listOf(true), seen.answers)
    }
}
