package com.wanderwildwood.amime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.mesh.Admin
import com.wanderwildwood.amime.mesh.ConsoleLine

/**
 * Administering a repeater over the mesh.
 *
 * A repeater has no Bluetooth — the firmware that makes one has no companion interface at all
 * — so this is the only way to reach one that is already up a pole. Commands go out as
 * ordinary messages marked as CLI data and the answers come back the same way, which means
 * every round trip is a radio round trip: seconds, not milliseconds, and sometimes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    admin: Admin,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = admin.person.label, fontSize = 24.sp) },
                navigationIcon = { BarButton(Icons.Close, "Close", onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The state of the login is worth a line of its own, because until it is in there
            // is nothing to be done here and no way to tell that from a slow answer.
            TextMMD(
                text = when (admin.state) {
                    Admin.State.LOGGING_IN -> "Logging in. The answer comes back over the air."
                    Admin.State.REFUSED ->
                        "Refused. It does not say whether that was the password or a full node."
                    Admin.State.IN ->
                        if (admin.isAdmin) "Logged in as administrator." else "Logged in as a guest, which can do very little."
                },
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (admin.lines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextMMD(
                        text = if (admin.state == Admin.State.IN) {
                            "Nothing asked yet. `help` lists what it will answer."
                        } else {
                            ""
                        },
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(admin.lines.size) { index -> Line(admin.lines[index]) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextFieldMMD(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButtonMMD(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = admin.state == Admin.State.IN && draft.isNotBlank(),
                ) {
                    TextMMD(text = "Send", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Line(line: ConsoleLine) {
    // Monospaced, because what comes back is a machine's output and its columns mean something.
    TextMMD(
        text = if (line.fromUs) "> ${line.text}" else line.text,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (line.fromUs) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    )
}
