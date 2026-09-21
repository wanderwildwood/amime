package com.wanderwildwood.amime.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.amime.BuildConfig
import com.wanderwildwood.amime.R

/**
 * What this is, what it sends and where, and whose the parts are.
 *
 * A messaging app owes a stranger the line about where the messages go, and the answer here
 * is not the one anybody would guess: not a carrier, not a server, a radio.
 *
 * No source line, because there is no published repository to name. It goes in when there is.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = "Mesh ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "A companion for a MeshCore radio. Messages go over Bluetooth to the radio " +
                "beside you and out through the mesh.",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "Nothing here touches the internet. There is no account, no server and no " +
                "carrier — only the radio, and whatever it can reach.",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "GNU General Public License v3 only",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "MeshCore's protocol, implemented from reading its source. MeshCore itself is " +
                "MIT and none of it is copied here.",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "Icons from Material Symbols, Apache 2.0",
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(14.dp))
        Llama()

        Spacer(Modifier.height(18.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = "Close", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to.
 *
 * Three words rather than an address: a verb and an object, so what happens when you press
 * them is not a surprise even though the page is not named. The drawing is his own, and it is
 * ink rather than an emoji, which is a colour glyph and reaches the panel as a pale smudge.
 *
 * The Kompakt may have nothing registered for a web address, so the press is allowed to fail
 * and says so out loud rather than dying quietly.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Straight to the checkout. The Donate button on the site only leads
                // here anyway, so the page in between is a press the reader does not need.
                // The short square.link form, not the long checkout.square.site address it
                // redirects to -- the short one is what the site itself links to, so a
                // regenerated checkout follows it and a published app does not break.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://square.link/u/AGu8oT10")),
                    )
                }.onFailure {
                    Toast.makeText(
                        context,
                        "There is no browser on this phone to open that with.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .padding(vertical = 4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.llama),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        TextMMD(text = "Feed the llamas", style = MaterialTheme.typography.labelSmall)
    }
}
