package com.wanderwildwood.amime.meshtastic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which settings restart the radio, as the firmware's handleSetConfig and handleSetModuleConfig
 * (2.7.26) have it: the table must say so field by field, not once for the section.
 */
class MtRestartTest {
    private fun of(id: String) = MtFields.get(id)!!.restart

    @Test
    fun lora_fields_the_firmware_does_not_compare_apply_at_once() {
        listOf("hop_limit", "tx_enabled", "override_duty_cycle", "ignore_mqtt", "config_ok_to_mqtt")
            .forEach { assertEquals("lora.$it", Restart.NO, of("lora.$it")) }
    }

    @Test
    fun lora_modem_fields_may_restart_depending_on_the_firmware() {
        listOf("modem_preset", "tx_power", "channel_num", "sx126x_rx_boosted_gain")
            .forEach { assertEquals("lora.$it", Restart.MAYBE, of("lora.$it")) }
    }

    @Test
    fun device_restarts_only_for_role_and_rebroadcast() {
        assertEquals(Restart.YES, of("device.role"))
        assertEquals(Restart.YES, of("device.rebroadcast_mode"))
        listOf("node_info_broadcast_secs", "double_tap_as_button_press", "disable_triple_click", "led_heartbeat_disabled", "tzdef")
            .forEach { assertEquals("device.$it", Restart.NO, of("device.$it")) }
    }

    @Test
    fun display_restarts_only_for_the_four_compared_fields() {
        listOf("screen_on_secs", "flip_screen", "displaymode", "oled")
            .forEach { assertEquals("display.$it", Restart.YES, of("display.$it")) }
        listOf("auto_screen_carousel_secs", "units", "heading_bold", "wake_on_tap_or_motion", "compass_orientation", "use_12h_clock")
            .forEach { assertEquals("display.$it", Restart.NO, of("display.$it")) }
    }

    @Test
    fun position_bluetooth_power_network_names_and_modules_restart() {
        val always = setOf(Part.POSITION, Part.BLUETOOTH, Part.POWER, Part.NETWORK, Part.OWNER, Part.MQTT, Part.TELEMETRY,
            Part.STORE_FORWARD, Part.SERIAL, Part.EXT_NOTIFICATION, Part.RANGE_TEST, Part.CANNED_MESSAGE,
            Part.NEIGHBOR_INFO, Part.DETECTION_SENSOR, Part.PAXCOUNTER)
        MtFields.all.filter { it.part in always && it.kind !is Kind.Info }
            .forEach { assertEquals(it.id, Restart.YES, it.restart) }
    }

    @Test
    fun rows_that_only_show_a_value_never_restart_anything() {
        val info = MtFields.all.filter { it.kind is Kind.Info }
        assertTrue(info.isNotEmpty())
        info.forEach { assertEquals(it.id, Restart.NO, it.restart) }
    }
}
