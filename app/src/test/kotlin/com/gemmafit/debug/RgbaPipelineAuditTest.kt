package com.gemmafit.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbaPipelineAuditTest {
    @Test
    fun recordsFormatConfigAndTimingSummary() {
        RgbaPipelineAudit.reset()

        assertTrue(RgbaPipelineAudit.record(sample(timestampMs = 1_000L, yuvToBitmapUs = 1_000L)))
        RgbaPipelineAudit.record(sample(timestampMs = 1_100L, yuvToBitmapUs = 2_000L))

        val snapshot = RgbaPipelineAudit.snapshotJson()
        val timing = snapshot.getJSONObject("timing_us")

        assertEquals(2, snapshot.getInt("sample_count"))
        assertEquals(2, snapshot.getJSONObject("pipeline_variants").getInt("CURRENT_YUV_BITMAP_ROTATE"))
        assertEquals(2, snapshot.getJSONObject("input_formats").getInt("YUV_420_888"))
        assertEquals(2, snapshot.getJSONObject("frame_bitmap_configs").getInt("ARGB_8888"))
        assertEquals(1_500, timing.getJSONObject("yuv_to_bitmap").getInt("avg"))
        assertEquals("ARGB_8888", snapshot.getJSONObject("latest_sample").getString("frame_bitmap_config"))
    }

    @Test
    fun resetClearsSamples() {
        RgbaPipelineAudit.reset()
        RgbaPipelineAudit.record(sample(timestampMs = 1_000L))

        RgbaPipelineAudit.reset()

        val snapshot = RgbaPipelineAudit.snapshotJson()
        assertEquals(0, snapshot.getInt("sample_count"))
        assertEquals(0, snapshot.getInt("total_seen_since_reset"))
    }

    private fun sample(
        timestampMs: Long,
        yuvToBitmapUs: Long = 1_000L,
    ): RgbaPipelineFrameSample {
        return RgbaPipelineFrameSample(
            timestampMs = timestampMs,
            source = "test",
            pipelineVariant = "CURRENT_YUV_BITMAP_ROTATE",
            cameraXOutputRotationEnabled = false,
            imageFormat = 35,
            imageFormatName = "YUV_420_888",
            planeCount = 3,
            proxyWidth = 640,
            proxyHeight = 480,
            rotationDegrees = 90,
            rawBitmapConfig = "ARGB_8888",
            rawBitmapWidth = 640,
            rawBitmapHeight = 480,
            frameBitmapConfig = "ARGB_8888",
            frameBitmapWidth = 480,
            frameBitmapHeight = 640,
            yuvToBitmapUs = yuvToBitmapUs,
            rotateUs = 500L,
            bitmapImageBuildUs = 250L,
            detectAsyncUs = 100L,
            appearanceSnapshotCopyUs = 700L,
            totalAcceptedFrameUs = 3_000L,
        )
    }
}
