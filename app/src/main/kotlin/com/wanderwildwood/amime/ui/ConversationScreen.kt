package com.wanderwildwood.amime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.mesh.Conversation
import com.wanderwildwood.amime.mesh.Delivery
import com.wanderwildwood.amime.mesh.Message

/**
 * One thread.
 *
 * What is different here from any other messaging app is that a sent message has four honest
 * states rather than two, and three of them are not failures. The mesh may take a long time,
 * may acknowledge, or may tell you plainly that no acknowledgement is coming — and that last
 * one has to look settled rather than pending, or the screen claims something is still in
 * flight when the radio has already said it is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversation: Conversation,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = conversation.person.label) },
                navigationIcon = { BarButton(Icons.Close, "Back", onBack) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(conversation.messages.size) { index ->
                    MessageRow(conversation.messages[index])
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
                    // A repeater has no inbox, and the radio would refuse the send. Saying so
                    // by the button being dead is thinner than a line of text under it.
                    enabled = canSend && draft.isNotBlank(),
                ) {
                    TextMMD(text = "Send", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                // Dotted while the outcome is genuinely unknown, solid once it is settled —
                // including settled as "no acknowledgement is coming", which is knowledge
                // rather than uncertainty.
                .stateBorder(settled = message.delivery.isSettled)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            TextMMD(
                text = message.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (message.mine) FontWeight.Normal else FontWeight.Bold,
            )
            message.note()?.let { TextMMD(text = it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Settled means the outcome is known, not that it was good. */
private val Delivery.isSettled: Boolean
    get() = this == Delivery.ACKNOWLEDGED ||
        this == Delivery.NO_ACK_EXPECTED ||
        this == Delivery.REFUSED

/**
 * The line under a message, where there is something to say that the border cannot carry.
 *
 * Nothing is said for the ordinary case. An acknowledged message needs no caption, and a
 * received one that arrived normally does not either — a note on every row is furniture, and
 * furniture stops being read in the row where it mattered.
 */
private fun Message.note(): String? = when {
    mine && delivery == Delivery.REFUSED -> "The radio would not send this"
    mine && delivery == Delivery.NO_ACK_EXPECTED -> "Sent; there will be no confirmation"
    mine && delivery == Delivery.SENDING -> "Sending"
    mine && delivery == Delivery.AWAITING_ACK -> "Waiting for a confirmation"
    // Only worth saying where it is not the ordinary case: a message that came through
    // repeaters travelled further than one that did not, and the signal is the reason a
    // reply might not make it back.
    !mine && direct == false -> "Through the mesh" + snrNote()
    !mine && snr != null && snr < WEAK_SNR -> "Weak signal" + snrNote()
    else -> null
}

private fun Message.snrNote(): String = snr?.let { ", %.1f dB".format(it) } ?: ""

/**
 * Below this, a link is working but has little margin left. Chosen as the point where
 * MeshCore's slower spreading factors stop being comfortable rather than from a datasheet,
 * and it is a judgement rather than a measurement.
 */
private const val WEAK_SNR = -10f
