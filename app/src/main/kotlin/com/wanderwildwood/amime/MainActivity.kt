package com.wanderwildwood.amime

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.amime.link.RadioKind
import com.wanderwildwood.amime.mesh.GeoPoint
import com.wanderwildwood.amime.mesh.NoticeScreen
import com.wanderwildwood.amime.mesh.Person
import com.wanderwildwood.amime.ui.PersonHeader
import com.wanderwildwood.amime.ui.PositionDialog
import com.wanderwildwood.amime.ui.ChannelEditScreen
import com.wanderwildwood.amime.ui.ChannelImportScreen
import com.wanderwildwood.amime.ui.ChannelQrScreen
import com.wanderwildwood.amime.ui.ChannelsScreen
import com.wanderwildwood.amime.ui.adminNotice
import com.wanderwildwood.amime.ui.PasteLinkDialog
import com.wanderwildwood.amime.ui.RadioSettingsScreen
import com.wanderwildwood.amime.ui.RegionScreen
import com.wanderwildwood.amime.ui.regionName
import com.wanderwildwood.amime.meshtastic.ImportPlan
import com.wanderwildwood.amime.meshtastic.MtChannelInfo
import com.wanderwildwood.amime.protocol.Sizes
import com.wanderwildwood.amime.ui.LeaveChannelAction
import com.wanderwildwood.amime.ui.ConsoleScreen
import com.wanderwildwood.amime.ui.ConversationScreen
import com.wanderwildwood.amime.ui.PeopleScreen
import com.wanderwildwood.amime.ui.RadiosScreen
import com.wanderwildwood.amime.ui.monochrome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // A steady cursor: a blinking one is a repaint twice a second on an e-ink panel.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalCursorBlinkEnabled provides false,
            ) {
                ThemeMMD(colorScheme = monochrome) {
                    Mesh()
                }
            }
        }
    }
}

@Composable
private fun Mesh(viewModel: MeshViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Person?>(null) }

    /**
     * The channel open, by its key. Looked up again on every change rather than held, because
     * the slot it sits in is the radio's to report — and once it has been left, there is
     * nothing to show and the list comes back.
     */
    var openChannelKey by remember { mutableStateOf<List<Byte>?>(null) }

    // Both are asked for at once because neither is any use without the other: the app
    // cannot find the radio without SCAN and cannot talk to it without CONNECT.
    val radios by viewModel.radios.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    val context0 = androidx.compose.ui.platform.LocalContext.current
    // The permission is Android's to give, and Android stops showing the question after two
    // refusals; the app's own settings page is then the only place left to give it.
    var permissionMissing by remember { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            permissionMissing = false
            viewModel.findRadios()
        } else {
            permissionMissing = true
            viewModel.bluetoothRefused()
        }
    }
    val bluetoothPermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

    LaunchedEffect(Unit) {
        ask.launch(bluetoothPermissions)
    }

    // Leaving the app ends a scan that was cut short, and coming back from Android's settings
    // is where a permission given there is noticed.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        viewModel.stopScanning()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (permissionMissing && viewModel.mayUseBluetooth()) {
            permissionMissing = false
            viewModel.findRadios()
        }
    }

    // Administering a repeater takes over the screen: it is a different job from messaging,
    // with a different thing on the other end.
    val admin = state.admin
    if (admin != null) {
        ConsoleScreen(
            admin = admin,
            onSend = viewModel::sendCommand,
            onClose = viewModel::endAdmin,
        )
        return
    }

    var regionOpen by remember { mutableStateOf(false) }
    var positionOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<MtPage?>(null) }
    var channelNotice by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    val meshtastic = state.radio == RadioKind.MESHTASTIC
    // A notice about a write is said where the write was made, and goes when that screen is left.
    val noticeScreen = when (page) {
        MtPage.Settings -> NoticeScreen.SETTINGS
        null -> NoticeScreen.OTHER
        else -> NoticeScreen.CHANNELS
    }
    LaunchedEffect(noticeScreen) { viewModel.noticeScreen(noticeScreen) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val nearest by viewModel.nearestFirst.collectAsStateWithLifecycle()

    // The link read, by pasting or by the Camera app, becomes a plan to look at before anything is sent.
    val readLink: (String) -> Unit = { text ->
        val plan = viewModel.planImport(text)
        if (plan == null) {
            channelNotice = context.getString(R.string.mtx_bad_link)
        } else {
            channelNotice = null
            page = MtPage.Import(plan)
        }
    }
    // The standard ZXing scan request, which the Camera app answers. Mesh holds no camera permission.
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringExtra("SCAN_RESULT")
            if (text != null) readLink(text)
        }
    }
    val startScan = {
        val intent = android.content.Intent("com.google.zxing.client.android.SCAN")
            .putExtra("SCAN_MODE", "QR_CODE_MODE")
            .putExtra("PROMPT_MESSAGE", context.getString(R.string.mtx_scan_prompt))
        try {
            scan.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            channelNotice = context.getString(R.string.mtx_scan_none)
        }
    }

    if (meshtastic && state.ready) {
        val primaryName = state.mtConfigs?.lora?.modem_preset?.let { com.wanderwildwood.amime.meshtastic.MtSession.presetLabel(it) } ?: "LongFast"
        when (val p = page) {
            MtPage.Channels -> {
                ChannelsScreen(
                    channels = state.mtChannels,
                    primaryName = primaryName,
                    notice = channelNotice ?: adminNotice(state.adminNoticeFor(NoticeScreen.CHANNELS)),
                    onEdit = { channelNotice = null; page = MtPage.Edit(it) },
                    onAdd = { channelNotice = null; page = MtPage.Edit(null) },
                    onScan = startScan,
                    onPaste = { pasting = true },
                    onShow = { page = MtPage.Qr },
                    onClose = { channelNotice = null; page = null },
                )
                if (pasting) {
                    PasteLinkDialog(onDismiss = { pasting = false }, onRead = { pasting = false; readLink(it) })
                }
                return
            }
            is MtPage.Edit -> {
                val free = state.mtChannels.firstOrNull { !it.enabled && it.index > 0 }?.index ?: -1
                ChannelEditScreen(
                    existing = p.channel,
                    slot = p.channel?.index ?: free,
                    primaryName = primaryName,
                    newKey = viewModel::randomKey,
                    onSave = { name, key, up, down ->
                        val ok = if (p.channel == null) {
                            key != null && viewModel.addChannel(name, key, up, down) >= 0
                        } else {
                            viewModel.saveChannel(p.channel.index, name, key, up, down)
                        }
                        channelNotice = if (ok) null else context.getString(R.string.mtx_refused_save)
                        page = MtPage.Channels
                    },
                    onDelete = {
                        p.channel?.let { viewModel.deleteChannel(it.index) }
                        channelNotice = null
                        page = MtPage.Channels
                    },
                    onClose = { page = MtPage.Channels },
                )
                return
            }
            is MtPage.Import -> {
                ChannelImportScreen(
                    plan = p.plan,
                    regionName = regionName(p.plan.lora?.region?.value),
                    onAdd = {
                        val n = viewModel.applyImport(p.plan)
                        channelNotice = if (n > 0) null else context.getString(R.string.mtx_added, n)
                        page = MtPage.Channels
                    },
                    onApplyRadio = {
                        viewModel.applyLoraFrom(p.plan)
                        channelNotice = null
                        page = MtPage.Channels
                    },
                    onClose = { page = MtPage.Channels },
                )
                return
            }
            MtPage.Qr -> {
                ChannelQrScreen(
                    linkWith = viewModel.channelLink(true),
                    linkWithout = viewModel.channelLink(false),
                    onClose = { page = MtPage.Channels },
                )
                return
            }
            MtPage.Settings -> {
                if (regionOpen) {
                    RegionScreen(
                        current = state.region,
                        onChoose = {
                            regionOpen = false
                            viewModel.setRegion(it)
                        },
                        onClose = { regionOpen = false },
                    )
                } else {
                    RadioSettingsScreen(
                        configs = state.mtConfigs,
                        regionLabel = regionName(state.region),
                        typedPosition = viewModel.typedHere,
                        report = state.adminNoticeFor(NoticeScreen.SETTINGS),
                        onRegion = { regionOpen = true },
                        onChange = viewModel::changeSetting,
                        onFixedPosition = { viewModel.typedHere?.let(viewModel::setFixedPosition) ?: false },
                        onClearFixed = viewModel::clearFixedPosition,
                        onClose = { page = null },
                    )
                }
                return
            }
            null -> Unit
        }
    }
    if (regionOpen && meshtastic) {
        RegionScreen(
            current = state.region,
            onChoose = {
                regionOpen = false
                viewModel.setRegion(it)
            },
            onClose = { regionOpen = false },
        )
        return
    }
    if (positionOpen) {
        PositionDialog(
            onDismiss = { positionOpen = false },
            onSet = { lat, lon ->
                positionOpen = false
                viewModel.setTypedPosition(GeoPoint(lat, lon))
            },
        )
    }

    val person = open
    val channel = openChannelKey?.let(state::channel)
    if (channel != null) {
        val closeChannel = {
            viewModel.markRead(channel)
            openChannelKey = null
        }
        ConversationScreen(
            title = channel.name,
            messages = state.conversations[channel.key].orEmpty(),
            canSend = state.ready,
            radio = state.radio,
            channel = true,
            // The radio puts "name: " in front and cuts the whole at the limit without a word,
            // so the room for the words is what the name leaves.
            maxBytes = if (meshtastic) MT_TEXT_BYTES else Sizes.MAX_TEXT - ((state.nodeName ?: "") + ": ").toByteArray().size,
            onSend = { viewModel.sendToChannel(channel, it) },
            onBack = closeChannel,
            actions = {
                // Public is every radio's from the start, and the one place a stranger can be
                // heard; it stays.
                if (!meshtastic && !channel.isPublic && state.ready) {
                    LeaveChannelAction {
                        viewModel.leaveChannel(channel)
                        closeChannel()
                    }
                }
            },
        )
    } else if (!state.ready && person == null) {
        RadiosScreen(
            radios = radios,
            scanning = scanning,
            problem = problem,
            pairing = pairing,
            connecting = connecting,
            searched = searched,
            onScan = {
                // Ask again when the permission is missing; Android answers at once, without a
                // dialog, once it has been refused twice, and the settings button is then the way.
                if (viewModel.mayUseBluetooth()) viewModel.findRadios() else ask.launch(bluetoothPermissions)
            },
            onOpenSettings = if (permissionMissing) {
                {
                    runCatching {
                        context0.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context0.packageName, null),
                            ),
                        )
                    }
                }
            } else null,
            onConnect = viewModel::connect,
        )
    } else if (person != null) {
        // The row as the radio last described it: its route can change while the thread is
        // open, and the offer under an unanswered message depends on it.
        val current = state.people.firstOrNull { it.prefix == person.prefix } ?: person
        ConversationScreen(
            title = current.label,
            messages = state.conversations[person.prefix].orEmpty(),
            canSend = state.ready,
            radio = state.radio,
            sendAgain = if (current.pathKnown) {
                R.string.message_send_again_new_route
            } else {
                R.string.message_send_again
            },
            onSendAgain = { viewModel.sendAgain(current, it) },
            onSend = { viewModel.send(person, it) },
            maxBytes = if (meshtastic) MT_DIRECT_BYTES else Sizes.MAX_TEXT,
            header = if (meshtastic) {
                { PersonHeader(current, state.here) }
            } else {
                null
            },
            onBack = {
                // Anything that arrived while it was open was read as it landed.
                viewModel.markRead(person)
                open = null
            },
        )
    } else {
        PeopleScreen(
            state = state,
            problem = problem,
            onOpen = {
                viewModel.markRead(it)
                open = it
            },
            onOpenChannel = {
                viewModel.markRead(it)
                openChannelKey = it.key
            },
            onJoinChannel = viewModel::joinChannel,
            onAdminister = viewModel::beginAdmin,
            onAnnounce = viewModel::announce,
            onDismissProblem = viewModel::dismissProblem,
            onRegion = { regionOpen = true },
            onPosition = { positionOpen = true },
            onChannels = { channelNotice = null; page = MtPage.Channels },
            onSettings = { page = MtPage.Settings },
            nearestFirst = nearest,
            onNearestFirst = viewModel::toggleNearestFirst,
        )
    }
}

/** What a text can hold on a channel: the 233-byte payload less the framing, 228 bytes of words. */
private const val MT_TEXT_BYTES = 228

/** Direct messages are encrypted to the recipient, which costs another 12 bytes. */
private const val MT_DIRECT_BYTES = 216

/** Which Meshtastic screen is open, when one is. */
private sealed interface MtPage {
    data object Channels : MtPage
    data class Edit(val channel: MtChannelInfo?) : MtPage
    data class Import(val plan: ImportPlan) : MtPage
    data object Qr : MtPage
    data object Settings : MtPage
}
