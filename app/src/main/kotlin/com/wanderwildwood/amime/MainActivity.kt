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
import com.wanderwildwood.amime.mesh.Person
import com.wanderwildwood.amime.ui.PersonHeader
import com.wanderwildwood.amime.ui.PositionDialog
import com.wanderwildwood.amime.ui.RegionScreen
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
            ThemeMMD(colorScheme = monochrome) {
                Mesh()
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

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.findRadios() else viewModel.bluetoothRefused()
    }

    LaunchedEffect(Unit) {
        ask.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        )
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
    val meshtastic = state.radio == RadioKind.MESHTASTIC
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
            onScan = viewModel::findRadios,
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
        )
    }
}

/** What a text can hold on a channel: the 233-byte payload less the framing, 228 bytes of words. */
private const val MT_TEXT_BYTES = 228

/** Direct messages are encrypted to the recipient, which costs another 12 bytes. */
private const val MT_DIRECT_BYTES = 216
