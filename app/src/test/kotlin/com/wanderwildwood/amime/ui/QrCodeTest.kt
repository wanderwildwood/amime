package com.wanderwildwood.amime.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.wanderwildwood.amime.meshtastic.ChannelKeys
import com.wanderwildwood.amime.meshtastic.ChannelUrl
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

class QrCodeTest {
    /** Draw the modules the way the screen does (a quiet zone of four, whole pixels) and read them back. */
    private fun scan(modules: List<BooleanArray>, cell: Int = 4): String {
        val n = modules.size + 8
        val side = n * cell
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in modules.indices) for (x in modules[y].indices) if (modules[y][x]) {
            for (dy in 0 until cell) for (dx in 0 until cell) {
                pixels[((y + 4) * cell + dy) * side + (x + 4) * cell + dx] = 0xFF000000.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun the_default_channel_link_comes_back_from_its_code() {
        val url = "https://meshtastic.org/e/#CgMSAQESBggBQANIAQ"
        assertEquals(url, scan(qrModules(url)!!))
    }

    @Test
    fun a_link_with_three_own_keys_fits_and_reads_back_as_the_same_channels() {
        val set = ChannelSet(
            settings = listOf(
                ChannelSettings(psk = ChannelKeys.DEFAULT),
                ChannelSettings(name = "ops", psk = ByteArray(32) { (it * 7).toByte() }.toByteString()),
                ChannelSettings(name = "hike", psk = ByteArray(16) { (it * 3).toByte() }.toByteString(), uplink_enabled = true),
            ),
            lora_config = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US, use_preset = true, hop_limit = 3),
        )
        val url = ChannelUrl.encode(set)
        val modules = qrModules(url)
        assertNotNull(modules)
        assertTrue(modules!!.size <= 97) // version 18 or smaller: still readable at arm's length
        assertEquals(set, ChannelUrl.parse(scan(modules)))
    }

    @Test
    fun the_code_is_square_and_text_too_long_for_any_code_gives_none() {
        val m = qrModules("hello")!!
        assertTrue(m.all { it.size == m.size })
        assertNull(qrModules("x".repeat(5000)))
    }
}
