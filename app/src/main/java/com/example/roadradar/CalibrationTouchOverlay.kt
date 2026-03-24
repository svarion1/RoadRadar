package com.example.roadradar

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A full-screen Compose canvas letting the user drag 4 pins to define
 * the calibration quadrilateral on the frozen road image.
 *
 * Pin order:  0=Top-Left  1=Top-Right  2=Bottom-Right  3=Bottom-Left
 *
 * Coordinates are stored as fractions (0..1) relative to the canvas size
 * so they stay correct regardless of device resolution.
 *
 * Fix notes vs previous version:
 *  - onDrag now uses `dragAmount` (delta) instead of `change.position`
 *    (absolute) so pins move 1:1 with the finger.
 *  - pointerInput key is `Unit` (not `pins`) so the gesture detector is
 *    never torn down mid-drag when pin state updates.
 *  - Hit-target radius multiplier raised to 2.5× for easier picking.
 *  - Visual pin radius increased to 28 dp.
 */
@Composable
fun CalibrationTouchOverlay(
    pins: List<HomographyCalibrator.Point2f>,
    onPinsChanged: (List<HomographyCalibrator.Point2f>) -> Unit,
    modifier: Modifier = Modifier
) {
    val pinLabels  = listOf("TL", "TR", "BR", "BL")
    val pinColors  = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val pinRadiusDp = 28.dp

    // Keep draggingIndex in a State so it survives recompositions without
    // restarting the gesture detector.
    val draggingIndex = remember { mutableIntStateOf(-1) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {                      // ← Unit key: detector lives forever
                detectDragGestures(
                    onDragStart = { offset ->
                        val radiusPx = pinRadiusDp.toPx()
                        // Find the closest pin within a generous hit area
                        draggingIndex.intValue = pins.indexOfFirst { pin ->
                            val px = pin.x * size.width
                            val py = pin.y * size.height
                            val dx = offset.x - px
                            val dy = offset.y - py
                            kotlin.math.sqrt(dx * dx + dy * dy) < radiusPx * 2.5f
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val idx = draggingIndex.intValue
                        if (idx >= 0) {
                            val current = pins[idx]
                            // Translate delta pixels to fraction-of-canvas
                            val newX = (current.x + dragAmount.x / size.width ).coerceIn(0f, 1f)
                            val newY = (current.y + dragAmount.y / size.height).coerceIn(0f, 1f)
                            val updated = pins.toMutableList()
                            updated[idx] = HomographyCalibrator.Point2f(newX, newY)
                            onPinsChanged(updated)
                        }
                    },
                    onDragEnd   = { draggingIndex.intValue = -1 },
                    onDragCancel = { draggingIndex.intValue = -1 }
                )
            }
    ) {
        val w    = size.width
        val h    = size.height
        val rPx  = pinRadiusDp.toPx()

        val offsets = pins.map { Offset(it.x * w, it.y * h) }

        // Dashed quad outline
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        for (i in offsets.indices) {
            drawLine(
                color       = Color.White.copy(alpha = 0.7f),
                start       = offsets[i],
                end         = offsets[(i + 1) % 4],
                strokeWidth = 2.dp.toPx(),
                pathEffect  = dashEffect
            )
        }

        // Pin circles, crosshairs, labels
        offsets.forEachIndexed { i, offset ->
            val pinColor  = pinColors[i]
            val isActive  = draggingIndex.intValue == i

            // Highlight ring when actively dragged
            if (isActive) {
                drawCircle(
                    color  = Color.White.copy(alpha = 0.25f),
                    radius = rPx * 1.6f,
                    center = offset
                )
            }

            drawCircle(color = pinColor.copy(alpha = 0.35f), radius = rPx,  center = offset)
            drawCircle(
                color       = pinColor,
                radius      = rPx,
                center      = offset,
                style       = Stroke(width = 3.dp.toPx())
            )
            // Crosshair
            drawLine(pinColor, offset.copy(y = offset.y - rPx * 0.6f), offset.copy(y = offset.y + rPx * 0.6f), 2.dp.toPx())
            drawLine(pinColor, offset.copy(x = offset.x - rPx * 0.6f), offset.copy(x = offset.x + rPx * 0.6f), 2.dp.toPx())

            // Label
            drawContext.canvas.nativeCanvas.drawText(
                pinLabels[i],
                offset.x + rPx + 4.dp.toPx(),
                offset.y + 6.dp.toPx(),
                NativePaint().apply {
                    textSize = 14.sp.toPx()
                    color    = android.graphics.Color.WHITE
                    isFakeBoldText = true
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }
            )
        }
    }
}
