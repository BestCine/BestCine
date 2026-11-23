package com.nuvio.app.bestcine

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal actual fun encodeBestCineQrMatrix(content: String): BestCineQrMatrix? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
    )
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = bitMatrix.width
    val h = bitMatrix.height
    val modules = BooleanArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            modules[y * w + x] = bitMatrix.get(x, y)
        }
    }
    BestCineQrMatrix(width = w, height = h, modules = modules)
}.getOrNull()
