package ro.zapakid.app.family

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR modules for [text] (error correction M, no quiet zone; the composable adds a white margin). */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
)

/** Black on white in both themes (scanners need the contrast). [description] is what TalkBack reads. */
@Composable
fun QrImage(text: String, description: String, size: Dp = 260.dp) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(
        modifier =
            Modifier
                .size(size)
                .background(Color.White)
                .padding(12.dp)
                .testTag("qr")
                .semantics { contentDescription = description }
    ) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        Color.Black,
                        Offset(x * cell, y * cell),
                        Size(cell + OVERLAP, cell + OVERLAP)
                    )
                }
            }
        }
    }
}

/** Half a pixel of overlap, so no hairline gaps show between QR modules. */
private const val OVERLAP = 0.5f
