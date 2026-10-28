package com.wanderwildwood.amime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.R
import org.meshtastic.proto.Config.LoRaConfig.RegionCode

/** The regions a Meshtastic radio can be set to, the United States first. */
private val CHOICES: List<RegionCode> =
    RegionCode.entries.filter { it != RegionCode.UNSET }.sortedBy { if (it == RegionCode.US) 0 else 1 }

/** A region in words, or its code where there are none yet. */
@Composable
fun regionName(value: Int?): String {
    val code = RegionCode.fromValue(value ?: 0) ?: return ""
    return when (code) {
        RegionCode.UNSET -> stringResource(R.string.mt_region_none)
        RegionCode.US -> stringResource(R.string.mt_region_us)
        RegionCode.EU_433 -> stringResource(R.string.mt_region_eu433)
        RegionCode.EU_868 -> stringResource(R.string.mt_region_eu868)
        RegionCode.CN -> stringResource(R.string.mt_region_cn)
        RegionCode.JP -> stringResource(R.string.mt_region_jp)
        RegionCode.ANZ -> stringResource(R.string.mt_region_anz)
        RegionCode.KR -> stringResource(R.string.mt_region_kr)
        RegionCode.TW -> stringResource(R.string.mt_region_tw)
        RegionCode.RU -> stringResource(R.string.mt_region_ru)
        RegionCode.IN -> stringResource(R.string.mt_region_in)
        else -> code.name.replace('_', ' ')
    }
}

/**
 * Choosing the LoRa region of a Meshtastic radio.
 *
 * The law differs from place to place, so the one sentence the screen carries is that the
 * choice has to match it. Setting it restarts the radio, hence the second tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionScreen(current: Int?, onChoose: (Int) -> Unit, onClose: () -> Unit) {
    var picked by remember { mutableStateOf<RegionCode?>(null) }
    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.mt_region_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.conversation_back), onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TextMMD(
                text = stringResource(R.string.mt_region_law),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(CHOICES.size) { index ->
                    val code = CHOICES[index]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .stateBorder(settled = code.value == current)
                            .clickableTarget { picked = code }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        TextMMD(text = regionName(code.value), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    picked?.let { code ->
        EInkDialog(onDismiss = { picked = null }) {
            TextMMD(
                text = stringResource(R.string.mt_region_confirm, regionName(code.value)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButtonMMD(onClick = { picked = null }, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.login_cancel), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButtonMMD(
                    onClick = {
                        picked = null
                        onChoose(code.value)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    TextMMD(text = stringResource(R.string.mt_region_set), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Typing where you are, for distances when the radio has no fix of its own. */
@Composable
fun PositionDialog(onDismiss: () -> Unit, onSet: (Double, Double) -> Unit) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    val latValue = lat.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lonValue = lon.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.mt_position_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        TextMMD(text = stringResource(R.string.mt_position_help), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(10.dp))
        TextMMD(text = stringResource(R.string.mt_position_lat), style = MaterialTheme.typography.labelSmall)
        com.mudita.mmd.components.text_field.TextFieldMMD(
            value = lat, onValueChange = { lat = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(text = stringResource(R.string.mt_position_lon), style = MaterialTheme.typography.labelSmall)
        com.mudita.mmd.components.text_field.TextFieldMMD(
            value = lon, onValueChange = { lon = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.login_cancel), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButtonMMD(
                onClick = { onSet(latValue!!, lonValue!!) },
                enabled = latValue != null && lonValue != null,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                TextMMD(text = stringResource(R.string.mt_region_set), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
