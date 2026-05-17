package com.gemmafit.video

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OnnxYoloPersonDetector private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val assetName: String,
    private val inputName: String,
    private val inputShape: LongArray,
    private val scoreThreshold: Float = 0.25f,
    private val maxProposals: Int = 6,
) : PersonDetector {
    override val source: String = "yolo_person_onnx"
    override val isAvailable: Boolean = true

    private val inputHeight = if (inputShape.size == 4) inputShape[2].toInt().coerceAtLeast(1) else 384
    private val inputWidth = if (inputShape.size == 4) inputShape[3].toInt().coerceAtLeast(1) else 384

    override fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        predictedBbox: PoseBoundingBox?,
    ): PersonDetectionResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val tensor = OnnxTensor.createTensor(environment, preprocess(bitmap), longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()))
            tensor.use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    val first = result[0].value
                    val proposals = decodeOutput(first)
                    PersonDetectionResult(
                        source = source,
                        available = true,
                        requested = true,
                        proposals = proposals,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        reason = "model=$assetName",
                    )
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "ONNX YOLO person detector failed: ${error.message}", error)
            PersonDetectionResult(
                source = source,
                available = true,
                requested = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                error = error.message ?: "unknown",
                reason = "person_detector_failed",
            )
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val values = FloatArray(3 * inputWidth * inputHeight)
        val planeSize = inputWidth * inputHeight
        for (i in pixels.indices) {
            val pixel = pixels[i]
            values[i] = ((pixel shr 16) and 0xFF) / 255f
            values[planeSize + i] = ((pixel shr 8) and 0xFF) / 255f
            values[planeSize * 2 + i] = (pixel and 0xFF) / 255f
        }
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        return FloatBuffer.wrap(values)
    }

    private fun decodeOutput(output: Any): List<PersonProposal> {
        val rows = outputRows(output)
        return rows.mapNotNull { row -> decodeRow(row) }
            .filter { it.score >= scoreThreshold && it.bbox.area > 0.0005f }
            .sortedByDescending { it.score }
            .take(maxProposals)
    }

    @Suppress("UNCHECKED_CAST")
    private fun outputRows(output: Any): List<FloatArray> {
        return when (output) {
            is Array<*> -> {
                val first = output.firstOrNull() ?: return emptyList()
                when (first) {
                    is FloatArray -> output.filterIsInstance<FloatArray>()
                    is Array<*> -> {
                        val inner = first.firstOrNull()
                        if (inner is FloatArray) {
                            (first as Array<FloatArray>).toList()
                        } else {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun decodeRow(row: FloatArray): PersonProposal? {
        if (row.size < 6) return null
        val score = row[4]
        val classId = row[5].roundToInt()
        if (classId != PERSON_CLASS_ID) return null
        return PersonProposal(
            bbox = normalizeXyxy(row[0], row[1], row[2], row[3]),
            score = score,
            source = source,
            labelId = classId,
        )
    }

    private fun normalizeXyxy(x1: Float, y1: Float, x2: Float, y2: Float): PoseBoundingBox {
        val normalized = max(max(x1, y1), max(x2, y2)) <= 2f
        val scaleX = if (normalized) 1f else inputWidth.toFloat()
        val scaleY = if (normalized) 1f else inputHeight.toFloat()
        return PoseBoundingBox(
            minX = (min(x1, x2) / scaleX).coerceIn(0f, 1f),
            minY = (min(y1, y2) / scaleY).coerceIn(0f, 1f),
            maxX = (max(x1, x2) / scaleX).coerceIn(0f, 1f),
            maxY = (max(y1, y2) / scaleY).coerceIn(0f, 1f),
        )
    }

    companion object {
        private const val TAG = "GemmaFit.OnnxYolo"
        private const val PERSON_CLASS_ID = 0

        fun createOrNull(
            context: Context,
            assetCandidates: List<String>,
        ): OnnxYoloPersonDetector? {
            for (assetName in assetCandidates) {
                val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }
                    .getOrNull()
                    ?: continue
                return runCatching {
                    val environment = OrtEnvironment.getEnvironment()
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    val session = environment.createSession(bytes, sessionOptions)
                    val inputName = session.inputNames.first()
                    val inputShape = session.inputInfo[inputName]?.info
                        ?.let { it as? ai.onnxruntime.TensorInfo }
                        ?.shape
                        ?: longArrayOf(1, 3, 384, 384)
                    OnnxYoloPersonDetector(
                        environment = environment,
                        session = session,
                        assetName = assetName,
                        inputName = inputName,
                        inputShape = inputShape,
                    )
                }.onFailure {
                    Log.w(TAG, "Cannot initialize $assetName: ${it.message}", it)
                }.getOrNull()
            }
            return null
        }
    }
}
