package com.wanderwildwood.amime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.link.RadioKind

/** "Meshtastic radio" or "MeshCore radio": which firmware is on the other end of the link. */
@Composable
fun radioSubtitle(kind: RadioKind): String = stringResource(
    if (kind == RadioKind.MESHTASTIC) R.string.radio_subtitle_meshtastic else R.string.radio_subtitle_meshcore,
)

/** A bar title with, when [radio] is known, a small line under it saying which kind of radio is connected. */
@Composable
fun BarTitle(title: String, radio: RadioKind? = null) {
    if (radio == null) {
        TextMMD(text = title)
    } else {
        Column {
            TextMMD(text = title)
            TextMMD(text = radioSubtitle(radio), style = MaterialTheme.typography.labelSmall)
        }
    }
}
