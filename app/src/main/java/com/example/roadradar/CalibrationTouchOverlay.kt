package com.example.roadradar

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Full-screen Compose canvas for dragging 4 calibration pins.
 *
 * Pin order:  0=Top-Left  1=Top-Right  2=Bottom-Right  3=Bottom-Left
 * Positions stored as fractions (0..1) of canvas size.
 *
 * Architecture note — why localPins:
 *   pointerInput(Unit) captures a closure once and never re-captures.
 *   Reading the `pins` parameter inside that closure always returns the
 *   value from the *first* composition — a stale closure. Deltas applied
 *   to the stale value are immediately overwritten by the fresh recomposition,
 *   producing the "magnetic snap-back" bug.
 *
 *   The fix: `localPins` is a MutableState<List<...>> owned by this composable.
 *   The gesture handler reads and writes it directly (always fresh via State read).
 *   LaunchedEffect(pins) syncs external → local whenever the parent legitimately
 *   changes the list (initial load, profile switch) but is never triggered by
 *   our own drag updates because we don't touch the parent `pins` parameter
 *   during a drag — only via onPinsChanged which fires upward.
 */
@Composable
fun CalibrationTouchOverlay(
    pins: List<HomographyCalibrator.Point2f>,
    onPinsChanged: (List<HomographyCalibrator.Point2f>) -> Unit,
    modifier: Modifier = Modifier
) {
    val pinLabels   = listOf("TL", "TR", "BR", "BL")
    val pinColors   = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val pinRadiusDp = 28.dp

    // Internal source-of-truth for the gesture handler — never stale.
    var localPins by remember { mutableStateOf(pins) }
    val draggingIndex = remember { mutableIntStateOf(-1) }

    // Sync external changes in (initial composition, profile load) → local.
    // This does NOT fire for our own onPinsChanged callbacks because the
    // parent re-sets `pins` with the same values we already wrote locally.
    LaunchedEffect(pins) {
        localPins = pins
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val radiusPx = pinRadiusDp.toPx()
                        // Read localPins — always fresh, never stale
                        draggingIndex.intValue = localPins.indexOfFirst { pin ->
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
                            // Read current position from localPins (fresh State read)
                            val current = localPins[idx]
                            val newX = (current.x + dragAmount.x / size.width ).coerceIn(0f, 1f)
                            val newY = (current.y + dragAmount.y / size.height).coerceIn(0f, 1f)
                            val updated = localPins.toMutableList()
                            updated[idx] = HomographyCalibrator.Point2f(newX, newY)
                            // Write locally first — instant visual update
                            localPins = updated
                            // Notify parent to keep its state in sync
                            onPinsChanged(updated)
                        }
                    },
                    onDragEnd    = { draggingIndex.intValue = -1 },
                    onDragCancel = { draggingIndex.intValue = -1 }
                )
            }
    ) {
        val w   = size.width
        val h   = size.height
        val rPx = pinRadiusDp.toPx()

        // Draw from localPins so the canvas is always in sync with gesture state
        val offsets = localPins.map { Offset(it.x * w, it.y * h) }

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
            val pinColor = pinColors[i]
            val isActive = draggingIndex.intValue == i

            if (isActive) {
                drawCircle(color = Color.White.copy(alpha = 0.25f), radius = rPx * 1.6f, center = offset)
            }
            drawCircle(color = pinColor.copy(alpha = 0.35f), radius = rPx, center = offset)
            drawCircle(color = pinColor, radius = rPx, center = offset, style = Stroke(width = 3.dp.toPx()))

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
