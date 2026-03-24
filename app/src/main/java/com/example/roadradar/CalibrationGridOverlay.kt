package com.example.roadradar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Semi-transparent grid overlay that visualises the active calibration's
 * road-plane homography on the live camera feed.
 *
 * The grid is drawn in *world space* (metres) and projected into image
 * pixels via [HomographyProjector]. Minor gridlines are drawn every
 * [spacingMeters] metres; every 5th line is a major (brighter) line.
 *
 * Nothing is drawn when [profile] is null (no calibration saved yet).
 *
 * Typical placement in the layout stack:
 *   CameraPreview ← BoundingBoxOverlay ← **CalibrationGridOverlay** ← SpeedDisplay
 */
@Composable
fun CalibrationGridOverlay(
    profile: CalibrationProfile?,
    modifier: Modifier = Modifier,
    gridWidthMeters: Float = 12f,
    gridDepthMeters: Float = 30f,
    spacingMeters: Float = 1f,
    minorColor: Color = Color.Cyan.copy(alpha = 0.28f),
    majorColor: Color = Color.Yellow.copy(alpha = 0.42f),
    minorStroke: Float = 2f,
    majorStroke: Float = 3.5f
) {
    if (profile == null) return

    // Invert the homography once per profile instance — memoised so we don't
    // recompute on every recomposition.
    val projector = remember(profile) { HomographyProjector(profile.homography) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val xSteps = (gridWidthMeters / spacingMeters).toInt()
        val ySteps = (gridDepthMeters / spacingMeters).toInt()

        // Vertical world lines (constant x, varying depth y)
        for (i in 0..xSteps) {
            val wx = i * spacingMeters
            val isMajorX = (i % 5 == 0)
            val pts = (0..ySteps).mapNotNull { j ->
                projector.worldToImage(wx, j * spacingMeters)
                    ?.let { (px, py) -> Offset(px, py) }
            }
            drawWorldPolyline(
                points = pts,
                color = if (isMajorX) majorColor else minorColor,
                strokeWidth = if (isMajorX) majorStroke else minorStroke
            )
        }

        // Horizontal world lines (constant depth y, varying x)
        for (j in 0..ySteps) {
            val wy = j * spacingMeters
            val isMajorY = (j % 5 == 0)
            val pts = (0..xSteps).mapNotNull { i ->
                projector.worldToImage(i * spacingMeters, wy)
                    ?.let { (px, py) -> Offset(px, py) }
            }
            drawWorldPolyline(
                points = pts,
                color = if (isMajorY) majorColor else minorColor,
                strokeWidth = if (isMajorY) majorStroke else minorStroke
            )
        }
    }
}

// ── Private draw helper ──────────────────────────────────────────────────────

private fun DrawScope.drawWorldPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}
