package com.gemmafit.debug

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.roundToLong

data class RgbaPipelineFrameSample(
    val timestampMs: Long,
    val source: String,
    val pipelineVariant: String,
    val cameraXOutputRotationEnabled: Boolean,
    val imageFormat: Int,
    val imageFormatName: String,
    val planeCount: Int,
    val proxyWidth: Int,
    val proxyHeight: Int,
    val rotationDegrees: Int,
    val rawBitmapConfig: String,
    val rawBitmapWidth: Int,
    val rawBitmapHeight: Int,
    val frameBitmapConfig: String,
    val frameBitmapWidth: Int,
    val frameBitmapHeight: Int,
    val yuvToBitmapUs: Long,
    val rotateUs: Long,
    val bitmapImageBuildUs: Long,
    val detectAsyncUs: Long,
    val appearanceSnapshotCopyUs: Long?,
    val totalAcceptedFrameUs: Long,
    val error: String? = null,
)

object RgbaPipelineAudit {
    private const val MAX_SAMPLES = 900
    private const val PUBLISH_INTERVAL_MS = 1_000L

    private val lock = Any()
    private val samples = ArrayDeque<RgbaPipelineFrameSample>()
    private var totalSeen = 0L
    private var lastPublishedAtMs = 0L
    private var lastResetAtMs = System.currentTimeMillis()

    fun record(sample: RgbaPipelineFrameSample): Boolean {
        synchronized(lock) {
            samples.addLast(sample)
            totalSeen += 1
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
            if (sample.timestampMs - lastPublishedAtMs < PUBLISH_INTERVAL_MS) {
                return false
            }
            lastPublishedAtMs = sample.timestampMs
            return true
        }
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            totalSeen = 0L
            lastPublishedAtMs = 0L
            lastResetAtMs = System.currentTimeMillis()
        }
    }

    fun snapshotJson(): JSONObject {
        val copy = synchronized(lock) { samples.toList() }
        val first = copy.firstOrNull()
        val last = copy.lastOrNull()
        val durationMs = if (first != null && last != null) {
            (last.timestampMs - first.timestampMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val sampleRate = if (durationMs > 0) {
            copy.size * 1000.0 / durationMs.toDouble()
        } else {
            0.0
        }

        return JSONObject()
            .put("section", "rgba_pipeline_audit")
            .put("generated_at_ms", System.currentTimeMillis())
            .put("last_reset_at_ms", synchronized(lock) { lastResetAtMs })
            .put("pipeline", "Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.")
            .put("sample_count", copy.size)
            .put("total_seen_since_reset", synchronized(lock) { totalSeen })
            .put("window_duration_ms", durationMs)
            .put("estimated_sample_rate_hz", roundDouble(sampleRate))
            .put("pipeline_variants", countBy(copy.map { it.pipelineVariant }))
            .put("camerax_output_rotation_enabled", countBy(copy.map { it.cameraXOutputRotationEnabled.toString() }))
            .put("input_formats", countBy(copy.map { it.imageFormatName }))
            .put("raw_bitmap_configs", countBy(copy.map { it.rawBitmapConfig }))
            .put("frame_bitmap_configs", countBy(copy.map { it.frameBitmapConfig }))
            .put("rotations", countBy(copy.map { it.rotationDegrees.toString() }))
            .put("proxy_dimensions", countBy(copy.map { "${it.proxyWidth}x${it.proxyHeight}" }))
            .put("frame_bitmap_dimensions", countBy(copy.map { "${it.frameBitmapWidth}x${it.frameBitmapHeight}" }))
            .put("timing_us", timingJson(copy))
            .put("latest_sample", last?.toJson() ?: JSONObject.NULL)
            .put("recent_samples", recentSamples(copy))
            .put("notes", JSONArray(listOf(
                "This is an audit counter only; it does not change pose inference behavior.",
                "ARGB_8888 is the Android Bitmap storage format; MediaPipe receives a BitmapImage, not raw video.",
                "Use p95 values over a 30s live-camera window before optimizing this path.",
            )))
    }

    private fun timingJson(samples: List<RgbaPipelineFrameSample>): JSONObject {
        return JSONObject()
            .put("yuv_to_bitmap", stat(samples.map { it.yuvToBitmapUs }))
            .put("rotate", stat(samples.map { it.rotateUs }))
            .put("bitmap_image_build", stat(samples.map { it.bitmapImageBuildUs }))
            .put("detect_async_enqueue", stat(samples.map { it.detectAsyncUs }))
            .put("appearance_snapshot_copy", stat(samples.mapNotNull { it.appearanceSnapshotCopyUs }))
            .put("total_accepted_frame", stat(samples.map { it.totalAcceptedFrameUs }))
    }

    private fun stat(values: List<Long>): JSONObject {
        val valid = values.filter { it >= 0L }.sorted()
        if (valid.isEmpty()) {
            return JSONObject()
                .put("count", 0)
                .put("avg", 0)
                .put("p50", 0)
                .put("p95", 0)
                .put("max", 0)
        }
        return JSONObject()
            .put("count", valid.size)
            .put("avg", valid.average().roundToLong())
            .put("p50", percentile(valid, 50))
            .put("p95", percentile(valid, 95))
            .put("max", valid.last())
    }

    private fun percentile(sortedValues: List<Long>, percentile: Int): Long {
        if (sortedValues.isEmpty()) return 0L
        val index = ((percentile.coerceIn(0, 100) / 100.0) * (sortedValues.size - 1)).roundToLong()
            .toInt()
            .coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    private fun countBy(values: List<String>): JSONObject {
        val obj = JSONObject()
        values.groupingBy { it }.eachCount()
            .toSortedMap()
            .forEach { (key, count) -> obj.put(key, count) }
        return obj
    }

    private fun recentSamples(samples: List<RgbaPipelineFrameSample>): JSONArray {
        val arr = JSONArray()
        samples.takeLast(8).forEach { arr.put(it.toJson()) }
        return arr
    }

    private fun RgbaPipelineFrameSample.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp_ms", timestampMs)
            .put("source", source)
            .put("pipeline_variant", pipelineVariant)
            .put("camerax_output_rotation_enabled", cameraXOutputRotationEnabled)
            .put("image_format", imageFormat)
            .put("image_format_name", imageFormatName)
            .put("plane_count", planeCount)
            .put("proxy_width", proxyWidth)
            .put("proxy_height", proxyHeight)
            .put("rotation_degrees", rotationDegrees)
            .put("raw_bitmap_config", rawBitmapConfig)
            .put("raw_bitmap_width", rawBitmapWidth)
            .put("raw_bitmap_height", rawBitmapHeight)
            .put("frame_bitmap_config", frameBitmapConfig)
            .put("frame_bitmap_width", frameBitmapWidth)
            .put("frame_bitmap_height", frameBitmapHeight)
            .put("yuv_to_bitmap_us", yuvToBitmapUs)
            .put("rotate_us", rotateUs)
            .put("bitmap_image_build_us", bitmapImageBuildUs)
            .put("detect_async_us", detectAsyncUs)
            .put("appearance_snapshot_copy_us", appearanceSnapshotCopyUs ?: JSONObject.NULL)
            .put("total_accepted_frame_us", totalAcceptedFrameUs)
            .put("error", error ?: JSONObject.NULL)
    }

    private fun roundDouble(value: Double): Double {
        return (value * 100.0).roundToLong() / 100.0
    }
}
