package com.gemmafit.jni

import androidx.annotation.Keep
import org.json.JSONObject

/**
 * JNI Bridge: Kotlin → C++ Biomechanics Engine
 *
 * Input: float[99] (33 landmarks × 3 coords: x, y, visibility)
 * Output: JSON string with safety report, movement pattern, muscle focus
 *
 * The native library `gemmafit_kinematics` must be loaded before use.
 */
@Keep
object KinematicsBridge {

    init {
        System.loadLibrary("gemmafit_bridge")
    }

    /**
     * Process a single frame of pose landmarks through the C++ biomechanics pipeline.
     *
     * @param landmarks FloatArray of size 99 (33 keypoints × x,y,visibility)
     * @param prevLandmarks Previous frame landmarks for velocity calculation (optional)
     * @param visibilityThreshold Minimum visibility score to accept (default 0.6)
     * @return JSON string containing pattern, safety, muscle, and confidence data
     */
    @Keep
    external fun processFrame(
        landmarks: FloatArray,
        prevLandmarks: FloatArray? = null,
        visibilityThreshold: Float = 0.6f,
    ): String

    /**
     * Parsed result from the biomechanics pipeline.
     */
    data class BiomechanicsResult(
        val success: Boolean,
        val patternJson: String,
        val safetyJson: String,
        val muscleJson: String,
        val motionReportJson: String,
        val confidenceJson: String,
        val combinedJson: String,
        val gateBlocked: Boolean = false,
        val gateReason: String = "",
    )

    /**
     * Parse the raw JSON output from [processFrame] into a typed result.
     */
    fun parseResult(jsonOutput: String): BiomechanicsResult {
        return try {
            val root = JSONObject(jsonOutput)
            if (root.has("gate") && root.getString("gate") == "blocked") {
                BiomechanicsResult(
                    success = true,
                    patternJson = "",
                    safetyJson = "",
                    muscleJson = "",
                    motionReportJson = "",
                    confidenceJson = "",
                    combinedJson = jsonOutput,
                    gateBlocked = true,
                    gateReason = root.optString("reason", "visibility too low"),
                )
            } else {
                BiomechanicsResult(
                    success = true,
                    patternJson = root.optJSONObject("pattern")?.toString() ?: "",
                    safetyJson = root.optJSONObject("safety")?.toString() ?: "",
                    muscleJson = root.optJSONObject("muscle")?.toString() ?: "",
                    motionReportJson = root.optJSONObject("motion_report")?.toString() ?: "",
                    confidenceJson = root.optJSONObject("confidence")?.toString() ?: "",
                    combinedJson = jsonOutput,
                )
            }
        } catch (e: Exception) {
            BiomechanicsResult(
                success = false,
                patternJson = "",
                safetyJson = "",
                muscleJson = "",
                motionReportJson = "",
                confidenceJson = "",
                combinedJson = jsonOutput,
            )
        }
    }

    /**
     * Extract safety violations from the safety JSON for UI display.
     */
    fun extractViolations(safetyJson: String): List<SafetyViolation> {
        val violations = mutableListOf<SafetyViolation>()
        try {
            val root = JSONObject(safetyJson)
            val arr = root.optJSONArray("violations") ?: return violations
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                violations.add(
                    SafetyViolation(
                        rule = v.getInt("rule"),
                        joint = v.getString("joint"),
                        description = v.getString("description"),
                        severity = v.getDouble("severity").toFloat(),
                        value = v.getDouble("value").toFloat(),
                        threshold = v.getDouble("threshold").toFloat(),
                    )
                )
            }
        } catch (_: Exception) {}
        return violations
    }

    data class SafetyViolation(
        val rule: Int,
        val joint: String,
        val description: String,
        val severity: Float,
        val value: Float,
        val threshold: Float,
    )
}
