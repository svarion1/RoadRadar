package com.example.roadradar

import android.graphics.Rect
import android.util.Log

/**
 * Manages one [SpeedCalculator] per tracked vehicle, keyed by ML Kit's [trackingId].
 *
 * ML Kit STREAM_MODE assigns a stable integer [trackingId] to each object across
 * consecutive frames, so we use it as a natural key. When a track disappears for
 * longer than [staleThresholdMs] it is pruned to avoid unbounded memory growth.
 *
 * The active [CalibrationProfile] is propagated to every new calculator that is
 * created, so a mid-session recalibration takes effect immediately for any new
 * vehicle that enters the frame.
 */
class VehicleTracker {

    data class TrackState(
        val calculator: SpeedCalculator,
        val lastBoundingBox: Rect,
        val lastTimestampMs: Long
    )

    private val tracks = mutableMapOf<Int, TrackState>()
    private var activeProfile: CalibrationProfile? = null
    private val staleThresholdMs = 2_000L

    // ── Calibration ──────────────────────────────────────────────────────────

    /**
     * Load (or clear) a calibration profile. All existing calculators and every
     * new one created afterwards will use this profile.
     */
    fun loadCalibrationProfile(profile: CalibrationProfile?) {
        activeProfile = profile
        tracks.values.forEach { state ->
            if (profile != null) state.calculator.loadCalibrationProfile(profile)
            else state.calculator.clearCalibrationProfile()
        }
        Log.i("VehicleTracker", "Profile ${profile?.name ?: "<none>"} applied to " +
                "${tracks.size} existing tracks")
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    /**
     * Process one frame's worth of detections.
     *
     * @param detections   List of (trackingId, boundingBox) pairs for the current frame
     * @param imageWidth   Camera image width in pixels
     * @param timestampMs  Wall-clock time of this frame
     * @return             Map of trackingId → smoothed speed in km/h for every
     *                     active track that had a previous frame to compare against
     */
    fun update(
        detections: List<Pair<Int, Rect>>,
        imageWidth: Int,
        timestampMs: Long
    ): Map<Int, Double> {
        val speeds = mutableMapOf<Int, Double>()

        for ((trackId, boundingBox) in detections) {
            val existing = tracks[trackId]

            if (existing != null) {
                // We have a previous frame for this track — calculate speed
                val timeDelta = (timestampMs - existing.lastTimestampMs) / 1000.0
                if (timeDelta > 0) {
                    val speed = existing.calculator.calculateSpeed(
                        previousBox = existing.lastBoundingBox,
                        currentBox = boundingBox,
                        imageWidth = imageWidth,
                        timeDeltaSeconds = timeDelta
                    )
                    speeds[trackId] = speed
                }
                // Update state
                tracks[trackId] = existing.copy(
                    lastBoundingBox = boundingBox,
                    lastTimestampMs = timestampMs
                )
            } else {
                // First time we see this track — create a fresh calculator
                val calc = SpeedCalculator()
                activeProfile?.let { calc.loadCalibrationProfile(it) }
                tracks[trackId] = TrackState(
                    calculator = calc,
                    lastBoundingBox = boundingBox,
                    lastTimestampMs = timestampMs
                )
                Log.d("VehicleTracker", "New track #$trackId created (total: ${tracks.size})")
            }
        }

        // Prune stale tracks
        val activeIds = detections.map { it.first }.toSet()
        val staleIds = tracks.keys
            .filter { id ->
                id !in activeIds &&
                (timestampMs - (tracks[id]?.lastTimestampMs ?: 0L)) > staleThresholdMs
            }
        staleIds.forEach { id ->
            tracks.remove(id)
            Log.d("VehicleTracker", "Track #$id pruned (stale). Remaining: ${tracks.size}")
        }

        return speeds
    }

    /** Number of currently live tracks. */
    val trackCount: Int get() = tracks.size

    /** Reset all tracks (e.g. on app resume). */
    fun reset() {
        tracks.clear()
    }
}
