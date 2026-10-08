package com.wanderwildwood.amime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.mesh.Channel
import kotlinx.coroutines.delay

/**
 * One channel in the list, above the people.
 *
 * Its name is its label — Public, or the hashtag — and the second line says it is a channel
 * unless there is something unread to say instead. Always a solid border: a channel has no
 * route to know or not know.
 */
@Composable
fun ChannelRow(channel: Channel, unread: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .stateBorder(settled = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TextMMD(text = channel.name, style = MaterialTheme.typography.bodyMedium)
        TextMMD(
            text = if (unread > 0) {
                pluralStringResource(R.plurals.people_unread, unread, unread)
            } else {
                stringResource(R.string.channel_kind)
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** The way into another channel: one row of words at the foot of the channels. */
@Composable
fun JoinChannelRow(enabled: Boolean, onClick: () -> Unit) {
    TextMMD(
        text = stringResource(R.string.channel_join),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

/**
 * Asking which channel.
 *
 * A name alone is a hashtag channel, and that is nearly always what is wanted — it is how a
 * community mesh names its rooms. The key is there for a private channel and is left empty
 * otherwise. [onJoin] answers with a line saying why not, or null once it has gone.
 */
@Composable
fun JoinChannelDialog(onDismiss: () -> Unit, onJoin: (String, String) -> String?) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(R.string.channel_join),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = stringResource(R.string.join_name_note),
            style = MaterialTheme.typography.labelSmall,
        )
        TextFieldMMD(
            value = name,
            onValueChange = {
                name = it
                refusal = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = stringResource(R.string.join_key_note),
            style = MaterialTheme.typography.labelSmall,
        )
        TextFieldMMD(
            value = key,
            onValueChange = {
                key = it
                refusal = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        refusal?.let {
            Spacer(Modifier.height(10.dp))
            TextMMD(text = it, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButtonMMD(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                TextMMD(
                    text = stringResource(R.string.login_cancel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButtonMMD(
                onClick = {
                    refusal = onJoin(name, key)
                    if (refusal == null) onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                TextMMD(
                    text = stringResource(R.string.join_join),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Leaving, in the channel's own top bar. It asks in its own face, as the house does — the
 * first tap arms it and says so, a second leaves, and it disarms itself after four seconds.
 */
@Composable
fun LeaveChannelAction(onLeave: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    TextMMD(
        text = stringResource(if (armed) R.string.channel_leave_armed else R.string.channel_leave),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clickable {
                if (armed) onLeave() else armed = true
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}
