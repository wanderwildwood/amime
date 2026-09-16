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
import com.wanderwildwood.amime.mesh.Person
import com.wanderwildwood.amime.ui.ConversationScreen
import com.wanderwildwood.amime.ui.PeopleScreen
import com.wanderwildwood.amime.ui.RadiosScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThemeMMD {
                Mesh()
            }
        }
    }
}

@Composable
private fun Mesh(viewModel: MeshViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Person?>(null) }

    // Both are asked for at once because neither is any use without the other: the app
    // cannot find the radio without SCAN and cannot talk to it without CONNECT.
    val radios by viewModel.radios.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.findRadios()
    }

    LaunchedEffect(Unit) {
        ask.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        )
    }

    val person = open
    if (!state.ready && person == null) {
        RadiosScreen(
            radios = radios,
            scanning = scanning,
            onScan = viewModel::findRadios,
            onConnect = { viewModel.connect(it.device) },
        )
    } else if (person != null) {
        ConversationScreen(
            conversation = state.conversationWith(person.prefix)
                ?: com.wanderwildwood.amime.mesh.Conversation(person),
            // A repeater has no inbox to write to.
            canSend = state.ready && !person.isRepeater,
            onSend = { viewModel.send(person, it) },
            onBack = { open = null },
        )
    } else {
        PeopleScreen(state = state, onOpen = { open = it })
    }
}
