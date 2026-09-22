package com.wanderwildwood.amime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.MeshViewModel
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.ble.NordicUart

/**
 * Choosing a radio.
 *
 * Shown only until one is connected: a phone has one radio, and a screen you pass through
 * once should not become a place you live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadiosScreen(
    radios: List<MeshViewModel.Radio>,
    scanning: Boolean,
    problem: String?,
    pairing: Boolean,
    connecting: String?,
    onScan: () -> Unit,
    onConnect: (MeshViewModel.Radio) -> Unit,
) {
    var aboutOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.radios_title)) },
                // Here as well as on the list of people, because this is the screen an app
                // with no radio yet never gets past — and About is the one thing a stranger
                // looks for before trusting a thing they have just installed. Behind a
                // connection it would be unreachable by exactly the reader who wants it.
                actions = {
                    BarButton(Icons.Info, stringResource(R.string.about)) { aboutOpen = true }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The app knowing something is wrong and showing nothing is worse than the
            // trouble itself: a radio that will not talk looks identical to one that is not
            // there, and only this line tells them apart.
            problem?.let {
                TextMMD(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // The PIN the system dialog is asking for belongs to the radio, not to the phone,
            // and on a node with a screen it is a fresh six digits after every power cut.
            // Without this line the obvious thing to try is the phone's own PIN, and the
            // obvious conclusion when that fails is that the app does not work.
            // Between the tap and the radio saying who it is there is a bond, a GATT
            // connection, an MTU negotiation and a handshake, and on this screen none of
            // them used to show. A tap that changes nothing invites a second tap.
            connecting?.let {
                TextMMD(
                    text = stringResource(R.string.radios_connecting, it),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (pairing) {
                TextMMD(
                    text = stringResource(R.string.radios_pairing, NordicUart.DEFAULT_PIN),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (radios.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextMMD(
                        text = when {
                            scanning -> stringResource(R.string.radios_looking_sentence)
                            // Where something went wrong, the line above has already said
                            // what. Saying "no radio found" under it would be the screen
                            // reporting the result of a search that never happened — which
                            // is exactly the case where somebody goes looking at the radio
                            // instead of at the permission they refused.
                            problem != null -> ""
                            // Two different things look the same from here, and neither is
                            // worth guessing between on the reader's behalf.
                            else -> stringResource(R.string.radios_none)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(radios.size) { index ->
                        RadioRow(
                            radio = radios[index],
                            // One connection at a time. A second radio tapped while the
                            // first is still negotiating leaves two GATT connections racing
                            // for one session.
                            enabled = connecting == null,
                            onClick = { onConnect(radios[index]) },
                        )
                    }
                }
            }

            OutlinedButtonMMD(
                onClick = onScan,
                enabled = !scanning && connecting == null,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                TextMMD(
                    text = stringResource(
                        if (scanning) R.string.radios_looking else R.string.radios_look_again,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
}

@Composable
private fun RadioRow(radio: MeshViewModel.Radio, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Solid for a radio this phone is already paired with; dotted for one it has only
            // just heard, which will want a PIN before it will say anything.
            .stateBorder(settled = radio.bonded)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TextMMD(text = radio.name, style = MaterialTheme.typography.bodyMedium)
        // The signal is worth a line here and nowhere else: it is the one number that says
        // whether the thing you are about to pair with is near enough to stay paired.
        radio.rssi?.let {
            TextMMD(
                text = stringResource(R.string.radios_signal, it),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
