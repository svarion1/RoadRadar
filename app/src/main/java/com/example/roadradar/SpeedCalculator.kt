package com.example.roadradar

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Handles vehicle speed calculation logic based on changes in bounding box dimensions
 * and positions between frames
 */
class SpeedCalculator {

    /**
     * Calibration constants - these would be adjusted based on camera positioning,
     * lens characteristics, and real-world testing
     */
    private var VEHICLE_WIDTH_METERS = 1.8f     // Average vehicle width in meters
    private var CAMERA_HEIGHT_METERS = 1.5f     // Height of camera above ground
    private var PIXEL_TO_METER_RATIO = 100f     // Default calibration factor - pixels per meter

    // Smoothing factor for speed calculations (0.0-1.0)
    // Lower value = more smoothing
    private var SMOOTHING_FACTOR = 0.3f

    // Store previous measurements for smoothing and filtering
    private var lastCalculatedSpeed = 0.0
    private val speedHistory = mutableListOf<Double>()
    private val maxHistorySize = 5 // Number of speed readings to keep for filtering

    // Speed threshold for outlier rejection (km/h)
    private val maxSpeedChange = 20.0

    /**
     * Calculate vehicle speed based on changes in bounding box
     *
     * @param previousBox The vehicle's bounding box in the previous frame
     * @param currentBox The vehicle's bounding box in the current frame
     * @param imageWidth Width of the camera image in pixels
     * @param imageHeight Height of the camera image in pixels
     * @param timeDeltaSeconds Time between frames in seconds
     * @return Estimated speed in km/h
     */
    fun calculateSpeed(
        previousBox: Rect,
        currentBox: Rect,
        imageWidth: Int,
        timeDeltaSeconds: Double
    ): Double {
        if (timeDeltaSeconds <= 0) return lastCalculatedSpeed

        // Calculate the center points of both boxes
        val prevCenterX = previousBox.exactCenterX()
        val prevCenterY = previousBox.exactCenterY()
        val currentCenterX = currentBox.exactCenterX()
        val currentCenterY = currentBox.exactCenterY()

        // Calculate pixel movement
        val deltaX = currentCenterX - prevCenterX
        val deltaY = currentCenterY - prevCenterY
        val pixelDistance = sqrt(deltaX.pow(2) + deltaY.pow(2))

        // Get real-world distance using calibration factor (convert pixels to meters)
        val distanceMeters = pixelDistance / PIXEL_TO_METER_RATIO

        // Alternative calculation using apparent size
        // Size-based approach is often more accurate for objects moving toward/away from camera
        val prevWidth = previousBox.width().toFloat()
        val currentWidth = currentBox.width().toFloat()

        // Calculate potential distance using size change
        // Only use if significant size change detected
        val sizeRatio = if (prevWidth > 0 && currentWidth > 0) prevWidth / currentWidth else 1f
        val sizeBasedDistance = if (abs(sizeRatio - 1f) > 0.02f) {
            VEHICLE_WIDTH_METERS * abs(sizeRatio - 1f) * (imageWidth / max(prevWidth, currentWidth))
        } else {
            // Default to basic pixel distance if size change is minimal
            distanceMeters
        }

        // Combine both methods, favoring size-based for front/rear movement
        // and pixel-based for lateral movement
        val estimatedDistanceMeters = distanceMeters * 0.7 + sizeBasedDistance * 0.3

        // Calculate speed in m/s
        val speedMps = estimatedDistanceMeters / timeDeltaSeconds

        // Convert to km/h
        val rawSpeedKmh = speedMps * 3.6

        // Filter out unrealistic speed changes (outliers)
        val filteredSpeed = if (lastCalculatedSpeed > 0 &&
            abs(rawSpeedKmh - lastCalculatedSpeed) > maxSpeedChange) {
            lastCalculatedSpeed
        } else {
            rawSpeedKmh
        }

        // Add to history for further filtering
        speedHistory.add(filteredSpeed)
        if (speedHistory.size > maxHistorySize) {
            speedHistory.removeAt(0)
        }

        // Calculate median speed from history (more robust than average)
        val sortedSpeeds = speedHistory.sorted()
        val medianSpeed = if (speedHistory.size % 2 == 0) {
            val mid = speedHistory.size / 2
            (sortedSpeeds[mid - 1] + sortedSpeeds[mid]) / 2
        } else {
            sortedSpeeds[speedHistory.size / 2]
        }
        Log.d("SpeedCalc", "Pixel distance: $pixelDistance, Distance meters: $distanceMeters, Time delta: $timeDeltaSeconds, Raw speed: $rawSpeedKmh")
        // Apply exponential smoothing
        val smoothedSpeed = lastCalculatedSpeed * (1 - SMOOTHING_FACTOR) + medianSpeed * SMOOTHING_FACTOR

        // Store for next calculation
        lastCalculatedSpeed = smoothedSpeed

        return smoothedSpeed
    }

    /**
     * Reset the speed calculator state
     */
    fun reset() {
        lastCalculatedSpeed = 0.0
        speedHistory.clear()
    }

    /**
     * Set calibration factor (pixels per meter)
     */
    fun setCalibration(pixelsPerMeter: Float) {
        this.PIXEL_TO_METER_RATIO = pixelsPerMeter
    }

    /**
     * Set average vehicle width used for calculation
     */
    fun setVehicleWidth(widthMeters: Float) {
        this.VEHICLE_WIDTH_METERS = widthMeters
    }

    /**
     * Set camera height from ground
     */
    fun setCameraHeight(heightMeters: Float) {
        this.CAMERA_HEIGHT_METERS = heightMeters
    }

    /**
     * Set smoothing factor
     */
    fun setSmoothingFactor(factor: Float) {
        this.SMOOTHING_FACTOR = factor.coerceIn(0.1f, 1.0f)
    }
}