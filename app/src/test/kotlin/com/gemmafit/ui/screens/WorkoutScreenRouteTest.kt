package com.gemmafit.ui.screens

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutScreenRouteTest {
    @Test
    fun previewBitmapKeepsAnalysisSurfaceWhileFullPassStarts() {
        assertTrue(
            shouldShowVideoAnalysisSurface(
                analyzedFrames = 0,
                hasVideoPreview = true,
            )
        )
    }

    @Test
    fun noFramesAndNoPreviewUsesLoadingSurface() {
        assertFalse(
            shouldShowVideoAnalysisSurface(
                analyzedFrames = 0,
                hasVideoPreview = false,
            )
        )
    }

    @Test
    fun cameraLensToggleSwitchesBetweenBackAndFront() {
        assertEquals(
            CameraSelector.LENS_FACING_FRONT,
            nextCameraLensFacing(CameraSelector.LENS_FACING_BACK),
        )
        assertEquals(
            CameraSelector.LENS_FACING_BACK,
            nextCameraLensFacing(CameraSelector.LENS_FACING_FRONT),
        )
    }

    @Test
    fun frontCameraMirrorsOverlayAndTapCoordinates() {
        assertFalse(shouldMirrorCameraOverlay(CameraSelector.LENS_FACING_BACK))
        assertTrue(shouldMirrorCameraOverlay(CameraSelector.LENS_FACING_FRONT))
        assertEquals(
            0.75f,
            mirrorNormalizedXForLens(0.25f, CameraSelector.LENS_FACING_FRONT),
            0.0001f,
        )
        assertEquals(
            0.25f,
            mirrorNormalizedXForLens(0.25f, CameraSelector.LENS_FACING_BACK),
            0.0001f,
        )
    }
}
