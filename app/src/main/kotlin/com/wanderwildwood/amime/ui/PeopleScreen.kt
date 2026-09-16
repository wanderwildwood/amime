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
import androidx.compose.ui.unit.sp
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    // The node's own name, because on a mesh which radio you are is the first
                    // thing worth knowing and there is nowhere else it would be said.
                    TextMMD(text = state.nodeName ?: "Mesh", fontSize = 24.sp)
                },
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
        TextMMD(text = person.label, fontSize = 18.sp)
        // A second line only where it carries something the label could not: what kind of
        // node this is, and only when it is not the ordinary kind.
        if (person.isRepeater) {
            TextMMD(text = "Repeater", fontSize = 14.sp)
        } else if (unread) {
            TextMMD(text = "Has messages", fontSize = 14.sp)
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
            text = if (state.ready) {
                "Nobody yet. The radio hears a node when it advertises itself, which can take a while."
            } else {
                "Not connected to a radio."
            },
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
