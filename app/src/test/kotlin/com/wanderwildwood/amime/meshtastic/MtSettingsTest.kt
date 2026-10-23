package com.wanderwildwood.amime.meshtastic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

/** Every section present, with values that are not the defaults so a change shows. */
private fun full(region: Config.LoRaConfig.RegionCode = Config.LoRaConfig.RegionCode.US) = MtConfigs(
    lora = Config.LoRaConfig(
        region = region, use_preset = true, hop_limit = 4, tx_power = 20, tx_enabled = true, channel_num = 9,
        bandwidth = 250, spread_factor = 11, coding_rate = 5, ignore_incoming = listOf(7, 8),
    ),
    device = Config.DeviceConfig(node_info_broadcast_secs = 10800, tzdef = "EST5EDT"),
    position = Config.PositionConfig(position_broadcast_secs = 900, rx_gpio = 5, tx_gpio = 6, position_flags = 811),
    power = Config.PowerConfig(sds_secs = 100, adc_multiplier_override = 2f),
    network = Config.NetworkConfig(wifi_ssid = "home", wifi_psk = "secret"),
    display = Config.DisplayConfig(screen_on_secs = 60),
    bluetooth = Config.BluetoothConfig(enabled = true, fixed_pin = 123456),
    mqtt = ModuleConfig.MQTTConfig(address = "mqtt.example", username = "u", password = "p", root = "msh"),
    serial = ModuleConfig.SerialConfig(rxd = 3, txd = 4),
    external_notification = ModuleConfig.ExternalNotificationConfig(output = 12),
    store_forward = ModuleConfig.StoreForwardConfig(records = 50),
    range_test = ModuleConfig.RangeTestConfig(),
    telemetry = ModuleConfig.TelemetryConfig(device_update_interval = 1800),
    canned_message = ModuleConfig.CannedMessageConfig(inputbroker_pin_a = 2),
    neighbor_info = ModuleConfig.NeighborInfoConfig(),
    detection_sensor = ModuleConfig.DetectionSensorConfig(monitor_pin = 7),
    paxcounter = ModuleConfig.PaxcounterConfig(wifi_threshold = -80),
    owner = User(id = "!00000011", long_name = "Mine", short_name = "MI", is_licensed = true),
)

private fun field(id: String) = MtFields.get(id)!!

/** Values worth trying on a field: its bounds, a middle, every option, both flags. */
private fun samples(f: Field): List<Any> = when (val k = f.kind) {
    is Kind.Choice -> k.options.map { it.value }
    is Kind.Num -> listOfNotNull(k.min, k.max, (k.min + k.max) / 2, if (k.zero != null) 0 else null)
    Kind.Flag -> listOf(true, false)
    is Kind.Text -> listOf("", "a", "x".repeat(k.maxBytes))
    Kind.Info -> emptyList()
}

class MtSettingsTest {

    @Test
    fun ids_are_unique_and_every_part_has_fields() {
        assertEquals(MtFields.all.size, MtFields.all.map { it.id }.toSet().size)
        Part.entries.forEach { assertTrue("${it.name} has no field", MtFields.inPart(it).isNotEmpty()) }
        assertTrue(MtFields.all.size >= 90)
    }

    @Test
    fun every_field_reads_what_it_wrote_and_changes_nothing_else() {
        val c0 = full()
        for (f in MtFields.all.filter { MtFields.editable(it) }) {
            val original = f.read(c0)!!
            for (v in samples(f)) {
                val c1 = f.write(c0, v)!!
                assertEquals("${f.id} read back", v, f.read(c1))
                // writing the old value back restores the exact whole: nothing else moved
                val c2 = f.write(c1, original)!!
                assertEquals("${f.id} left something else changed", c0, c2)
            }
        }
    }

    @Test
    fun every_value_the_table_offers_is_accepted() {
        for (f in MtFields.all.filter { MtFields.editable(it) }) {
            for (v in samples(f)) {
                val c = if (f.id == "lora.tx_power") full() else full()
                val r = MtFields.refusal(f, v, c)
                assertNull("${f.id}=$v: $r", if (f.id == "lora.tx_power" && (v as Int) > 30) null else r)
            }
        }
    }

    @Test
    fun a_field_whose_section_has_not_been_read_cannot_be_written() {
        for (f in MtFields.all) assertNull("${f.id} read", f.read(MtConfigs()))
        for (f in MtFields.all.filter { MtFields.editable(it) }) {
            assertEquals(MtFields.Applied.NotRead, MtFields.apply(f, samples(f).first(), MtConfigs()))
        }
    }

    @Test
    fun numbers_outside_their_bounds_are_refused() {
        val c = full()
        val hop = field("lora.hop_limit")
        assertNotNull(MtFields.refusal(hop, 0, c))
        assertNotNull(MtFields.refusal(hop, 8, c))
        assertNull(MtFields.refusal(hop, 7, c))
        assertNull(MtFields.refusal(hop, 1, c))
        assertNotNull(MtFields.refusal(field("bluetooth.fixed_pin"), 12345, c))
        assertNotNull(MtFields.refusal(field("bluetooth.fixed_pin"), 1000000, c))
        // 0 is allowed where it means something, and not where it does not
        assertNull(MtFields.refusal(field("position.position_broadcast_secs"), 0, c))
        assertNotNull(MtFields.refusal(hop, 0, c))
        // wrong kinds of value
        assertNotNull(MtFields.refusal(hop, "3", c))
        assertNotNull(MtFields.refusal(field("lora.tx_enabled"), 1, c))
        assertNotNull(MtFields.refusal(field("lora.modem_preset"), 99, c))
        assertNotNull(MtFields.refusal(field("device.role"), 13, c))
    }

    @Test
    fun text_is_cut_off_by_bytes_and_a_name_cannot_be_blank() {
        val c = full()
        assertNull(MtFields.refusal(field("owner.long_name"), "x".repeat(39), c))
        assertNotNull(MtFields.refusal(field("owner.long_name"), "x".repeat(40), c))
        assertNotNull(MtFields.refusal(field("owner.long_name"), "   ", c))
        assertNotNull(MtFields.refusal(field("owner.short_name"), "ABCDE", c))
        assertNull(MtFields.refusal(field("owner.short_name"), "ABCD", c))
        // four letters of two bytes each is eight bytes
        assertNotNull(MtFields.refusal(field("owner.short_name"), "ééé", c))
    }

    @Test
    fun the_power_cap_follows_the_region() {
        val tx = field("lora.tx_power")
        assertEquals(30, MtFields.powerCap(full()))
        assertNull(MtFields.refusal(tx, 30, full()))
        assertNotNull(MtFields.refusal(tx, 31, full()))
        val eu433 = full(Config.LoRaConfig.RegionCode.EU_433)
        assertEquals(10, MtFields.powerCap(eu433))
        assertNull(MtFields.refusal(tx, 10, eu433))
        assertTrue(MtFields.refusal(tx, 11, eu433)!!.contains("10 dBm"))
        assertNull(MtFields.refusal(tx, 0, eu433)) // 0 means the region's own maximum
        assertEquals(36, MtFields.powerCap(full(Config.LoRaConfig.RegionCode.NZ_865)))
        assertEquals(13, MtFields.powerCap(full(Config.LoRaConfig.RegionCode.JP)))
        assertEquals(10, MtFields.powerCap(full(Config.LoRaConfig.RegionCode.LORA_24)))
        // no region chosen yet: the radio holds it to 30 and so do we
        assertEquals(30, MtFields.powerCap(full(Config.LoRaConfig.RegionCode.UNSET)))
        assertNull(MtRegions.powerLimit(999))
    }

    @Test
    fun the_region_table_matches_the_firmwares_for_a_sample() {
        val expected = mapOf(
            Config.LoRaConfig.RegionCode.US to 30, Config.LoRaConfig.RegionCode.EU_868 to 27,
            Config.LoRaConfig.RegionCode.CN to 19, Config.LoRaConfig.RegionCode.KR to 23,
            Config.LoRaConfig.RegionCode.TW to 27, Config.LoRaConfig.RegionCode.PH_915 to 24,
            Config.LoRaConfig.RegionCode.UA_868 to 14, Config.LoRaConfig.RegionCode.ANZ_433 to 14,
        )
        expected.forEach { (r, w) -> assertEquals(r.name, w, MtRegions.powerLimit(r.value)) }
    }

    @Test
    fun a_change_sends_the_whole_section_with_only_that_field_different() {
        val c = full()
        val r = MtFields.apply(field("lora.hop_limit"), 6, c) as MtFields.Applied.Change
        assertEquals(c.lora!!.copy(hop_limit = 6), r.admin.set_config!!.lora)
        assertEquals(20, r.admin.set_config!!.lora!!.tx_power)
        assertEquals(listOf(7, 8), r.admin.set_config!!.lora!!.ignore_incoming)
        assertNull(r.admin.set_config!!.device)
        assertEquals(c.copy(lora = c.lora!!.copy(hop_limit = 6)), r.configs)
    }

    @Test
    fun a_module_change_goes_as_module_config_with_the_pins_and_secrets_it_already_had() {
        val c = full()
        val r = MtFields.apply(field("mqtt.enabled"), true, c) as MtFields.Applied.Change
        val sent = r.admin.set_module_config!!.mqtt!!
        assertTrue(sent.enabled)
        assertEquals("mqtt.example", sent.address)
        assertEquals("p", sent.password)
        assertNull(r.admin.set_config)
        val s = MtFields.apply(field("serial.baud"), 11, c) as MtFields.Applied.Change
        assertEquals(3, s.admin.set_module_config!!.serial!!.rxd)
        assertEquals(ModuleConfig.SerialConfig.Serial_Baud.BAUD_115200, s.admin.set_module_config!!.serial!!.baud)
    }

    @Test
    fun the_preset_turns_presets_on_and_keeps_the_rest() {
        val c = full().let { it.copy(lora = it.lora!!.copy(use_preset = false)) }
        val r = MtFields.apply(field("lora.modem_preset"), 4, c) as MtFields.Applied.Change
        val sent = r.admin.set_config!!.lora!!
        assertEquals(Config.LoRaConfig.ModemPreset.MEDIUM_FAST, sent.modem_preset)
        assertTrue(sent.use_preset)
        assertEquals(9, sent.channel_num)
        assertEquals(20, sent.tx_power)
    }

    @Test
    fun the_owner_goes_as_set_owner_keeping_the_licence_flag_and_id() {
        val r = MtFields.apply(field("owner.long_name"), "Wander", full()) as MtFields.Applied.Change
        val sent = r.admin.set_owner!!
        assertEquals("Wander", sent.long_name)
        assertEquals("MI", sent.short_name)
        assertTrue(sent.is_licensed)
        assertEquals("!00000011", sent.id)
        assertNull(r.admin.set_config)
    }

    @Test
    fun bluetooth_pairing_changes_keep_the_other_bluetooth_settings() {
        val r = MtFields.apply(field("bluetooth.mode"), 1, full()) as MtFields.Applied.Change
        val sent = r.admin.set_config!!.bluetooth!!
        assertEquals(Config.BluetoothConfig.PairingMode.FIXED_PIN, sent.mode)
        assertEquals(123456, sent.fixed_pin)
        assertTrue(sent.enabled)
    }

    @Test
    fun the_wifi_switch_leaves_the_network_and_password_alone() {
        val r = MtFields.apply(field("network.wifi_enabled"), true, full()) as MtFields.Applied.Change
        val sent = r.admin.set_config!!.network!!
        assertTrue(sent.wifi_enabled)
        assertEquals("home", sent.wifi_ssid)
        assertEquals("secret", sent.wifi_psk)
        assertTrue(MtFields.all.none { it.id.contains("psk") || it.id.contains("ssid") || it.id.contains("password") })
        assertTrue(MtFields.all.none { it.id.contains("private_key") || it.id.contains("admin_key") || it.id.contains("security") })
    }

    @Test
    fn the_dangerous_ones_carry_a_warning() {
        val roles = (field("device.role").kind as Kind.Choice).options
        assertNotNull(roles.first { it.label == "Router" }.warn)
        assertNotNull(roles.first { it.label == "Router Late" }.warn)
        assertNull(roles.first { it.label == "Client" }.warn)
        assertNotNull(field("lora.override_duty_cycle").warn)
        assertEquals(false, field("lora.override_duty_cycle").read(full()))
        val pairing = (field("bluetooth.mode").kind as Kind.Choice).options
        assertNotNull(pairing.first { it.label == "No PIN" }.warn)
        assertNotNull(field("mqtt.enabled").warn)
    }

    @Test
    fun values_are_shown_in_words() {
        val c = full()
        assertEquals("Long Fast", MtFields.display(field("lora.modem_preset"), c))
        assertEquals("4 hops", MtFields.display(field("lora.hop_limit"), c))
        assertEquals("20 dBm", MtFields.display(field("lora.tx_power"), c))
        assertEquals("Region maximum", MtFields.display(field("lora.tx_power"), c.copy(lora = c.lora!!.copy(tx_power = 0))))
        assertEquals("On", MtFields.display(field("lora.tx_enabled"), c))
        assertEquals("Client", MtFields.display(field("device.role"), c))
        assertEquals("Mine", MtFields.display(field("owner.long_name"), c))
        assertEquals("Not set", MtFields.display(field("detection_sensor.name"), c))
        assertEquals("mqtt.example", MtFields.display(field("mqtt.address"), c))
        assertNull(MtFields.display(field("lora.hop_limit"), MtConfigs()))
    }

    @Test
    fun shown_only_rows_are_never_changed() {
        val info = MtFields.all.filter { !MtFields.editable(it) }
        assertTrue(info.isNotEmpty())
        info.forEach { assertNotNull(MtFields.refusal(it, "x", full())) }
        // the MQTT server, user name and password cannot be typed here
        assertTrue(MtFields.all.none { it.part == Part.MQTT && it.kind is Kind.Text })
    }

    // ---- through the session ----

    private class Radio : MtTransport {
        val written = mutableListOf<ToRadio>()
        val waiting = ArrayDeque<ByteArray>()
        lateinit var session: MtSession
        var sections = true
        override fun write(toRadio: ByteArray) {
            val t = ToRadio.ADAPTER.decode(toRadio)
            written += t
            if (t.want_config_id != null) {
                val c = full()
                val frames = mutableListOf(
                    FromRadio(my_info = MyNodeInfo(my_node_num = 0x11)),
                    FromRadio(node_info = NodeInfo(num = 0x11, user = c.owner)),
                    FromRadio(metadata = DeviceMetadata(firmware_version = "2.7.26.x")),
                    FromRadio(channel = Channel(index = 0, role = Channel.Role.PRIMARY)),
                )
                if (sections) {
                    frames += FromRadio(config = Config(lora = c.lora))
                    frames += FromRadio(config = Config(device = c.device))
                    frames += FromRadio(config = Config(position = c.position))
                    frames += FromRadio(config = Config(bluetooth = c.bluetooth))
                    frames += FromRadio(moduleConfig = ModuleConfig(telemetry = c.telemetry))
                    frames += FromRadio(moduleConfig = ModuleConfig(mqtt = c.mqtt))
                }
                frames += FromRadio(config_complete_id = 0x4D4D)
                frames.forEach { waiting += it.encode() }
            }
        }
        override fun read(delayMs: Long) = session.onRead(waiting.removeFirstOrNull() ?: ByteArray(0))
    }

    private class Heard : MtSession.Listener {
        var configs: MtConfigs? = null
        override fun onSettings(configs: MtConfigs) { this.configs = configs }
    }

    private fun session(sections: Boolean = true): Triple<Radio, Heard, MtSession> {
        val radio = Radio().also { it.sections = sections }
        val heard = Heard()
        val s = MtSession(radio, heard)
        radio.session = s
        return Triple(radio, heard, s)
    }

    @Test
    fun the_handshake_fills_in_the_sections_and_the_owner() {
        val (_, heard, s) = session()
        s.start()
        val c = heard.configs!!
        assertEquals(full().lora, c.lora)
        assertEquals(full().device, c.device)
        assertEquals(full().bluetooth, c.bluetooth)
        assertEquals(full().telemetry, c.telemetry)
        assertEquals(full().mqtt, c.mqtt)
        assertEquals("Mine", c.owner!!.long_name)
        assertNull(c.power)
        assertTrue(s.configs.has(Part.LORA))
        assertFalse(s.configs.has(Part.POWER))
    }

    @Test
    fun changing_a_setting_writes_the_section_to_our_own_node() {
        val (radio, heard, s) = session()
        s.start()
        radio.written.clear()
        val r = s.change(field("lora.hop_limit"), 5)
        assertTrue(r is MtFields.Applied.Change)
        val p = radio.written.single().packet!!
        assertEquals(0x11, p.to)
        assertTrue(p.want_ack)
        assertEquals(PortNum.ADMIN_APP, p.decoded!!.portnum)
        val sent = AdminMessage.ADAPTER.decode(p.decoded!!.payload).set_config!!.lora!!
        assertEquals(full().lora!!.copy(hop_limit = 5), sent)
        assertEquals(5, heard.configs!!.lora!!.hop_limit)
    }

    @Test
    fun nothing_is_sent_when_the_section_has_not_been_read_or_the_value_is_refused() {
        val (radio, _, s) = session()
        s.start()
        radio.written.clear()
        assertEquals(MtFields.Applied.NotRead, s.change(field("power.sds_secs"), 100))
        assertTrue(s.change(field("lora.hop_limit"), 9) is MtFields.Applied.Refused)
        assertTrue(s.change(field("lora.tx_power"), 31) is MtFields.Applied.Refused)
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun nothing_is_sent_before_the_handshake_is_done() {
        val (radio, _, s) = session()
        assertEquals(MtFields.Applied.NotRead, s.change(field("lora.hop_limit"), 5))
        assertFalse(s.setFixedPosition(35.0, -82.0))
        assertTrue(radio.written.isEmpty())
    }

    @Test
    fun a_fixed_position_is_set_in_tenths_of_microdegrees_and_can_be_cleared() {
        val (radio, heard, s) = session()
        s.start()
        radio.written.clear()
        assertTrue(s.setFixedPosition(35.5951, -82.5515))
        val sent = AdminMessage.ADAPTER.decode(radio.written.single().packet!!.decoded!!.payload).set_fixed_position!!
        assertEquals(355951000, sent.latitude_i)
        assertEquals(-825515000, sent.longitude_i)
        assertTrue(heard.configs!!.position!!.fixed_position)
        radio.written.clear()
        assertTrue(s.clearFixedPosition())
        assertEquals(true, AdminMessage.ADAPTER.decode(radio.written.single().packet!!.decoded!!.payload).remove_fixed_position)
        assertFalse(heard.configs!!.position!!.fixed_position)
    }
}
