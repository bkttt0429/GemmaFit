package com.gemmafit.video

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable

data class PersonProposal(
    val bbox: PoseBoundingBox,
    val score: Float,
    val source: String,
    val labelId: Int = 0,
) {
    val centerX: Float get() = (bbox.minX + bbox.maxX) / 2f
    val centerY: Float get() = (bbox.minY + bbox.maxY) / 2f
}

data class PersonDetectionResult(
    val source: String,
    val available: Boolean,
    val requested: Boolean,
    val proposals: List<PersonProposal> = emptyList(),
    val latencyMs: Long = 0L,
    val reason: String = "",
    val error: String = "",
)

interface PersonDetector : Closeable {
    val source: String
    val isAvailable: Boolean

    fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        predictedBbox: PoseBoundingBox? = null,
    ): PersonDetectionResult
}

class DisabledPersonDetector(
    private val disabledReason: String = "person_detector_model_missing",
) : PersonDetector {
    override val source: String = "none"
    override val isAvailable: Boolean = false

    override fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        predictedBbox: PoseBoundingBox?,
    ): PersonDetectionResult {
        return PersonDetectionResult(
            source = source,
            available = false,
            requested = true,
            reason = disabledReason,
        )
    }

    override fun close() = Unit
}

object PersonDetectorFactory {
    private const val TAG = "GemmaFit.PersonDetector"

    private val onnxAssetCandidates = listOf(
        "yolo26n_person_384.onnx",
        "yolo26n_384.onnx",
        "yolo26n.onnx",
    )

    private val yoloAssetCandidates = listOf(
        "yolo26n_person_384_float32.tflite",
        "yolo26n_person_384_fp16.tflite",
        "yolo26n_384_float32.tflite",
        "yolo26n_384_fp16.tflite",
        "yolo26n.tflite",
        "yolov8n_person_384_float32.tflite",
        "yolov8n_384_float32.tflite",
        "yolov8n.tflite",
    )

    fun create(context: Context): PersonDetector {
        val detector = TfliteYoloPersonDetector.createOrNull(
            context = context.applicationContext,
            assetCandidates = yoloAssetCandidates,
        )
        if (detector != null) return detector

        val onnxDetector = OnnxYoloPersonDetector.createOrNull(
            context = context.applicationContext,
            assetCandidates = onnxAssetCandidates,
        )
        if (onnxDetector != null) return onnxDetector
        Log.i(TAG, "No mobile person detector asset found; fallback remains disabled")
        return DisabledPersonDetector()
    }
}
