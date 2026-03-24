package com.example.roadradar

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calculates vehicle speed using a perspective-correct homography transform.
 *
 * When a [CalibrationProfile] is loaded, bounding-box centres are first
 * projected from image-pixel space onto the road plane (in metres) before
 * computing the displacement, giving physically meaningful distance values
 * regardless of camera angle or height.
 *
 * If no homography is available, the calculator falls back to the original
 * pixel-ratio method so the app is still usable before calibration.
 *
 * Each vehicle track should use its own [SpeedCalculator] instance so that
 * speed histories are never mixed across different objects.
 */
class SpeedCalculator {

    // ── Fallback (legacy) calibration ────────────────────────────────────────
    private var PIXEL_TO_METER_RATIO = 100f

    // ── Homography-based calibration ─────────────────────────────────────────
    private var homographyMatrix: FloatArray? = null

    // ── Smoothing & filtering ─────────────────────────────────────────────────
    private var SMOOTHING_FACTOR = 0.3f
    private var maxSpeedChangePct = 0.5   // Reject jumps > 50% of last speed

    private var lastCalculatedSpeed = 0.0
    private val speedHistory = mutableListOf<Double>()
    private val maxHistorySize = 5

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load a saved calibration profile. Once set, all speed calculations use
     * perspective-correct homography instead of the flat pixel ratio.
     */
    fun loadCalibrationProfile(profile: CalibrationProfile) {
        homographyMatrix = profile.homography
        Log.i("SpeedCalculator", "Loaded calibration profile '${profile.name}' " +
                "(reprojErr=${profile.reprojectionErrorPx} px)")
    }

    fun clearCalibrationProfile() {
        homographyMatrix = null
    }

    /**
     * Calculate speed from two consecutive bounding boxes.
     *
     * @param previousBox       Bounding box in the previous frame (image pixels)
     * @param currentBox        Bounding box in the current frame (image pixels)
     * @param imageWidth        Camera image width in pixels
     * @param timeDeltaSeconds  Elapsed time between the two frames
     * @return Smoothed speed in km/h
     */
    fun calculateSpeed(
        previousBox: Rect,
        currentBox: Rect,
        imageWidth: Int,
        timeDeltaSeconds: Double
    ): Double {
        if (timeDeltaSeconds <= 0) return lastCalculatedSpeed

        val distanceMeters = if (homographyMatrix != null) {
            homographyDistance(previousBox, currentBox)
        } else {
            pixelDistance(previousBox, currentBox)
        }

        val speedMps   = distanceMeters / timeDeltaSeconds
        val rawSpeedKmh = speedMps * 3.6

        // Outlier rejection — reject implausible jumps relative to last reading
        val filteredSpeed = if (lastCalculatedSpeed > 5.0 &&
            abs(rawSpeedKmh - lastCalculatedSpeed) > lastCalculatedSpeed * maxSpeedChangePct) {
            lastCalculatedSpeed
        } else {
            rawSpeedKmh
        }

        speedHistory.add(filteredSpeed)
        if (speedHistory.size > maxHistorySize) speedHistory.removeAt(0)

        val medianSpeed = median(speedHistory)
        val smoothed = lastCalculatedSpeed * (1 - SMOOTHING_FACTOR) + medianSpeed * SMOOTHING_FACTOR
        lastCalculatedSpeed = smoothed

        Log.d("SpeedCalc", "dist=${"%.3f".format(distanceMeters)}m " +
                "raw=${"%.1f".format(rawSpeedKmh)} smooth=${"%.1f".format(smoothed)} km/h")
        return smoothed
    }

    fun reset() {
        lastCalculatedSpeed = 0.0
        speedHistory.clear()
    }

    // ── Legacy setters (kept for backward-compat with CalibrationSettingsScreen) ──
    fun setCalibration(pixelsPerMeter: Float)  { PIXEL_TO_METER_RATIO = pixelsPerMeter }
    fun setVehicleWidth(widthMeters: Float)    { /* absorbed into homography */ }
    fun setCameraHeight(heightMeters: Float)   { /* absorbed into homography */ }
    fun setSmoothingFactor(factor: Float)      { SMOOTHING_FACTOR = factor.coerceIn(0.1f, 1.0f) }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun homographyDistance(prev: Rect, curr: Rect): Double {
        val H = homographyMatrix!!
        val prevWorld = HomographyCalibrator.transform(
            H, HomographyCalibrator.Point2f(prev.exactCenterX(), prev.exactCenterY())
        )
        val currWorld = HomographyCalibrator.transform(
            H, HomographyCalibrator.Point2f(curr.exactCenterX(), curr.exactCenterY())
        )
        val dx = (currWorld.x - prevWorld.x).toDouble()
        val dy = (currWorld.y - prevWorld.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun pixelDistance(prev: Rect, curr: Rect): Double {
        val deltaX = (curr.exactCenterX() - prev.exactCenterX()).toDouble()
        val deltaY = (curr.exactCenterY() - prev.exactCenterY()).toDouble()
        val pixelDist = sqrt(deltaX * deltaX + deltaY * deltaY)
        return pixelDist / PIXEL_TO_METER_RATIO
    }

    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        return if (sorted.size % 2 == 0) {
            val mid = sorted.size / 2
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
    }
}
