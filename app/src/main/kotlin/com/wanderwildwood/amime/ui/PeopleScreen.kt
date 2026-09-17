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
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.mesh.MeshState
import com.wanderwildwood.amime.mesh.Person

/**
 * Everyone the radio knows about.
 *
 * One node is one row, whether it is a person, a repeater or something that has never given
 * a name. A repeater cannot be written to and says so rather than offering a thread that
 * would go nowhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    state: MeshState,
    onOpen: (Person) -> Unit,
) {
    var aboutOpen by remember { mutableStateOf(false) }

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
        if (state.people.isEmpty()) {
            Empty(state, Modifier.padding(padding))
        } else {
            LazyColumnMMD(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(state.people.size) { index ->
                    val person = state.people[index]
                    PersonRow(
                        person = person,
                        unread = state.conversations[person.prefix].orEmpty().isNotEmpty(),
                        onClick = { onOpen(person) },
                    )
                }
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
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
