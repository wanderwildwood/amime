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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.MeshViewModel
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
    onScan: () -> Unit,
    onConnect: (MeshViewModel.Radio) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopAppBarMMD(title = { TextMMD(text = "Radios") }) },
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
            if (pairing) {
                TextMMD(
                    text = "Pairing. The PIN is the radio's own: a node with a screen shows " +
                        "it, and one without has ${NordicUart.DEFAULT_PIN} until it is changed.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (radios.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextMMD(
                        text = if (scanning) {
                            "Looking."
                        } else {
                            // Two different things look the same from here, and neither is
                            // worth guessing between on the reader's behalf.
                            "No radio found. It may be out of range, or switched off."
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(radios.size) { index ->
                        RadioRow(radios[index], onClick = { onConnect(radios[index]) })
                    }
                }
            }

            OutlinedButtonMMD(
                onClick = onScan,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                TextMMD(text = if (scanning) "Looking" else "Look again", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun RadioRow(radio: MeshViewModel.Radio, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Solid for a radio this phone is already paired with; dotted for one it has only
            // just heard, which will want a PIN before it will say anything.
            .stateBorder(settled = radio.bonded)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TextMMD(text = radio.name, style = MaterialTheme.typography.bodyMedium)
        // The signal is worth a line here and nowhere else: it is the one number that says
        // whether the thing you are about to pair with is near enough to stay paired.
        radio.rssi?.let { TextMMD(text = "$it dBm", style = MaterialTheme.typography.labelSmall) }
    }
}
