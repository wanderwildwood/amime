package com.wanderwildwood.amime.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The house rule for showing state: solid for settled, dotted for provisional.
 *
 * Colour is not available — sixteen greys read outdoors means black on white and a hard edge
 * — and a dash survives being printed in grey, which a shade would not.
 */
fun Modifier.stateBorder(
    settled: Boolean,
    width: Dp = 1.dp,
    corner: Dp = 4.dp,
): Modifier = if (settled) {
    border(BorderStroke(width, Color.Black), RoundedCornerShape(corner))
} else {
    drawBehind {
        val stroke = width.toPx()
        drawRoundRect(
            brush = SolidColor(Color.Black),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(corner.toPx()),
            style = Stroke(
                width = stroke,
                // Long dashes on purpose: a fine dot pattern on a panel that thresholds hard
                // renders as a smudged grey line rather than as dashes.
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            ),
        )
    }
}
