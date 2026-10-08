package com.wanderwildwood.amime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.mesh.Delivery
import com.wanderwildwood.amime.mesh.Message
import com.wanderwildwood.amime.protocol.Sizes

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
    title: String,
    messages: List<Message>,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    /**
     * What to offer under a message that went out and never came back, or null for a thread
     * with nothing to wait for — a channel, where nobody acknowledges anything.
     */
    sendAgain: Int? = null,
    onSendAgain: (Message) -> Unit = {},
    /**
     * A channel: received messages carry their sender's name, and almost all of them came
     * through the mesh, so saying so under each would be furniture.
     */
    channel: Boolean = false,
    /** The most bytes the radio will carry for this thread. A channel spends some on the name. */
    maxBytes: Int = Sizes.MAX_TEXT,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }

    // The bar at the top has a way back and so does the phone; without this the phone's way
    // out of a thread is out of the app entirely.
    BackHandler(onBack = onBack)

    // Counted in bytes, because that is what the radio counts. For anything typed on a Latin
    // keyboard the two are the same number; for anything else they are not, and the limit
    // that matters is the one the firmware will enforce.
    val length = remember(draft) { draft.toByteArray(Charsets.UTF_8).size }
    val overBy = length - maxBytes

    /*
     * A thread opens at its newest message rather than at its oldest.
     *
     * The house rule says a thread of bubbles should not use MMD's list, because it pages by
     * four items and an item whose height is unknown — a photograph, a message longer than
     * the screen — is then skipped rather than paged through. Neither can happen here: there
     * are no pictures, and the radio will not carry more than 160 bytes, which is three or
     * four lines. Every item is smaller than a page, so paging by items is paging by pages.
     */
    /*
     * Where to offer sending it again, if anywhere.
     *
     * Under the newest message of yours that went out and never came back — either the
     * waiting ran out or the app was closed while it was still in flight. Under every one of
     * them would be the same sentence three times over; under the newest it reads as the
     * thing to do next, which is what it is.
     *
     * One offer rather than two. Where the radio has a route, the useful thing is to throw
     * that route away *and* send again, because the same way twice is the same silence
     * twice; where it has none, the message already floods and there is nothing to forget.
     * Two tappable lines under one message would be asking the reader to know which.
     */
    val unanswered = remember(messages, sendAgain) {
        if (sendAgain == null) return@remember null
        messages.lastOrNull {
            it.mine &&
                (it.delivery == Delivery.UNANSWERED || it.delivery == Delivery.UNRESOLVED)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            // Instant, not animated: the panel redraws in full and a smooth scroll on E Ink
            // is a smear with a battery cost.
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = title) },
                navigationIcon = {
                    BarButton(Icons.Close, stringResource(R.string.conversation_back), onBack)
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumnMMD(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages.size) { index ->
                    val message = messages[index]
                    // One item rather than two emissions, so that the offer stays on the
                    // same page as the message it is about — MMD's list turns pages by
                    // counting items, not by measuring them.
                    Column {
                        MessageRow(message, channel)
                        if (sendAgain != null && message.id == unanswered?.id) {
                            TextMMD(
                                text = stringResource(sendAgain),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSendAgain(message) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // Only near the limit, and only then. A count under every message is furniture
            // on a screen this size, and the number matters for about one message in fifty.
            if (length > maxBytes - NEARLY) {
                TextMMD(
                    text = if (overBy > 0) {
                        pluralStringResource(R.plurals.conversation_too_many, overBy, overBy)
                    } else {
                        pluralStringResource(R.plurals.conversation_left, -overBy, -overBy)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
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
                    // Dead where the radio would refuse it anyway: not connected, or a
                    // message longer than the firmware will carry. Saying so by the button
                    // being dead is thinner than a line of text under it, and the count
                    // above says which of the two it is.
                    enabled = canSend && draft.isNotBlank() && overBy <= 0,
                ) {
                    TextMMD(
                        text = stringResource(R.string.conversation_send),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message, channel: Boolean) {
    // On a channel the sender's radio writes "name: words"; the name goes above the words.
    val (sender, body) = if (channel && !message.mine) splitSender(message.text) else null to message.text
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
            sender?.let { TextMMD(text = it, style = MaterialTheme.typography.labelSmall) }
            TextMMD(
                text = body,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (message.mine) FontWeight.Normal else FontWeight.Bold,
            )
            message.note(channel)?.let { TextMMD(text = it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * A channel message's sender and words, from the "name: words" the sender's radio wrote
 * (`sendGroupMessage` in `BaseChatMesh.cpp`). Text without the separator — something other
 * than MeshCore's own firmware put it there — is all words and no name.
 */
internal fun splitSender(text: String): Pair<String?, String> {
    val at = text.indexOf(": ")
    if (at <= 0) return null to text
    return text.substring(0, at) to text.substring(at + 2)
}

/** Settled means the outcome is known, not that it was good. */
private val Delivery.isSettled: Boolean
    get() = this == Delivery.ACKNOWLEDGED ||
        this == Delivery.SENT ||
        this == Delivery.NO_ACK_EXPECTED ||
        this == Delivery.REFUSED ||
        // The waiting is over, which is knowledge, even though what happened is not.
        this == Delivery.UNANSWERED ||
        // Not settled as in arrived — settled as in nothing further is coming. A dotted
        // border here would say the app was still waiting for something, and it is not.
        this == Delivery.UNRESOLVED

/**
 * The line under a message, where there is something to say that the border cannot carry.
 *
 * Nothing is said for the ordinary case. An acknowledged message needs no caption, and a
 * received one that arrived normally does not either — a note on every row is furniture, and
 * furniture stops being read in the row where it mattered.
 */
@Composable
private fun Message.note(channel: Boolean): String? = when {
    mine && delivery == Delivery.REFUSED -> stringResource(R.string.message_refused)
    mine && delivery == Delivery.NO_ACK_EXPECTED ->
        stringResource(R.string.message_no_confirmation)
    mine && delivery == Delivery.SENDING && attempt > 0 ->
        pluralStringResource(R.plurals.message_sending_again, attempt, attempt + 1)
    mine && delivery == Delivery.SENDING -> stringResource(R.string.message_sending)
    mine && delivery == Delivery.UNRESOLVED -> stringResource(R.string.message_unresolved)
    mine && delivery == Delivery.AWAITING_ACK -> stringResource(R.string.message_waiting)
    mine && delivery == Delivery.UNANSWERED -> stringResource(R.string.message_unanswered)
    // Only worth saying where it is not the ordinary case: a message that came through
    // repeaters travelled further than one that did not, and the signal is the reason a
    // reply might not make it back.
    !mine && direct == false && !channel -> stringResource(R.string.message_through_mesh) + snrNote()
    !mine && snr != null && snr < WEAK_SNR -> stringResource(R.string.message_weak_signal) + snrNote()
    else -> null
}

@Composable
private fun Message.snrNote(): String =
    snr?.let { stringResource(R.string.message_signal, it) }.orEmpty()

/**
 * Below this, a link is working but has little margin left. Chosen as the point where
 * MeshCore's slower spreading factors stop being comfortable rather than from a datasheet,
 * and it is a judgement rather than a measurement.
 */
private const val WEAK_SNR = -10f

/** How near the limit a message has to be before the screen starts counting, in bytes. */
private const val NEARLY = 20
