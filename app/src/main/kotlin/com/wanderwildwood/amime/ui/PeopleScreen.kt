package com.wanderwildwood.amime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.mesh.MeshState
import com.wanderwildwood.amime.mesh.Person

/**
 * Everyone the radio knows about.
 *
 * One node is one row, whether it is a person, a repeater or something that has never given
 * a name. A repeater has no inbox, so its row does not open a thread that would go nowhere —
 * it opens the one thing a repeater is for, which is being told what to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    state: MeshState,
    problem: String?,
    onOpen: (Person) -> Unit,
    onAdminister: (Person, String) -> Unit,
    onDismissProblem: () -> Unit,
) {
    var aboutOpen by remember { mutableStateOf(false) }
    var loginTo by remember { mutableStateOf<Person?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    Column {
                        // The node's own name, because on a mesh which radio you are is the
                        // first thing worth knowing and there is nowhere else it would say it.
                        TextMMD(text = state.nodeName ?: "Mesh")
                        // Only where there is a reading. A node that has not answered yet is
                        // not a node at nothing, and a figure drawn from no reply would say
                        // it was. It matters for a node that leaves the desk.
                        state.batteryMillivolts?.let {
                            TextMMD(
                                text = batteryLine(it),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                // About is not a setting, and it is the one thing a stranger looks for
                // before trusting an app. An i in the top right, as everywhere else here.
                actions = { BarButton(Icons.Info, "About", { aboutOpen = true }) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The last thing that went wrong, until somebody has read it. This is the screen
            // the app lives on, so a fault raised after connecting — a frame that arrived
            // truncated, a log that cannot be written — has nowhere else to be said, and
            // saying it nowhere is how an app comes to be quietly wrong for a week.
            problem?.let {
                TextMMD(
                    text = "$it — tap to clear",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissProblem)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (state.people.isEmpty()) {
                Empty(state, Modifier.weight(1f))
            } else {
                LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(state.people.size) { index ->
                        val person = state.people[index]
                        PersonRow(
                            person = person,
                            unread = state.conversations[person.prefix].orEmpty().isNotEmpty(),
                            onClick = {
                                if (person.isRepeater) loginTo = person else onOpen(person)
                            },
                        )
                    }
                }
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    loginTo?.let { person ->
        LoginDialog(
            person = person,
            onDismiss = { loginTo = null },
            onLogIn = { password ->
                loginTo = null
                onAdminister(person, password)
            },
        )
    }
}

/**
 * Asking for a repeater's password.
 *
 * The password belongs to the node rather than to the phone or the person, which is the one
 * thing worth saying here: an untouched repeater still has the firmware's, and somebody who
 * assumes it is theirs will type the wrong thing three times before doubting the app.
 *
 * Not masked. A repeater's password is shared by everyone who looks after it rather than
 * personal, and on a panel that redraws this slowly a row of dots is how a typo survives to
 * become a refused login with nothing to show for it.
 */
@Composable
private fun LoginDialog(person: Person, onDismiss: () -> Unit, onLogIn: (String) -> Unit) {
    var password by remember { mutableStateOf("") }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = "Log in to ${person.label}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "The node's own password, not this phone's.",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        TextFieldMMD(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButtonMMD(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = "Cancel", style = MaterialTheme.typography.bodySmall) }
            OutlinedButtonMMD(
                onClick = { onLogIn(password) },
                enabled = password.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = "Log in", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The radio's battery, in its own terms.
 *
 * Millivolts rather than a percentage, with a rough reading of what they mean beside it. A
 * percentage would be invented: the firmware reports a voltage and nothing about the cell it
 * came from, so any curve mapping one to the other here would be a guess wearing a number's
 * clothes. The words say roughly, because roughly is what is known.
 */
private fun batteryLine(millivolts: Int): String {
    val volts = millivolts / 1000f
    val sense = when {
        millivolts >= 4000 -> "full"
        millivolts >= 3700 -> "good"
        millivolts >= 3500 -> "getting low"
        else -> "low"
    }
    return "%.2f V, %s".format(volts, sense)
}

@Composable
private fun PersonRow(person: Person, unread: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Solid where the radio knows a route, dotted where it only floods to reach them.
            .stateBorder(settled = person.pathKnown)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TextMMD(text = person.label, style = MaterialTheme.typography.bodyMedium)
        // A second line only where it carries something the label could not: what kind of
        // node this is, and only when it is not the ordinary kind.
        if (person.isRepeater) {
            TextMMD(text = "Repeater", style = MaterialTheme.typography.labelSmall)
        } else if (unread) {
            TextMMD(text = "Has messages", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * What to say before the radio has told us anything.
 *
 * Says which of the two situations it is rather than one line that covers both, because
 * "no contacts" and "not connected" want different things done about them.
 */
@Composable
private fun Empty(state: MeshState, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TextMMD(
            // Three situations, and they want different things done about them. An empty
            // list because nothing is transmitting is a reason to move the antenna; an empty
            // list while it is picking up traffic is a reason to wait.
            text = when {
                !state.ready -> "Not connected to a radio."
                state.heard.packets == 0 ->
                    "Nobody yet, and nothing at all on the air since connecting."
                else ->
                    "Nobody yet, but ${state.heard.packets} " +
                        (if (state.heard.packets == 1) "packet" else "packets") +
                        " heard since connecting" +
                        (state.heard.bestSnr?.let { ", strongest %.1f dB".format(it) } ?: "") +
                        ". Something is transmitting in range; none of it a readable advert."
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }

}
