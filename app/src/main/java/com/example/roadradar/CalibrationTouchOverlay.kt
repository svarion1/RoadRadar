package com.example.roadradar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint as NativePaint

/**
 * A full-screen Compose canvas that lets the user drag 4 pins to define
 * the calibration quadrilateral on the road image.
 *
 * The pins are identified as:
 *   0 = Top-Left     1 = Top-Right
 *   2 = Bottom-Right 3 = Bottom-Left
 *
 * @param pins          Current list of 4 pin positions in image-fraction coords (0..1)
 * @param onPinsChanged Callback fired after each drag with the updated pin list
 * @param imageWidthPx  Actual image width in pixels (for coordinate mapping)
 * @param imageHeightPx Actual image height in pixels
 */
@Composable
fun CalibrationTouchOverlay(
    pins: List<HomographyCalibrator.Point2f>,
    onPinsChanged: (List<HomographyCalibrator.Point2f>) -> Unit,
    modifier: Modifier = Modifier
) {
    val pinLabels = listOf("TL", "TR", "BR", "BL")
    val pinColors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val pinRadius = 24.dp
    var draggingIndex by remember { mutableStateOf(-1) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pins) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Find closest pin within tap radius
                        val radiusPx = pinRadius.toPx()
                        draggingIndex = pins.indexOfFirst { pin ->
                            val px = pin.x * size.width
                            val py = pin.y * size.height
                            val dx = offset.x - px
                            val dy = offset.y - py
                            kotlin.math.sqrt(dx * dx + dy * dy) < radiusPx * 1.5f
                        }
                    },
                    onDrag = { change, _ ->
                        if (draggingIndex >= 0) {
                            val newX = (change.position.x / size.width).coerceIn(0f, 1f)
                            val newY = (change.position.y / size.height).coerceIn(0f, 1f)
                            val updated = pins.toMutableList()
                            updated[draggingIndex] = HomographyCalibrator.Point2f(newX, newY)
                            onPinsChanged(updated)
                        }
                    },
                    onDragEnd = { draggingIndex = -1 }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val rPx = pinRadius.toPx()

        // Convert fraction pins to canvas pixels
        val offsets = pins.map { Offset(it.x * w, it.y * h) }

        // Draw connecting quad lines
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        for (i in offsets.indices) {
            val next = offsets[(i + 1) % 4]
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = offsets[i],
                end = next,
                strokeWidth = 2.dp.toPx(),
                pathEffect = dashEffect
            )
        }

        // Draw pin circles and labels
        offsets.forEachIndexed { i, offset ->
            val pinColor = pinColors[i]
            drawCircle(color = pinColor.copy(alpha = 0.3f), radius = rPx, center = offset)
            drawCircle(
                color = pinColor,
                radius = rPx,
                center = offset,
                style = Stroke(width = 3.dp.toPx())
            )
            // Cross-hair
            drawLine(pinColor, offset.copy(y = offset.y - rPx * 0.6f), offset.copy(y = offset.y + rPx * 0.6f), 2.dp.toPx())
            drawLine(pinColor, offset.copy(x = offset.x - rPx * 0.6f), offset.copy(x = offset.x + rPx * 0.6f), 2.dp.toPx())

            // Label
            drawContext.canvas.nativeCanvas.drawText(
                pinLabels[i],
                offset.x + rPx + 4.dp.toPx(),
                offset.y + 6.dp.toPx(),
                NativePaint().apply {
                    textSize = 14.sp.toPx()
                    this.color = android.graphics.Color.WHITE
                    isFakeBoldText = true
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }
            )
        }
    }
}
