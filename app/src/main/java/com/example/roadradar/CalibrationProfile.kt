package com.example.roadradar

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a named calibration profile storing the 3x3 homography matrix
 * and the 4 image / world point pairs used to compute it.
 */
data class CalibrationProfile(
    val name: String,
    val homography: FloatArray,           // 9 elements, row-major
    val imagePoints: List<HomographyCalibrator.Point2f>,  // 4 image-space pins
    val worldPoints: List<HomographyCalibrator.Point2f>,  // 4 world-space metres
    val reprojectionErrorPx: Float        // quality indicator
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("homography", JSONArray(homography.map { it.toDouble() }))
        put("imagePoints", JSONArray(imagePoints.map {
            JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())
        }))
        put("worldPoints", JSONArray(worldPoints.map {
            JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())
        }))
        put("reprojectionErrorPx", reprojectionErrorPx.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): CalibrationProfile {
            val hArray = json.getJSONArray("homography")
            val homography = FloatArray(9) { hArray.getDouble(it).toFloat() }

            fun parsePoints(key: String): List<HomographyCalibrator.Point2f> {
                val arr = json.getJSONArray(key)
                return List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    HomographyCalibrator.Point2f(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
                }
            }

            return CalibrationProfile(
                name = json.getString("name"),
                homography = homography,
                imagePoints = parsePoints("imagePoints"),
                worldPoints = parsePoints("worldPoints"),
                reprojectionErrorPx = json.getDouble("reprojectionErrorPx").toFloat()
            )
        }
    }
}

/**
 * Persistence helpers — stores a list of CalibrationProfile objects in SharedPreferences.
 */
object CalibrationProfileStore {
    private const val PREFS_NAME = "road_radar_calibration_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile_name"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(ctx: Context): List<CalibrationProfile> {
        val raw = prefs(ctx).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { CalibrationProfile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun save(ctx: Context, profile: CalibrationProfile) {
        val existing = loadAll(ctx).toMutableList()
        existing.removeAll { it.name == profile.name }
        existing.add(0, profile)
        val arr = JSONArray(existing.map { it.toJson() })
        prefs(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun loadActive(ctx: Context): CalibrationProfile? {
        val activeName = prefs(ctx).getString(KEY_ACTIVE, null) ?: return null
        return loadAll(ctx).firstOrNull { it.name == activeName }
    }

    fun setActive(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, name).apply()
    }

    fun delete(ctx: Context, name: String) {
        val updated = loadAll(ctx).filter { it.name != name }
        val arr = JSONArray(updated.map { it.toJson() })
        prefs(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }
}
