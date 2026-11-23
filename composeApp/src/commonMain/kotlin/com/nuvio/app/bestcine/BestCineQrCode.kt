package com.nuvio.app.bestcine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal class BestCineQrMatrix(
    val width: Int,
    val height: Int,
    private val modules: BooleanArray,
) {
    fun isDark(x: Int, y: Int): Boolean = modules[y * width + x]
}

internal expect fun encodeBestCineQrMatrix(content: String): BestCineQrMatrix?

@Composable
internal fun BestCineQrCodeView(
    content: String,
    size: Dp = 188.dp,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) {
        if (content.isBlank()) null else encodeBestCineQrMatrix(content)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix != null && matrix.width > 0 && matrix.height > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellWidth = this.size.width / matrix.width.toFloat()
                val cellHeight = this.size.height / matrix.height.toFloat()
                val darkColor = Color(0xFF0A0E17)
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix.isDark(x, y)) {
                            drawRect(
                                color = darkColor,
                                topLeft = Offset(x * cellWidth, y * cellHeight),
                                size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
