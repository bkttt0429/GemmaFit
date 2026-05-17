package com.gemmafit.video

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TfliteYoloPersonDetector private constructor(
    private val interpreter: Interpreter,
    private val assetName: String,
    private val inputShape: IntArray,
    private val inputType: DataType,
    private val scoreThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.50f,
    private val maxProposals: Int = 6,
) : PersonDetector {
    override val source: String = "yolo_person"
    override val isAvailable: Boolean = true

    private val channelFirst = inputShape.size == 4 && inputShape[1] == 3
    private val inputHeight = if (inputShape.size == 4) {
        (if (channelFirst) inputShape[2] else inputShape[1]).coerceAtLeast(1)
    } else {
        384
    }
    private val inputWidth = if (inputShape.size == 4) {
        (if (channelFirst) inputShape[3] else inputShape[2]).coerceAtLeast(1)
    } else {
        384
    }

    override fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        predictedBbox: PoseBoundingBox?,
    ): PersonDetectionResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val input = preprocess(bitmap)
            val outputs = allocateOutputs()
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            val output0 = outputs[0] ?: throw IllegalStateException("YOLO output 0 missing")
            val rows = firstOutputRows(output0)
            val proposals = decodeRows(rows)
            PersonDetectionResult(
                source = source,
                available = true,
                requested = true,
                proposals = proposals,
                latencyMs = System.currentTimeMillis() - startedAt,
                reason = "model=$assetName",
            )
        }.getOrElse { error ->
            Log.w(TAG, "YOLO person detector failed: ${error.message}", error)
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
        runCatching { interpreter.close() }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = when (inputType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("Unsupported YOLO input type: $inputType")
        }
        val buffer = ByteBuffer
            .allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (channelFirst) {
            writeChannelFirst(buffer, pixels)
        } else {
            writeChannelLast(buffer, pixels)
        }
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        buffer.rewind()
        return buffer
    }

    private fun writeChannelLast(buffer: ByteBuffer, pixels: IntArray) {
        for (pixel in pixels) {
            writeChannel(buffer, (pixel shr 16) and 0xFF)
            writeChannel(buffer, (pixel shr 8) and 0xFF)
            writeChannel(buffer, pixel and 0xFF)
        }
    }

    private fun writeChannelFirst(buffer: ByteBuffer, pixels: IntArray) {
        for (channel in 0 until 3) {
            for (pixel in pixels) {
                val value = when (channel) {
                    0 -> (pixel shr 16) and 0xFF
                    1 -> (pixel shr 8) and 0xFF
                    else -> pixel and 0xFF
                }
                writeChannel(buffer, value)
            }
        }
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> error("Unsupported YOLO input type: $inputType")
        }
    }

    private fun allocateOutputs(): MutableMap<Int, Any> {
        val outputs = mutableMapOf<Int, Any>()
        for (index in 0 until interpreter.outputTensorCount) {
            val tensor = interpreter.getOutputTensor(index)
            if (tensor.dataType() != DataType.FLOAT32) {
                throw IllegalArgumentException("Unsupported YOLO output type: ${tensor.dataType()}")
            }
            outputs[index] = makeFloatOutput(tensor.shape())
        }
        return outputs
    }

    private fun makeFloatOutput(shape: IntArray): Any {
        return when (shape.size) {
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> throw IllegalArgumentException("Unsupported YOLO output rank: ${shape.contentToString()}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstOutputRows(output: Any): List<FloatArray> {
        return when (output) {
            is Array<*> -> {
                val first = output.firstOrNull() ?: return emptyList()
                when (first) {
                    is FloatArray -> output.filterIsInstance<FloatArray>()
                    is Array<*> -> {
                        val firstInner = first.firstOrNull()
                        if (firstInner is FloatArray) {
                            val matrix = first as Array<FloatArray>
                            matrixToRows(matrix)
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

    private fun matrixToRows(matrix: Array<FloatArray>): List<FloatArray> {
        if (matrix.isEmpty()) return emptyList()
        val rows = matrix.size
        val cols = matrix[0].size
        return if (rows <= 128 && cols > rows) {
            List(cols) { col ->
                FloatArray(rows) { row -> matrix[row][col] }
            }
        } else {
            matrix.toList()
        }
    }

    private fun decodeRows(rows: List<FloatArray>): List<PersonProposal> {
        val decoded = rows.mapNotNull { row -> decodeRow(row) }
            .filter { it.score >= scoreThreshold && it.bbox.area > 0.0005f }
            .sortedByDescending { it.score }
        val kept = mutableListOf<PersonProposal>()
        for (proposal in decoded) {
            if (kept.none { PersonProposalFusion.iou(it.bbox, proposal.bbox) > iouThreshold }) {
                kept += proposal
            }
            if (kept.size >= maxProposals) break
        }
        return kept
    }

    private fun decodeRow(row: FloatArray): PersonProposal? {
        if (row.size < 6) return null
        val box = if (row.size == 6) decodeSixValueBox(row) else decodeYoloClassRow(row)
        return box?.copy(source = source)
    }

    private fun decodeSixValueBox(row: FloatArray): PersonProposal? {
        val score = row[4]
        val classId = row[5].roundToInt()
        if (classId != PERSON_CLASS_ID) return null
        val xyxyLooksValid = row[2] > row[0] && row[3] > row[1]
        val bbox = if (xyxyLooksValid) {
            normalizeXyxy(row[0], row[1], row[2], row[3])
        } else {
            normalizeCxcywh(row[0], row[1], row[2], row[3])
        }
        return PersonProposal(bbox = bbox, score = score, source = source, labelId = classId)
    }

    private fun decodeYoloClassRow(row: FloatArray): PersonProposal? {
        val classScores = row.copyOfRange(4, row.size)
        var bestClass = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in classScores.indices) {
            if (classScores[i] > bestScore) {
                bestScore = classScores[i]
                bestClass = i
            }
        }
        if (bestClass != PERSON_CLASS_ID) return null
        return PersonProposal(
            bbox = normalizeCxcywh(row[0], row[1], row[2], row[3]),
            score = bestScore,
            source = source,
            labelId = bestClass,
        )
    }

    private fun normalizeCxcywh(cx: Float, cy: Float, width: Float, height: Float): PoseBoundingBox {
        val normalized = max(max(cx, cy), max(width, height)) <= 2f
        val scaleX = if (normalized) 1f else inputWidth.toFloat()
        val scaleY = if (normalized) 1f else inputHeight.toFloat()
        val halfW = width / scaleX / 2f
        val halfH = height / scaleY / 2f
        val x = cx / scaleX
        val y = cy / scaleY
        return PoseBoundingBox(
            minX = (x - halfW).coerceIn(0f, 1f),
            minY = (y - halfH).coerceIn(0f, 1f),
            maxX = (x + halfW).coerceIn(0f, 1f),
            maxY = (y + halfH).coerceIn(0f, 1f),
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
        private const val TAG = "GemmaFit.YoloDetector"
        private const val PERSON_CLASS_ID = 0

        fun createOrNull(
            context: Context,
            assetCandidates: List<String>,
        ): TfliteYoloPersonDetector? {
            for (assetName in assetCandidates) {
                val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }
                    .getOrNull()
                    ?: continue
                val model = ByteBuffer
                    .allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes)
                model.rewind()
                return runCatching {
                    val options = Interpreter.Options().setNumThreads(2)
                    val interpreter = Interpreter(model, options)
                    TfliteYoloPersonDetector(
                        interpreter = interpreter,
                        assetName = assetName,
                        inputShape = interpreter.getInputTensor(0).shape(),
                        inputType = interpreter.getInputTensor(0).dataType(),
                    )
                }.onFailure {
                    Log.w(TAG, "Cannot initialize $assetName: ${it.message}", it)
                }.getOrNull()
            }
            return null
        }
    }
}
