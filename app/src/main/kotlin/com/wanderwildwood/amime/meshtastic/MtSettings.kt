package com.wanderwildwood.amime.meshtastic

import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User

/**
 * Everything the radio's settings screens read and write, as the radio last reported it.
 *
 * Each part is a whole protocol section. A setting is changed by sending its whole section
 * back with the one field changed (the radio replaces a section it is given, so a partial
 * one would zero the rest), which is why the sections are kept whole here and never
 * flattened. A part that is null has not been read from the radio yet.
 */
data class MtConfigs(
    val lora: Config.LoRaConfig? = null,
    val device: Config.DeviceConfig? = null,
    val position: Config.PositionConfig? = null,
    val power: Config.PowerConfig? = null,
    val network: Config.NetworkConfig? = null,
    val display: Config.DisplayConfig? = null,
    val bluetooth: Config.BluetoothConfig? = null,
    val mqtt: ModuleConfig.MQTTConfig? = null,
    val serial: ModuleConfig.SerialConfig? = null,
    val external_notification: ModuleConfig.ExternalNotificationConfig? = null,
    val store_forward: ModuleConfig.StoreForwardConfig? = null,
    val range_test: ModuleConfig.RangeTestConfig? = null,
    val telemetry: ModuleConfig.TelemetryConfig? = null,
    val canned_message: ModuleConfig.CannedMessageConfig? = null,
    val neighbor_info: ModuleConfig.NeighborInfoConfig? = null,
    val detection_sensor: ModuleConfig.DetectionSensorConfig? = null,
    val paxcounter: ModuleConfig.PaxcounterConfig? = null,
    /** This radio's own user record: the names, and whether it is a licensed (ham) node. */
    val owner: User? = null,
) {
    /** Fold in one Config frame from the radio. */
    fun with(c: Config): MtConfigs = copy(
        lora = c.lora ?: lora,
        device = c.device ?: device,
        position = c.position ?: position,
        power = c.power ?: power,
        network = c.network ?: network,
        display = c.display ?: display,
        bluetooth = c.bluetooth ?: bluetooth,
    )

    /** Fold in one ModuleConfig frame from the radio. */
    fun with(m: ModuleConfig): MtConfigs = copy(
        mqtt = m.mqtt ?: mqtt,
        serial = m.serial ?: serial,
        external_notification = m.external_notification ?: external_notification,
        store_forward = m.store_forward ?: store_forward,
        range_test = m.range_test ?: range_test,
        telemetry = m.telemetry ?: telemetry,
        canned_message = m.canned_message ?: canned_message,
        neighbor_info = m.neighbor_info ?: neighbor_info,
        detection_sensor = m.detection_sensor ?: detection_sensor,
        paxcounter = m.paxcounter ?: paxcounter,
    )

    /** The message that sends the whole of [part] to the radio, or null when it has not been read. */
    fun toAdmin(part: Part): AdminMessage? = when (part) {
        Part.OWNER -> owner?.let { AdminMessage(set_owner = it) }
        Part.LORA -> lora?.let { AdminMessage(set_config = Config(lora = it)) }
        Part.DEVICE -> device?.let { AdminMessage(set_config = Config(device = it)) }
        Part.POSITION -> position?.let { AdminMessage(set_config = Config(position = it)) }
        Part.POWER -> power?.let { AdminMessage(set_config = Config(power = it)) }
        Part.NETWORK -> network?.let { AdminMessage(set_config = Config(network = it)) }
        Part.DISPLAY -> display?.let { AdminMessage(set_config = Config(display = it)) }
        Part.BLUETOOTH -> bluetooth?.let { AdminMessage(set_config = Config(bluetooth = it)) }
        Part.MQTT -> mqtt?.let { AdminMessage(set_module_config = ModuleConfig(mqtt = it)) }
        Part.SERIAL -> serial?.let { AdminMessage(set_module_config = ModuleConfig(serial = it)) }
        Part.EXT_NOTIFICATION -> external_notification?.let { AdminMessage(set_module_config = ModuleConfig(external_notification = it)) }
        Part.STORE_FORWARD -> store_forward?.let { AdminMessage(set_module_config = ModuleConfig(store_forward = it)) }
        Part.RANGE_TEST -> range_test?.let { AdminMessage(set_module_config = ModuleConfig(range_test = it)) }
        Part.TELEMETRY -> telemetry?.let { AdminMessage(set_module_config = ModuleConfig(telemetry = it)) }
        Part.CANNED_MESSAGE -> canned_message?.let { AdminMessage(set_module_config = ModuleConfig(canned_message = it)) }
        Part.NEIGHBOR_INFO -> neighbor_info?.let { AdminMessage(set_module_config = ModuleConfig(neighbor_info = it)) }
        Part.DETECTION_SENSOR -> detection_sensor?.let { AdminMessage(set_module_config = ModuleConfig(detection_sensor = it)) }
        Part.PAXCOUNTER -> paxcounter?.let { AdminMessage(set_module_config = ModuleConfig(paxcounter = it)) }
    }

    /** This config with the one section [part] taken from [from]. */
    fun takePart(part: Part, from: MtConfigs): MtConfigs = when (part) {
        Part.OWNER -> copy(owner = from.owner)
        Part.LORA -> copy(lora = from.lora)
        Part.DEVICE -> copy(device = from.device)
        Part.POSITION -> copy(position = from.position)
        Part.POWER -> copy(power = from.power)
        Part.NETWORK -> copy(network = from.network)
        Part.DISPLAY -> copy(display = from.display)
        Part.BLUETOOTH -> copy(bluetooth = from.bluetooth)
        Part.MQTT -> copy(mqtt = from.mqtt)
        Part.SERIAL -> copy(serial = from.serial)
        Part.EXT_NOTIFICATION -> copy(external_notification = from.external_notification)
        Part.STORE_FORWARD -> copy(store_forward = from.store_forward)
        Part.RANGE_TEST -> copy(range_test = from.range_test)
        Part.TELEMETRY -> copy(telemetry = from.telemetry)
        Part.CANNED_MESSAGE -> copy(canned_message = from.canned_message)
        Part.NEIGHBOR_INFO -> copy(neighbor_info = from.neighbor_info)
        Part.DETECTION_SENSOR -> copy(detection_sensor = from.detection_sensor)
        Part.PAXCOUNTER -> copy(paxcounter = from.paxcounter)
    }

    /** Whether [part] has been read. */
    fun has(part: Part): Boolean = toAdmin(part) != null
}

/** A section of settings, in the order the screen lists them. */
enum class Part(val title: String, val module: Boolean) {
    LORA("Radio (LoRa)", false),
    OWNER("Names", false),
    DEVICE("Device", false),
    POSITION("Position and GPS", false),
    BLUETOOTH("Bluetooth", false),
    POWER("Power", false),
    DISPLAY("Screen", false),
    NETWORK("Wi-Fi", false),
    TELEMETRY("Telemetry", true),
    STORE_FORWARD("Store and forward", true),
    SERIAL("Serial", true),
    EXT_NOTIFICATION("Alerts (LED, buzzer, vibration)", true),
    RANGE_TEST("Range test", true),
    CANNED_MESSAGE("Canned messages", true),
    NEIGHBOR_INFO("Neighbour info", true),
    DETECTION_SENSOR("Detection sensor", true),
    PAXCOUNTER("People counter", true),
    MQTT("MQTT", true),
}

/** One choice in a list of choices. */
data class Opt(val value: Int, val label: String, val note: String? = null, val warn: String? = null)

/** What kind of value a field holds, and so what its dialog asks. */
sealed interface Kind {
    /** One of a list. The value is the proto enum's number. */
    data class Choice(val options: List<Opt>) : Kind

    /** A whole number between [min] and [max]; [zero] says what 0 means when it means something else. */
    data class Num(val min: Int, val max: Int, val unit: String = "", val zero: String? = null) : Kind

    /** On or off. */
    data object Flag : Kind

    /** Words, at most [maxBytes] bytes of UTF-8. */
    data class Text(val maxBytes: Int) : Kind

    /** Shown, never changed here. */
    data object Info : Kind
}

/**
 * One setting. The table [MtFields] is the only place a setting is described: what it is
 * called, where it lives in the protocol, what it may be set to, what to warn about. The
 * screens and the tests are written over the table, so a new setting is a new row.
 */
class Field(
    val id: String,
    val part: Part,
    val title: String,
    val kind: Kind,
    /** One plain line of what it does. */
    val help: String?,
    /** Said in the dialog before the change is made, for the ones that can hurt. */
    val warn: String?,
    /** Whether the radio restarts after this change; see [Restart] and [MtRestarts]. */
    val restart: Restart,
    /** Reads the value from the configs: Int for a choice or number, Boolean, String. Null when not read. */
    val read: (MtConfigs) -> Any?,
    /** Returns the configs with the value changed, or null when the section has not been read. */
    val write: (MtConfigs, Any) -> MtConfigs?,
    /** Anything that makes this value wrong for this radio as it now is; a sentence, or null. */
    val guard: ((MtConfigs, Any) -> String?)? = null,
)

/** The most each region's law allows, in dBm, as the firmware's own region table has it. */
object MtRegions {
    private val limits = mapOf(
        "UNSET" to 30, "US" to 30, "EU_433" to 10, "EU_868" to 27, "CN" to 19, "JP" to 13, "ANZ" to 30,
        "KR" to 23, "TW" to 27, "RU" to 20, "IN" to 30, "NZ_865" to 36, "TH" to 27, "LORA_24" to 10,
        "UA_433" to 10, "UA_868" to 14, "MY_433" to 20, "MY_919" to 27, "SG_923" to 20, "PH_433" to 10,
        "PH_868" to 14, "PH_915" to 24, "ANZ_433" to 14, "KZ_433" to 10, "KZ_863" to 30, "NP_865" to 30,
        "BR_902" to 30, "ITU1_2M" to 30, "ITU2_2M" to 30, "ITU3_2M" to 30, "ITU1_70CM" to 30,
        "ITU2_70CM" to 30, "ITU3_70CM" to 30, "ITU2_125CM" to 30, "EU_866" to 27, "EU_N_868" to 27,
    )

    /** The legal limit for [region] in dBm, or null for a region this table does not know. */
    fun powerLimit(region: Int): Int? = Config.LoRaConfig.RegionCode.fromValue(region)?.let { limits[it.name] }

    /** The hardest limit any region has, which is as high as a setting is ever accepted. */
    const val ABSOLUTE_MAX = 36
}

/**
 * Whether the radio restarts after a settings write. The firmware decides, field by field:
 * in AdminModule.cpp, handleSetConfig sets a reboot only when certain fields of the section
 * written differ from what the radio holds, and handleSetModuleConfig reboots for every
 * module but one. [MAYBE] is for what a firmware version has changed: the LoRa modem
 * fields restart the radio in 2.7 and apply live in newer builds.
 */
enum class Restart { NO, MAYBE, YES }

/** The per-field answers that differ from their section's. The table is the firmware's, read for 2.7.26. */
object MtRestarts {
    private val exceptions: Map<String, Restart> = mapOf(
        // LoRa: only a change to the modem, region, power or slot restarts the radio (2.7.26);
        // the rest of the section is not compared and applies at once.
        "lora.hop_limit" to Restart.NO,
        "lora.tx_enabled" to Restart.NO,
        "lora.override_duty_cycle" to Restart.NO,
        "lora.ignore_mqtt" to Restart.NO,
        "lora.config_ok_to_mqtt" to Restart.NO,
        // Device: only the role, the rebroadcast mode and the two pins restart it.
        "device.role" to Restart.YES,
        "device.rebroadcast_mode" to Restart.YES,
        // Display: the screen time-out, flip, chip and style restart it; the rest does not.
        "display.screen_on_secs" to Restart.YES,
        "display.flip_screen" to Restart.YES,
        "display.displaymode" to Restart.YES,
        "display.oled" to Restart.YES,
    )

    /** Sections whose fields do not restart the radio unless listed above. */
    private val sectionDefault: Map<String, Restart> = mapOf(
        "lora" to Restart.MAYBE,
        "device" to Restart.NO,
        "display" to Restart.NO,
    )

    fun of(id: String, fallback: Restart): Restart =
        exceptions[id] ?: sectionDefault[id.substringBefore('.')] ?: fallback
}

private class Sect<S>(
    val part: Part,
    val get: (MtConfigs) -> S?,
    val put: (MtConfigs, S) -> MtConfigs,
    val restart: Restart = Restart.YES,
) {
    private fun <V : Any> field(
        id: String, title: String, kind: Kind, help: String?, warn: String?, restart: Restart,
        rd: (S) -> V, wr: (S, V) -> S, guard: ((MtConfigs, Any) -> String?)?,
    ) = Field(
        id = "${part.name.lowercase()}.$id", part = part, title = title, kind = kind, help = help, warn = warn,
        restart = MtRestarts.of("${part.name.lowercase()}.$id", restart),
        read = { c -> get(c)?.let { rd(it) } },
        write = { c, v ->
            val s = get(c)
            @Suppress("UNCHECKED_CAST")
            if (s == null) null else put(c, wr(s, v as V))
        },
        guard = guard,
    )

    fun flag(
        id: String, title: String, help: String? = null, warn: String? = null,
        guard: ((MtConfigs, Any) -> String?)? = null, rd: (S) -> Boolean, wr: (S, Boolean) -> S,
    ) = field(id, title, Kind.Flag, help, warn, restart, rd, wr, guard)

    fun num(
        id: String, title: String, min: Int, max: Int, unit: String = "", zero: String? = null,
        help: String? = null, warn: String? = null, guard: ((MtConfigs, Any) -> String?)? = null,
        rd: (S) -> Int, wr: (S, Int) -> S,
    ) = field(id, title, Kind.Num(min, max, unit, zero), help, warn, restart, rd, wr, guard)

    fun choice(
        id: String, title: String, options: List<Opt>, help: String? = null, warn: String? = null,
        guard: ((MtConfigs, Any) -> String?)? = null, rd: (S) -> Int, wr: (S, Int) -> S,
    ) = field(id, title, Kind.Choice(options), help, warn, restart, rd, wr, guard)

    fun text(
        id: String, title: String, maxBytes: Int, help: String? = null,
        rd: (S) -> String, wr: (S, String) -> S,
    ) = field(id, title, Kind.Text(maxBytes), help, null, restart, rd, wr, null)

    fun info(id: String, title: String, rd: (S) -> String) =
        field(id, title, Kind.Info, null, null, Restart.NO, rd, { s, _: String -> s }, null)
}

private fun opts(vararg o: Pair<Int, String>) = o.map { Opt(it.first, it.second) }

private const val ROUTER_WARN =
    "A router repeats everything it hears, all the time. It belongs on a tower or a mountain top with nobody carrying it. " +
        "On a handheld or a roof it makes the mesh worse for everyone nearby."

/** Every setting this app can change or show, grouped by [Part]. */
object MtFields {
    private val lora = Sect<Config.LoRaConfig>(Part.LORA, { it.lora }, { c, s -> c.copy(lora = s) })
    private val owner = Sect<User>(Part.OWNER, { it.owner }, { c, s -> c.copy(owner = s) })
    private val device = Sect<Config.DeviceConfig>(Part.DEVICE, { it.device }, { c, s -> c.copy(device = s) })
    private val position = Sect<Config.PositionConfig>(Part.POSITION, { it.position }, { c, s -> c.copy(position = s) })
    private val bluetooth = Sect<Config.BluetoothConfig>(Part.BLUETOOTH, { it.bluetooth }, { c, s -> c.copy(bluetooth = s) })
    private val power = Sect<Config.PowerConfig>(Part.POWER, { it.power }, { c, s -> c.copy(power = s) })
    private val display = Sect<Config.DisplayConfig>(Part.DISPLAY, { it.display }, { c, s -> c.copy(display = s) })
    private val network = Sect<Config.NetworkConfig>(Part.NETWORK, { it.network }, { c, s -> c.copy(network = s) })
    private val mqtt = Sect<ModuleConfig.MQTTConfig>(Part.MQTT, { it.mqtt }, { c, s -> c.copy(mqtt = s) })
    private val telemetry = Sect<ModuleConfig.TelemetryConfig>(Part.TELEMETRY, { it.telemetry }, { c, s -> c.copy(telemetry = s) })
    private val storeForward = Sect<ModuleConfig.StoreForwardConfig>(Part.STORE_FORWARD, { it.store_forward }, { c, s -> c.copy(store_forward = s) })
    private val serial = Sect<ModuleConfig.SerialConfig>(Part.SERIAL, { it.serial }, { c, s -> c.copy(serial = s) })
    private val ext = Sect<ModuleConfig.ExternalNotificationConfig>(Part.EXT_NOTIFICATION, { it.external_notification }, { c, s -> c.copy(external_notification = s) })
    private val rangeTest = Sect<ModuleConfig.RangeTestConfig>(Part.RANGE_TEST, { it.range_test }, { c, s -> c.copy(range_test = s) })
    private val canned = Sect<ModuleConfig.CannedMessageConfig>(Part.CANNED_MESSAGE, { it.canned_message }, { c, s -> c.copy(canned_message = s) })
    private val neighbor = Sect<ModuleConfig.NeighborInfoConfig>(Part.NEIGHBOR_INFO, { it.neighbor_info }, { c, s -> c.copy(neighbor_info = s) })
    private val detection = Sect<ModuleConfig.DetectionSensorConfig>(Part.DETECTION_SENSOR, { it.detection_sensor }, { c, s -> c.copy(detection_sensor = s) })
    private val pax = Sect<ModuleConfig.PaxcounterConfig>(Part.PAXCOUNTER, { it.paxcounter }, { c, s -> c.copy(paxcounter = s) })

    /** A power over the region's limit is refused; the radio would quietly cut it back anyway. */
    private val powerGuard: (MtConfigs, Any) -> String? = { c, v ->
        val region = c.lora?.region?.value ?: 0
        val cap = MtRegions.powerLimit(region)
        if (cap != null && (v as Int) > cap) "The most this region allows is $cap dBm." else null
    }

    /** What the radio will use for 0: the region's limit. Null when the region is not in the table. */
    fun powerCap(c: MtConfigs): Int? = MtRegions.powerLimit(c.lora?.region?.value ?: 0)

    private val presets = listOf(
        Opt(0, "Long Fast", "The usual default. Long reach, quick enough for messages."),
        Opt(7, "Long Moderate", "A little longer reach than Long Fast, a little slower."),
        Opt(1, "Long Slow", "Older. Longer reach, much slower; few meshes use it now."),
        Opt(4, "Medium Fast", "Shorter reach, faster."),
        Opt(3, "Medium Slow", "Shorter reach than Long, slower than Medium Fast."),
        Opt(6, "Short Fast", "Short reach, fast. For a small area."),
        Opt(5, "Short Slow", "Short reach, a little more sensitive than Short Fast."),
        Opt(8, "Short Turbo", "Shortest reach, fastest. Needs a wide channel that some regions do not allow."),
        Opt(9, "Long Turbo", "Long reach on a wide channel that some regions do not allow."),
    )

    val roles = listOf(
        Opt(0, "Client", "A phone-connected or stand-alone messaging radio. The usual choice."),
        Opt(1, "Client Mute", "Messages only; does not pass on other people's packets."),
        Opt(8, "Client Hidden", "Speaks only when it has to."),
        Opt(12, "Client Base", "A rooftop radio that passes on for nearby Client Mute radios."),
        Opt(2, "Router", "Infrastructure on a tower or mountain top.", ROUTER_WARN),
        Opt(11, "Router Late", "Infrastructure that repeats after all the others.", ROUTER_WARN),
        Opt(5, "Tracker", "Sends its position as priority."),
        Opt(6, "Sensor", "Sends readings as priority."),
        Opt(9, "Lost and Found", "Sends its position to the default channel regularly, to be found."),
        Opt(7, "TAK", "For ATAK."),
        Opt(10, "TAK Tracker", "For ATAK; sends its position."),
    )

    val gpsModes = opts(1 to "Enabled", 0 to "Disabled", 2 to "Not present")
    val bluetoothModes = listOf(
        Opt(0, "Random PIN", "The radio shows a new PIN on its screen to pair."),
        Opt(1, "Fixed PIN", "A PIN you choose, the same each time."),
        Opt(
            2, "No PIN", "Anyone in Bluetooth range can connect.",
            "With no PIN anyone nearby can connect to this radio and change its settings.",
        ),
    )

    private val units = opts(0 to "Metric", 1 to "Imperial")
    private val displayModes = opts(0 to "Default", 1 to "Two colour", 2 to "Inverted", 3 to "Colour")
    private val oleds = opts(0 to "Automatic", 1 to "SSD1306", 2 to "SH1106", 3 to "SH1107", 4 to "SH1107 128x128", 5 to "SH1107 rotated")
    private val orientations = opts(
        0 to "0 degrees", 1 to "90 degrees", 2 to "180 degrees", 3 to "270 degrees",
        4 to "0 degrees, mirrored", 5 to "90 degrees, mirrored", 6 to "180 degrees, mirrored", 7 to "270 degrees, mirrored",
    )
    private val rebroadcast = listOf(
        Opt(0, "All", "Pass on whatever it hears on its own channels or the same radio settings."),
        Opt(2, "Local only", "Pass on only what belongs to its own channels."),
        Opt(3, "Known only", "Local only, and only from radios it already knows."),
        Opt(4, "None", "Pass on nothing. Allowed only for some roles."),
        Opt(5, "Core only", "Pass on only the core kinds of message."),
        Opt(1, "All, skip decoding", "As All, without reading the packets. Repeater roles only."),
    )
    private val baud = opts(
        0 to "Default", 1 to "110", 2 to "300", 3 to "600", 4 to "1200", 5 to "2400", 6 to "4800", 7 to "9600",
        8 to "19200", 9 to "38400", 10 to "57600", 11 to "115200", 12 to "230400", 13 to "460800",
        14 to "576000", 15 to "921600",
    )
    private val serialModes = opts(
        0 to "Default", 1 to "Simple", 2 to "Protobuf", 3 to "Text messages", 4 to "NMEA", 5 to "CalTopo",
        6 to "WS85", 7 to "VE.Direct", 9 to "Log", 10 to "Log text",
    )
    private val triggers = opts(
        0 to "Logic low", 1 to "Logic high", 2 to "Falling edge", 3 to "Rising edge",
        4 to "Either edge, active low", 5 to "Either edge, active high",
    )

    /** Every field, in the order the screens list them. */
    val all: List<Field> = listOf(
        // ---- LoRa ----
        lora.choice(
            "modem_preset", "Modem preset", presets,
            help = "How the radio trades reach for speed. Everyone on the mesh must use the same one.",
            rd = { it.modem_preset.value },
            wr = { s, v -> Config.LoRaConfig.ModemPreset.fromValue(v)?.let { s.copy(modem_preset = it, use_preset = true) } ?: s },
        ),
        lora.num(
            "hop_limit", "Hop limit", 1, 7, unit = "hops",
            help = "How many times a message of yours may be passed on. 3 is usual; more adds load for everyone.",
            rd = { it.hop_limit }, wr = { s, v -> s.copy(hop_limit = v) },
        ),
        lora.flag(
            "tx_enabled", "Transmit", "Off turns the radio into a listener.",
            rd = { it.tx_enabled }, wr = { s, v -> s.copy(tx_enabled = v) },
        ),
        lora.num(
            "tx_power", "Transmit power", 0, MtRegions.ABSOLUTE_MAX, unit = "dBm", zero = "Region maximum",
            help = "0 uses the most the region allows. A radio never goes above its region's limit.",
            guard = powerGuard,
            rd = { it.tx_power }, wr = { s, v -> s.copy(tx_power = v) },
        ),
        lora.num(
            "channel_num", "Frequency slot", 0, 200, zero = "Automatic",
            help = "0 picks the slot from the main channel's name. Everyone on the mesh must use the same slot.",
            rd = { it.channel_num }, wr = { s, v -> s.copy(channel_num = v) },
        ),
        lora.flag(
            "override_duty_cycle", "Ignore the duty-cycle limit",
            help = "Off keeps the radio inside the legal limit on how much of the time it may transmit.",
            warn = "This turns off the legal limit on how much this radio may transmit. Leave it off unless you know your region allows it.",
            rd = { it.override_duty_cycle }, wr = { s, v -> s.copy(override_duty_cycle = v) },
        ),
        lora.flag(
            "sx126x_rx_boosted_gain", "Boosted receive gain", "Hears a little further; uses a little more power.",
            rd = { it.sx126x_rx_boosted_gain }, wr = { s, v -> s.copy(sx126x_rx_boosted_gain = v) },
        ),
        lora.flag(
            "ignore_mqtt", "Ignore messages that came by MQTT", "Hears only what came over the air.",
            rd = { it.ignore_mqtt }, wr = { s, v -> s.copy(ignore_mqtt = v) },
        ),
        lora.flag(
            "config_ok_to_mqtt", "Allow my messages to go to MQTT", "Lets other radios pass your messages to the internet.",
            rd = { it.config_ok_to_mqtt }, wr = { s, v -> s.copy(config_ok_to_mqtt = v) },
        ),

        // ---- Names ----
        owner.text(
            "long_name", "Name", 39, "What others see in their list.",
            rd = { it.long_name }, wr = { s, v -> s.copy(long_name = v) },
        ),
        owner.text(
            "short_name", "Short name", 4, "Up to four letters, for small screens.",
            rd = { it.short_name }, wr = { s, v -> s.copy(short_name = v) },
        ),

        // ---- Device ----
        device.choice(
            "role", "Role", roles,
            help = "What this radio does for the mesh. Client is right for nearly every radio that is carried.",
            rd = { it.role.value },
            wr = { s, v -> Config.DeviceConfig.Role.fromValue(v)?.let { s.copy(role = it) } ?: s },
        ),
        device.choice(
            "rebroadcast_mode", "Pass on", rebroadcast,
            help = "Which packets this radio passes on for others.",
            rd = { it.rebroadcast_mode.value },
            wr = { s, v -> Config.DeviceConfig.RebroadcastMode.fromValue(v)?.let { s.copy(rebroadcast_mode = it) } ?: s },
        ),
        device.num(
            "node_info_broadcast_secs", "Tell the mesh who I am every", 3600, 86400, unit = "s", zero = "Default (3 hours)",
            help = "How often the radio announces its name. The radio will not go below an hour.",
            rd = { it.node_info_broadcast_secs }, wr = { s, v -> s.copy(node_info_broadcast_secs = v) },
        ),
        device.flag(
            "double_tap_as_button_press", "Double tap counts as a button press", null,
            rd = { it.double_tap_as_button_press }, wr = { s, v -> s.copy(double_tap_as_button_press = v) },
        ),
        device.flag(
            "disable_triple_click", "Turn off triple-click", "Triple-click on the button toggles the GPS.",
            rd = { it.disable_triple_click }, wr = { s, v -> s.copy(disable_triple_click = v) },
        ),
        device.flag(
            "led_heartbeat_disabled", "Turn off the blinking light", null,
            rd = { it.led_heartbeat_disabled }, wr = { s, v -> s.copy(led_heartbeat_disabled = v) },
        ),
        device.text(
            "tzdef", "Time zone", 64, "A POSIX zone, for example EST5EDT,M3.2.0,M11.1.0. Empty for none.",
            rd = { it.tzdef }, wr = { s, v -> s.copy(tzdef = v) },
        ),

        // ---- Position ----
        position.choice(
            "gps_mode", "GPS", gpsModes,
            help = "Enabled uses the radio's own GPS. Not present is for a radio without one.",
            rd = { it.gps_mode.value },
            wr = { s, v -> Config.PositionConfig.GpsMode.fromValue(v)?.let { s.copy(gps_mode = it) } ?: s },
        ),
        position.num(
            "position_broadcast_secs", "Share my position every", 0, 86400, unit = "s", zero = "Default (15 minutes)",
            help = "How often the radio sends its position to the mesh, on channels that share it.",
            rd = { it.position_broadcast_secs }, wr = { s, v -> s.copy(position_broadcast_secs = v) },
        ),
        position.flag(
            "position_broadcast_smart_enabled", "Smart position", "Send sooner when moving, less when still.",
            rd = { it.position_broadcast_smart_enabled }, wr = { s, v -> s.copy(position_broadcast_smart_enabled = v) },
        ),
        position.num(
            "broadcast_smart_minimum_distance", "Smart position: after moving", 0, 100000, unit = "m",
            rd = { it.broadcast_smart_minimum_distance }, wr = { s, v -> s.copy(broadcast_smart_minimum_distance = v) },
        ),
        position.num(
            "broadcast_smart_minimum_interval_secs", "Smart position: not more often than", 0, 86400, unit = "s",
            rd = { it.broadcast_smart_minimum_interval_secs }, wr = { s, v -> s.copy(broadcast_smart_minimum_interval_secs = v) },
        ),
        position.num(
            "gps_update_interval", "Look for a GPS fix every", 0, 86400, unit = "s", zero = "Default (2 minutes)",
            rd = { it.gps_update_interval }, wr = { s, v -> s.copy(gps_update_interval = v) },
        ),
        position.info("fixed_position", "Fixed position") { if (it.fixed_position) "On" else "Off" },

        // ---- Bluetooth ----
        bluetooth.choice(
            "mode", "Pairing", bluetoothModes,
            help = "Changing this restarts the radio, so the Bluetooth link drops and Mesh has to connect again.",
            rd = { it.mode.value },
            wr = { s, v -> Config.BluetoothConfig.PairingMode.fromValue(v)?.let { s.copy(mode = it) } ?: s },
        ),
        bluetooth.num(
            "fixed_pin", "Fixed PIN", 100000, 999999, help = "Six digits. Used when pairing is Fixed PIN.",
            rd = { it.fixed_pin }, wr = { s, v -> s.copy(fixed_pin = v) },
        ),

        // ---- Power ----
        power.flag(
            "is_power_saving", "Power saving", "Sleeps between moments of activity. Bluetooth may be slower to connect.",
            rd = { it.is_power_saving }, wr = { s, v -> s.copy(is_power_saving = v) },
        ),
        power.num(
            "on_battery_shutdown_after_secs", "Shut down on battery after", 0, 86400, unit = "s", zero = "Never",
            rd = { it.on_battery_shutdown_after_secs }, wr = { s, v -> s.copy(on_battery_shutdown_after_secs = v) },
        ),
        power.num(
            "wait_bluetooth_secs", "Wait for Bluetooth", 0, 3600, unit = "s", zero = "Default (1 minute)",
            rd = { it.wait_bluetooth_secs }, wr = { s, v -> s.copy(wait_bluetooth_secs = v) },
        ),
        power.num(
            "sds_secs", "Deep sleep for", 0, 604800, unit = "s", zero = "Default",
            help = "How long a radio on power saving sleeps deeply between wake-ups.",
            rd = { it.sds_secs }, wr = { s, v -> s.copy(sds_secs = v) },
        ),
        power.num(
            "ls_secs", "Light sleep for", 0, 86400, unit = "s", zero = "Default",
            rd = { it.ls_secs }, wr = { s, v -> s.copy(ls_secs = v) },
        ),
        power.num(
            "min_wake_secs", "Stay awake at least", 0, 3600, unit = "s", zero = "Default",
            rd = { it.min_wake_secs }, wr = { s, v -> s.copy(min_wake_secs = v) },
        ),

        // ---- Display ----
        display.num(
            "screen_on_secs", "Screen stays on for", 0, 86400, unit = "s", zero = "Default (1 minute)",
            rd = { it.screen_on_secs }, wr = { s, v -> s.copy(screen_on_secs = v) },
        ),
        display.num(
            "auto_screen_carousel_secs", "Turn to the next page every", 0, 3600, unit = "s", zero = "Off",
            rd = { it.auto_screen_carousel_secs }, wr = { s, v -> s.copy(auto_screen_carousel_secs = v) },
        ),
        display.flag(
            "flip_screen", "Flip the screen", "Upside down.",
            rd = { it.flip_screen }, wr = { s, v -> s.copy(flip_screen = v) },
        ),
        display.choice(
            "units", "Units", units, rd = { it.units.value },
            wr = { s, v -> Config.DisplayConfig.DisplayUnits.fromValue(v)?.let { s.copy(units = it) } ?: s },
        ),
        display.choice(
            "displaymode", "Screen style", displayModes, rd = { it.displaymode.value },
            wr = { s, v -> Config.DisplayConfig.DisplayMode.fromValue(v)?.let { s.copy(displaymode = it) } ?: s },
        ),
        display.choice(
            "oled", "Screen chip", oleds, help = "Leave on Automatic unless the screen shows garbage.",
            rd = { it.oled.value },
            wr = { s, v -> Config.DisplayConfig.OledType.fromValue(v)?.let { s.copy(oled = it) } ?: s },
        ),
        display.flag(
            "heading_bold", "Bold heading", null,
            rd = { it.heading_bold }, wr = { s, v -> s.copy(heading_bold = v) },
        ),
        display.flag(
            "wake_on_tap_or_motion", "Wake the screen on tap or motion", null,
            rd = { it.wake_on_tap_or_motion }, wr = { s, v -> s.copy(wake_on_tap_or_motion = v) },
        ),
        display.choice(
            "compass_orientation", "Compass orientation", orientations, rd = { it.compass_orientation.value },
            wr = { s, v -> Config.DisplayConfig.CompassOrientation.fromValue(v)?.let { s.copy(compass_orientation = it) } ?: s },
        ),
        display.flag(
            "use_12h_clock", "12-hour clock", null,
            rd = { it.use_12h_clock }, wr = { s, v -> s.copy(use_12h_clock = v) },
        ),

        // ---- Wi-Fi: on or off only. The network name and password are not entered here. ----
        network.flag(
            "wifi_enabled", "Wi-Fi", "Only switches it. The network and its password are set on a computer.",
            rd = { it.wifi_enabled }, wr = { s, v -> s.copy(wifi_enabled = v) },
        ),

        // ---- Telemetry ----
        telemetry.num(
            "device_update_interval", "Send battery and channel use every", 0, 86400, unit = "s", zero = "Default (30 minutes)",
            rd = { it.device_update_interval }, wr = { s, v -> s.copy(device_update_interval = v) },
        ),
        telemetry.flag(
            "environment_measurement_enabled", "Send sensor readings", "Temperature and the like, if a sensor is fitted.",
            rd = { it.environment_measurement_enabled }, wr = { s, v -> s.copy(environment_measurement_enabled = v) },
        ),
        telemetry.num(
            "environment_update_interval", "Send sensor readings every", 0, 86400, unit = "s", zero = "Default (30 minutes)",
            rd = { it.environment_update_interval }, wr = { s, v -> s.copy(environment_update_interval = v) },
        ),
        telemetry.flag(
            "environment_screen_enabled", "Show sensor readings on the screen", null,
            rd = { it.environment_screen_enabled }, wr = { s, v -> s.copy(environment_screen_enabled = v) },
        ),
        telemetry.flag(
            "environment_display_fahrenheit", "Show Fahrenheit", null,
            rd = { it.environment_display_fahrenheit }, wr = { s, v -> s.copy(environment_display_fahrenheit = v) },
        ),
        telemetry.flag(
            "air_quality_enabled", "Send air-quality readings", null,
            rd = { it.air_quality_enabled }, wr = { s, v -> s.copy(air_quality_enabled = v) },
        ),
        telemetry.num(
            "air_quality_interval", "Send air-quality readings every", 0, 86400, unit = "s", zero = "Default (30 minutes)",
            rd = { it.air_quality_interval }, wr = { s, v -> s.copy(air_quality_interval = v) },
        ),
        telemetry.flag(
            "power_measurement_enabled", "Send power readings", null,
            rd = { it.power_measurement_enabled }, wr = { s, v -> s.copy(power_measurement_enabled = v) },
        ),
        telemetry.num(
            "power_update_interval", "Send power readings every", 0, 86400, unit = "s", zero = "Default (30 minutes)",
            rd = { it.power_update_interval }, wr = { s, v -> s.copy(power_update_interval = v) },
        ),

        // ---- Store and forward ----
        storeForward.flag(
            "enabled", "Store and forward", "Keeps recent messages and hands them to radios that were away.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        storeForward.flag(
            "is_server", "Act as the server", "Holds the messages for others. Needs a radio with extra memory.",
            warn = "A server radio uses memory and airtime to keep everyone's messages. Only turn it on for a radio that stays in one place.",
            rd = { it.is_server }, wr = { s, v -> s.copy(is_server = v) },
        ),
        storeForward.flag(
            "heartbeat", "Announce itself", "The server tells the mesh it is there.",
            rd = { it.heartbeat }, wr = { s, v -> s.copy(heartbeat = v) },
        ),
        storeForward.num(
            "records", "Messages to keep", 0, 1000, zero = "Default",
            rd = { it.records }, wr = { s, v -> s.copy(records = v) },
        ),
        storeForward.num(
            "history_return_max", "Most messages to hand back at once", 0, 1000, zero = "Default",
            rd = { it.history_return_max }, wr = { s, v -> s.copy(history_return_max = v) },
        ),
        storeForward.num(
            "history_return_window", "Hand back messages from the last", 0, 10080, unit = "min", zero = "Default",
            rd = { it.history_return_window }, wr = { s, v -> s.copy(history_return_window = v) },
        ),

        // ---- Serial ----
        serial.flag(
            "enabled", "Serial port", "Lets a wired device talk to the radio.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        serial.flag(
            "echo", "Echo what is sent", null,
            rd = { it.echo }, wr = { s, v -> s.copy(echo = v) },
        ),
        serial.choice(
            "baud", "Speed", baud, rd = { it.baud.value },
            wr = { s, v -> ModuleConfig.SerialConfig.Serial_Baud.fromValue(v)?.let { s.copy(baud = it) } ?: s },
        ),
        serial.choice(
            "mode", "Mode", serialModes, rd = { it.mode.value },
            wr = { s, v -> ModuleConfig.SerialConfig.Serial_Mode.fromValue(v)?.let { s.copy(mode = it) } ?: s },
        ),
        serial.num(
            "timeout", "Wait for more data", 0, 3600, unit = "s", zero = "Default",
            rd = { it.timeout }, wr = { s, v -> s.copy(timeout = v) },
        ),
        serial.flag(
            "override_console_serial_port", "Use the console port", "Takes the port the radio logs on.",
            rd = { it.override_console_serial_port }, wr = { s, v -> s.copy(override_console_serial_port = v) },
        ),

        // ---- External notification ----
        ext.flag(
            "enabled", "Alerts", "LED, buzzer or vibration on a message.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        ext.flag(
            "alert_message", "Light the LED on a message", null,
            rd = { it.alert_message }, wr = { s, v -> s.copy(alert_message = v) },
        ),
        ext.flag(
            "alert_message_buzzer", "Buzz on a message", null,
            rd = { it.alert_message_buzzer }, wr = { s, v -> s.copy(alert_message_buzzer = v) },
        ),
        ext.flag(
            "alert_message_vibra", "Vibrate on a message", null,
            rd = { it.alert_message_vibra }, wr = { s, v -> s.copy(alert_message_vibra = v) },
        ),
        ext.flag(
            "alert_bell", "Light the LED on a bell", "A bell is a message with the bell character.",
            rd = { it.alert_bell }, wr = { s, v -> s.copy(alert_bell = v) },
        ),
        ext.flag(
            "alert_bell_buzzer", "Buzz on a bell", null,
            rd = { it.alert_bell_buzzer }, wr = { s, v -> s.copy(alert_bell_buzzer = v) },
        ),
        ext.flag(
            "alert_bell_vibra", "Vibrate on a bell", null,
            rd = { it.alert_bell_vibra }, wr = { s, v -> s.copy(alert_bell_vibra = v) },
        ),
        ext.num(
            "output_ms", "Alert lasts", 0, 60000, unit = "ms", zero = "Default",
            rd = { it.output_ms }, wr = { s, v -> s.copy(output_ms = v) },
        ),
        ext.num(
            "nag_timeout", "Repeat the alert for", 0, 3600, unit = "s", zero = "Once",
            rd = { it.nag_timeout }, wr = { s, v -> s.copy(nag_timeout = v) },
        ),
        ext.flag(
            "active", "Output is active high", null,
            rd = { it.active }, wr = { s, v -> s.copy(active = v) },
        ),
        ext.flag(
            "use_pwm", "Drive the buzzer with PWM", null,
            rd = { it.use_pwm }, wr = { s, v -> s.copy(use_pwm = v) },
        ),
        ext.flag(
            "use_i2s_as_buzzer", "Use the I2S speaker as the buzzer", null,
            rd = { it.use_i2s_as_buzzer }, wr = { s, v -> s.copy(use_i2s_as_buzzer = v) },
        ),

        // ---- Range test ----
        rangeTest.flag(
            "enabled", "Range test", "Sends or records test messages to measure how far the radio reaches.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        rangeTest.num(
            "sender", "Send a test message every", 0, 3600, unit = "s", zero = "Never (only listen)",
            warn = "A radio that sends a test message every few seconds uses the air for everyone near it. Use a long gap, and only while testing.",
            rd = { it.sender }, wr = { s, v -> s.copy(sender = v) },
        ),
        rangeTest.flag(
            "save", "Save the results", "To a file on the radio, if it has room.",
            rd = { it.save }, wr = { s, v -> s.copy(save = v) },
        ),
        rangeTest.flag(
            "clear_on_reboot", "Clear the saved results on restart", null,
            rd = { it.clear_on_reboot }, wr = { s, v -> s.copy(clear_on_reboot = v) },
        ),

        // ---- Canned messages ----
        canned.flag(
            "updown1_enabled", "Up/down buttons choose the message", null,
            rd = { it.updown1_enabled }, wr = { s, v -> s.copy(updown1_enabled = v) },
        ),
        canned.flag(
            "rotary1_enabled", "A rotary dial chooses the message", null,
            rd = { it.rotary1_enabled }, wr = { s, v -> s.copy(rotary1_enabled = v) },
        ),
        canned.flag(
            "send_bell", "Add a bell to what is sent", null,
            rd = { it.send_bell }, wr = { s, v -> s.copy(send_bell = v) },
        ),

        // ---- Neighbour info ----
        neighbor.flag(
            "enabled", "Neighbour info", "Shares which radios this one hears directly.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        neighbor.num(
            "update_interval", "Share every", 0, 86400, unit = "s", zero = "Default (6 hours)",
            rd = { it.update_interval }, wr = { s, v -> s.copy(update_interval = v) },
        ),
        neighbor.flag(
            "transmit_over_lora", "Send it over the air", "Without this it goes only to the phone and MQTT.",
            rd = { it.transmit_over_lora }, wr = { s, v -> s.copy(transmit_over_lora = v) },
        ),

        // ---- Detection sensor ----
        detection.flag(
            "enabled", "Detection sensor", "Sends a message when a wired input changes.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        detection.text(
            "name", "Message to send", 19,
            rd = { it.name }, wr = { s, v -> s.copy(name = v) },
        ),
        detection.num(
            "minimum_broadcast_secs", "Not more often than", 0, 86400, unit = "s",
            rd = { it.minimum_broadcast_secs }, wr = { s, v -> s.copy(minimum_broadcast_secs = v) },
        ),
        detection.num(
            "state_broadcast_secs", "Report the state every", 0, 86400, unit = "s", zero = "Only on change",
            rd = { it.state_broadcast_secs }, wr = { s, v -> s.copy(state_broadcast_secs = v) },
        ),
        detection.choice(
            "detection_trigger_type", "Triggers on", triggers, rd = { it.detection_trigger_type.value },
            wr = { s, v -> ModuleConfig.DetectionSensorConfig.TriggerType.fromValue(v)?.let { s.copy(detection_trigger_type = it) } ?: s },
        ),
        detection.flag(
            "use_pullup", "Use the pull-up resistor", null,
            rd = { it.use_pullup }, wr = { s, v -> s.copy(use_pullup = v) },
        ),
        detection.flag(
            "send_bell", "Add a bell to the message", null,
            rd = { it.send_bell }, wr = { s, v -> s.copy(send_bell = v) },
        ),

        // ---- People counter ----
        pax.flag(
            "enabled", "People counter", "Counts nearby Wi-Fi and Bluetooth devices.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        pax.num(
            "paxcounter_update_interval", "Report every", 0, 86400, unit = "s", zero = "Default (15 minutes)",
            rd = { it.paxcounter_update_interval }, wr = { s, v -> s.copy(paxcounter_update_interval = v) },
        ),

        // ---- MQTT: on or off, and what it is set to. The server and password are not entered here. ----
        mqtt.flag(
            "enabled", "MQTT", "Sends traffic to an internet server. It needs the radio to have Wi-Fi or a link of its own.",
            warn = "MQTT passes messages from the mesh to a server on the internet. Check the server and what is shared before turning it on.",
            rd = { it.enabled }, wr = { s, v -> s.copy(enabled = v) },
        ),
        mqtt.info("address", "Server") { it.address.ifEmpty { "Default" } },
        mqtt.info("encryption_enabled", "Encrypted messages") { if (it.encryption_enabled) "On" else "Off" },
        mqtt.info("tls_enabled", "TLS") { if (it.tls_enabled) "On" else "Off" },
        mqtt.info("proxy_to_client_enabled", "Through the phone") { if (it.proxy_to_client_enabled) "On" else "Off" },
        mqtt.info("map_reporting_enabled", "Report to the public map") { if (it.map_reporting_enabled) "On" else "Off" },
    )

    private val byId: Map<String, Field> = all.associateBy { it.id }

    fun get(id: String): Field? = byId[id]

    fun inPart(part: Part): List<Field> = all.filter { it.part == part }

    /** Whether the user can change it (everything but the info rows). */
    fun editable(f: Field): Boolean = f.kind !is Kind.Info

    /** The value as the dialog shows it, or null when the section has not been read. */
    fun display(f: Field, c: MtConfigs): String? {
        val v = f.read(c) ?: return null
        return when (val k = f.kind) {
            is Kind.Choice -> k.options.firstOrNull { it.value == v }?.label ?: "Value $v"
            is Kind.Num -> {
                val n = v as Int
                if (n == 0 && k.zero != null) k.zero else if (k.unit.isEmpty()) "$n" else "$n ${k.unit}"
            }
            Kind.Flag -> if (v as Boolean) "On" else "Off"
            is Kind.Text -> (v as String).ifEmpty { "Not set" }
            Kind.Info -> v as String
        }
    }

    /** Why [value] cannot be set, as a sentence, or null when it can. */
    fun refusal(f: Field, value: Any, c: MtConfigs): String? {
        when (val k = f.kind) {
            is Kind.Choice -> if (value !is Int || k.options.none { it.value == value }) return "That is not one of the choices."
            is Kind.Num -> {
                if (value !is Int) return "That is not a number."
                if (value == 0 && k.zero != null) Unit
                else if (value < k.min || value > k.max) return "Use a number from ${k.min} to ${k.max}."
            }
            Kind.Flag -> if (value !is Boolean) return "That is not on or off."
            is Kind.Text -> {
                if (value !is String) return "That is not text."
                if (value.toByteArray().size > k.maxBytes) return "At most ${k.maxBytes} bytes."
                if (f.part == Part.OWNER && value.isBlank()) return "A name needs at least one letter."
            }
            Kind.Info -> return "This one is shown, not changed here."
        }
        return f.guard?.invoke(c, value)
    }

    /** What changing [f] to [value] results in, or why not. */
    fun apply(f: Field, value: Any, c: MtConfigs): Applied {
        refusal(f, value, c)?.let { return Applied.Refused(it) }
        val next = f.write(c, value) ?: return Applied.NotRead
        val admin = next.toAdmin(f.part) ?: return Applied.NotRead
        return Applied.Change(next, admin)
    }

    sealed interface Applied {
        data object NotRead : Applied
        data class Refused(val reason: String) : Applied
        data class Change(val configs: MtConfigs, val admin: AdminMessage) : Applied
    }
}
