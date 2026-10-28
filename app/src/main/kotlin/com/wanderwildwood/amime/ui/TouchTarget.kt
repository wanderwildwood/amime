package com.wanderwildwood.amime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [clickable], at least 48 dp tall: a row or a link of one short line would otherwise be as tall
 * as its text and padding, under what a finger can be trusted to hit. The content stays centred
 * in the extra height, so put this before the content's own padding.
 */
fun Modifier.clickableTarget(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    heightIn(min = 48.dp)
        .clickable(enabled = enabled, onClick = onClick)
        .wrapContentHeight(Alignment.CenterVertically)
