package com.example.roadradar

import kotlin.math.abs

/**
 * Pure-Kotlin implementation of a 4-point homography solver.
 *
 * Given 4 source points (image pixel coords) and their corresponding
 * 4 destination points (real-world meters on the road plane), this class
 * computes the 3x3 homography matrix H such that:
 *
 *   [wx', wy', w]^T = H * [x, y, 1]^T
 *
 * where (x', y') = (wx'/w, wy'/w) are the road-plane coordinates in metres.
 *
 * Usage:
 *   val h = HomographyCalibrator.compute(srcPoints, dstPoints)
 *   val worldPt = HomographyCalibrator.transform(h, imagePoint)
 */
object HomographyCalibrator {

    data class Point2f(val x: Float, val y: Float)

    /**
     * Compute the 3x3 homography matrix from 4 point correspondences.
     * @param src  4 image points in pixels (top-left, top-right, bottom-right, bottom-left order)
     * @param dst  4 corresponding real-world points in metres
     * @return     FloatArray of size 9 representing the row-major 3x3 matrix, or null on failure
     */
    fun compute(src: List<Point2f>, dst: List<Point2f>): FloatArray? {
        require(src.size == 4 && dst.size == 4) { "Exactly 4 point pairs required" }

        // Build the 8x8 system A·h = b from the 4 point correspondences.
        // Each correspondence gives 2 equations.
        val A = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val sx = src[i].x.toDouble()
            val sy = src[i].y.toDouble()
            val dx = dst[i].x.toDouble()
            val dy = dst[i].y.toDouble()

            // Row for x-equation
            A[2 * i][0] = sx;  A[2 * i][1] = sy;  A[2 * i][2] = 1.0
            A[2 * i][3] = 0.0; A[2 * i][4] = 0.0; A[2 * i][5] = 0.0
            A[2 * i][6] = -dx * sx; A[2 * i][7] = -dx * sy
            b[2 * i] = dx

            // Row for y-equation
            A[2 * i + 1][0] = 0.0; A[2 * i + 1][1] = 0.0; A[2 * i + 1][2] = 0.0
            A[2 * i + 1][3] = sx;  A[2 * i + 1][4] = sy;  A[2 * i + 1][5] = 1.0
            A[2 * i + 1][6] = -dy * sx; A[2 * i + 1][7] = -dy * sy
            b[2 * i + 1] = dy
        }

        val h8 = gaussianElimination(A, b) ?: return null

        // h = [h0..h7, 1] reshaped to 3x3
        return floatArrayOf(
            h8[0].toFloat(), h8[1].toFloat(), h8[2].toFloat(),
            h8[3].toFloat(), h8[4].toFloat(), h8[5].toFloat(),
            h8[6].toFloat(), h8[7].toFloat(), 1f
        )
    }

    /**
     * Apply homography H to a single image point, returning the road-plane point in metres.
     */
    fun transform(H: FloatArray, pt: Point2f): Point2f {
        val x = pt.x.toDouble()
        val y = pt.y.toDouble()
        val w = H[6] * x + H[7] * y + H[8]
        val rx = (H[0] * x + H[1] * y + H[2]) / w
        val ry = (H[3] * x + H[4] * y + H[5]) / w
        return Point2f(rx.toFloat(), ry.toFloat())
    }

    /**
     * Compute mean reprojection error for the 4 calibration pairs (in pixels).
     * Uses inverse homography: transform world points back to image space.
     */
    fun reprojectionError(H: FloatArray, src: List<Point2f>, dst: List<Point2f>): Float {
        val Hinv = invert3x3(H) ?: return Float.MAX_VALUE
        var totalError = 0f
        for (i in 0 until 4) {
            val reprojected = transform(Hinv, dst[i])
            val dx = reprojected.x - src[i].x
            val dy = reprojected.y - src[i].y
            totalError += kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        return totalError / 4f
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun gaussianElimination(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 8
        val aug = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }

        for (col in 0 until n) {
            // Partial pivoting
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            if (abs(aug[col][col]) < 1e-10) return null // Singular

            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (k in col..n) aug[row][k] -= factor * aug[col][k]
            }
        }

        // Back substitution
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) x[i] -= aug[i][j] * x[j]
            x[i] /= aug[i][i]
        }
        return x
    }

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
