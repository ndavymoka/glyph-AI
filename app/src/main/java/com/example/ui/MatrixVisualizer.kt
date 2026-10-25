package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.glyph.PixelMatrixRenderer
import com.example.ui.theme.MatrixHousingBg
import com.example.ui.theme.MatrixLedOff
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingRed
import kotlinx.coroutines.flow.StateFlow

/**
 * Authentic 25x25 LED Glyph Matrix visualizer replicating the circular rear LED array of Nothing Phone (3).
 * Collects `matrixFrameFlow` locally so only the Canvas redraws when pixels change, keeping the rest of
 * the screen completely free of recompositions and silky-smooth at 120Hz.
 */
@Composable
fun GlyphMatrix25x25Visualizer(
    matrixFrameFlow: StateFlow<IntArray>,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val framePixels by matrixFrameFlow.collectAsStateWithLifecycle()
    val ringBorderColor = remember(isListening) { if (isListening) NothingRed else NothingBorder }

    // Precompute circular mask once so the 25x25 Canvas loop does zero math per frame
    val insideMask = remember {
        BooleanArray(PixelMatrixRenderer.TOTAL_PIXELS) { idx ->
            val x = idx % PixelMatrixRenderer.MATRIX_SIZE
            val y = idx / PixelMatrixRenderer.MATRIX_SIZE
            PixelMatrixRenderer.isInsideGlyphCircle(x, y)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MatrixHousingBg)
            .border(2.dp, ringBorderColor, CircleShape)
            .padding(14.dp)
            .testTag("glyph_matrix_25x25_canvas")
            .semantics {
                contentDescription = "Nothing Phone 3 25x25 Glyph Matrix Display"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pixels = framePixels
            val gridCount = PixelMatrixRenderer.MATRIX_SIZE // 25
            val cellWidth = size.width / gridCount
            val cellHeight = size.height / gridCount
            val cellSize = minOf(cellWidth, cellHeight)
            val ledRadius = cellSize * 0.36f
            val offLedRadius = ledRadius * 0.82f
            val glowRadius = cellSize * 0.54f

            drawCircle(
                color = Color(0xFF16161E),
                radius = size.minDimension * 0.495f,
                style = Stroke(width = 1.5f)
            )

            for (y in 0 until gridCount) {
                val cy = (y + 0.5f) * cellHeight
                val rowOffset = y * gridCount
                for (x in 0 until gridCount) {
                    val idx = rowOffset + x
                    if (!insideMask[idx]) continue

                    val cx = (x + 0.5f) * cellWidth
                    val center = Offset(cx, cy)

                    val rawBrightness = if (idx < pixels.size) pixels[idx] else 0
                    if (rawBrightness > 6) {
                        val normalized = (rawBrightness.coerceAtMost(255)) / 255f
                        drawCircle(
                            color = Color.White.copy(alpha = normalized * 0.22f),
                            radius = glowRadius,
                            center = center
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.25f + 0.75f * normalized),
                            radius = ledRadius,
                            center = center
                        )
                    } else {
                        drawCircle(
                            color = MatrixLedOff,
                            radius = offLedRadius,
                            center = center
                        )
                    }
                }
            }
        }
    }
}
