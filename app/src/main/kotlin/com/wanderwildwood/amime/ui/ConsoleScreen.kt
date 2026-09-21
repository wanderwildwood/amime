package com.wanderwildwood.amime.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.R
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

    // Leaving by the phone's own way out, which otherwise leaves the app rather than the
    // console — and leaving properly means logging out, which is what the close does.
    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = admin.person.label) },
                navigationIcon = {
                    BarButton(Icons.Close, stringResource(R.string.console_close), onClose)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The state of the login is worth a line of its own, because until it is in there
            // is nothing to be done here and no way to tell that from a slow answer.
            TextMMD(
                text = stringResource(
                    when (admin.state) {
                        Admin.State.LOGGING_IN -> R.string.console_logging_in
                        Admin.State.REFUSED -> R.string.console_refused
                        Admin.State.IN ->
                            if (admin.isAdmin) {
                                R.string.console_administrator
                            } else {
                                R.string.console_guest
                            }
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (admin.lines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextMMD(
                        text = if (admin.state == Admin.State.IN) {
                            stringResource(R.string.console_nothing_asked)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.titleSmall,
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
                    TextMMD(
                        text = stringResource(R.string.console_send),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Line(line: ConsoleLine) {
    // Monospaced, because what comes back is a machine's output and its columns mean something.
    // At the scale's floor rather than below it: a repeater's `status` table is wide, and the
    // temptation to buy a column by dropping a point is how a panel of sixteen greys ends up
    // with a line nobody can read outdoors.
    TextMMD(
        text = if (line.fromUs) "> ${line.text}" else line.text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (line.fromUs) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    )
}
