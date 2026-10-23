package com.wanderwildwood.amime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The dark modules of a QR code for [text], row by row, or null when the text does not fit in one. */
fun qrModules(text: String): List<BooleanArray>? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
    )
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}.getOrNull()

/**
 * A QR code, black on white with the four-module quiet zone around it, as large as the width
 * allows. Drawn in whole device pixels per module so no edge is blurred, and with the white
 * square always painted: a code on the grey of a panel will not scan.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) } ?: return
    val n = modules.size + 8
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val cell = kotlin.math.floor(size.width / n).coerceAtLeast(1f)
        val side = cell * n
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        drawRect(Color.White, Offset(left, top), Size(side, side))
        for (y in modules.indices) for (x in modules[y].indices) {
            if (modules[y][x]) {
                drawRect(Color.Black, Offset(left + (x + 4) * cell, top + (y + 4) * cell), Size(cell, cell))
            }
        }
    }
}
