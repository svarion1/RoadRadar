package com.example.roadradar

import kotlin.math.abs

/**
 * Projects world-plane metres → image-space pixels using the *inverse* of
 * the 3×3 homography stored in a [CalibrationProfile].
 *
 * The forward homography H maps image pixels → world metres (as used by
 * [SpeedCalculator]). To draw a grid we need the reverse: world → pixels.
 * We invert H once at construction time and apply it per point.
 */
class HomographyProjector(homography: FloatArray) {

    private val hinv: FloatArray? = invert3x3(homography)

    /**
     * Maps a world-plane point (in metres) to an image-space pixel position.
     * Returns null if the projection is behind the camera (w ≤ 0) or if the
     * calibration matrix is singular.
     */
    fun worldToImage(worldX: Float, worldY: Float): Pair<Float, Float>? {
        val m = hinv ?: return null
        val x = worldX.toDouble()
        val y = worldY.toDouble()
        val pw = m[6] * x + m[7] * y + m[8]
        if (pw == 0.0) return null
        val px = (m[0] * x + m[1] * y + m[2]) / pw
        val py = (m[3] * x + m[4] * y + m[5]) / pw
        return px.toFloat() to py.toFloat()
    }

    // ── Matrix helpers ───────────────────────────────────────────────────────

    private fun invert3x3(m: FloatArray): FloatArray? {
        val det = (m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6])).toDouble()
        if (abs(det) < 1e-10) return null
        val inv = FloatArray(9)
        inv[0] = ((m[4] * m[8] - m[5] * m[7]) / det).toFloat()
        inv[1] = ((m[2] * m[7] - m[1] * m[8]) / det).toFloat()
        inv[2] = ((m[1] * m[5] - m[2] * m[4]) / det).toFloat()
        inv[3] = ((m[5] * m[6] - m[3] * m[8]) / det).toFloat()
        inv[4] = ((m[0] * m[8] - m[2] * m[6]) / det).toFloat()
        inv[5] = ((m[2] * m[3] - m[0] * m[5]) / det).toFloat()
        inv[6] = ((m[3] * m[7] - m[4] * m[6]) / det).toFloat()
        inv[7] = ((m[1] * m[6] - m[0] * m[7]) / det).toFloat()
        inv[8] = ((m[0] * m[4] - m[1] * m[3]) / det).toFloat()
        return inv
    }
}
