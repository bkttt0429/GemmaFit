package com.gemmafit.jni

import androidx.annotation.Keep
import org.json.JSONObject

/**
 * JNI Bridge: Kotlin → C++ llama.cpp Engine
 *
 * Sends biomechanics JSON to Gemma 4 (GGUF) via llama.cpp
 * and receives Function Calling results.
 *
 * The native library `gemmafit_llm` must be loaded before use.
 */
@Keep
object LLMBridge {

    init {
        System.loadLibrary("gemmafit_bridge")
    }

    /**
     * Run inference on the biomechanics data.
     *
     * @param movementPatternJson Movement pattern from KinematicsBridge
     * @param safetyJson Safety report from KinematicsBridge
     * @param muscleJson Muscle focus from KinematicsBridge
     * @param modelPath Absolute path to the GGUF model file
     * @param conversationHistory Optional previous interactions for context
     * @return JSON string with the selected function call
     */
    @Keep
    external fun runInference(
        movementPatternJson: String,
        safetyJson: String,
        muscleJson: String,
        modelPath: String,
        conversationHistory: String = "",
    ): String

    /**
     * Check if the model file exists and is valid.
     */
    @Keep
    external fun validateModel(modelPath: String): Boolean

    /**
     * Get model info (context size, vocab size, etc.) for debugging.
     */
    @Keep
    external fun getModelInfo(modelPath: String): String

    data class FunctionCallResult(
        val success: Boolean,
        val functionName: String,
        val argsJson: String,
        val rawResponse: String,
        val inferenceTimeMs: Double,
        val errorMessage: String = "",
    )

    /**
     * Parse the raw inference output into a structured result.
     */
    fun parseFunctionCall(jsonOutput: String): FunctionCallResult {
        return try {
            val root = JSONObject(jsonOutput)
            FunctionCallResult(
                success = root.optBoolean("success", false),
                functionName = root.optString("function", ""),
                argsJson = root.optJSONObject("args")?.toString() ?: "{}",
                rawResponse = root.optString("raw_response", ""),
                inferenceTimeMs = root.optDouble("inference_time_ms", 0.0),
                errorMessage = root.optString("error", ""),
            )
        } catch (e: Exception) {
            FunctionCallResult(
                success = false,
                functionName = "",
                argsJson = "{}",
                rawResponse = jsonOutput,
                inferenceTimeMs = 0.0,
                errorMessage = e.message ?: "Parse error",
            )
        }
    }

    /**
     * Map function name to human-readable coaching message.
     */
    fun getCoachingMessage(functionName: String, argsJson: String): String {
        return when (functionName) {
            "correct_knee_alignment" -> "Keep knees tracking over your toes."
            "correct_spinal_alignment" -> "Maintain a neutral spine. Avoid rounding your back."
            "correct_joint_angle" -> "Don't lock your joints. Keep a slight bend."
            "correct_asymmetry" -> "Try to keep both sides even."
            "warn_com_offset" -> "Center your weight. Stay balanced."
            "warn_rapid_movement" -> "Slow down. Control the movement."
            "increase_range_of_motion" -> "Move through the full range if comfortable."
            "positive_reinforcement" -> "Good form! Keep it up."
            else -> "Adjust your form for better safety."
        }
    }
}