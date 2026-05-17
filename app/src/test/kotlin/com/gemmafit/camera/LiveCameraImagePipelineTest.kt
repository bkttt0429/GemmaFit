package com.gemmafit.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCameraImagePipelineTest {
    @Test
    fun defaultUsesCameraXRotatedRgbaPipeline() {
        assertEquals(
            LiveCameraImagePipeline.CAMERAX_ROTATED_RGBA_BITMAP,
            LiveCameraImagePipeline.DEFAULT,
        )
    }
}
